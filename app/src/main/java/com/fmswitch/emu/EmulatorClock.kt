package com.fmswitch.emu

import android.content.Context
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.RedtailListing

/**
 * The emulated chip's clock for TRY, remembered per firmware. A real FM-1 runs at 240 MHz, but
 * the emulator's cost grows with the clock, so most firmwares play at 100. A firmware that misses
 * its own audio deadlines at a low clock gaps or crackles while the phone's load stays low.
 */
object EmulatorClock {
    val CHOICES = listOf(100, 160, 240)
    const val DEFAULT = 100

    /** RedtailWorks' synths drop half-buffers of audio at 100 MHz; 160 plays them cleanly. */
    const val HEAVY = 160

    fun defaultFor(entry: CatalogueEntry): Int =
        if (entry.author == RedtailListing.AUTHOR || entry.id.startsWith("redtail-")) HEAVY else DEFAULT

    fun get(context: Context, entry: CatalogueEntry): Int =
        prefs(context).getInt(key(entry), defaultFor(entry)).takeIf { it in CHOICES } ?: defaultFor(entry)

    fun set(context: Context, entry: CatalogueEntry, mhz: Int) {
        prefs(context).edit().putInt(key(entry), mhz).apply()
    }

    private fun key(entry: CatalogueEntry) = "emulatorMhz:${entry.id}"

    private fun prefs(context: Context) = context.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
}
