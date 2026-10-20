package com.fmswitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import com.fmswitch.catalogue.SafetyNotice
import com.fmswitch.model.AppTab
import com.fmswitch.model.AppViewModel
import com.fmswitch.theme.CRTOverlay
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.PixelBox
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.pixelText

@Composable
fun RootScreen(model: AppViewModel) {
    val theme = model.theme
    BackHandler(
        enabled = !model.needsSafetyNotice &&
            model.job == null &&
            model.playingFirmware == null &&
            model.tab != AppTab.DEVICE,
    ) {
        model.tab = AppTab.DEVICE
    }
    CompositionLocalProvider(LocalPhosphor provides theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(theme.background),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                StatusLine(model)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (model.tab) {
                        AppTab.DEVICE -> DeviceScreen(model)
                        AppTab.FIRMWARE -> CatalogueScreen(model)
                        AppTab.LIBRARY -> LibraryScreen(model)
                        AppTab.SETTINGS -> SettingsScreen(model)
                    }
                }
                PixelTabBar(model)
            }
            if (model.job != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.background)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    FlashScreen(model)
                }
            }
            if (model.needsSafetyNotice) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.background)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    SafetyNoticeScreen(model)
                }
            }
            if (model.playingFirmware == null) CRTOverlay()
            model.playingFirmware?.let { entry ->
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                @Suppress("UNUSED_EXPRESSION")
                model.libraryTick
                val kept = model.library.stored(entry) != null
                val sideSystem = WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.background)
                        .then(
                            if (landscape) {
                                Modifier.windowInsetsPadding(sideSystem)
                            } else {
                                Modifier
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                            },
                        ),
                ) {
                    EmulatorScreen(
                        entry = entry,
                        prepared = model.emulatorPackage,
                        phosphorPanel = model.settings.phosphorPanel,
                        chooseClock = model.settings.chooseEmulatorClock,
                        canSave = entry.isDownloadable && !kept,
                        saving = entry.id in model.downloadingIds,
                        onSave = { model.save(entry) },
                        onRetry = { model.openEmulator(entry) },
                        onBack = { model.closeEmulator() },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(model: AppViewModel) {
    val theme = LocalPhosphor.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("FM-SWITCH", style = pixelText(12.sp, bold = true, color = theme.bright))
        Spacer(Modifier.weight(1f))
        BasicText(model.transportName, style = pixelText(9.sp, color = theme.muted))
        Spacer(Modifier.height(0.dp).padding(4.dp))
        val identity = model.identity?.text ?: "NO DEVICE"
        BasicText(
            identity,
            style = pixelText(10.sp, bold = true, color = if (model.identity == null) theme.bright else theme.background),
            modifier = Modifier
                .padding(start = 8.dp)
                .background(if (model.identity == null) androidx.compose.ui.graphics.Color.Transparent else theme.bright)
                .border(1.dp, theme.bright)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(theme.dim))
}

@Composable
private fun PixelTabBar(model: AppViewModel) {
    val theme = LocalPhosphor.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(theme.dim))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val selected = tab == model.tab
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { if (model.job == null && !model.needsSafetyNotice) model.tab = tab }
                        .background(if (selected) theme.bright else androidx.compose.ui.graphics.Color.Transparent, PixelBox(2.dp))
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(tab.glyph, style = pixelText(16.sp, color = if (selected) theme.background else theme.muted))
                    BasicText(tab.title, style = pixelText(9.sp, bold = true, color = if (selected) theme.background else theme.muted))
                }
            }
        }
    }
}

@Composable
fun SafetyNoticeScreen(model: AppViewModel) {
    val theme = LocalPhosphor.current
    var seconds = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(4) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (seconds.intValue > 0) {
            kotlinx.coroutines.delay(1000)
            seconds.intValue--
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        BasicText(
            "FM-SWITCH",
            style = pixelText(28.sp, bold = true, color = theme.bright, glow = true),
            modifier = Modifier.padding(top = 40.dp, bottom = 22.dp),
        )
        PixelPanel(title = "BEFORE YOU START") {
            MonoText(SafetyNotice.TEXT, size = 16)
            Spacer(Modifier.height(12.dp))
            MonoText(SafetyNotice.RECOVERY, size = 16)
        }
        Spacer(Modifier.height(12.dp))
        MonoText("This notice is also on the SETUP screen.", size = 13, dim = true)
        Spacer(Modifier.weight(1f))
        PixelButton(
            if (seconds.intValue > 0) "OK (${seconds.intValue})" else "OK",
            prominent = true,
            enabled = seconds.intValue == 0,
        ) { model.acknowledgeNotice() }
    }
}
