package com.fmswitch.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMidiTapTest {
    private val usb = 1
    private val virtual = 2

    @Test
    fun listensToUsbDevicesThatSendNotes() {
        val keyboard = UsbMidiCandidate(1, usb, "KeyStep", listOf(0))
        val fm1 = UsbMidiCandidate(2, usb, "FM-1", listOf(0))
        val inputOnly = UsbMidiCandidate(3, usb, "Thru", emptyList())
        val app = UsbMidiCandidate(4, virtual, "Virtual", listOf(0))
        assertEquals(
            listOf(keyboard),
            usbMidiListenTargets(listOf(keyboard, fm1, inputOnly, app), skip = setOf(2), usbType = usb),
        )
    }

    @Test
    fun namesTheProductBeforeThePortName() {
        assertEquals("KeyStep", midiProductName("USB MIDI", "KeyStep", "Arturia", 4))
        assertEquals("MIDI 4", midiProductName("  ", null, null, 4))
    }

    @Test
    fun lineNamesWhatOpenedOrWhatFailed() {
        assertEquals("", usbMidiLine(emptyList(), emptyList()))
        assertEquals("USB MIDI · KeyStep", usbMidiLine(listOf("KeyStep"), emptyList()))
        assertEquals("Couldn't open KeyStep.", usbMidiLine(emptyList(), listOf("KeyStep")))
    }

    @Test
    fun opensEveryPortAndPlaysChannelOneOnChannelTwo() {
        assertEquals(listOf(0, 1), usbMidiPortsToOpen(listOf(1, 0, 1)))
        val keys = byteArrayOf(0x90.toByte(), 60, 100)
        val heard = fm1ListenChannel(keys)
        assertEquals(0x91.toByte(), heard[0])
        assertEquals(60.toByte(), heard[1])
        val pads = fm1ListenChannel(byteArrayOf(0x99.toByte(), 36, 110))
        assertEquals(0x91.toByte(), pads[0])
        val already = byteArrayOf(0x91.toByte(), 60, 100)
        assertTrue(fm1ListenChannel(already) contentEquals already)
        val sysex = byteArrayOf(0xF0.toByte(), 0x43, 0xF7.toByte())
        assertTrue(fm1ListenChannel(sysex) contentEquals sysex)
        assertEquals(0xF8.toByte(), fm1ListenChannel(byteArrayOf(0xF8.toByte()))[0])
    }

    @Test
    fun anEchoOnTheSecondPortIsDropped() {
        val gate = MidiEchoGate(windowNs = 12_000_000L)
        val note = byteArrayOf(0x90.toByte(), 60, 100)
        assertTrue(gate.admit(note, 1_000))
        assertTrue(!gate.admit(note.copyOf(), 1_000 + 5_000_000L))
        assertTrue(gate.admit(byteArrayOf(0x80.toByte(), 60, 0), 1_000 + 6_000_000L))
        assertTrue(gate.admit(note, 1_000 + 20_000_000L))
    }

    @Test
    fun silenceReleasesEveryChannel() {
        val bytes = usbMidiSilence()
        assertEquals(16 * 6, bytes.size)
        assertEquals(0xB0.toByte(), bytes[0])
        assertEquals(120.toByte(), bytes[1])
        assertEquals(0xB0.toByte(), bytes[3])
        assertEquals(123.toByte(), bytes[4])
        assertEquals(0xBF.toByte(), bytes[15 * 6])
        assertTrue(bytes.toList().chunked(6).all { it[1] == 120.toByte() && it[4] == 123.toByte() })
    }
}
