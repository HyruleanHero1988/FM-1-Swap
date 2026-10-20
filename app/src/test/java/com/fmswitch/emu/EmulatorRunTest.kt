package com.fmswitch.emu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorRunTest {
    @Test
    fun stopsWhenTheAppLosesFocusUnlessBackgroundIsAllowed() {
        assertFalse(EmulatorRun.keepEngine(activityStarted = false, allowBackground = false))
        assertTrue(EmulatorRun.keepEngine(activityStarted = true, allowBackground = false))
        assertTrue(EmulatorRun.keepEngine(activityStarted = false, allowBackground = true))
    }
}
