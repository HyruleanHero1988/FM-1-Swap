package com.fmswitch.catalogue

/** How the firmware list is ordered. MY CURATION is the personal shortlist. */
enum class FirmwareSort(val id: String, val label: String) {
    CURATION("curation", "MY CURATION"),
    POPULAR("popular", "POPULAR"),
    NAME("name", "NAME"),
    AUTHOR("author", "AUTHOR"),
    NEWEST("newest", "NEWEST"),
}

object CatalogueOrder {
    /**
     * [curation] is the personal order: earlier ids sit higher when the sort is
     * [FirmwareSort.CURATION]. Everything left out keeps the order it arrived in,
     * which is the popularity ranking.
     */
    fun apply(entries: List<CatalogueEntry>, sort: FirmwareSort, curation: List<String>): List<CatalogueEntry> {
        return when (sort) {
            FirmwareSort.CURATION -> {
                val index = curation.withIndex().associate { it.value to it.index }
                entries.sortedBy { index[it.id] ?: Int.MAX_VALUE }
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
