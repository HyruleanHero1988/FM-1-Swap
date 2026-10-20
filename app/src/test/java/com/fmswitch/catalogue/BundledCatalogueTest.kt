package com.fmswitch.catalogue

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledCatalogueTest {
    @Test
    fun snapshotShipsTheKnownListWithPackages() {
        val file = File(moduleRoot(), "src/main/assets/${MergedCatalogue.ASSET_PATH}")
        assertTrue("missing ${file.canonicalPath}; run scripts/bake-catalogue.sh", file.isFile)
        val json = Json { ignoreUnknownKeys = true }
        val snapshot = json.decodeFromString<MergedCatalogue>(file.readText())
        assertTrue(snapshot.bundled)
        assertEquals(MergedCatalogue.BUNDLED_VERSION, snapshot.snapshotVersion)
        assertTrue(snapshot.entries.size >= 70)
        val names = snapshot.entries.map { CatalogueBuilder.normalisedName(it.name) }
        assertTrue(names.contains("sloop"))
        assertTrue(names.contains("felucca"))
        assertTrue(names.contains("baudgirlfm1va"))
        assertTrue(names.contains("clipper"))
        assertTrue(names.contains("officialopen"))
        assertTrue(names.contains("lof1"))
        assertTrue(names.contains("hyp1"))
        val official = snapshot.entries.single { it.isOfficial }
        assertEquals("FM-1 V15", official.name)
        assertEquals("mvave-official", official.id)
        assertNull(official.packageURL)
        val open = snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "officialopen" }
        assertNotNull(open.packageURL)
        assertTrue(open.packageURL!!.contains("Workbench", ignoreCase = true))
        assertTrue(snapshot.entries.count { it.packageURL != null } >= 70)
        assertNotNull(snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "sloop" }.packageURL)
        assertNotNull(snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "clipper" }.packageURL)
        assertNotNull(snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "officialopen" }.packageURL)
    }

    private fun moduleRoot(): File {
        val cwd = File(".").canonicalFile
        if (File(cwd, "src/main/assets").isDirectory) return cwd
        return File(cwd, "app")
    }
}
