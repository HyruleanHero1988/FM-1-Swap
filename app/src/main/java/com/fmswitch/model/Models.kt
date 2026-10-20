package com.fmswitch.model

import android.content.Context
import android.content.SharedPreferences
import com.fmswitch.catalogue.AboutOverlay
import com.fmswitch.catalogue.CatalogueBuilder
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.CatalogueRank
import com.fmswitch.catalogue.FirmwareSort
import com.fmswitch.catalogue.EditorListing
import com.fmswitch.catalogue.HubFeed
import com.fmswitch.catalogue.MergedCatalogue
import com.fmswitch.catalogue.PackageDiscovery
import com.fmswitch.catalogue.PhotoOverlay
import com.fmswitch.catalogue.RedtailListing
import com.fmswitch.catalogue.RepoAbout
import com.fmswitch.catalogue.RepoShots
import com.fmswitch.catalogue.fetchBytes
import com.fmswitch.catalogue.officialCatalogueEntry
import com.fmswitch.fm1kit.FwscFailure
import com.fmswitch.fm1kit.FwscPackage
import com.fmswitch.fm1kit.OfficialFirmware
import com.fmswitch.theme.PhosphorPreset
import com.fmswitch.theme.PhosphorTheme
import com.fmswitch.theme.invoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import androidx.compose.ui.graphics.Color

enum class TransportKind(val id: String, val label: String) {
    USB_MIDI("usbMIDI", "USB MIDI"),
    SIMULATED("simulated", "SIMULATED FM-1"),
}

class AppSettings(private val prefs: SharedPreferences) {
    var phosphorHex: String
        get() = prefs.getString("phosphorHex", PhosphorPreset.Green.hex) ?: PhosphorPreset.Green.hex
        set(value) { prefs.edit().putString("phosphorHex", value).apply() }
    var glow: Float
        get() = prefs.getFloat("glow", 0.25f)
        set(value) { prefs.edit().putFloat("glow", value).apply() }
    var scanlines: Boolean
        get() = prefs.getBoolean("scanlines", false)
        set(value) { prefs.edit().putBoolean("scanlines", value).apply() }
    var flicker: Boolean
        get() = prefs.getBoolean("flicker", false)
        set(value) { prefs.edit().putBoolean("flicker", value).apply() }
    var phosphorPanel: Boolean
        get() = prefs.getBoolean("phosphorPanel", true)
        set(value) { prefs.edit().putBoolean("phosphorPanel", value).apply() }
    var transportKind: TransportKind
        get() = TransportKind.entries.firstOrNull { it.id == prefs.getString("transport", TransportKind.USB_MIDI.id) }
            ?: TransportKind.USB_MIDI
        set(value) { prefs.edit().putString("transport", value.id).apply() }
    var showBeta: Boolean
        get() = prefs.getBoolean("showBeta", true)
        set(value) { prefs.edit().putBoolean("showBeta", value).apply() }
    var showLinksOnly: Boolean
        get() = prefs.getBoolean("showLinksOnly", false)
        set(value) { prefs.edit().putBoolean("showLinksOnly", value).apply() }
    var autoSaveTried: Boolean
        get() = prefs.getBoolean("autoSaveTried", true)
        set(value) { prefs.edit().putBoolean("autoSaveTried", value).apply() }
    /** Off, TRY runs at the real FM-1 clock and hides the 100 / 160 / 240 buttons. */
    var chooseEmulatorClock: Boolean
        get() = prefs.getBoolean("chooseEmulatorClock", false)
        set(value) { prefs.edit().putBoolean("chooseEmulatorClock", value).apply() }
    /** Catalogue ids, first in this list is the top of MY CURATION. */
    var curation: List<String>
        get() = prefs.getString("curation", null).orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        set(value) { prefs.edit().putString("curation", value.joinToString("\n")).apply() }
    var curatedOnly: Boolean
        get() = prefs.getBoolean("curatedOnly", false)
        set(value) { prefs.edit().putBoolean("curatedOnly", value).apply() }
    var firmwareSort: FirmwareSort
        get() = FirmwareSort.entries.firstOrNull { it.id == prefs.getString("firmwareSort", FirmwareSort.CURATION.id) }
            ?: FirmwareSort.CURATION
        set(value) { prefs.edit().putString("firmwareSort", value.id).apply() }
    var alwaysViaOfficial: Boolean
        get() = prefs.getBoolean("alwaysViaOfficial", false)
        set(value) { prefs.edit().putBoolean("alwaysViaOfficial", value).apply() }
    var simulatedRecoveryMode: Boolean
        get() = prefs.getBoolean("simulatedRescueMode", false)
        set(value) { prefs.edit().putBoolean("simulatedRescueMode", value).apply() }
    var simulatedCablePull: Boolean
        get() = prefs.getBoolean("simulatedCablePull", false)
        set(value) { prefs.edit().putBoolean("simulatedCablePull", value).apply() }
    var simulatedReadsBackwards: Boolean
        get() = prefs.getBoolean("simulatedReadsBackwards", false)
        set(value) { prefs.edit().putBoolean("simulatedReadsBackwards", value).apply() }
    var simulatedVerifies: Boolean
        get() = prefs.getBoolean("simulatedVerifies", false)
        set(value) { prefs.edit().putBoolean("simulatedVerifies", value).apply() }
    var acknowledgedNoticeVersion: Int
        get() = prefs.getInt("acknowledgedNoticeVersion", 0)
        set(value) { prefs.edit().putInt("acknowledgedNoticeVersion", value).apply() }

    val theme: PhosphorTheme
        get() = PhosphorTheme(
            base = Color(phosphorHex) ?: PhosphorPreset.Green.colour,
            glow = glow,
            scanlines = scanlines,
            flicker = flicker,
        )

    fun resetLook() {
        phosphorHex = PhosphorPreset.Green.hex
        glow = 0.25f
        scanlines = false
        flicker = false
        phosphorPanel = true
    }
}
