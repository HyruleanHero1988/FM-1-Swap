package com.fmswitch.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * README shots baked beside the shipped list. Applied only when an entry has no photo,
 * so a picture from fm1-editor.com or a publisher is left as it is.
 */
object PhotoOverlay {
    const val ASSET_PATH = "catalogue/photos.json"

    @Serializable
    private data class Shot(val photoURL: String? = null, val photoAlt: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun apply(entries: List<CatalogueEntry>, overlayText: String): List<CatalogueEntry> {
        if (overlayText.isBlank()) return entries
        val overlay = runCatching { json.decodeFromString<Map<String, Shot>>(overlayText) }.getOrNull()
            ?: return entries
        if (overlay.isEmpty()) return entries
        return entries.map { entry ->
            if (!entry.photoURL.isNullOrBlank()) return@map entry
            val shot = overlay[entry.id] ?: return@map entry
            val url = shot.photoURL?.takeIf { it.isNotBlank() } ?: return@map entry
            entry.copy(photoURL = url, photoAlt = shot.photoAlt ?: entry.photoAlt)
        }
    }
}
