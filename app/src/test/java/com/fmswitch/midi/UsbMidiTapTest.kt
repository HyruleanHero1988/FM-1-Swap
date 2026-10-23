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
