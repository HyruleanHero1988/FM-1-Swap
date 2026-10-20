package com.fmswitch.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackTest {
    @Test
    fun safetyNoticeLeavesTheApp() {
        assertEquals(BackDecision.LEAVE_APP, decide(showingSafetyNotice = true, tab = AppTab.SETTINGS))
    }

    @Test
    fun writeInProgressStaysPut() {
        assertEquals(BackDecision.STAY, decide(flashRunning = true, flashOpen = true, tab = AppTab.DEVICE))
        assertEquals(BackDecision.STAY, decide(voiceRestoreRunning = true, flashOpen = true, tab = AppTab.DEVICE))
    }

    @Test
    fun finishedFlashCloses() {
        assertEquals(BackDecision.DISMISS_FLASH, decide(flashOpen = true, tab = AppTab.LIBRARY))
    }

    @Test
    fun firmwareDetailPopsBeforeSearchAndTabs() {
        assertEquals(
            BackDecision.CLOSE_DETAIL,
            decide(firmwareDetailOpen = true, searchNonEmpty = true, tab = AppTab.FIRMWARE),
        )
    }

    @Test
    fun searchClearsBeforeLeavingFirmwareTab() {
        assertEquals(BackDecision.CLEAR_SEARCH, decide(searchNonEmpty = true, tab = AppTab.FIRMWARE))
    }

    @Test
    fun libraryClearConfirmCancels() {
        assertEquals(BackDecision.CANCEL_LIBRARY_CLEAR, decide(libraryClearConfirm = true, tab = AppTab.LIBRARY))
    }

    @Test
    fun otherTabsGoToDeviceThenLeave() {
        assertEquals(BackDecision.GO_TO_DEVICE, decide(tab = AppTab.FIRMWARE))
        assertEquals(BackDecision.GO_TO_DEVICE, decide(tab = AppTab.LIBRARY))
        assertEquals(BackDecision.GO_TO_DEVICE, decide(tab = AppTab.SETTINGS))
        assertEquals(BackDecision.LEAVE_APP, decide(tab = AppTab.DEVICE))
    }

    private fun decide(
        showingSafetyNotice: Boolean = false,
        flashRunning: Boolean = false,
        flashOpen: Boolean = false,
        voiceRestoreRunning: Boolean = false,
        firmwareDetailOpen: Boolean = false,
        searchNonEmpty: Boolean = false,
        libraryClearConfirm: Boolean = false,
        tab: AppTab,
    ) = AppBack.decide(
        showingSafetyNotice = showingSafetyNotice,
        flashRunning = flashRunning,
        flashOpen = flashOpen,
        voiceRestoreRunning = voiceRestoreRunning,
        firmwareDetailOpen = firmwareDetailOpen,
        searchNonEmpty = searchNonEmpty,
        libraryClearConfirm = libraryClearConfirm,
        tab = tab,
    )
}
