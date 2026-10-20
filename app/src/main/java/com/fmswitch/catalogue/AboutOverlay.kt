package com.fmswitch.catalogue

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * GitHub About lines baked beside the shipped list. A line already on the entry,
 * from a refresh, is left as it is.
 */
object AboutOverlay {
    const val ASSET_PATH = "catalogue/abouts.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun apply(entries: List<CatalogueEntry>, overlayText: String): List<CatalogueEntry> {
        if (overlayText.isBlank()) return entries
        val overlay = runCatching { json.decodeFromString<Map<String, String>>(overlayText) }.getOrNull()
            ?: return entries
        if (overlay.isEmpty()) return entries
        return entries.map { entry ->
            if (!entry.about.isNullOrBlank()) return@map entry
            val about = overlay[entry.id]?.trim()?.takeIf { it.isNotEmpty() } ?: return@map entry
            entry.copy(about = about)
        }
    }
}

/** The one line the firmware list shows so a row is more than a name. */
object CatalogueBlurb {
    fun line(entry: CatalogueEntry): String? {
        entry.about?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val what = entry.what?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (what.isEmpty()) return null
        if (what.length <= 140) return what
        val cut = what.take(140)
        val space = cut.lastIndexOf(' ')
        val base = if (space >= 80) cut.take(space) else cut
        return base.trimEnd() + "…"
    }
}
