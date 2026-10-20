package com.fmswitch.emu

/**
 * Whether the TRY engine keeps running.
 *
 * [allowBackground] covers the app losing focus (another app, screen off). It does not
 * cover leaving the TRY screen: that removes the screen, and the screen always closes
 * the engine on the way out.
 */
object EmulatorRun {
    fun keepEngine(activityStarted: Boolean, allowBackground: Boolean): Boolean =
        allowBackground || activityStarted
}
