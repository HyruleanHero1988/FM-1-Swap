package com.fmswitch.emu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The JNI surface against a host build of libfm1emu.so, booting the bundled V15. */
class Fm1NativeHostTest {
    @Test
    fun stockFirmwareBootsToAScreenAndSound() {
        assumeTrue("no host libfm1emu.so", Fm1Native.available)
        val pkg = File(System.getProperty("fm1.testPackage")!!).readBytes()
        val handle = Fm1Native.boot(pkg, null, 100, true)
        assertTrue(handle != 0L)
        try {
            assertEquals(0, Fm1Native.board(handle))
            Fm1Native.pot(handle, Fm1Panel.POT_START)
            val block = ShortArray(2 * 512)
            var frames = 0
            repeat(44_100 * 3 / 512) {
                val made = Fm1Native.render(handle, block, 50_000_000)
                assertTrue("the machine stopped", made >= 0)
                frames += made
            }
            assertTrue(frames > 44_100)
            assertTrue(Fm1Native.ms(handle) >= 2_500)
            val lcd = IntArray(240 * 240)
            Fm1Native.lcd(handle, lcd)
            assertTrue("the screen stays dark", lcd.count { it and 0xffffff != 0 } > 1_000)
            val leds = FloatArray(41)
            Fm1Native.leds(handle, leds)
            Fm1Native.key(handle, 26, true)
            Fm1Native.render(handle, block, 50_000_000)
            Fm1Native.key(handle, 26, false)
            Fm1Native.enc(handle, 2, 1)
            Fm1Native.midi(handle, 0x91)
            Fm1Native.midi(handle, 60)
            Fm1Native.midi(handle, 100)
            Fm1Native.render(handle, block, 50_000_000)
            assertNotNull(Fm1Native.flashChanges(handle))
        } finally {
            Fm1Native.release(handle)
        }
    }

    @Test
    fun junkDoesNotBoot() {
        assumeTrue("no host libfm1emu.so", Fm1Native.available)
        assertEquals(0L, Fm1Native.boot(ByteArray(64), null, 100, true))
    }
}
