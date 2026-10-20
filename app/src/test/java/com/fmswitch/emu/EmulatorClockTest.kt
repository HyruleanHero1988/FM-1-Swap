package com.fmswitch.emu

import com.fmswitch.catalogue.CatalogueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorClockTest {
    @Test
    fun redtailWorksSynthsStartFasterThanTheRest() {
        assertEquals(EmulatorClock.HEAVY, EmulatorClock.defaultFor(CatalogueEntry(id = "hub-hyp1", name = "HYP-1", author = "RedtailWorks")))
        assertEquals(EmulatorClock.HEAVY, EmulatorClock.defaultFor(CatalogueEntry(id = "redtail-lof", name = "LOF-1")))
        assertEquals(EmulatorClock.DEFAULT, EmulatorClock.defaultFor(CatalogueEntry(id = "felucca", name = "Felucca", author = "isod89")))
    }

    @Test
    fun everyDefaultIsOnTheClockRow() {
        assertTrue(EmulatorClock.DEFAULT in EmulatorClock.CHOICES)
        assertTrue(EmulatorClock.HEAVY in EmulatorClock.CHOICES)
    }
}
