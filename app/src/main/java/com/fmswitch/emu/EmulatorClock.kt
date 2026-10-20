package com.fmswitch.emu

import android.content.Context
import com.fmswitch.catalogue.CatalogueEntry

/**
 * The emulated chip's clock for TRY. A real FM-1 runs at 240 MHz, and that is what TRY uses
 * until SETUP ▸ CHOOSE EMULATOR CLOCK is on. The choices are then remembered per firmware.
 * The phone's cost grows with the clock; a lower one is for a phone whose load sits over 100%
 * or whose sound gaps.
 */
object EmulatorClock {
    val CHOICES = listOf(100, 160, 240)
    const val DEFAULT = 240

    fun get(context: Context, entry: CatalogueEntry, allowChanges: Boolean): Int {
        val saved = prefs(context).getInt(key(entry), -1)
        return effective(saved.takeIf { it >= 0 }, allowChanges)
    }

    fun set(context: Context, entry: CatalogueEntry, mhz: Int) {
        prefs(context).edit().putInt(key(entry), mhz).apply()
    }

    /** [saved] is a remembered choice, or null when this firmware has none. */
    internal fun effective(saved: Int?, allowChanges: Boolean): Int =
        if (allowChanges) saved?.takeIf { it in CHOICES } ?: DEFAULT else DEFAULT

    private fun key(entry: CatalogueEntry) = "emulatorMhz:${entry.id}"

    private fun prefs(context: Context) = context.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
}
