package com.fmswitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.FlashPhase
import com.fmswitch.model.VoiceRestore
import com.fmswitch.theme.BlinkingCursor
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.PixelProgressBar
import com.fmswitch.theme.PixelToggle
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.lcdText
import com.fmswitch.theme.pixelText

@Composable
fun FlashScreen(model: AppViewModel) {
    val job = model.job ?: return
    val theme = LocalPhosphor.current
    var restoring by remember { mutableStateOf(false) }
    BackHandler {
        if (!job.phase.isRunning) model.dismissFlash()
    }
    val title = when (job.phase) {
        is FlashPhase.Succeeded -> "FLASHED"
        is FlashPhase.Failed -> "INTERRUPTED"
        is FlashPhase.BackingUp -> "BACKING UP"
        else -> "FLASHING"
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader(title) {
            if (job.steps.size > 1) {
                BasicText("STEP ${job.index + 1}/${job.steps.size}", style = pixelText(10.sp, bold = true, color = theme.dim))
            }
        }
        val current = job.steps[job.index]
        BasicText(current.name.uppercase(), style = pixelText(22.sp, bold = true, color = theme.bright, glow = true))
        MonoText("${current.version} → ${current.product}", size = 15, dim = true)
        Row {
            BasicText(job.phase.label, style = pixelText(13.sp, bold = true, color = theme.bright))
            if (job.phase.isRunning) BlinkingCursor()
            Spacer(Modifier.weight(1f))
            job.phase.fraction?.let {
                BasicText("${(it * 100).toInt()}%", style = lcdText(30.sp, theme.bright))
            }
        }
        PixelProgressBar(job.phase.fraction)
        when (val phase = job.phase) {
            is FlashPhase.Succeeded -> PixelPanel(title = "DONE") {
                MonoText("The FM-1 now reports ${phase.identity}.", size = 15)
                model.lastBackup?.let { backup ->
                    NoteText("A copy of ${backup.summary} is on this phone under SAVED, taken before the write.")
                }
                if (phase.identity == FM1Identity.OFFICIAL_V15.text) {
                    NoteText("V15 can keep the old firmware's voices in some banks, some of them wrong or silent. Restoring writes its 128 factory voices over all four banks.")
                    PixelButton("RESTORE FACTORY VOICES") { restoring = true }
                }
                PixelButton("CLOSE", prominent = true) { model.dismissFlash() }
            }
            is FlashPhase.Failed -> PixelPanel(title = "STOPPED") {
                MonoText(phase.message, size = 15)
                if (phase.canResume) {
                    PixelButton("RESUME", prominent = true) { model.resumeFlash() }
                } else {
                    PixelButton("TRY AGAIN") { model.resumeFlash() }
                }
                PixelButton("CLOSE") { model.dismissFlash() }
            }
            is FlashPhase.BackingUp -> PixelPanel(title = "SAVING A COPY FIRST") {
                MonoText(
                    "Reading the running firmware's 128 presets and 16 patterns so they aren't lost if this flash overwrites them. The write has not started yet.",
                    size = 14,
                )
                NoteText("Same dump Baud Girl's Device Manager uses: a presets .syx and a patterns .syx, kept on this phone.")
            }
            else -> PixelPanel(title = "KEEP THIS SCREEN OPEN") {
                MonoText(
                    "Keep FM-SWITCH in the foreground and don't unplug the FM-1 until this finishes. Auto-lock is paused.",
                    size = 14,
                )
                NoteText("An install takes from about 35 seconds to a minute. If it is interrupted, the FM-1 waits in update mode and RESUME carries on.")
            }
        }
        PixelPanel(title = "LOG") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                model.log.forEach { MonoText(it, size = 12, dim = true) }
            }
        }
    }
    if (restoring) VoiceRestoreSheet(model) { restoring = false }
}

@Composable
fun VoiceRestoreSheet(model: AppViewModel, onDismiss: () -> Unit) {
    val theme = LocalPhosphor.current
    var understood by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = {
            if (!model.voiceRestore.isRunning) {
                model.dismissVoiceRestore()
                onDismiss()
            }
        },
    ) {
        Column(
            Modifier
                .background(theme.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("FACTORY VOICES")
            when (val state = model.voiceRestore) {
                VoiceRestore.Idle -> {
                    MonoText(
                        "This erases the FM-1's four voice banks and writes V15's 128 factory voices in their place. Every voice in them is replaced for good, including any you made, and V15 can't read them back first.",
                        size = 14,
                    )
                    NoteText("Worth doing after installing V15 over another firmware. Keep the cable connected until it finishes.")
                    if (model.identity != FM1Identity.OFFICIAL_V15) {
                        MonoText("The FM-1 needs to be running V15 (FM-1_015) for this.", size = 14)
                    }
                    PixelToggle("REPLACE EVERY VOICE", understood) { understood = it }
                }
                is VoiceRestore.Writing -> {
                    MonoText("Writing factory voices…", size = 15)
                    PixelProgressBar(state.fraction.toFloat())
                }
                VoiceRestore.Succeeded -> MonoText("Done: the FM-1's four voice banks now hold V15's 128 factory voices.", size = 15)
                is VoiceRestore.Failed -> MonoText(state.message, size = 15)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!model.voiceRestore.isRunning) {
                    PixelButton("CLOSE", modifier = Modifier.weight(1f)) {
                        model.dismissVoiceRestore()
                        onDismiss()
                    }
                }
                if (model.voiceRestore is VoiceRestore.Idle) {
                    PixelButton("RESTORE", modifier = Modifier.weight(1f), prominent = true, enabled = understood) {
                        model.restoreFactoryVoices()
                    }
                }
            }
        }
    }
}
