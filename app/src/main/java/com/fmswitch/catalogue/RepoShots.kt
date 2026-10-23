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
 * A picture for a firmware that has none, taken from its GitHub README.
 *
 * The first image under a Screenshots heading wins, because that is the one the page shows
 * first. Otherwise the first picture that is not a logo or badge. A README screenshot also
 * replaces a photo from somewhere else, since it is the project's own.
 */
object RepoShots {
    data class Shot(val url: String, val alt: String?, val fromSection: Boolean)

    private val heading = Regex("""(?m)^(#{1,6})[ \t]+(.+?)[ \t]*$""")
    private val markdownImage = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?[^)]*\)""")
    private val htmlImage = Regex("""(?is)<img\b([^>]*)>""")
    private val githubBlob = Regex("""^https://github\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.+)$""")
    private val decoration = Regex("""(?i)(^|[/_.=-])(logo|badge|shields?|icon|favicon|avatar|sponsor)([/_.?=]|$)""")

    fun pick(markdown: String, readmeUrl: String, sectionOnly: Boolean = false): Shot? {
        val images = images(markdown)
        val headings = heading.findAll(markdown).map { match ->
            Triple(match.range.first, match.groupValues[1].length, match.groupValues[2])
        }.toList()
        for ((index, found) in headings.withIndex()) {
            val (at, level, title) = found
            if (!title.contains("screenshot", ignoreCase = true) &&
                !title.contains("screen shot", ignoreCase = true)
            ) {
                continue
            }
            val end = headings.drop(index + 1).firstOrNull { it.second <= level }?.first ?: markdown.length
            for (image in images) {
                if (image.position <= at || image.position >= end) continue
                image.toShot(readmeUrl, fromSection = true)?.let { return it }
            }
        }
        if (sectionOnly) return null
        return images.firstNotNullOfOrNull { it.toShot(readmeUrl, fromSection = false) }
    }

    fun isScannedShot(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "raw.githubusercontent.com" ||
            host == "user-images.githubusercontent.com" ||
            host == "private-user-images.githubusercontent.com" ||
            host == "camo.githubusercontent.com" ||
            host == "img.youtube.com" ||
            host == "i.ytimg.com" ||
            HubShots.isHubShot(url) ||
            (host == "github.com" && (url.contains("/user-attachments/") || url.contains("/assets/")))
    }

    /**
     * Keeps a shot already taken from a README, then a new one, then whatever the entry or
     * the previously saved list already had.
     */
    fun choose(entry: CatalogueEntry, previous: CatalogueEntry?, shot: Shot?): CatalogueEntry {
        val kept = previous?.photoURL
        if (kept != null && isScannedShot(kept)) {
            return entry.copy(photoURL = kept, photoAlt = previous.photoAlt ?: entry.photoAlt)
        }
        if (shot != null && (shot.fromSection || entry.photoURL == null)) {
            return entry.copy(photoURL = shot.url, photoAlt = shot.alt ?: entry.photoAlt)
        }
        if (entry.photoURL == null && kept != null) {
            return entry.copy(photoURL = kept, photoAlt = entry.photoAlt ?: previous.photoAlt)
        }
        return entry
    }

    suspend fun fill(entries: List<CatalogueEntry>, previous: List<CatalogueEntry>): List<CatalogueEntry> =
        coroutineScope {
            val prevById = previous.associateBy { it.id }
            val gate = Semaphore(6)
            entries.map { entry ->
                async {
                    val prev = prevById[entry.id]
                    val shot = when {
                        prev?.photoURL != null && isScannedShot(prev.photoURL) -> null
                        entry.photoURL != null && prev?.photoURL == entry.photoURL -> null
                        entry.photoURL == null && prev != null && prev.photoURL == null && sameRepos(entry, prev) -> null
                        else -> gate.withPermit {
                            withContext(Dispatchers.IO) {
                                runCatching { fetchShot(entry, sectionOnly = entry.photoURL != null) }.getOrNull()
                            }
                        }
                    }
                    choose(entry, prev, shot)
                }
            }.awaitAll()
        }

    private fun fetchShot(entry: CatalogueEntry, sectionOnly: Boolean): Shot? {
        for ((owner, repo) in PackageDiscovery.gitHubRepos(entry.links)) {
            val (text, url) = readme(owner, repo) ?: continue
            pick(text, url, sectionOnly)?.let { return it }
        }
        return null
    }

    private fun readme(owner: String, repo: String): Pair<String, String>? {
        for (branch in listOf("main", "master")) {
            for (name in listOf("README.md", "readme.md")) {
                val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$name"
                val bytes = runCatching { fetchBytes(url, maxBytes = 512 * 1024) }.getOrNull() ?: continue
                return bytes.toString(Charsets.UTF_8) to url
            }
        }
        return null
    }

    private fun sameRepos(a: CatalogueEntry, b: CatalogueEntry): Boolean {
        fun ids(entry: CatalogueEntry) = PackageDiscovery.gitHubRepos(entry.links)
            .map { (owner, repo) -> "${owner.lowercase()}/${repo.lowercase()}" }
            .toSet()
        return ids(a) == ids(b)
    }

    private class Found(val position: Int, val src: String, val alt: String?) {
        fun toShot(readmeUrl: String, fromSection: Boolean): Shot? {
            if (decoration.containsMatchIn(src) || decoration.containsMatchIn(alt.orEmpty())) return null
            if (src.substringBefore('?').endsWith(".svg", ignoreCase = true)) return null
            val url = imageUrl(readmeUrl, src) ?: return null
            return Shot(url, alt?.takeIf { it.isNotBlank() }, fromSection)
        }
    }

    private fun images(markdown: String): List<Found> {
        val found = ArrayList<Found>()
        for (match in markdownImage.findAll(markdown)) {
            found += Found(match.range.first, match.groupValues[2], match.groupValues[1])
        }
        for (match in htmlImage.findAll(markdown)) {
            val attributes = Html.attributes(match.groupValues[1])
            val src = attributes["src"]?.takeIf { it.isNotBlank() } ?: continue
            found += Found(match.range.first, src, attributes["alt"])
        }
        return found.sortedBy { it.position }
    }

    private fun imageUrl(readmeUrl: String, src: String): String? {
        val trimmed = src.trim()
        if (trimmed.startsWith("data:")) return null
        val absolute = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            runCatching { URI(readmeUrl).resolve(trimmed).toString() }.getOrNull()
        } ?: return null
        val raw = githubBlob.matchEntire(absolute)?.let { match ->
            val (owner, repo, branch, path) = match.destructured
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        } ?: absolute
        return raw.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}
