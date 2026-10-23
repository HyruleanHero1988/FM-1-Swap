package com.fmswitch.catalogue

/**
 * The firmware-hub groups, as chips on FIRMWARE. [ALL] is the whole list.
 * [PLAY] is a browser emulator. [SRC] is a GitHub Actions build the hub verified.
 * [VINTAGE] is a classic instrument rebuilt, which the hub keeps as its own group.
 */
enum class FirmwareKind(val id: String, val label: String) {
    ALL("all", "ALL"),
    SYNTH("synth", "SYNTHS"),
    GROOVE("groove", "GROOVEBOXES"),
    DRUMS("drums", "DRUMS"),
    CHORDS("chords", "CHORDS"),
    TRACKER("tracker", "TRACKERS"),
    DEV("dev", "FOR DEVS"),
    OTHER("other", "OTHER"),
    VINTAGE("vintage", "VINTAGE"),
    PLAY("play", "IN BROWSER"),
    SRC("src", "FROM SOURCE");

    fun matches(entry: CatalogueEntry): Boolean = when (this) {
        ALL -> true
        PLAY -> FirmwareEmulator.playLink(entry) != null
        SRC -> entry.builtFromSource
        VINTAGE -> entry.vintage || "vintage" in entry.kinds
        else -> id in entry.kinds
    }

    companion object {
        fun fromId(id: String?): FirmwareKind =
            entries.firstOrNull { it.id == id } ?: ALL
    }
}
