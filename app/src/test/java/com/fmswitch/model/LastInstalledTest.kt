package com.fmswitch.model

import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastInstalledTest {
    private val zone = ZoneOffset.UTC
    private val day = 1_792_800_000_000L // 2026-10-24 00:00 UTC

    @Test
    fun marksTheFirmwareItselfNotEveryForkWithTheSameDeviceName() {
        val rainbow = FlashRecord(
            name = "Rainbow Mode",
            version = "1.1.5-rb",
            product = "FM-1_911",
            date = day,
            catalogueID = "rainbow",
            sha256 = "abc",
        )
        val other = FlashRecord(
            name = "Another Fork",
            version = "1.0",
            product = "FM-1_911",
            date = day - 86_400_000L,
            catalogueID = "other",
        )
        val records = listOf(rainbow, other)
        assertEquals(rainbow, lastInstalledRecord(records, "rainbow", null, "Rainbow Mode", "1.1.5-rb"))
        assertEquals(other, lastInstalledRecord(records, "other", null, "Another Fork", "1.0"))
        assertNull(lastInstalledRecord(records, "felucca", null, "Felucca", "1.6.1"))
    }

    @Test
    fun fileHashAndOlderNameRecordsStillMatch() {
        val byHash = FlashRecord(name = "SLOOP", version = "2.7.1", product = "FM-1_900", date = day, sha256 = "DEF")
        assertEquals(byHash, lastInstalledRecord(listOf(byHash), "sloop", "def", "SLOOP", "2.7.1"))
        val older = FlashRecord(name = "Clipper", version = "1.0", product = "FM-1_900", date = day)
        assertEquals(older, lastInstalledRecord(listOf(older), "clipper", null, "clipper", "1.0"))
        assertNull(lastInstalledRecord(listOf(older), "clipper-2", "nope", "Clipper", "1.1"))
    }

    @Test
    fun newestInstallOfThatFirmwareWins() {
        val first = FlashRecord(name = "Felucca", version = "1.6.0", product = "FM-1_911", date = day - 86_400_000L, catalogueID = "felucca")
        val second = FlashRecord(name = "Felucca", version = "1.6.1", product = "FM-1_911", date = day, catalogueID = "felucca")
        assertEquals(second, lastInstalledRecord(listOf(second, first), "felucca", null, "Felucca", "1.6.1"))
    }

    @Test
    fun dateIsDayMonthYear() {
        val record = FlashRecord(name = "SLOOP", version = "2.7.1", product = "FM-1_900", date = day)
        assertEquals("Last installed 24-10-26", lastInstalledMarker(record, "2.7.1", zone))
        assertEquals("Last installed 2.7.1 · 24-10-26", lastInstalledMarker(record, "2.8.0", zone))
    }

    @Test
    fun anOlderHistoryRecordStillLoads() {
        val record = Json { ignoreUnknownKeys = true }.decodeFromString<FlashRecord>(
            """{"id":"a","name":"SLOOP","version":"2.7","product":"FM-1_900","date":1}""",
        )
        assertNull(record.catalogueID)
        assertNull(record.sha256)
        assertEquals("SLOOP", record.name)
    }
}
