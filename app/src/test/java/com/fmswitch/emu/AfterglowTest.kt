package com.fmswitch.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterglowTest {
    @Test
    fun pressesLightAtOnce() {
        assertEquals(1f, Afterglow.glow(0f, 1f, 0.016f), 0f)
    }

    @Test
    fun releasesFadeInsteadOfSnappingOff() {
        val oneFrame = Afterglow.glow(1f, 0f, 0.016f)
        assertTrue(oneFrame in 0.9f..1f)
        val later = Afterglow.glow(1f, 0f, Afterglow.PERSISTENCE)
        assertEquals(0.368f, later, 0.01f)
    }

    @Test
    fun aDimLedHoldsTheFloor() {
        assertEquals(0.4f, Afterglow.glow(1f, 0.4f, 10f), 0f)
    }

    @Test
    fun sweepTrailCatchesUp() {
        val near = Afterglow.follow(0f, 1f, 2f)
        assertTrue(near > 0.99f)
        val start = Afterglow.follow(0f, 1f, 0.016f)
        assertTrue(start in 0f..0.1f)
    }
}
