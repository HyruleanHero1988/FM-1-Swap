package com.fmswitch.model

import com.fmswitch.catalogue.NewChipSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChipGateTest {
    private val works = "V15" to NewChipSupport.WORKS
    private val bricks = "Zp12" to NewChipSupport.BRICKS
    private val unknown = "Mystery" to NewChipSupport.UNKNOWN

    @Test
    fun aFirmwareKnownToFailNeedsAnAcknowledgement() {
        val gate = newChipGate(listOf(bricks), UnitGeneration.NEWER)
        assertEquals(listOf("Zp12"), gate.bricking)
        assertTrue(gate.needsAcknowledgement)
        assertFalse(newChipFlashAllowed(gate, acknowledged = false))
        assertTrue(newChipFlashAllowed(gate, acknowledged = true))
    }

    @Test
    fun anUntestedFirmwareWarnsButDoesNotNeedAnAcknowledgement() {
        val gate = newChipGate(listOf(unknown), UnitGeneration.NEWER)
        assertEquals(listOf("Mystery"), gate.untested)
        assertTrue(gate.warns)
        assertFalse(gate.needsAcknowledgement)
        assertTrue(newChipFlashAllowed(gate, acknowledged = false))
    }

    @Test
    fun aConfirmedFirmwareHasNoWarning() {
        val gate = newChipGate(listOf(works), UnitGeneration.NEWER)
        assertFalse(gate.warns)
        assertTrue(newChipFlashAllowed(gate, acknowledged = false))
    }

    @Test
    fun notBeingSureIsTreatedLikeANewerUnit() {
        assertEquals(UnitGeneration.UNSURE, UnitGeneration.fromId(null))
        assertEquals(UnitGeneration.UNSURE, UnitGeneration.fromId("garbage"))
        val gate = newChipGate(listOf(bricks, unknown), UnitGeneration.UNSURE)
        assertTrue(gate.needsAcknowledgement)
        assertEquals(listOf("Mystery"), gate.untested)
    }

    @Test
    fun anOlderUnitIsNotGated() {
        val gate = newChipGate(listOf(bricks, unknown), UnitGeneration.OLDER)
        assertFalse(gate.warns)
        assertTrue(newChipFlashAllowed(gate, acknowledged = false))
    }

    @Test
    fun aTwoStepFlashIsGatedByItsWorstStep() {
        val gate = newChipGate(listOf(works, bricks, bricks), UnitGeneration.NEWER)
        assertEquals(listOf("Zp12"), gate.bricking)
        assertTrue(gate.needsAcknowledgement)
    }

    @Test
    fun continueStaysOffUntilTheFailingFirmwareIsAcknowledged() {
        fun can(risk: Boolean, understood: Boolean) = flashCanProceed(
            checking = false,
            risky = false,
            understood = false,
            missingOfficial = false,
            switching = false,
            backedUp = false,
            autoBackup = false,
            newChipRisk = risk,
            newChipUnderstood = understood,
        )
        assertTrue(can(risk = false, understood = false))
        assertFalse(can(risk = true, understood = false))
        assertTrue(can(risk = true, understood = true))
    }

    @Test
    fun theWarningsNameTheFirmwaresAndTheChip() {
        val one = newChipBrickWarning(listOf("Zp12"))
        assertTrue(one.startsWith("Zp12 is known to fail on newer FM-1s"))
        assertTrue(one.contains("0xB36014"))
        val two = newChipBrickWarning(listOf("Zp12", "HYP-1", "Zp12"))
        assertTrue(two.startsWith("Zp12 and HYP-1 are known to fail"))
        assertTrue(newChipUntestedWarning(listOf("Mystery")).contains("not been confirmed"))
    }
}
