package com.fmswitch.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.FirmwareEmulator
import com.fmswitch.emu.Fm1Engine
import com.fmswitch.model.EmulatorPackage
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.pixelText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun EmulatorScreen(
    entry: CatalogueEntry,
    prepared: EmulatorPackage,
    phosphorPanel: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val authorEmulator = remember(entry.id, entry.links) { FirmwareEmulator.authorEmulator(entry) }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose { previous?.let { activity.requestedOrientation = it } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 12.dp, vertical = if (landscape) 6.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelButton("◂ BACK", compact = true, onClick = onBack)
            if (authorEmulator != null) {
                PixelButton("AUTHOR'S EMU", compact = true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorEmulator)))
                }
            }
            if (landscape) {
                BasicText(entry.name.uppercase(), style = pixelText(14.sp, bold = true, color = theme.bright, glow = true))
            }
        }
        if (!landscape) {
            BasicText(entry.name.uppercase(), style = pixelText(16.sp, bold = true, color = theme.bright, glow = true))
        }
        when (prepared) {
            EmulatorPackage.Preparing -> MonoText("FETCHING THE PACKAGE YOU WOULD FLASH…", size = 13, dim = true)
            is EmulatorPackage.Failed -> {
                MonoText("Couldn't get this firmware's package: ${prepared.message}", size = 14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("RETRY", compact = true, onClick = onRetry)
                }
            }
            is EmulatorPackage.Ready -> RunningEmulator(prepared, landscape, phosphorPanel)
        }
    }
}

@Composable
private fun ColumnScope.RunningEmulator(prepared: EmulatorPackage.Ready, landscape: Boolean, phosphorPanel: Boolean) {
    val context = LocalContext.current
    val theme = LocalPhosphor.current
    val engine by produceState<Fm1Engine?>(null, prepared.file, prepared.sha256) {
        val bytes = withContext(Dispatchers.IO) { runCatching { prepared.file.readBytes() }.getOrNull() }
        value = bytes?.let { Fm1Engine(it, Fm1Engine.saveFileFor(context.filesDir, prepared.sha256)) }
    }
    val running = engine
    if (running == null) {
        MonoText("BOOTING…", size = 13, dim = true)
        return
    }
    DisposableEffect(running) { onDispose { running.close() } }
    var status by remember(running) { mutableStateOf<Fm1Engine.Status>(Fm1Engine.Status.Booting) }
    LaunchedEffect(running) {
        while (true) {
            status = running.status
            delay(300)
        }
    }
    MonoText(statusLine(status), size = 12, dim = status !is Fm1Engine.Status.Stopped)
    Box(Modifier.fillMaxWidth().then(if (landscape) Modifier.weight(1f) else Modifier), contentAlignment = Alignment.Center) {
        Fm1PanelView(
            running,
            Modifier.fillMaxWidth(),
            phosphor = if (phosphorPanel) theme.base else null,
            glow = theme.glow,
        )
    }
    if (!landscape) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoText(
                "Tap keys and buttons (several at once is fine); drag a knob up or down to turn it, MASTER for volume. Sound comes from this phone. Turn it sideways for a bigger panel.",
                size = 13,
            )
            MonoText(
                "This runs the exact .fwsc a flash would write, on Joakim Eriksson's FM-1 emulator compiled into the app. What you save on the emulated FM-1 is kept for this package.",
                size = 12,
                dim = true,
            )
        }
    }
}

private fun statusLine(status: Fm1Engine.Status): String = when (status) {
    Fm1Engine.Status.Booting -> "BOOTING…"
    is Fm1Engine.Status.Running -> {
        val load = (status.load * 100).toInt()
        if (status.load > 0.95f) "RUNNING · LOAD $load% · THIS PHONE IS STRUGGLING, SOUND MAY BREAK UP"
        else "RUNNING · 100 MHz · LOAD $load%"
    }
    is Fm1Engine.Status.Stopped -> status.message
}
