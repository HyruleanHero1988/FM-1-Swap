package com.fmswitch.catalogue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

data class DiscoveredPackage(
    val url: String,
    val version: String? = null,
    val date: String? = null,
    val beta: Boolean = false,
    val product: String? = null,
    val size: Int? = null,
    val sha256: String? = null,
)

/**
 * Finds downloadable `.fwsc` packages for firmwares the hub doesn't mirror.
 *
 * In order: the newest GitHub release with a `.fwsc` asset, a JSON release list the publisher
 * embeds in their page (as Baud Girl's install page does), then `.fwsc` links on the project
 * pages themselves. Publishers often keep that list on an `/install` page one link away.
 */
object PackageDiscovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val fm1Identity = Regex("""^FM-1_\\d+$""")

    @Serializable
    data class ManifestRelease(
        val version: String? = null,
        val file: String,
        val sha256: String? = null,
        val date: String? = null,
        val beta: Boolean = false,
        val current: Boolean = false,
    )

    suspend fun discoverMissing(
        entries: List<CatalogueEntry>,
        previous: List<CatalogueEntry>,
    ): List<CatalogueEntry> = coroutineScope {
        val previousById = previous.associateBy { it.id }
        val gate = Semaphore(6)
        entries.map { entry ->
            async {
                if (entry.packageURL != null) return@async entry
                gate.withPermit {
                    val found = withContext(Dispatchers.IO) { discover(entry.name, entry.links) }
                    found?.let { apply(it, entry) } ?: CatalogueBuilder.fallBack(entry, previousById[entry.id])
                }
            }
        }.awaitAll()
    }

    fun discover(name: String, links: List<CatalogueLink>): DiscoveredPackage? {
        for ((owner, repo) in gitHubRepos(links)) {
            latestRelease(owner, repo)?.let { return it }
        }
        val words = identifyingWords(name, links)
        val pages = ArrayDeque<String>()
        for (link in links) {
            val host = runCatching { URI(link.url).host }.getOrNull()?.lowercase()
            if (host == "github.com" || link.url.lowercase().endsWith(".zip")) continue
            pages.add(link.url)
        }
        val visited = LinkedHashSet<String>()
        while (pages.isNotEmpty() && visited.size < 6) {
            val page = pages.removeFirst()
            if (!visited.add(page)) continue
            val html = runCatching { String(fetchBytes(page), Charsets.UTF_8) }.getOrNull() ?: continue
            val clean = Html.withoutComments(html)
            manifestPackage(clean, page)?.let { return it }
            val relevant = packageLinks(clean, page).filter { isRelevant(it, words) }
            if (relevant.isNotEmpty()) {
                return DiscoveredPackage(url = relevant.first())
            }
            for (install in installPageLinks(clean, page)) {
                if (install !in visited) pages.add(install)
            }
        }
        return null
    }

    fun apply(found: DiscoveredPackage, entry: CatalogueEntry): CatalogueEntry = entry.copy(
        packageURL = found.url,
        version = found.version ?: entry.version,
        date = found.date ?: entry.date,
        beta = entry.beta || found.beta,
        product = found.product ?: entry.product,
        size = found.size ?: entry.size,
        sha256 = found.sha256 ?: entry.sha256,
    )

    fun gitHubRepos(links: List<CatalogueLink>): List<Pair<String, String>> {
        val repos = ArrayList<Pair<String, String>>()
        for (link in links) {
            val uri = runCatching { URI(link.url) }.getOrNull() ?: continue
            val host = uri.host?.lowercase() ?: continue
            val parts = uri.path.split('/').filter { it.isNotEmpty() && it != "/" }
            val repo = when {
                host == "github.com" && parts.size >= 2 ->
                    parts[0] to parts[1].removeSuffix(".git")
                host.endsWith(".github.io") && parts.isNotEmpty() &&
                    parts[0].lowercase() !in setOf("webapp", "docs", "installer", "editor") ->
                    host.removeSuffix(".github.io") to parts[0]
                else -> null
            } ?: continue
            if (repos.none { it.first.equals(repo.first, true) && it.second.equals(repo.second, true) }) {
                repos += repo
            }
        }
        return repos
    }

    fun manifestPackage(html: String, pageUrl: String): DiscoveredPackage? {
        val page = URI(pageUrl)
        for (script in Html.elements("script", html)) {
            if (!(script.attributes["type"] ?: "").contains("json")) continue
            val releases = runCatching {
                json.decodeFromString<List<ManifestRelease>>(script.inner.trim())
            }.getOrNull().orEmpty()
            val fwsc = releases.filter { it.file.lowercase().endsWith(".fwsc") }
            val chosen = fwsc.firstOrNull { it.current } ?: fwsc.firstOrNull { !it.beta } ?: fwsc.firstOrNull()
                ?: continue
            val url = runCatching { page.resolve(chosen.file).toString() }.getOrNull() ?: continue
            val product = chosen.version?.takeIf { fm1Identity.matches(it) }
            return DiscoveredPackage(
                url = url,
                version = chosen.version,
                date = chosen.date,
                beta = chosen.beta,
                product = product,
                sha256 = chosen.sha256?.lowercase(),
            )
        }
        return null
    }

    fun packageLinks(html: String, pageUrl: String): List<String> {
        val page = URI(pageUrl)
        val found = ArrayList<String>()
        val pattern = Regex("""[\"'(=]([^\"'()<>\\s]*[A-Za-z0-9_\\-.+%].fwsc)(?=[\"'()<>\\s?#])""")
        for (match in pattern.findAll(html)) {
            val url = runCatching { page.resolve(match.groupValues[1]).toString() }.getOrNull() ?: continue
            if (!url.startsWith("http")) continue
            val file = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull() ?: continue
            if (file.equals("FM-1.fwsc", ignoreCase = true)) continue
            if (url !in found) found += url
        }
        return found
    }

    fun identifyingWords(name: String, links: List<CatalogueLink>): Set<String> {
        val common = setOf("fm", "fm1", "the", "and", "for", "firmware", "patch", "synth", "web", "app", "github", "io")
        val sources = listOf(name) + gitHubRepos(links).map { it.second }
        val words = LinkedHashSet<String>()
        for (source in sources) {
            for (word in source.lowercase().split(Regex("[^a-z0-9]+"))) {
                if (word.length >= 3 && word !in common && word.any { it.isLetter() }) words += word
            }
        }
        return words
    }

    fun isRelevant(url: String, words: Set<String>): Boolean {
        val fileName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?: return false
        return words.any { fileName.contains(it) }
    }

    fun installPageLinks(html: String, pageUrl: String): List<String> {
        val page = URI(pageUrl)
        val found = ArrayList<String>()
        for (anchor in Html.elements("a", html)) {
            val href = anchor.attributes["href"] ?: continue
            val url = runCatching { page.resolve(href).toString() }.getOrNull() ?: continue
            val resolved = runCatching { URI(url) }.getOrNull() ?: continue
            if (resolved.host != page.host) continue
            if (!resolved.path.lowercase().contains("install")) continue
            if (resolved.path == page.path) continue
            if (url !in found) found += url
        }
        return found.take(2)
    }

    private fun latestRelease(owner: String, repo: String): DiscoveredPackage? {
        val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=10"
        val body = runCatching { String(fetchBytes(url), Charsets.UTF_8) }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val releases = root as? kotlinx.serialization.json.JsonArray ?: return null
        for (element in releases) {
            val obj = element as? kotlinx.serialization.json.JsonObject ?: continue
            if (obj.flag("draft")) continue
            val assets = obj["assets"] as? kotlinx.serialization.json.JsonArray ?: continue
            val fwsc = assets.mapNotNull { asset ->
                val item = asset as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = item.str("name") ?: return@mapNotNull null
                if (!name.lowercase().endsWith(".fwsc")) return@mapNotNull null
                if (name.equals("FM-1.fwsc", ignoreCase = true)) return@mapNotNull null
                val address = item.str("browser_download_url") ?: return@mapNotNull null
                Triple(address, item["size"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }, item.str("digest"))
            }
            val chosen = fwsc.minByOrNull { it.first.length } ?: continue
            val digest = chosen.third?.let { if (it.startsWith("sha256:")) it.removePrefix("sha256:") else null }
            return DiscoveredPackage(
                url = chosen.first,
                version = obj.str("tag_name"),
                date = obj.str("published_at")?.take(10),
                beta = obj.flag("prerelease"),
                size = chosen.second,
                sha256 = digest?.lowercase(),
            )
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    private fun kotlinx.serialization.json.JsonObject.flag(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}
