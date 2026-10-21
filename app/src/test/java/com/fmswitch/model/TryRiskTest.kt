package com.fmswitch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryRiskTest {
    @Test
    fun aFailureFlagsThePackageAndALaterStartClearsIt() {
        val saved = mutableListOf<Map<String, String>>()
        val ledger = TryLedger(persist = { saved += it })
        assertEquals(TryVerdict.UNKNOWN, ledger.verdict("AbC"))
        ledger.note("AbC", started = false)
        assertEquals(TryVerdict.FAILED, ledger.verdict("abc"))
        ledger.note("abc", started = false)
        ledger.note("abc", started = true)
        assertEquals(TryVerdict.STARTED, ledger.verdict("ABC"))
        ledger.note("abc", started = false)
        assertEquals(TryVerdict.FAILED, ledger.verdict("abc"))
        assertEquals(3, saved.size)
        assertEquals(mapOf("abc" to "failed"), saved.last())
    }

    @Test
    fun verdictsRoundTrip() {
        val text = encodeTryVerdicts(mapOf("bbb" to "started", "aaa" to "failed"))
        assertEquals("aaa failed\nbbb started", text)
        assertEquals(mapOf("aaa" to "failed", "bbb" to "started"), decodeTryVerdicts(text))
        assertEquals(emptyMap<String, String>(), decodeTryVerdicts("nope\nzzz maybe"))
    }

    @Test
    fun anUnknownPackageIsProbedAndAFailedOneIsNamed() {
        val ledger = TryLedger(mapOf("known" to "started", "bad" to "failed"))
        val plan = planFlashTry(
            listOf("bad" to "Baud Girl", "known" to "V15", "fresh" to "Sloop", "fresh" to "Sloop again"),
            ledger::verdict,
        )
        assertEquals(listOf("fresh"), plan.probe)
        assertEquals(listOf("Baud Girl"), plan.failedNames)
    }

    @Test
    fun continueStaysOffUntilTheRiskIsAcknowledged() {
        assertFalse(flashCanProceed(checking = true, risky = true, understood = true, missingOfficial = false, switching = false, backedUp = false, autoBackup = false))
        assertFalse(flashCanProceed(checking = false, risky = true, understood = false, missingOfficial = false, switching = false, backedUp = false, autoBackup = false))
        assertTrue(flashCanProceed(checking = false, risky = true, understood = true, missingOfficial = false, switching = false, backedUp = false, autoBackup = false))
        assertFalse(flashCanProceed(checking = false, risky = true, understood = true, missingOfficial = false, switching = true, backedUp = false, autoBackup = false))
        assertTrue(flashCanProceed(checking = false, risky = false, understood = false, missingOfficial = false, switching = true, backedUp = false, autoBackup = true))
        assertFalse(flashCanProceed(checking = false, risky = false, understood = false, missingOfficial = false, switching = true, backedUp = false, autoBackup = false, backupPending = true))
        assertFalse(flashCanProceed(checking = false, risky = false, understood = true, missingOfficial = true, switching = false, backedUp = false, autoBackup = false))
    }

    @Test
    fun theWarningNamesEveryFirmwareThatFailed() {
        assertEquals(
            "Baud Girl failed to start in the emulator, so it is extremely risky to flash it to your FM-1. Proceed at your own risk.",
            riskWarning(listOf("Baud Girl")),
        )
        assertEquals(
            "Baud Girl and V15 failed to start in the emulator, so it is extremely risky to flash them to your FM-1. Proceed at your own risk.",
            riskWarning(listOf("Baud Girl", "V15", "Baud Girl")),
        )
    }
}
