package com.fmswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.fmswitch.model.MidiCheck
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
                MonoText("FIRMWARE IS RUNNING", size = 14, dim = true)
            }
            model.transportError?.let { MonoText(it, size = 13) }
            if (model.identity == null && !model.isScanning && model.ports.isNotEmpty()) {
                model.probeProblem?.let { MonoText(it, size = 13) }
            }
        }
        val identity = model.identity
        if (identity == null) {
            PixelPanel(title = "CONNECT") {
                MonoText(
                    "Plug the FM-1 into this phone with a USB cable. No extra driver is needed. SIMULATED FM-1 under SETUP is not a synth. It stands in for an FM-1 so you can try a flash. To hear a firmware before you write it, open FIRMWARE and press TRY.",
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
                    NoteText("Several firmwares report this, so this can't tell which one is running.")
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
        if (identity != null) {
            PixelPanel(title = "MIDI CHECK") {
                MonoText(
                    "Sends a short C–E–G–C. If you hear it, notes are getting through. Some firmwares, such as SLOOP, stay quiet on a plain note. The FM-1 answering again afterwards means the cable is fine.",
                    size = 14,
                )
                PixelButton(
                    if (model.isCheckingMidi) "PLAYING" else "PLAY TEST NOTES",
                    prominent = true,
                    enabled = !model.isWriting && !model.isCheckingMidi,
                ) { model.playTestNotes() }
                Spacer(Modifier.height(8.dp))
                MonoText("HOLD A KEY", size = 12, dim = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    LIVE_KEYS.forEach { (label, note) ->
                        MidiHoldKey(
                            label = label,
                            enabled = !model.isWriting && !model.isCheckingMidi,
                            onDown = { model.sendLiveNote(note, true) },
                            onUp = { model.sendLiveNote(note, false) },
                        )
                    }
                }
                when (val check = model.midiCheck) {
                    MidiCheck.Idle -> {}
                    is MidiCheck.Played -> {
                        val heard = if (check.simulated) {
                            "The stand-in heard the notes and still answers as ${check.identity}. It makes no sound. On a real FM-1 you should hear them."
                        } else if (check.loader) {
                            "Notes went out and the update loader still answers. You won't hear anything in this mode."
                        } else if (check.stillAnswering) {
                            "Notes went out and the FM-1 still answers as ${check.identity}. If you heard them, the cable is fine."
                        } else {
                            "Notes were sent, but the FM-1 didn't answer afterwards. Unplug it and scan again."
                        }
                        MonoText(heard, size = 14)
                    }
                    is MidiCheck.Failed -> MonoText(check.message, size = 14)
                }
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

private val LIVE_KEYS = listOf(
    "C" to 60,
    "D" to 62,
    "E" to 64,
    "F" to 65,
    "G" to 67,
    "A" to 69,
    "B" to 71,
)

@Composable
private fun MidiHoldKey(
    label: String,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val theme = LocalPhosphor.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!enabled) {
            if (held) {
                onUp()
                held = false
            }
            return@LaunchedEffect
        }
        if (pressed && !held) {
            held = true
            onDown()
        } else if (!pressed && held) {
            held = false
            onUp()
        }
    }
    BasicText(
        label,
        style = lcdText(18.sp, if (pressed && enabled) theme.background else theme.bright),
        modifier = Modifier
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {}
            .background(if (pressed && enabled) theme.bright else theme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}
