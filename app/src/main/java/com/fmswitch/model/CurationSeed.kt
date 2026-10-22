package com.fmswitch.model

/**
 * The order the firmware list opens in, before anyone has marked a favorite.
 * HYP-1 here is Rustypawz's hypnotic techno, and Rainbow Mode is the Felucca rainbow build.
 *
 * Version 1 copied [IDS] into the favorite list, so a fresh install showed those rows as KEPT.
 * Version 2 leaves favorites empty. An untouched copy of that old list is cleared.
 */
object CurationSeed {
    const val VERSION = 2
    val IDS = listOf(
        "fm1-editor-baud-girl",
        "sloop",
        "fm1-editor-clipper",
        "hyp1",
        "felucca",
        "rainbow",
    )

    fun shouldApply(storedVersion: Int): Boolean = storedVersion < VERSION

    fun favoritesAfterUpgrade(storedVersion: Int, current: List<String>): List<String> {
        if (storedVersion >= VERSION) return current
        if (storedVersion >= 1 && current == IDS) return emptyList()
        return current
    }
}
