package com.fmswitch.catalogue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The short description GitHub shows under the repo name. REFRESH fills any
 * firmware that does not already have one, and stops if GitHub asks us to slow down.
 */
object RepoAbout {
    private val json = Json { ignoreUnknownKeys = true }

    fun description(body: String): String? =
        runCatching {
            json.parseToJsonElement(body).jsonObject["description"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun fill(entries: List<CatalogueEntry>, previous: List<CatalogueEntry>): List<CatalogueEntry> =
        coroutineScope {
            val earlier = previous.associate { it.id to it.about }
            val carried = entries.map { entry ->
                if (!entry.about.isNullOrBlank()) entry
                else entry.copy(about = earlier[entry.id]?.trim()?.takeIf { it.isNotEmpty() })
            }
            val pending = carried.filter { it.about.isNullOrBlank() }
            if (pending.isEmpty()) return@coroutineScope carried
            val gate = Semaphore(4)
            var blocked = false
            val found = pending.map { entry ->
                async {
                    if (blocked) return@async entry.id to null
                    val repo = PackageDiscovery.gitHubRepos(entry.links).firstOrNull() ?: return@async entry.id to null
                    gate.withPermit {
                        if (blocked) return@withPermit entry.id to null
                        val url = "https://api.github.com/repos/${repo.first}/${repo.second}"
                        val body = withContext(Dispatchers.IO) {
                            runCatching { String(fetchBytes(url, maxBytes = 64 * 1024), Charsets.UTF_8) }
                        }
                        if (body.isFailure) {
                            blocked = true
                            return@withPermit entry.id to null
                        }
                        entry.id to description(body.getOrThrow())
                    }
                }
            }.awaitAll().toMap()
            carried.map { entry ->
                val about = found[entry.id] ?: return@map entry
                entry.copy(about = about)
            }
        }
}
