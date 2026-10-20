package com.fmswitch.catalogue

/**
 * How TRY plays a firmware before it is flashed.
 *
 * The APK carries Joakim Eriksson's FM-1 emulator (fm1-emu, MIT) as native code
 * (`libfm1emu.so`, built from `native/fm1-jni`): the same `.fwsc` the app would
 * flash boots on an emulated JieLi AC791N, with sound through the phone and the
 * panel drawn in Compose.
 *
 * Some publishers also ship their own browser emulator (RedtailWorks' rustypawz
 * pages, hub `/play/` links); [authorEmulator] finds those as an extra option.
 */
object FirmwareEmulator {
    const val CHIP_EMU = "https://joakimeriksson.github.io/fm1-emu/"
    const val CHIP_SOURCE = "https://github.com/joakimeriksson/fm1-emu"

    /** fm1-emu's public page for this firmware, for opening in a browser. */
    fun onlineUrl(entry: CatalogueEntry): String = buildString {
        append(CHIP_EMU)
        chipSlug(entry)?.let { append("?fw=").append(it) }
    }

    fun authorEmulator(entry: CatalogueEntry): String? = dedicatedUrl(entry) ?: playLink(entry)

    fun playLink(entry: CatalogueEntry): String? {
        val ranked = entry.links.mapNotNull { link ->
            val label = link.label.lowercase()
            val url = link.url.lowercase()
            val score = when {
                "/emu" in url || url.endsWith("/emu") || url.endsWith("/emu/") -> 0
                "/play/" in url -> 1
                "browser" in label && "emu" in url -> 0
                "browser" in label -> 2
                "workbench" in url && CatalogueBuilder.normalisedName(entry.name).contains("officialopen") -> 3
                else -> return@mapNotNull null
            }
            score to link.url
        }
        return ranked.minByOrNull { it.first }?.second
    }

    fun dedicatedUrl(entry: CatalogueEntry): String? {
        val key = CatalogueBuilder.normalisedName(entry.name).ifEmpty {
            entry.id.lowercase().filter { it.isLetterOrDigit() }
        }
        return REDTAIL[key] ?: REDTAIL[entry.id.lowercase()]
    }

    fun chipSlug(entry: CatalogueEntry): String? {
        if (entry.isOfficial) return "mvave"
        val id = entry.id.lowercase()
        return when {
            id.startsWith("redtail-") -> when (id.removePrefix("redtail-")) {
                "hyp" -> "hyp1"
                "lof" -> "lof1"
                "eco" -> "eco1"
                "grav" -> "grav1"
                else -> null
            }
            id.startsWith("fm1-editor-") -> id.removePrefix("fm1-editor-").replace("-", "")
            else -> id
        }
    }

    private val REDTAIL = mapOf(
        "hyp1" to "https://rustypawz.github.io/fm1-HYP1/emu/",
        "hyp" to "https://rustypawz.github.io/fm1-HYP1/emu/",
        "redtail-hyp" to "https://rustypawz.github.io/fm1-HYP1/emu/",
        "lof1" to "https://rustypawz.github.io/fm1-HYP1/lof/emu/",
        "lof" to "https://rustypawz.github.io/fm1-HYP1/lof/emu/",
        "redtail-lof" to "https://rustypawz.github.io/fm1-HYP1/lof/emu/",
        "eco1" to "https://rustypawz.github.io/fm1-HYP1/eco/emu/",
        "eco" to "https://rustypawz.github.io/fm1-HYP1/eco/emu/",
        "redtail-eco" to "https://rustypawz.github.io/fm1-HYP1/eco/emu/",
        "grav1" to "https://rustypawz.github.io/fm1-HYP1/grav/emu/",
        "grav" to "https://rustypawz.github.io/fm1-HYP1/grav/emu/",
        "redtail-grav" to "https://rustypawz.github.io/fm1-HYP1/grav/emu/",
    )
}
