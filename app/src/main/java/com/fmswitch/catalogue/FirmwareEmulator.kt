package com.fmswitch.catalogue

/**
 * Where to try a firmware before flashing it.
 *
 * Many publishers already ship a playable browser emulator (RedtailWorks'
 * rustypawz pages, AMB-1, X0X, and the hub's `/play/` links). Those run the
 * author's own UI. Everything else falls through to Joakim Eriksson's FM-1
 * chip emulator, which executes the same `.fwsc` on a virtual JieLi AC791N.
 */
object FirmwareEmulator {
    const val CHIP_EMU = "https://joakimeriksson.github.io/fm1-emu/"
    const val CHIP_SOURCE = "https://github.com/joakimeriksson/fm1-emu"

    data class Launch(
        val url: String,
        val credit: String,
        val hint: String,
        val dedicated: Boolean,
    )

    fun launch(entry: CatalogueEntry): Launch {
        dedicatedUrl(entry)?.let { url ->
            return Launch(
                url = url,
                credit = "Author's own emulator (same code the FM-1 would run).",
                hint = "Tap Start if it asks, then play the on-screen keys and knobs. Sound comes from this phone, not the hardware.",
                dedicated = true,
            )
        }
        playLink(entry)?.let { url ->
            return Launch(
                url = url,
                credit = "Publisher's in-browser emulator.",
                hint = "Tap Start if it asks, then play the on-screen keys and knobs. Sound comes from this phone, not the hardware.",
                dedicated = true,
            )
        }
        val slug = chipSlug(entry)
        val url = buildString {
            append(CHIP_EMU)
            append("?embed=1")
            if (slug != null) append("&fw=").append(slug)
        }
        val officialHint = entry.isOfficial
        return Launch(
            url = url,
            credit = "Joakim Eriksson's FM-1 chip emulator (github.com/joakimeriksson/fm1-emu).",
            hint = if (officialHint) {
                "M-VAVE's stock package isn't fetched automatically. On the next screen pick M-VAVE stock firmware, or open a saved V15 .fwsc, then press Start."
            } else {
                "Press Start, then play the on-screen panel. It downloads the same .fwsc you would flash and runs it on a virtual AC791N. Turn the phone sideways for bigger controls."
            },
            dedicated = false,
        )
    }

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
