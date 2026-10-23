package com.fmswitch.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.BuildConfig
import com.fmswitch.catalogue.SafetyNotice
import com.fmswitch.model.AppIcon
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.TransportKind
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PhosphorPreset
import com.fmswitch.theme.PixelBox
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.PixelToggle
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.pixelText

@Composable
fun SettingsScreen(model: AppViewModel) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION")
    model.themeTick
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader("SETUP")
        PixelPanel(title = "SAFETY + RECOVERY") {
            MonoText(SafetyNotice.TEXT, size = 14)
            Spacer(Modifier.height(8.dp))
            MonoText(SafetyNotice.RECOVERY, size = 14)
        }
        PixelPanel(title = "IF IT WON'T START AT ALL") {
            MonoText(SafetyNotice.ROM_RECOVERY, size = 14)
            SafetyNotice.ROM_LINKS.forEach { link ->
                BasicText(
                    "▸ ${link.label.uppercase()}",
                    style = pixelText(11.sp, bold = true, color = theme.bright),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) },
                )
            }
        }
        PixelPanel(title = "COLOUR + SCREEN") {
            MonoText("APP ICON", size = 14)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { icon ->
                            AppIconChip(
                                icon = icon,
                                selected = model.settings.appIcon == icon,
                                onClick = { model.setAppIcon(icon) },
                            )
                        }
                    }
                }
            }
            NoteText("The plastic of your FM-1, not the phosphor. Some phones take a moment to change the home-screen picture. A themed icon may flatten it to one colour.")
            Spacer(Modifier.height(8.dp))
            MonoText("COLOUR", size = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhosphorPreset.entries.forEach { preset ->
                    val selected = model.settings.phosphorHex.equals(preset.hex, true)
                    BasicText(
                        preset.label,
                        style = pixelText(10.sp, bold = true, color = if (selected) theme.background else theme.bright),
                        modifier = Modifier
                            .clickable {
                                model.settings.phosphorHex = preset.hex
                                model.bumpTheme()
                            }
                            .background(if (selected) theme.bright else theme.surface, PixelBox(2.dp))
                            .border(2.dp, theme.bright, PixelBox(2.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            MonoText("GLOW  ${(model.settings.glow * 100).toInt()}%", size = 14)
            Row {
                PixelButton("-", compact = true) {
                    model.settings.glow = (model.settings.glow - 0.05f).coerceIn(0f, 1f)
                    model.bumpTheme()
                }
                PixelButton("+", compact = true) {
                    model.settings.glow = (model.settings.glow + 0.05f).coerceIn(0f, 1f)
                    model.bumpTheme()
                }
            }
            PixelButton("RESET LOOK", compact = true) {
                model.settings.resetLook()
                model.bumpTheme()
            }
            PixelToggle("CRT SCANLINES", model.settings.scanlines) {
                model.settings.scanlines = it
                model.bumpTheme()
            }
            PixelToggle("CRT FLICKER", model.settings.flicker) {
                model.settings.flicker = it
                model.bumpTheme()
            }
            PixelToggle("PHOSPHOR EMULATOR PANEL", model.settings.phosphorPanel) {
                model.settings.phosphorPanel = it
                model.bumpTheme()
            }
            NoteText("TRY draws the virtual FM-1 as glowing vector lines in this colour, like a scope's electron beam. Off shows the realistic panel. The firmware's own screen is never changed either way.")
            PixelToggle("CHOOSE EMULATOR CLOCK", model.settings.chooseEmulatorClock) {
                model.settings.chooseEmulatorClock = it
                model.bumpTheme()
            }
            NoteText("Off, TRY runs at the same speed as a real FM-1, and the clock stays off the TRY screen. On, TRY offers 100, 160 and 240, remembered per firmware. Lower it when the sound gaps or the phone struggles.")
            PixelToggle("RUN EMULATOR IN BACKGROUND", model.settings.backgroundEmulator) {
                model.settings.backgroundEmulator = it
                model.bumpTheme()
            }
            NoteText("Off, TRY stops when you switch away or the screen turns off. On, it keeps sounding until you leave TRY. Leaving TRY always stops it. Turning the phone does not: the panel changes shape and the firmware keeps running.")
        }
        PixelPanel(title = "CONNECTION") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportKind.entries.forEach { kind ->
                    PixelButton(
                        kind.label,
                        compact = true,
                        prominent = model.settings.transportKind == kind,
                    ) {
                        model.settings.transportKind = kind
                        model.configureTransport()
                        model.bumpTheme()
                    }
                }
            }
            NoteText("Plug the FM-1 straight into the phone if you can. Hubs often hide it. Close other MIDI apps first. Once it answers, leave this app open so the connection doesn't blink on and off. SIMULATED FM-1 is not a synth. It stands in for an FM-1 so you can try a flash. To hear a firmware, open it on FIRMWARE and press TRY.")
            if (model.settings.transportKind == TransportKind.SIMULATED) {
                PixelToggle("START IN USB RECOVERY MODE", model.settings.simulatedRecoveryMode) {
                    model.settings.simulatedRecoveryMode = it
                    model.configureTransport()
                    model.bumpTheme()
                }
                PixelToggle("INTERRUPT THE PRETEND FLASH HALFWAY", model.settings.simulatedCablePull) {
                    model.settings.simulatedCablePull = it
                    model.configureTransport()
                    model.bumpTheme()
                }
                PixelToggle("READ THE WRITE BACK", model.settings.simulatedReadsBackwards) {
                    model.settings.simulatedReadsBackwards = it
                    model.configureTransport()
                    model.bumpTheme()
                }
                PixelToggle("CHECK THE WRITE AFTERWARDS", model.settings.simulatedVerifies) {
                    model.settings.simulatedVerifies = it
                    model.configureTransport()
                    model.bumpTheme()
                }
                PixelButton("RESET THE STAND-IN", enabled = !model.isWriting) { model.configureTransport() }
            }
        }
        PixelPanel(title = "FLASHING") {
            PixelToggle("ALWAYS VIA OFFICIAL FIRMWARE", model.settings.alwaysViaOfficial) {
                model.settings.alwaysViaOfficial = it
                model.bumpTheme()
            }
            PixelToggle("SHOW BETA RELEASES", model.settings.showBeta) {
                model.settings.showBeta = it
                model.bumpTheme()
            }
            PixelToggle("SHOW LISTINGS WITHOUT A PACKAGE", model.settings.showLinksOnly) {
                model.settings.showLinksOnly = it
                model.bumpTheme()
            }
            PixelToggle("SAVE WHAT YOU TRY", model.settings.autoSaveTried) {
                model.settings.autoSaveTried = it
                model.bumpTheme()
            }
            NoteText("On, TRY keeps the firmware under SAVED. Off, it does not keep it. A firmware already on the phone is not downloaded again.")
        }
        PixelPanel(title = "BUILD") {
            MonoText("FM-1 SWAP  ${BuildConfig.VERSION_NAME}", size = 14)
            NoteText("If a new download says App not installed, uninstall this one once, then install the new file. After that, later downloads install over this one.")
        }
        PixelPanel(title = "CREDITS") {
            MonoText(
                "FM-1 Swap for the M-VAVE FM-1. The idea of swapping FM-1 firmware from a phone started with ocean. Protocol from FM1Kit (MIT) and the FM-1-RE notes (WTFPL). Bundled V15 is M-VAVE's, Apache-2.0, unmodified from fm1_open. The hosted patches are the FM-1's own four banks, captured by KingParamount, and Aquanode's CC0 banks. TRY runs Joakim Eriksson's fm1-emu (MIT), compiled into the app. Pixel fonts: Silkscreen, Departure Mono and VT323 (SIL OFL). Not affiliated with M-VAVE.",
                size = 14,
            )
            BasicText(
                "▸ OCEAN",
                style = pixelText(11.sp, bold = true, color = theme.bright),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ocean/FM-SWITCH")))
                    },
            )
            BasicText(
                "▸ FM1KIT",
                style = pixelText(11.sp, bold = true, color = theme.bright),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ocean/FM1Kit")))
                    },
            )
            BasicText(
                "▸ FM1-EMU (TRY)",
                style = pixelText(11.sp, bold = true, color = theme.bright),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/joakimeriksson/fm1-emu")))
                    },
            )
        }
    }
}

@Composable
private fun AppIconChip(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalPhosphor.current
    Box(
        Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .border(2.dp, if (selected) theme.bright else theme.dim, PixelBox(2.dp))
            .padding(4.dp)
            .background(icon.body, PixelBox(1.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val gap = (1.5.dp).toPx()
            drawRect(
                Color(0xFF111111),
                topLeft = Offset(w * 0.10f, h * 0.14f),
                size = Size(w * 0.30f, h * 0.28f),
            )
            val pad = (w * 0.10f).coerceAtLeast(2f)
            for (row in 0..1) {
                for (col in 0..3) {
                    drawRect(
                        icon.pad,
                        topLeft = Offset(w * 0.48f + col * (pad + gap), h * 0.14f + row * (pad + gap)),
                        size = Size(pad, pad),
                    )
                }
            }
            val keyW = w * 0.08f
            val keyH = h * 0.22f
            for (i in 0..6) {
                drawRect(
                    icon.key,
                    topLeft = Offset(w * 0.10f + i * (keyW + gap), h * 0.62f),
                    size = Size(keyW, keyH),
                )
            }
        }
    }
}
