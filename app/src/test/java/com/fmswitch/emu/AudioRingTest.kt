package com.fmswitch.emu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRingTest {
    @Test
    fun pushAndPullRoundTrip() {
        val ring = AudioRing(8)
        val src = shortArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(3, ring.push(src, 3))
        assertEquals(3, ring.available())
        val out = ShortArray(8)
        assertEquals(3, ring.pull(out))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 0, 0), out)
        assertEquals(0, ring.available())
    }

    @Test
    fun freeLeavesOneSlotSoWriteNeverCatchesRead() {
        val ring = AudioRing(4)
        assertEquals(3, ring.free())
        assertEquals(3, ring.push(ShortArray(8) { it.toShort() }, 4))
        assertEquals(0, ring.free())
        assertEquals(0, ring.push(shortArrayOf(9, 10), 1))
    }

    @Test
    fun wrapAroundKeepsOrder() {
        val ring = AudioRing(4)
        ring.push(shortArrayOf(1, 2, 3, 4), 2)
        val first = ShortArray(4)
        ring.pull(first)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), first)
        assertEquals(2, ring.push(shortArrayOf(5, 6, 7, 8), 2))
        val second = ShortArray(4)
        assertEquals(2, ring.pull(second))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), second)
    }
}
