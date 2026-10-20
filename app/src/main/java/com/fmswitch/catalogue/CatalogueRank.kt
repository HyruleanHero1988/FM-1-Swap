package com.fmswitch.catalogue

/**
 * Order the firmware list the way people actually look at it: official V15 first,
 * then the ones everyone already knows (SLOOP, Felucca, …), then the long tail.
 *
 * Popularity is GitHub stars the hub reports, plus hub install counts. Editor-pinned
 * listings with no GitHub numbers (Baud Girl) still sit with the well-known ones.
 */
object CatalogueRank {
    private const val PINNED_STAR_FLOOR = 200

    fun compare(a: CatalogueEntry, b: CatalogueEntry): Int {
        if (a.isOfficial != b.isOfficial) return if (a.isOfficial) -1 else 1
        val byPopularity = popularity(b).compareTo(popularity(a))
        if (byPopularity != 0) return byPopularity
        return a.name.lowercase().compareTo(b.name.lowercase())
    }

    fun ordered(entries: List<CatalogueEntry>): List<CatalogueEntry> =
        entries.sortedWith(::compare)

    fun popularity(entry: CatalogueEntry): Int {
        val stars = entry.stars ?: 0
        val installs = entry.installs ?: 0
        val featured = if (entry.pinned) maxOf(stars, PINNED_STAR_FLOOR) else stars
        return featured * 10 + installs
    }
}
