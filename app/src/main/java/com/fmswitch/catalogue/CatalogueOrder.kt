package com.fmswitch.catalogue

/** How the firmware list is ordered. FAVORITES puts marked firmwares first. */
enum class FirmwareSort(val id: String, val label: String) {
    CURATION("curation", "FAVORITES"),
    POPULAR("popular", "POPULAR"),
    NAME("name", "NAME"),
    AUTHOR("author", "AUTHOR"),
    NEWEST("newest", "NEWEST"),
}

object CatalogueOrder {
    /**
     * [favorites] sit first, in the order the user set, when the sort is
     * [FirmwareSort.CURATION]. With none marked, [starter] is that opening order.
     * Everything else keeps the order it arrived in, which is the popularity ranking.
     */
    fun apply(
        entries: List<CatalogueEntry>,
        sort: FirmwareSort,
        favorites: List<String>,
        starter: List<String> = emptyList(),
    ): List<CatalogueEntry> {
        return when (sort) {
            FirmwareSort.CURATION -> {
                val priority = (if (favorites.isEmpty()) starter else favorites)
                    .withIndex().associate { it.value to it.index }
                entries.withIndex()
                    .sortedWith(compareBy({ priority[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
                    .map { it.value }
            }
            FirmwareSort.POPULAR -> entries.sortedWith(CatalogueRank::compare)
            FirmwareSort.NAME -> entries.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            FirmwareSort.AUTHOR -> entries.sortedWith(
                compareBy<CatalogueEntry> { it.author == null }
                    .thenBy { (it.author ?: "").lowercase() }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id },
            )
            FirmwareSort.NEWEST -> entries.sortedWith(
                compareByDescending<CatalogueEntry> { it.date ?: "" }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id },
            )
        }
    }
}
