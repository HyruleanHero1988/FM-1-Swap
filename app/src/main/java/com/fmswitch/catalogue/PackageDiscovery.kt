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
import java.util.Base64

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
 * In order: the newest GitHub release with a `.fwsc` asset, a Workbench HTML that embeds
 * `FM1_PACKAGE` (M-VAVE Official Open), a JSON release list the publisher embeds in their
 * page (as Baud Girl's install page does), then `.fwsc` links on the project pages themselves.
 * Publishers often keep that list on an `/install` page one link away.
 */
object PackageDiscovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val fm1Identity = Regex("^FM-1_[0-9]+$")

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
        val words = identifyingWords(name, links)
        for ((owner, repo) in gitHubRepos(links)) {
            latestRelease(owner, repo, words)?.let { return it }
        }
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
            installerMetaPackage(clean, page)?.takeIf { words.isEmpty() || isRelevant(it.url, words) }?.let { return it }
            workbenchPackage(clean, page)?.let { return it }
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

    /**
     * M-VAVE's Official Open workbench ships the `.fwsc` as Base64 in `FM1_PACKAGE`,
     * not as a release asset. The catalogue URL is the workbench HTML; [firmwareBytes]
     * unwraps it when the user saves the firmware.
     */
    fun workbenchPackage(html: String, pageUrl: String): DiscoveredPackage? {
        val start = html.indexOf("FM1_PACKAGE")
        if (start < 0) return null
        if (!pageUrl.startsWith("http")) return null
        val head = html.substring(start, minOf(html.length, start + 3000))
        fun quoted(name: String): String? =
            Regex("\"$name\"[ \\t\\n\\r]*:[ \\t\\n\\r]*\"([^\"]+)\"").find(head)?.groupValues?.get(1)
        fun number(name: String): Int? =
            Regex("\"$name\"[ \\t\\n\\r]*:[ \\t\\n\\r]*([0-9]+)").find(head)?.groupValues?.get(1)?.toIntOrNull()
        val product = quoted("product")?.takeIf { fm1Identity.matches(it) } ?: return null
        return DiscoveredPackage(
            url = pageUrl,
            version = quoted("release_version"),
            product = product,
            size = number("bytes"),
            sha256 = quoted("sha256")?.lowercase(),
        )
    }

    fun firmwareBytes(downloaded: ByteArray): ByteArray {
        val html = runCatching { String(downloaded, Charsets.UTF_8) }.getOrNull() ?: return downloaded
        val packageAt = html.indexOf("FM1_PACKAGE")
        if (packageAt < 0) return downloaded
        val marker = "\"data\":\""
        val from = html.indexOf(marker, packageAt)
        if (from < 0) return downloaded
        val dataStart = from + marker.length
        val dataEnd = html.indexOf('"', dataStart)
        if (dataEnd <= dataStart) return downloaded
        return runCatching { Base64.getDecoder().decode(html.substring(dataStart, dataEnd)) }.getOrNull()
            ?: downloaded
    }

    fun installerMetaPackage(html: String, pageUrl: String): DiscoveredPackage? {
        val match = Regex("(?s)const[ \\t\\n\\r]+meta[ \\t\\n\\r]*=[ \\t\\n\\r]*(\\{.*?\\});").find(html) ?: return null
        val obj = runCatching { json.parseToJsonElement(match.groupValues[1]) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject ?: return null
        val pkg = obj.str("pkg") ?: return null
        if (!pkg.lowercase().endsWith(".fwsc")) return null
        val url = runCatching { URI(pageUrl).resolve(pkg).toString() }.getOrNull() ?: return null
        if (!url.startsWith("http")) return null
        val version = obj.str("version")
        val product = obj.str("product")?.takeIf { fm1Identity.matches(it) }
        return DiscoveredPackage(
            url = url,
            version = version,
            beta = version?.startsWith("0.") == true,
            product = product,
        )
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
        fun add(relative: String) {
            val url = runCatching { page.resolve(relative).toString() }.getOrNull() ?: return
            if (!url.startsWith("http")) return
            val file = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull() ?: return
            if (!file.lowercase().endsWith(".fwsc")) return
            if (file.equals("FM-1.fwsc", ignoreCase = true)) return
            if (url !in found) found += url
        }
        for (anchor in Html.elements("a", html)) {
            val href = anchor.attributes["href"] ?: continue
            if (href.lowercase().contains(".fwsc")) add(href)
        }
        // No capturing group: match.value is the path. Clipper's installer uses a relative
        // ../../firmware/clipper-1.0-experimental.fwsc href, not always a closed <a> tag.
        val pattern = Regex("[A-Za-z0-9_./%+-]+[.]fwsc")
        for (match in pattern.findAll(html)) {
            add(match.value)
        }
        return found
    }

    fun identifyingWords(name: String, links: List<CatalogueLink>): Set<String> {
        val common = setOf("fm", "fm1", "the", "and", "for", "firmware", "patch", "synth", "web", "app", "github", "io")
        val nameNorm = name.lowercase().filter { it.isLetterOrDigit() }
        val words = LinkedHashSet<String>()
        fun addFrom(source: String, requireInName: Boolean) {
            for (word in source.lowercase().split(Regex("[^a-z0-9]+"))) {
                if (word.length < 3 || word in common || word.none { it.isLetter() }) continue
                if (!requireInName || nameNorm.contains(word)) words += word
            }
        }
        addFrom(name, requireInName = false)
        // Shared pack repos (RustyPawz/fm1-HYP1 hosts LOF/ECO/GRAV too) must not add sibling names.
        for (repo in gitHubRepos(links)) addFrom(repo.second, requireInName = true)
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

    private fun latestRelease(owner: String, repo: String, words: Set<String> = emptySet()): DiscoveredPackage? {
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
            val matching = if (words.isEmpty()) fwsc else fwsc.filter { isRelevant(it.first, words) }
            val chosen = matching.minByOrNull { it.first.length }
            if (chosen != null) {
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
            val workbenches = assets.mapNotNull { asset ->
                val item = asset as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = item.str("name") ?: return@mapNotNull null
                if (!name.lowercase().endsWith(".html")) return@mapNotNull null
                if (!name.contains("workbench", ignoreCase = true)) return@mapNotNull null
                item.str("browser_download_url")
            }
            val workbench = if (words.isEmpty()) workbenches.firstOrNull()
            else workbenches.firstOrNull { isRelevant(it, words) } ?: workbenches.firstOrNull()
            if (workbench != null) {
                val html = runCatching { String(fetchBytes(workbench), Charsets.UTF_8) }.getOrNull()
                val embedded = html?.let { workbenchPackage(it, workbench) }
                return DiscoveredPackage(
                    url = workbench,
                    version = embedded?.version ?: obj.str("tag_name"),
                    date = obj.str("published_at")?.take(10),
                    beta = obj.flag("prerelease"),
                    product = embedded?.product,
                    size = embedded?.size,
                    sha256 = embedded?.sha256,
                )
            }
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    private fun kotlinx.serialization.json.JsonObject.flag(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}
