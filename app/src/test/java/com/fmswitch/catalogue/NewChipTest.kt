package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewChipTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    private fun entry(id: String, sha: String?, support: NewChipSupport = NewChipSupport.UNKNOWN) =
        CatalogueEntry(id = id, name = id, sha256 = sha, newChip = support)

    @Test
    fun everyFirmwareIsUnknownByDefault() {
        assertEquals(NewChipSupport.UNKNOWN, CatalogueEntry(id = "x", name = "X").newChip)
        assertEquals(NewChipSupport.UNKNOWN, officialCatalogueEntry().copy(sha256 = null).newChip)
    }

    @Test
    fun theHubChipFieldMapsOnlyNewToWorksAndOldToBricks() {
        assertEquals(NewChipSupport.WORKS, NewChipSupport.fromHub("new"))
        assertEquals(NewChipSupport.BRICKS, NewChipSupport.fromHub("old"))
        assertEquals(NewChipSupport.UNKNOWN, NewChipSupport.fromHub("any"))
        assertEquals(NewChipSupport.UNKNOWN, NewChipSupport.fromHub(null))
        assertEquals(NewChipSupport.UNKNOWN, NewChipSupport.fromHub("both"))
    }

    @Test
    fun theHubFeedCarriesTheChipIntoTheEntry() {
        val feed = """
            {"updated": "2026-11-11 03:01 UTC", "firmwares": [
              {"id": "a", "name": "A", "pkg": "fw/a.fwsc", "chip": "new"},
              {"id": "b", "name": "B", "pkg": "fw/b.fwsc", "chip": "old"},
              {"id": "c", "name": "C", "pkg": "fw/c.fwsc", "chip": "any"},
              {"id": "d", "name": "D", "pkg": "fw/d.fwsc"}
            ]}
        """.trimIndent()
        val entries = HubFeed.decode(feed).second.associate { it.id to it.newChip }
        assertEquals(NewChipSupport.WORKS, entries["a"])
        assertEquals(NewChipSupport.BRICKS, entries["b"])
        assertEquals(NewChipSupport.UNKNOWN, entries["c"])
        assertEquals(NewChipSupport.UNKNOWN, entries["d"])
    }

    @Test
    fun aFailureWinsWhenSourcesDisagree() {
        assertEquals(NewChipSupport.BRICKS, NewChipSupport.merge(NewChipSupport.WORKS, NewChipSupport.BRICKS))
        assertEquals(NewChipSupport.WORKS, NewChipSupport.merge(NewChipSupport.UNKNOWN, NewChipSupport.WORKS))
        assertEquals(NewChipSupport.UNKNOWN, NewChipSupport.merge(emptyList()))
    }

    @Test
    fun theFilterShowsOnlyFirmwaresConfirmedOnNewerUnits() {
        val works = entry("works", shaA, NewChipSupport.WORKS)
        val bricks = entry("bricks", shaB, NewChipSupport.BRICKS)
        val unknown = entry("unknown", shaC)
        val shown = listOf(works, bricks, unknown).filter { FirmwareKind.NEW_CHIP.matches(it) }
        assertEquals(listOf("works"), shown.map { it.id })
        assertTrue(FirmwareKind.ALL.matches(bricks))
        assertFalse(FirmwareKind.NEW_CHIP.matches(unknown))
        assertFalse(FirmwareKind.NEW_CHIP.matches(bricks))
    }

    @Test
    fun theFilterIsOneOfTheChipsAndSurvivesStoredIds() {
        assertEquals(FirmwareKind.NEW_CHIP, FirmwareKind.fromId("newchip"))
        assertEquals("SAVES ON NEWER FM-1S", FirmwareKind.NEW_CHIP.label)
    }

    @Test
    fun theOverlayMarksOnlyThePackageItNames() {
        val text = """
            {"firmwares": [
              {"sha256": "$shaA", "status": "works"},
              {"sha256": "${shaB.uppercase()}", "status": "bricks"},
              {"sha256": "$shaC", "status": "safe-i-guess"},
              {"sha256": "not-a-hash", "status": "works"}
            ]}
        """.trimIndent()
        val marked = NewChipOverlay.apply(
            listOf(
                entry("a", shaA),
                entry("b", shaB),
                entry("c", shaC),
                entry("a-newer-build", "d".repeat(64)),
                entry("no-package", null),
            ),
            text,
        ).associate { it.id to it.newChip }
        assertEquals(NewChipSupport.WORKS, marked["a"])
        assertEquals(NewChipSupport.BRICKS, marked["b"])
        assertEquals(NewChipSupport.UNKNOWN, marked["c"])
        assertEquals(NewChipSupport.UNKNOWN, marked["a-newer-build"])
        assertEquals(NewChipSupport.UNKNOWN, marked["no-package"])
    }

    @Test
    fun theOverlayNeverClearsAFailureTheHubFeedReported() {
        val marked = NewChipOverlay.apply(
            listOf(entry("a", shaA, NewChipSupport.BRICKS)),
            """{"firmwares": [{"sha256": "$shaA", "status": "works"}]}""",
        )
        assertEquals(NewChipSupport.BRICKS, marked.single().newChip)
    }

    @Test
    fun aBrokenOverlayLeavesEverythingUnknown() {
        val entries = listOf(entry("a", shaA))
        assertEquals(entries, NewChipOverlay.apply(entries, ""))
        assertEquals(entries, NewChipOverlay.apply(entries, "{not json"))
        assertEquals(entries, NewChipOverlay.apply(entries, """{"firmwares": 3}"""))
    }

    @Test
    fun theShippedMarksAreSourcedAndNeverGuessSafe() {
        val text = File(moduleRoot(), "src/main/assets/${NewChipOverlay.ASSET_PATH}").readText()
        val marks = NewChipOverlay.parse(text)
        assertTrue(marks.size > 100)
        assertTrue(marks.count(NewChipSupport.WORKS) > 0)
        assertTrue(marks.count(NewChipSupport.BRICKS) > 0)
        val official = marks.statusOf(officialCatalogueEntry().sha256)
        assertEquals(NewChipSupport.WORKS, official)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val source = root["source"] as kotlinx.serialization.json.JsonObject
        assertTrue(source["url"].toString().contains("thefirmwarehub.com"))
        assertTrue(source["license"].toString().contains("CC-BY-4.0"))
        val rows = root["firmwares"] as kotlinx.serialization.json.JsonArray
        for (row in rows) {
            val obj = row as kotlinx.serialization.json.JsonObject
            val status = (obj["status"] as kotlinx.serialization.json.JsonPrimitive).content
            assertTrue("bad status $status", status in setOf("works", "bricks", "unknown"))
            val origin = (obj["origin"] as kotlinx.serialization.json.JsonPrimitive).content
            if (origin == "manual") assertTrue("a manual mark needs a source", obj["source"] != null)
        }
    }

    @Test
    fun theBundledListOnlyShowsWhatTheMarksConfirm() {
        val marks = NewChipOverlay.parse(File(moduleRoot(), "src/main/assets/${NewChipOverlay.ASSET_PATH}").readText())
        val snapshot = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MergedCatalogue>(File(moduleRoot(), "src/main/assets/${MergedCatalogue.ASSET_PATH}").readText())
        val shown = NewChipOverlay.apply(snapshot.entries + officialCatalogueEntry(), marks)
        for (entry in shown.filter { FirmwareKind.NEW_CHIP.matches(it) }) {
            assertEquals(NewChipSupport.WORKS, marks.statusOf(entry.sha256))
        }
        assertTrue(shown.count { it.newChip == NewChipSupport.UNKNOWN } > 0)
    }

    private fun moduleRoot(): File {
        val cwd = File(".").canonicalFile
        if (File(cwd, "src/main/assets").isDirectory) return cwd
        return File(cwd, "app")
    }
}
