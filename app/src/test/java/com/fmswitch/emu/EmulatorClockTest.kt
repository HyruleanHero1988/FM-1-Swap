package com.fmswitch.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorClockTest {
    @Test
    fun theRealFm1SpeedIsTheDefaultAndAChoice() {
        assertEquals(240, EmulatorClock.DEFAULT)
        assertEquals(listOf(100, 160, 240), EmulatorClock.CHOICES)
        assertTrue(EmulatorClock.DEFAULT in EmulatorClock.CHOICES)
    }

    @Test
    fun clockStaysAt240UntilChangesAreAllowed() {
        assertEquals(240, EmulatorClock.effective(saved = 100, allowChanges = false))
        assertEquals(240, EmulatorClock.effective(saved = null, allowChanges = false))
    }

    @Test
    fun anAllowedChangeUsesTheRememberedChoice() {
        assertEquals(100, EmulatorClock.effective(saved = 100, allowChanges = true))
        assertEquals(160, EmulatorClock.effective(saved = 160, allowChanges = true))
        assertEquals(240, EmulatorClock.effective(saved = null, allowChanges = true))
        assertEquals(240, EmulatorClock.effective(saved = 90, allowChanges = true))
    }
}
