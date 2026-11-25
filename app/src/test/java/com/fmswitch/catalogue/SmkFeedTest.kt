package com.fmswitch.catalogue

import com.fmswitch.fm1kit.DeviceFamily
import com.fmswitch.fm1kit.FM1Identity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmkFeedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun bundledSmkShipsGenkigenkiVariantsAndBaudGirlStub() {
        val file = File(moduleRoot(), "src/main/assets/${SmkFeed.ASSET_PATH}")
        assertTrue(file.isFile)
        val snapshot = json.decodeFromString<MergedCatalogue>(file.readText())
        assertTrue(snapshot.bundled)
        assertTrue(snapshot.entries.size >= 25)
        val baud = snapshot.entries.single { it.id == "baudgirl-smk37va" }
        assertEquals("SMK-37+VA", baud.name)
        assertNull(baud.packageURL)
        assertTrue(baud.allowSiblingSmkModel)
        assertTrue(baud.isSmkListing)
        assertFalse(baud.isDownloadable)
        assertTrue(baud.links.any { it.url.contains("baudgirl.com/work/SMK-37+VA") })
        assertTrue(baud.links.any { it.label.equals("Install", ignoreCase = true) })

        val makkuro = snapshot.entries.single { it.id == "makkuro37" }
        assertEquals(3, makkuro.smkModels.size)
        assertNotNull(makkuro.smkModels["pro"]?.packageURL)
        assertNotNull(makkuro.smkModels["elite"]?.packageURL)
        assertNotNull(makkuro.smkModels["mke"]?.packageURL)

        val sloop = snapshot.entries.single { it.id == "sloop-pro" }
        assertTrue(sloop.smkModels.containsKey("pro"))
        assertTrue(sloop.smkModels.containsKey("elite"))
        assertFalse(snapshot.entries.any { it.id == "sloop-pro-elite" })
    }

    @Test
    fun forDevicePicksEliteAndFallsBackToPro() {
        val entry = CatalogueEntry(
            id = "makkuro37",
            name = "Makkuro37",
            platforms = listOf(DevicePlatform.SMK37),
            allowSiblingSmkModel = true,
            smkModels = mapOf(
                "pro" to SmkModelPackage("SMK-37 Pro_703", "https://example/pro.fwsc", "aa", 1),
                "elite" to SmkModelPackage("SMK-37 Elite_703", "https://example/elite.fwsc", "bb", 2),
                "mke" to SmkModelPackage("MKE-P37_703", "https://example/mke.fwsc", "cc", 3),
            ),
        )
        val elite = entry.forDevice(FM1Identity.parse("SMK-37 Elite_001"))
        assertEquals("https://example/elite.fwsc", elite.packageURL)
        assertEquals("SMK-37 Elite_703", elite.product)
        assertEquals("bb", elite.sha256)

        val starry = entry.forDevice(FM1Identity.parse("STARRYKEY-37 Play_001"))
        assertEquals("https://example/pro.fwsc", starry.packageURL)
        assertTrue(starry.allowSiblingSmkModel)

        val mke = entry.forDevice(FM1Identity.parse("MKE-P37_001"))
        assertEquals("https://example/mke.fwsc", mke.packageURL)
        assertEquals(DeviceFamily.VARIANT_MKE, DeviceFamily.smkVariantKey(FM1Identity.parse("MKE-P37_001")))
    }

    @Test
    fun parsePageReadsFwArrayAndMergeKeepsBaudGirl() {
        val html = """
            <script>
            const FW = [
              {
                "id": "makkuro37",
                "name": "Makkuro37",
                "version": "0.3",
                "v": {
                  "pro": {"product":"SMK-37 Pro_703","pkg":"firmware/makkuro37-0.3.fwsc","sha256":"aa","size":10},
                  "elite": {"product":"SMK-37 Elite_703","pkg":"firmware/makkuro37-0.3-elite.fwsc","sha256":"bb","size":11},
                  "mke": {"product":"MKE-P37_703","pkg":"firmware/makkuro37-0.3-mke.fwsc","sha256":"cc","size":12}
                }
              }
            ];
            </script>
        """.trimIndent()
        val parsed = SmkFeed.parsePage(html)
        assertEquals(1, parsed.size)
        assertEquals("makkuro37", parsed[0].id)
        assertTrue(parsed[0].smkModels["pro"]!!.packageURL.endsWith("/firmware/makkuro37-0.3.fwsc"))

        val baud = CatalogueEntry(
            id = "baudgirl-smk37va",
            name = "SMK-37+VA",
            platforms = listOf(DevicePlatform.SMK37),
            allowSiblingSmkModel = true,
            links = listOf(CatalogueLink("Install", "https://baudgirl.com/work/SMK-37+VA/install")),
        )
        val merged = SmkFeed.merge(listOf(baud), parsed)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == "baudgirl-smk37va" })
        assertTrue(merged.any { it.id == "makkuro37" && it.smkModels.size == 3 })
    }

    private fun moduleRoot(): File {
        val cwd = File(".").canonicalFile
        if (File(cwd, "src/main/assets").isDirectory) return cwd
        return File(cwd, "app")
    }
}
