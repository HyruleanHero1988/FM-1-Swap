package com.fmswitch.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a firmware is known to do on a newer FM-1: the units sold since autumn 2026 carry a UC25HQ80
 * flash chip (JEDEC ID 0xB36014) where the first units have a Puya (0x856014). A firmware that only
 * knows the old ID does not write the flash there: saves stay in RAM, and the firmware often cannot
 * take another update either, which leaves the unit stuck until it is recovered from a computer.
 *
 * Every firmware is [UNKNOWN] until a source says otherwise. Nothing is ever inferred to be safe.
 */
@Serializable
enum class NewChipSupport(val id: String) {
    UNKNOWN("unknown"),

    /** Read from the package as knowing the 0xB36014 chip: saves and updates work on a newer FM-1. */
    WORKS("works"),

    /** Only knows the first units' chip: saves are lost and updates often fail on a newer FM-1 (stuck, not destroyed). */
    BRICKS("bricks"),
    ;

    companion object {
        fun fromId(id: String?): NewChipSupport =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: UNKNOWN

        /**
         * The Firmware Hub's `chip` field, read from each package: `new` knows the newer chip, `old` knows only the
         * first units' chip, and `any` means the package does not check the chip and the hub has not tried it on the
         * newer one. Only `new` counts as confirmed, and only `old` as a known failure.
         */
        fun fromHub(chip: String?): NewChipSupport = when (chip?.trim()?.lowercase()) {
            "new" -> WORKS
            "old" -> BRICKS
            else -> UNKNOWN
        }

        /** When two sources disagree the failure wins: a warning too many is better than a missing one. */
        fun merge(vararg all: NewChipSupport): NewChipSupport = merge(all.asList())

        fun merge(all: Collection<NewChipSupport>): NewChipSupport = when {
            BRICKS in all -> BRICKS
            WORKS in all -> WORKS
            else -> UNKNOWN
        }
    }
}

/**
 * Compatibility marks kept in `assets/catalogue/newchip.json`, matched to a package by its SHA-256 so a mark
 * never carries over to a different build of the same firmware. A package that is not in the file keeps
 * whatever the live hub feed said, which is [NewChipSupport.UNKNOWN] when nothing did.
 *
 * `scripts/update-newchip.py` rewrites the file from the hub's catalog.json. A maintainer can add or change a
 * line by hand: `{"sha256": "...", "status": "bricks", "source": "link to the report"}`.
 */
object NewChipOverlay {
    const val ASSET_PATH = "catalogue/newchip.json"

    class Marks internal constructor(private val bySha: Map<String, NewChipSupport>) {
        val size: Int get() = bySha.size
        fun statusOf(sha256: String?): NewChipSupport =
            sha256?.lowercase()?.let { bySha[it] } ?: NewChipSupport.UNKNOWN
        fun count(status: NewChipSupport): Int = bySha.values.count { it == status }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Marks {
        if (text.isBlank()) return Marks(emptyMap())
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Marks(emptyMap())
        val firmwares = (root["firmwares"] as? kotlinx.serialization.json.JsonArray) ?: return Marks(emptyMap())
        val marks = LinkedHashMap<String, NewChipSupport>()
        for (element in firmwares) {
            val obj = element as? JsonObject ?: continue
            val sha = obj["sha256"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: continue
            if (!SHA256.matches(sha)) continue
            val status = NewChipSupport.fromId(obj["status"]?.jsonPrimitive?.contentOrNull)
            marks[sha] = NewChipSupport.merge(listOfNotNull(marks[sha], status))
        }
        return Marks(marks)
    }

    fun apply(entries: List<CatalogueEntry>, marks: Marks): List<CatalogueEntry> {
        if (marks.size == 0) return entries
        return entries.map { entry ->
            val merged = NewChipSupport.merge(entry.newChip, marks.statusOf(entry.sha256))
            if (merged == entry.newChip) entry else entry.copy(newChip = merged)
        }
    }

    fun apply(entries: List<CatalogueEntry>, overlayText: String): List<CatalogueEntry> =
        apply(entries, parse(overlayText))

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}
