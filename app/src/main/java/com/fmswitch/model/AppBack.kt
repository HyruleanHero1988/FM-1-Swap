package com.fmswitch.model

enum class BackDecision {
    STAY,
    DISMISS_FLASH,
    CLOSE_DETAIL,
    CLEAR_SEARCH,
    CANCEL_LIBRARY_CLEAR,
    GO_TO_DEVICE,
    LEAVE_APP,
}

object AppBack {
    fun decide(
        showingSafetyNotice: Boolean,
        flashRunning: Boolean,
        flashOpen: Boolean,
        voiceRestoreRunning: Boolean,
        firmwareDetailOpen: Boolean,
        searchNonEmpty: Boolean,
        libraryClearConfirm: Boolean,
        tab: AppTab,
    ): BackDecision {
        if (showingSafetyNotice) return BackDecision.LEAVE_APP
        if (voiceRestoreRunning || flashRunning) return BackDecision.STAY
        if (flashOpen) return BackDecision.DISMISS_FLASH
        if (firmwareDetailOpen) return BackDecision.CLOSE_DETAIL
        if (searchNonEmpty) return BackDecision.CLEAR_SEARCH
        if (libraryClearConfirm) return BackDecision.CANCEL_LIBRARY_CLEAR
        if (tab != AppTab.DEVICE) return BackDecision.GO_TO_DEVICE
        return BackDecision.LEAVE_APP
    }
}
