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
