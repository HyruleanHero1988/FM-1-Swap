package com.fmswitch.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.BuildConfig
import com.fmswitch.catalogue.SafetyNotice
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
            NoteText("A real FM-1 talks class-compliant USB-MIDI. Plug straight into the phone if you can; hubs often hide the ports. Once the FM-1 answers, FM-SWITCH keeps that port open so the chip doesn't drop USB and blink on and off. Close other MIDI apps first. SIMULATED FM-1 is only a protocol dummy for flashing tests. To play a firmware, open it on FIRMWARE and press TRY.")
            if (model.settings.transportKind == TransportKind.SIMULATED) {
                PixelToggle("START IN USB RECOVERY MODE", model.settings.simulatedRecoveryMode) {
                    model.settings.simulatedRecoveryMode = it
                    model.configureTransport()
                }
                PixelToggle("PULL THE CABLE AT 45%", model.settings.simulatedCablePull) {
                    model.settings.simulatedCablePull = it
                    model.configureTransport()
                }
                PixelToggle("READ BACKWARDS, LIKE V15", model.settings.simulatedReadsBackwards) {
                    model.settings.simulatedReadsBackwards = it
                    model.configureTransport()
                }
                PixelToggle("VERIFY AFTER WRITING, LIKE FELUCCA", model.settings.simulatedVerifies) {
                    model.settings.simulatedVerifies = it
                    model.configureTransport()
                }
                PixelButton("RESET SIMULATED FM-1", enabled = !model.isWriting) { model.configureTransport() }
            }
        }
        PixelPanel(title = "FLASHING") {
            PixelToggle("ALWAYS VIA OFFICIAL FIRMWARE", model.settings.alwaysViaOfficial) {
                model.settings.alwaysViaOfficial = it
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
            NoteText("On, a package TRY downloads goes straight into SAVED, so playing it again or flashing it later costs no more data. Off, TRY keeps it only in a cache Android may clear. Either way, a package already on the phone is never downloaded twice.")
        }
        PixelPanel(title = "BUILD") {
            MonoText("FM-SWITCH  ${BuildConfig.VERSION_NAME}", size = 14)
            MonoText("versionCode  ${BuildConfig.VERSION_CODE}", size = 14, dim = true)
            NoteText("If a new APK says App not installed, uninstall this one once, then install the new file. After that, later APKs overlay.")
        }
        PixelPanel(title = "CREDITS") {
            MonoText(
                "Android port of ocean's FM-SWITCH for the M-VAVE FM-1. Protocol from FM1Kit (MIT) and the FM-1-RE notes (WTFPL). Bundled V15 is M-VAVE's, Apache-2.0, unmodified from fm1_open. TRY runs Joakim Eriksson's fm1-emu (MIT), compiled into the app. Pixel fonts: Silkscreen, Departure Mono and VT323 (SIL OFL). Not affiliated with M-VAVE.",
                size = 14,
            )
            BasicText(
                "▸ FM-SWITCH (iOS)",
                style = pixelText(11.sp, bold = true, color = theme.bright),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HyruleanHero1988/FM-SWITCH")))
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
            BasicText(
                "▸ REDTAILWORKS EMULATORS",
                style = pixelText(11.sp, bold = true, color = theme.bright),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rustypawz.github.io/fm1-HYP1/")))
                    },
            )
        }
    }
}
