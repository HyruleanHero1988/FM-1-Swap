package com.fmswitch.emu

import android.content.Context
import com.fmswitch.catalogue.CatalogueEntry

/**
 * The emulated chip's clock for TRY. A real FM-1 runs at 240 MHz.
 *
 * When SETUP ▸ CHOOSE EMULATOR CLOCK is off, TRY starts at 240 MHz and may step down
 * automatically (240 → 200 → 160 → 120) if the phone can't keep real time. When that
 * toggle is on, TRY offers a fixed choice remembered per firmware.
 */
object EmulatorClock {
    /** Manual choices on the TRY screen when CHOOSE EMULATOR CLOCK is on. */
    val CHOICES = listOf(100, 160, 240)
    /** Automatic step-down ladder when CHOOSE is off. */
    val AUTO_STEPS = listOf(240, 200, 160, 120)
    const val DEFAULT = 240

    /** Below this ×-real-time, sustained, the automatic clock counts the firmware as slow. */
    const val SLOW_SPEED = 1.15f
    /** Emulated seconds of sustained slowness before a step (after [WARMUP_MS]). */
    const val SLOW_MS = 5_000L
    /** Ignore load right after a boot or clock step while the JIT is still compiling. */
    const val WARMUP_MS = 6_000L

    fun get(context: Context, entry: CatalogueEntry, allowChanges: Boolean): Int {
        val saved = prefs(context).getInt(key(entry), -1)
        return effective(saved.takeIf { it >= 0 }, allowChanges)
    }

    fun set(context: Context, entry: CatalogueEntry, mhz: Int) {
        prefs(context).edit().putInt(key(entry), mhz).apply()
    }

    /** Next lower automatic clock, or null when already at the bottom. */
    fun nextAuto(mhz: Int): Int? {
        val i = AUTO_STEPS.indexOf(mhz)
        return if (i in 0 until AUTO_STEPS.lastIndex) AUTO_STEPS[i + 1] else null
    }

    /** [saved] is a remembered choice, or null when this firmware has none. */
    internal fun effective(saved: Int?, allowChanges: Boolean): Int =
        if (allowChanges) saved?.takeIf { it in CHOICES } ?: DEFAULT else DEFAULT

    private fun key(entry: CatalogueEntry) = "emulatorMhz:${entry.id}"

    private fun prefs(context: Context) = context.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
}
