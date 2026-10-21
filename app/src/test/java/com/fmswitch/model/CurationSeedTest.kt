package com.fmswitch.model

import com.fmswitch.catalogue.MergedCatalogue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CurationSeedTest {
    @Test
    fun shortlistIsBaudGirlSloopClipperHyp1FeluccaThenRainbow() {
        val snapshot = Json { ignoreUnknownKeys = true }
            .decodeFromString<MergedCatalogue>(snapshotFile().readText())
        val chosen = CurationSeed.IDS.map { id -> snapshot.entries.single { it.id == id } }
        assertEquals(
            listOf(
                "Baud Girl (FM-1+VA)",
                "SLOOP",
                "Clipper",
                "HYP-1",
                "Felucca",
                "Rainbow Mode",
            ),
            chosen.map { it.name },
        )
        assertEquals("RedtailWorks", chosen[3].author)
        assertTrue(chosen[3].what.orEmpty().contains("techno"))
        assertTrue(chosen[5].links.any { it.url.contains("felucca-rainbow") })
    }

    @Test
    fun seedRunsOnce() {
        assertTrue(CurationSeed.shouldApply(0))
        assertFalse(CurationSeed.shouldApply(CurationSeed.VERSION))
    }

    private fun snapshotFile(): File {
        val direct = File("src/main/assets/catalogue/snapshot.json")
        if (direct.isFile) return direct
        return File("app/src/main/assets/catalogue/snapshot.json")
    }
}
