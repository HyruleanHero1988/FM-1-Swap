package com.fmswitch.catalogue

import java.net.URI

/**
 * RedtailWorks' pack at rustypawz.github.io/fm1-HYP1: HYP-1 plus the experimental
 * LOF-1, ECO-1 and GRAV-1 cards. The firmware hub only mirrors HYP-1.
 */
data class RedtailListing(val entries: List<Entry>) {
    data class Entry(
        val slug: String,
        val name: String,
        val tagline: String? = null,
        val blurb: String? = null,
        val experimental: Boolean = false,
        val links: List<CatalogueLink> = emptyList(),
        val photoURL: String? = null,
        val photoAlt: String? = null,
    )

    companion object {
        const val DEFAULT_URL = "https://rustypawz.github.io/fm1-HYP1/"
        const val AUTHOR = "RedtailWorks"
        private val slugs = setOf("hyp", "lof", "eco", "grav")

        fun parse(html: String, pageUrl: String = DEFAULT_URL): RedtailListing {
            val page = URI(pageUrl)
            val entries = Html.elements("article", Html.withoutComments(html)).mapNotNull { article ->
                entry(article, page)
            }
            return RedtailListing(entries)
        }

        private fun entry(article: Html.Element, page: URI): Entry? {
            if (!(article.attributes["class"] ?: "").contains("fw")) return null
            val title = Html.elements("h3", article.inner).firstOrNull() ?: return null
            val name = Html.elements("a", title.inner).firstOrNull()?.let { Html.text(it.inner) }
                ?: Html.text(title.inner)
            if (name.isEmpty()) return null
            val slug = slugOf(article.inner, page) ?: return null
            val experimental = Html.elements("span", article.inner).any {
                (it.attributes["class"] ?: "").contains("status") &&
                    Html.text(it.inner).contains("Experimental", ignoreCase = true)
            }
            val tagline = Html.elements("p", article.inner).firstOrNull { paragraph ->
                (paragraph.attributes["class"] ?: "").contains("what")
            }?.let { Html.text(it.inner) }?.takeIf { it.isNotEmpty() }
            val blurb = Html.elements("p", article.inner).firstOrNull { paragraph ->
                val cls = paragraph.attributes["class"] ?: ""
                "what" !in cls && Html.text(paragraph.inner).isNotEmpty()
            }?.let { Html.text(it.inner) }
            val img = Html.voids("img", article.inner).firstOrNull()
            val photoURL = img?.attributes?.get("src")?.takeIf { it.isNotBlank() }?.let { src ->
                runCatching { page.resolve(src).toString() }.getOrNull()?.takeIf { it.startsWith("http") }
            }
            val links = ArrayList<CatalogueLink>()
            fun add(label: String, href: String) {
                val url = runCatching { page.resolve(href).toString() }.getOrNull() ?: return
                if (!url.startsWith("http")) return
                val link = CatalogueLink(label, url)
                if (links.none { it.comparable == link.comparable }) links.add(link)
            }
            for (anchor in Html.elements("a", article.inner)) {
                val href = anchor.attributes["href"] ?: continue
                val label = Html.text(anchor.inner)
                if (label.isEmpty() || label.equals(name, ignoreCase = true)) continue
                add(label, href)
            }
            add("RedtailWorks pack", "./")
            add("Source", "https://github.com/RustyPawz/fm1-HYP1")
            val ordered = links.sortedBy { link ->
                when {
                    link.url.lowercase().contains("/install") || link.label.contains("install", true) -> 0
                    link.label.contains("about", true) -> 1
                    link.label.contains("try", true) || link.label.contains("browser", true) -> 2
                    else -> 3
                }
            }
            return Entry(
                slug = slug,
                name = name,
                tagline = tagline,
                blurb = blurb,
                experimental = experimental,
                links = ordered,
                photoURL = photoURL,
                photoAlt = img?.attributes?.get("alt")?.takeIf { it.isNotBlank() },
            )
        }

        private fun slugOf(inner: String, page: URI): String? {
            for (anchor in Html.elements("a", inner)) {
                val href = anchor.attributes["href"] ?: continue
                val path = runCatching { page.resolve(href).path.trim('/') }.getOrNull() ?: continue
                val last = path.substringAfterLast('/').lowercase()
                if (last in slugs) return last
                val piece = path.lowercase().split('/').firstOrNull { it in slugs }
                if (piece != null) return piece
            }
            return null
        }
    }
}
