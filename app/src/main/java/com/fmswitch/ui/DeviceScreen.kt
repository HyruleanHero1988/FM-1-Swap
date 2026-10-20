package com.fmswitch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.model.AppTab
import com.fmswitch.model.AppViewModel
import com.fmswitch.theme.BlinkingCursor
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.lcdText

@Composable
fun DeviceScreen(model: AppViewModel) {
    var restoring by remember { mutableStateOf(false) }
    val theme = LocalPhosphor.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader("DEVICE") {
            PixelButton(if (model.isScanning) "SCANNING" else "SCAN", compact = true, enabled = !model.isScanning) {
                model.scan(force = true)
            }
        }
        PixelPanel(title = "REPORTS") {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(
                    model.identity?.text ?: if (model.isScanning) "LISTENING" else "----",
                    style = lcdText(48.sp, theme.bright),
                    maxLines = 1,
                )
                if (model.identity == null) BlinkingCursor()
            }
            if (model.identity?.isLoader == true) {
                MonoText("UPDATE LOADER IS RUNNING", size = 14, dim = true)
            } else if (model.identity != null) {
                MonoText("FIRMWARE IS RUNNING · USB-MIDI HELD", size = 14, dim = true)
            }
            model.transportError?.let { MonoText(it, size = 13) }
        }
        val identity = model.identity
        if (identity == null) {
            PixelPanel(title = "CONNECT") {
                MonoText(
                    "Plug the FM-1 into this Android device with a USB-C cable. Class-compliant USB-MIDI is enough; no extra driver. Or pick SIMULATED FM-1 under SETUP to try the protocol without hardware.",
                    size = 14,
                )
            }
        } else if (identity.isLoader) {
            PixelPanel(title = "UPDATE LOADER") {
                MonoText(
                    "The FM-1 is waiting in its update loader after an interrupted flash or USB recovery mode. Open a saved firmware and resume.",
                    size = 14,
                )
                PixelButton("GO TO SAVED") { model.tab = AppTab.LIBRARY }
            }
        } else {
            PixelPanel(title = "PROBABLY") {
                MonoText(model.probablyRunning(identity), size = 15)
                if (model.catalogue.entriesMatching(identity.text).size > 1 && model.history.lastInstall(identity.text) == null) {
                    NoteText("Several firmwares share this ID, so the device can't say which one it is.")
                }
            }
        }
        if (identity == FM1Identity.OFFICIAL_V15) {
            PixelPanel(title = "FACTORY VOICES") {
                MonoText(
                    "After V15 goes on over another firmware, some voice banks can still hold the old firmware's voices. Restoring writes V15's 128 factory voices over all four banks.",
                    size = 14,
                )
                PixelButton("RESTORE FACTORY VOICES", enabled = !model.isWriting) { restoring = true }
            }
        }
        PixelPanel(title = "PORTS") {
            if (model.ports.isEmpty()) {
                MonoText("No MIDI ports right now.", size = 14, dim = true)
            } else {
                model.ports.forEach { port ->
                    MonoText("· ${port.name}", size = 14)
                }
            }
        }
    }
    if (restoring) {
        VoiceRestoreSheet(model) { restoring = false }
    }
}
