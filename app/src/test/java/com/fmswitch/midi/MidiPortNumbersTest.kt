package com.fmswitch.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MidiPortNumbersTest {
    private val input = 1
    private val output = 2

    @Test
    fun usesEachPortsOwnNumberNotItsPositionInTheList() {
        val fm1 = listOf(input to 0, output to 0)
        assertEquals(0, MidiPortNumbers.first(fm1, input))
        assertEquals(0, MidiPortNumbers.first(fm1, output))
    }

    @Test
    fun picksTheLowestNumberedPortOfEachKind() {
        val twoEach = listOf(input to 1, input to 0, output to 1, output to 0)
        assertEquals(0, MidiPortNumbers.first(twoEach, input))
        assertEquals(0, MidiPortNumbers.first(twoEach, output))
    }

    @Test
    fun noneWhenTheDeviceLacksThatDirection() {
        assertNull(MidiPortNumbers.first(listOf(input to 0), output))
    }
}
