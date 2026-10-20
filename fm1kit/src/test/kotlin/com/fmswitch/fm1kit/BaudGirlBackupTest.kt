package com.fmswitch.fm1kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Dx7VoiceTest {
    @Test
    fun packedVoiceRoundTripsThroughTheEditBuffer() {
        val packed = SimulatedBaudGirl.namedVoice("TEST VOICE")
        packed[0] = 99
        packed[11] = 0x09
        packed[110] = 7
        val again = Dx7Voice.pack(Dx7Voice.unpack(packed))
        assertArrayEquals(packed, again)
        assertEquals("TEST VOICE", Dx7Voice.nameOf(packed))
    }
}

class BaudGirlSysexTest {
    @Test
    fun presetReadChecksumMatchesTheKnownSlotZeroMessage() {
        val message = BaudGirlSysex.encodePresetRead(0)
        assertArrayEquals(
            byteArrayOf(0xF0.toByte(), 0x43, 0x00, 0x7D, 0x10, 0x00, 0x6E, 0xF7.toByte()),
            message,
        )
    }

    @Test
    fun presetBackupFileRoundTripsAVoiceAndRecord() {
        val voice = SimulatedBaudGirl.namedVoice("ORGAN 3")
        val record = SimulatedBaudGirl.defaultRecord(0x5A)
        val file = BaudGirlSysex.encodePresetWrite(0, voice, record)
        assertEquals(231, file.size)
        val decoded = BaudGirlSysex.decodePresetWrite(file)!!
        assertEquals(0, decoded.first)
        assertArrayEquals(voice, decoded.second)
        assertArrayEquals(record, decoded.third)
    }

    @Test
    fun highBitGroupsRoundTripBytesAbove127() {
        val data = byteArrayOf(0x03, 0x5A, 0xC3.toByte(), 0xFF.toByte(), 0x00, 0x7F, 0x80.toByte(), 0x11, 0x22)
        val packed = BaudGirlSysex.packHighBits(data)
        assertTrue(packed.all { it.toUInt8() < 0x80 })
        assertArrayEquals(data, BaudGirlSysex.unpackHighBits(packed, data.size))
    }

    @Test
    fun librarianReplyRoundTripsThroughSevenBitPacking() {
        val data = ByteArray(187) { i -> (i and 0xFF).toByte() }
        val encoded = BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PRESET, 0, 3, data)
        val decoded = BaudGirlSysex.decodeReply(encoded)!!
        assertEquals(BaudGirlSysex.KIND_PRESET, decoded.kind)
        assertEquals(0, decoded.status)
        assertEquals(3, decoded.argument)
        assertArrayEquals(data, decoded.data)
    }

    @Test
    fun baudGirlIdentitiesAreTheOnesThatDump() {
        assertTrue(FM1Identity.parse("FM-1_079")!!.supportsBaudGirlBackup)
        assertTrue(FM1Identity.parse("FM-1_102")!!.supportsBaudGirlBackup)
        assertFalse(FM1Identity.parse("FM-1_015")!!.supportsBaudGirlBackup)
        assertFalse(FM1Identity.parse("FM-1_915")!!.supportsBaudGirlBackup)
        assertFalse(FM1Identity.parse("ota-FM-1_009")!!.supportsBaudGirlBackup)
    }
}

class BaudGirlLibrarianTest {
    @Test
    fun dumpsPresetsAndPatternsFromTheSimulatedDevice() = runBlocking {
        val device = SimulatedFM1(SimulatedOptions(installedIdentity = FM1Identity.parse("FM-1_102")!!))
        val found = FM1Updater(device, UpdaterTiming.Fast).find()!!
        val snapshot = BaudGirlLibrarian(timeoutMs = 400, attempts = 2, writePaceMs = 0)
            .dump(found.connection, found.identity)
        found.connection.close()
        assertEquals("FM-1_102", snapshot.identity)
        assertEquals(128, snapshot.presets.size)
        assertEquals("ORGAN 3", snapshot.presets[0].name)
        assertEquals("BASS", snapshot.presets[1].name)
        assertEquals(0x5A, snapshot.presets[1].record[18].toUInt8())
        assertEquals(60, snapshot.patterns.steps[0].toUInt8())
        assertEquals(100, snapshot.patterns.steps[20].toUInt8())
        val presetsFile = snapshot.presetsFile()
        val patternsFile = snapshot.patternsFile()
        assertEquals(231 * 128, presetsFile.size)
        assertEquals(162 * 16 * 16 + 74 * 16 * 8, patternsFile.size)
        val reloaded = BaudGirlSnapshot.parsePresets(presetsFile)
        assertEquals("ORGAN 3", reloaded[0].name)
        val reloadedPatterns = BaudGirlSnapshot.parsePatterns(patternsFile)
        assertEquals(60, reloadedPatterns.steps[0].toUInt8())
    }

    @Test
    fun restoreWritesPresetsBack() = runBlocking {
        val device = SimulatedFM1(SimulatedOptions(installedIdentity = FM1Identity.parse("FM-1_102")!!))
        val found = FM1Updater(device, UpdaterTiming.Fast).find()!!
        val librarian = BaudGirlLibrarian(timeoutMs = 400, attempts = 2, writePaceMs = 0)
        val original = librarian.dump(found.connection, found.identity)
        val changed = original.presets.map { preset ->
            if (preset.slot == 0) {
                preset.copy(voice = SimulatedBaudGirl.namedVoice("CHANGED"))
            } else {
                preset
            }
        }
        librarian.restorePresets(found.connection, changed)
        val again = librarian.dump(found.connection, found.identity)
        found.connection.close()
        assertEquals("CHANGED", again.presets[0].name)
        assertEquals("BASS", again.presets[1].name)
    }

    @Test
    fun restoreWritesPatternsBack() = runBlocking {
        val device = SimulatedFM1(SimulatedOptions(installedIdentity = FM1Identity.parse("FM-1_102")!!))
        val found = FM1Updater(device, UpdaterTiming.Fast).find()!!
        val librarian = BaudGirlLibrarian(timeoutMs = 400, attempts = 2, writePaceMs = 0)
        val original = librarian.dump(found.connection, found.identity)
        original.patterns.steps[0] = 48
        original.patterns.steps[20] = 90
        librarian.restorePatterns(found.connection, original.patterns)
        val again = librarian.dump(found.connection, found.identity)
        found.connection.close()
        assertEquals(48, again.patterns.steps[0].toUInt8())
        assertEquals(90, again.patterns.steps[20].toUInt8())
    }

    @Test
    fun dumpRefusesFirmwaresWithoutTheLibrarian() = runBlocking {
        val device = SimulatedFM1()
        val found = FM1Updater(device, UpdaterTiming.Fast).find()!!
        try {
            BaudGirlLibrarian(timeoutMs = 200, attempts = 1).dump(found.connection, found.identity)
            throw AssertionError("Felucca should not dump")
        } catch (_: FM1UpdateError.BackupUnavailable) {
        }
        found.connection.close()
    }
}
