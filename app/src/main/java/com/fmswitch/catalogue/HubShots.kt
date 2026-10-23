package com.fmswitch.catalogue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Screenshots the hub hosts at `/img/{id}.png`, used when a firmware still has none
 * after its own README and fm1-editor.com have been tried.
 */
object HubShots {
    const val BASE = "https://thefirmwarehub.com/img/"

    fun urlFor(id: String): List<String> {
        val slug = id.removePrefix("fm1-editor-").removePrefix("redtail-")
        return listOf(id, slug).distinct().map { "$BASE$it.png" }
    }

    fun isHubShot(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "thefirmwarehub.com" && URI(url).path.orEmpty().startsWith("/img/")
    }

    fun choose(entry: CatalogueEntry, url: String?): CatalogueEntry {
        if (!entry.photoURL.isNullOrBlank() || url.isNullOrBlank()) return entry
        return entry.copy(
            photoURL = url,
            photoAlt = entry.photoAlt ?: "${entry.name} for the M-VAVE FM-1: its screen",
        )
    }

    suspend fun fill(entries: List<CatalogueEntry>): List<CatalogueEntry> =
        coroutineScope {
            val gate = Semaphore(6)
            entries.map { entry ->
                async {
                    if (!entry.photoURL.isNullOrBlank()) return@async entry
                    val url = gate.withPermit {
                        withContext(Dispatchers.IO) {
                            urlFor(entry.id).firstOrNull(::isPng)
                        }
                    }
                    choose(entry, url)
                }
            }.awaitAll()
        }

    private fun isPng(url: String): Boolean {
        val bytes = runCatching { fetchBytes(url, maxBytes = 8) }.getOrNull() ?: return false
        return bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }
}
