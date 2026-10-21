package com.fmswitch.model

/**
 * The shortlist the app starts with. Applied once. Later KEEP and LATER edits stay put.
 * HYP-1 here is Rustypawz's hypnotic techno, and Rainbow Mode is the Felucca rainbow build.
 */
object CurationSeed {
    const val VERSION = 1
    val IDS = listOf(
        "fm1-editor-baud-girl",
        "sloop",
        "fm1-editor-clipper",
        "hyp1",
        "felucca",
        "rainbow",
    )

    fun shouldApply(storedVersion: Int): Boolean = storedVersion < VERSION
}
