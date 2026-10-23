package com.fmswitch.catalogue

/**
 * How TRY plays a firmware before it is flashed.
 *
 * The APK carries Joakim Eriksson's FM-1 emulator (fm1-emu, MIT) as native code
 * (`libfm1emu.so`, built from `native/fm1-jni`): the same `.fwsc` the app would
 * flash boots on an emulated JieLi AC791N, with sound through the phone and the
 * panel drawn in Compose.
 *
 * A few publishers also ship a browser emulator (hub `/play/` links, M-VAVE's
 * Workbench). [playLink] finds those for the IN BROWSER catalogue chip.
 * RedtailWorks' machines play here, in the same emulator as everything else.
 */
object FirmwareEmulator {
    const val CHIP_EMU = "https://joakimeriksson.github.io/fm1-emu/"
    const val CHIP_SOURCE = "https://github.com/joakimeriksson/fm1-emu"

    /** fm1-emu's public page for this firmware, for opening in a browser. */
    fun onlineUrl(entry: CatalogueEntry): String = buildString {
        append(CHIP_EMU)
        chipSlug(entry)?.let { append("?fw=").append(it) }
    }

    fun playLink(entry: CatalogueEntry): String? {
        val ranked = entry.links.mapNotNull { link ->
            val label = link.label.lowercase()
            val url = link.url.lowercase()
            if ("rustypawz.github.io" in url) return@mapNotNull null
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
}
