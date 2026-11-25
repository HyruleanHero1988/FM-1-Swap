package com.fmswitch.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorClockTest {
    @Test
    fun defaultIsFullSpeedAndInTheManualChoices() {
        assertEquals(240, EmulatorClock.DEFAULT)
        assertEquals(listOf(100, 160, 240), EmulatorClock.CHOICES)
        assertTrue(EmulatorClock.DEFAULT in EmulatorClock.CHOICES)
        assertEquals(listOf(240, 200, 160, 120), EmulatorClock.AUTO_STEPS)
    }

    @Test
    fun withoutChooseTheSavedChoiceIsIgnored() {
        assertEquals(240, EmulatorClock.effective(saved = 100, allowChanges = false))
        assertEquals(240, EmulatorClock.effective(saved = null, allowChanges = false))
    }

    @Test
    fun withChooseARememberedChoiceWins() {
        assertEquals(100, EmulatorClock.effective(saved = 100, allowChanges = true))
        assertEquals(160, EmulatorClock.effective(saved = 160, allowChanges = true))
        assertEquals(240, EmulatorClock.effective(saved = null, allowChanges = true))
        assertEquals(240, EmulatorClock.effective(saved = 90, allowChanges = true))
    }

    @Test
    fun autoStepsDownThenStops() {
        assertEquals(200, EmulatorClock.nextAuto(240))
        assertEquals(160, EmulatorClock.nextAuto(200))
        assertEquals(120, EmulatorClock.nextAuto(160))
        assertNull(EmulatorClock.nextAuto(120))
        assertNull(EmulatorClock.nextAuto(100))
    }
}
