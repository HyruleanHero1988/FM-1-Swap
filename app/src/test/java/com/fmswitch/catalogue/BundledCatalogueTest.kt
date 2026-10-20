package com.fmswitch.catalogue

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

class BundledCatalogueTest {
    @Test
    fun snapshotShipsTheKnownListWithPackages() {
        val file = File(moduleRoot(), "src/main/assets/${MergedCatalogue.ASSET_PATH}")
        if (!file.isFile) restorePackedSnapshot(file)
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
        val shown = PhotoOverlay.apply(
            snapshot.entries,
            File(moduleRoot(), "src/main/assets/catalogue/photos.json").readText(),
        )
        val sloop = shown.single { CatalogueBuilder.normalisedName(it.name) == "sloop" }
        assertNotNull(sloop.packageURL)
        assertEquals(
            "https://raw.githubusercontent.com/isod89/sloop-fm1/main/assets/screens/sloop-2.6-screens.png",
            sloop.photoURL,
        )
        assertNotNull(snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "clipper" }.packageURL)
        assertNotNull(snapshot.entries.single { CatalogueBuilder.normalisedName(it.name) == "officialopen" }.packageURL)
    }

    private fun restorePackedSnapshot(file: File) {
        val packed = packedSnapshotText() ?: return
        val bytes = GZIPInputStream(
            Base64.getMimeDecoder().decode(packed).inputStream(),
        ).use { it.readBytes() }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun packedSnapshotText(): String? {
        val scripts = File(moduleRoot(), "../scripts").canonicalFile
        val whole = File(scripts, "catalogue-snapshot.json.gz.b64")
        if (whole.isFile) return whole.readText()
        val parts = scripts.listFiles { _, name ->
            name.startsWith("catalogue-snapshot.json.gz.b64.part")
        }?.sortedBy { it.name }.orEmpty()
        if (parts.isEmpty()) return null
        return parts.joinToString("") { it.readText() }
    }

    private fun moduleRoot(): File {
        val cwd = File(".").canonicalFile
        if (File(cwd, "src/main/assets").isDirectory) return cwd
        return File(cwd, "app")
    }
}
