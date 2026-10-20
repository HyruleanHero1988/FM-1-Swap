package com.fmswitch.fm1kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiNotesTest {
    @Test
    fun encodesNoteOnAndOffOnTheAskedChannel() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 100), MidiNotes.noteOn(1, 60, 100))
        assertArrayEquals(byteArrayOf(0x91.toByte(), 64, 100), MidiNotes.noteOn(2, 64, 100))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 60, 0), MidiNotes.noteOff(1, 60))
        assertEquals(
            MidiNotes.Heard(channel = 2, note = 67, velocity = 100, on = true),
            MidiNotes.parse(MidiNotes.noteOn(2, 67, 100)),
        )
        assertEquals(false, MidiNotes.parse(MidiNotes.noteOff(2, 67))!!.on)
    }

    @Test
    fun testPhraseHitsBothListeningChannels() {
        val phrase = MidiNotes.testPhrase()
        val ons = phrase.mapNotNull { MidiNotes.parse(it.bytes)?.takeIf { note -> note.on } }
        assertEquals(MidiNotes.TEST_NOTES.size * MidiNotes.TEST_CHANNELS.size, ons.size)
        assertTrue(ons.any { it.channel == 1 && it.note == 60 })
        assertTrue(ons.any { it.channel == 2 && it.note == 72 })
        assertTrue(MidiNotes.isChannelVoice(MidiNotes.noteOn(1, 60)))
        assertEquals(false, MidiNotes.isChannelVoice(FM1Message.HANDSHAKE_QUERY))
    }
}

class ConnectionToneTest {
    @Test
    fun simulatedDeviceHearsThePhraseAndStillAnswersHandshake() = runBlocking {
        val device = SimulatedFM1()
        val found = FM1Updater(device).find() ?: error("simulated FM-1 should answer")
        try {
            val result = ConnectionTone.play(found.connection) { FM1Updater(device).handshake(it) }
            assertEquals(MidiNotes.testPhrase().size, result.notesSent)
            assertTrue(result.stillAnswering)
            assertEquals(FM1Identity.of("FM-1", 900), result.identity)
            val heardOns = device.notesHeard.filter { it.on }
            assertEquals(MidiNotes.TEST_NOTES.size * MidiNotes.TEST_CHANNELS.size, heardOns.size)
            assertTrue(heardOns.any { it.channel == 1 && it.note == 60 })
            assertTrue(heardOns.any { it.channel == 2 && it.note == 72 })
        } finally {
            found.connection.close()
            device.shutdown()
        }
    }
}
