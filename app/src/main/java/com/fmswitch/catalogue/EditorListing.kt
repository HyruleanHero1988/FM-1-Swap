package com.fmswitch.catalogue

import java.net.URI

/**
 * The firmware list on fm1-editor.com, read from its HTML.
 *
 * The page has no feed, so each `<article class="firmware-entry">` is picked apart. It lists
 * firmwares the hub does not, such as Baud Girl's and M-VAVE's Official Open.
 */
data class EditorListing(val entries: List<Entry>) {
    data class Entry(
        val anchor: String,
        val name: String,
        val author: String? = null,
        val blurb: String? = null,
        val latest: String? = null,
        val updated: String? = null,
        val stars: Int? = null,
        val pinned: Boolean = false,
        val experimental: Boolean = false,
        val links: List<CatalogueLink> = emptyList(),
    )

    companion object {
        const val DEFAULT_URL = "https://fm1-editor.com/firmware/"

        fun parse(html: String, pageUrl: String = DEFAULT_URL): EditorListing {
            val page = URI(pageUrl)
            val entries = Html.elements("article", Html.withoutComments(html)).mapNotNull { article ->
                entry(article, page)
            }
            return EditorListing(entries)
        }

        private fun entry(article: Html.Element, page: URI): Entry? {
            if (!(article.attributes["class"] ?: "").contains("firmware-entry")) return null
            val anchor = article.attributes["id"] ?: return null
            val title = Html.elements("h2", article.inner).firstOrNull()?.let { Html.text(it.inner) }.orEmpty()
            if (title.isEmpty()) return null
            val paragraphs = Html.elements("p", article.inner)
            val author = paragraphs.firstOrNull { (it.attributes["class"] ?: "").contains("synthwave-kicker") }
                ?.let { Html.text(it.inner) }
                ?.takeIf { it.isNotEmpty() }
            val blurbs = paragraphs
                .filter { (it.attributes["class"] ?: "").contains("firmware-blurb") }
                .map { Html.text(it.inner) }
                .filter { it.isNotEmpty() }
            val status = Html.elements("span", article.inner).map { Html.text(it.inner) }
            val latest = status.firstOrNull { it.startsWith("Latest ") }?.removePrefix("Latest ")
                ?: status.firstOrNull { it.contains("Latest ") }?.substringAfter("Latest ")
            val experimental = status.any { it.contains("Experimental", ignoreCase = true) }
            val links = ArrayList<CatalogueLink>()
            val linkList = Html.elements("ul", article.inner).firstOrNull { list ->
                (list.attributes["class"] ?: "").contains("firmware-links")
            }
            for (anchorElement in Html.elements("a", linkList?.inner ?: "")) {
                val href = anchorElement.attributes["href"] ?: continue
                val url = runCatching { page.resolve(href).toString() }.getOrNull() ?: continue
                if (!url.startsWith("http")) continue
                val label = Html.text(anchorElement.inner).replace("↗", "").trim().ifEmpty {
                    URI(url).host ?: "Link"
                }
                val link = CatalogueLink(label, url)
                if (links.none { it.comparable == link.comparable }) links.add(link)
            }
            return Entry(
                anchor = anchor,
                name = title,
                author = author,
                blurb = blurbs.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
                latest = latest,
                updated = article.attributes["data-updated"],
                stars = article.attributes["data-stars"]?.toIntOrNull(),
                pinned = article.attributes.containsKey("data-pinned"),
                experimental = experimental,
                links = links,
            )
        }
    }
}

object CatalogueBuilder {
    fun merge(
        hub: List<CatalogueEntry>,
        editor: List<EditorListing.Entry>,
        editorUrl: String = EditorListing.DEFAULT_URL,
    ): List<CatalogueEntry> {
        val entries = hub.toMutableList()
        val page = URI(editorUrl)
        for (listed in editor) {
            val pageLink = CatalogueLink(
                "fm1-editor.com listing",
                page.resolve("#${listed.anchor}").toString(),
            )
            val index = entries.indexOfFirst { matches(it, listed) }
            if (index >= 0) {
                val entry = entries[index]
                val extra = (listed.links + pageLink).filter { link ->
                    entry.links.none { it.comparable == link.comparable }
                }
                entries[index] = entry.copy(
                    what = entry.what ?: listed.blurb,
                    author = entry.author ?: listed.author,
                    stars = entry.stars ?: listed.stars,
                    links = entry.links + extra,
                )
            } else {
                val official = isOfficialV15(listed)
                val id = if (official) "mvave-official" else "fm1-editor-${listed.anchor}"
                if (entries.any { it.id == id }) continue
                entries += CatalogueEntry(
                    id = id,
                    name = if (official) "FM-1 V15" else listed.name,
                    author = listed.author,
                    what = listed.blurb,
                    version = listed.latest,
                    date = listed.updated,
                    beta = listed.experimental,
                    links = listed.links + pageLink,
                    stars = listed.stars,
                    isOfficial = official,
                )
            }
        }
        return entries
    }

    fun isOfficialV15(listed: EditorListing.Entry): Boolean {
        if (listed.anchor == "stock") return true
        val isOpen = listOf(listed.anchor, listed.name).any { it.contains("open", ignoreCase = true) }
        return !isOpen && listed.links.any { it.url.contains("m-vave.com", ignoreCase = true) }
    }

    fun matches(entry: CatalogueEntry, listed: EditorListing.Entry): Boolean {
        // Official Open shares M-VAVE GitHub links with V15; it is not the same firmware.
        if (entry.isOfficial != isOfficialV15(listed)) return false
        val ours = PackageDiscovery.gitHubRepos(entry.links).map { "${it.first}/${it.second}".lowercase() }.toSet()
        val theirs = PackageDiscovery.gitHubRepos(listed.links).map { "${it.first}/${it.second}".lowercase() }.toSet()
        if (ours.any { it in theirs }) return true
        return normalisedName(entry.name) == normalisedName(listed.name)
    }

    fun normalisedName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun fallBack(entry: CatalogueEntry, previous: CatalogueEntry?): CatalogueEntry {
        if (previous?.packageURL == null) return entry
        return entry.copy(
            packageURL = previous.packageURL,
            version = previous.version,
            date = previous.date,
            beta = previous.beta,
            product = previous.product,
            size = previous.size,
            sha256 = previous.sha256,
        )
    }
}
