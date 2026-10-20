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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.FirmwareEmulator
import com.fmswitch.emu.EmulatorClock
import com.fmswitch.emu.Fm1Engine
import com.fmswitch.model.EmulatorPackage
import com.fmswitch.model.StoredFirmware
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.pixelText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EmulatorScreen(
    entry: CatalogueEntry,
    prepared: EmulatorPackage,
    phosphorPanel: Boolean,
    canSave: Boolean,
    saving: Boolean,
    onSave: suspend () -> Result<StoredFirmware>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val authorEmulator = remember(entry.id, entry.links) { FirmwareEmulator.authorEmulator(entry) }
    var saveError by remember(entry.id) { mutableStateOf<String?>(null) }
    var mhz by remember(entry.id) { mutableIntStateOf(EmulatorClock.get(context, entry)) }
    fun clock(value: Int) {
        mhz = value
        EmulatorClock.set(context, entry, value)
    }
    fun keep() {
        scope.launch {
            saveError = null
            onSave().onFailure { saveError = it.message }
        }
    }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose { previous?.let { activity.requestedOrientation = it } }
    }

    if (landscape) {
        var liveStatus by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(theme.background)) {
            when (prepared) {
                EmulatorPackage.Preparing -> MonoText(
                    "FETCHING THE PACKAGE YOU WOULD FLASH…",
                    size = 13,
                    dim = true,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is EmulatorPackage.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MonoText("Couldn't get this firmware's package: ${prepared.message}", size = 14)
                    PixelButton("RETRY", compact = true, onClick = onRetry)
                }
                is EmulatorPackage.Ready -> RunningEmulator(
                    prepared,
                    mhz = mhz,
                    expand = true,
                    phosphorPanel = phosphorPanel,
                    onStatus = { liveStatus = it },
                )
            }
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .padding(start = 8.dp, end = 8.dp)
                    .widthIn(max = 148.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PixelButton("◂ BACK", compact = true, onClick = onBack)
                if (canSave) {
                    PixelButton(if (saving) "SAVING" else "SAVE", compact = true, enabled = !saving, onClick = ::keep)
                }
                saveError?.let { MonoText(it, size = 11) }
                BasicText(entry.name.uppercase(), style = pixelText(13.sp, bold = true, color = theme.bright, glow = true))
                if (authorEmulator != null) {
                    PixelButton("AUTHOR'S EMU", compact = true) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorEmulator)))
                    }
                }
                if (prepared is EmulatorPackage.Ready) ClockRow(mhz, ::clock, narrow = true)
                if (liveStatus.isNotEmpty()) MonoText(liveStatus, size = 11, dim = true)
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelButton("◂ BACK", compact = true, onClick = onBack)
            if (canSave) {
                PixelButton(if (saving) "SAVING" else "SAVE", compact = true, enabled = !saving, onClick = ::keep)
            }
            if (authorEmulator != null) {
                PixelButton("AUTHOR'S EMU", compact = true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorEmulator)))
                }
            }
        }
        BasicText(entry.name.uppercase(), style = pixelText(16.sp, bold = true, color = theme.bright, glow = true))
        saveError?.let { MonoText(it, size = 13) }
        when (prepared) {
            EmulatorPackage.Preparing -> MonoText("FETCHING THE PACKAGE YOU WOULD FLASH…", size = 13, dim = true)
            is EmulatorPackage.Failed -> {
                MonoText("Couldn't get this firmware's package: ${prepared.message}", size = 14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("RETRY", compact = true, onClick = onRetry)
                }
            }
            is EmulatorPackage.Ready -> {
                ClockRow(mhz, ::clock)
                RunningEmulator(prepared, mhz = mhz, expand = false, phosphorPanel = phosphorPanel)
            }
        }
    }
}

@Composable
private fun ClockRow(mhz: Int, onClock: (Int) -> Unit, narrow: Boolean = false) {
    val buttons = @Composable {
        EmulatorClock.CHOICES.forEach { choice ->
            PixelButton("$choice", compact = true, prominent = choice == mhz) { if (choice != mhz) onClock(choice) }
        }
    }
    if (narrow) {
        // One column: a row of 100 / 160 / 240 is wider than the margin and lands on the panel.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoText("CLOCK · MHZ", size = 11, dim = true)
            buttons()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoText("CLOCK", size = 11, dim = true)
            buttons()
            MonoText("MHZ", size = 11, dim = true)
        }
    }
}

@Composable
private fun RunningEmulator(
    prepared: EmulatorPackage.Ready,
    mhz: Int,
    expand: Boolean,
    phosphorPanel: Boolean,
    onStatus: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val theme = LocalPhosphor.current
    // The app in the background (home, another app, screen off) has no emulator running.
    val foreground by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = foreground.isAtLeast(Lifecycle.State.STARTED)
    // One engine at a time: the next boot reads the flash the previous one saved as it closed.
    val current = remember { arrayOfNulls<Fm1Engine>(1) }
    DisposableEffect(Unit) { onDispose { current[0]?.close(); current[0] = null } }
    val engine by produceState<Fm1Engine?>(null, prepared.file, prepared.sha256, mhz, visible) {
        value = null
        current[0]?.close()
        current[0] = null
        if (!visible) return@produceState
        val bytes = withContext(Dispatchers.IO) { runCatching { prepared.file.readBytes() }.getOrNull() }
        val next = bytes?.let { Fm1Engine(it, Fm1Engine.saveFileFor(context.filesDir, prepared.sha256), mhz) }
        current[0] = next
        value = next
    }
    val running = engine
    if (running == null) {
        MonoText(if (visible) "BOOTING…" else "PAUSED", size = 13, dim = true)
        return
    }
    var status by remember(running) { mutableStateOf<Fm1Engine.Status>(Fm1Engine.Status.Booting) }
    LaunchedEffect(running) {
        while (true) {
            status = running.status
            onStatus(statusLine(status, running.mhz))
            delay(300)
        }
    }
    if (!expand) MonoText(statusLine(status, running.mhz), size = 12, dim = status !is Fm1Engine.Status.Stopped)
    Box(
        Modifier.then(if (expand) Modifier.fillMaxSize() else Modifier.fillMaxWidth()),
        contentAlignment = Alignment.Center,
    ) {
        Fm1PanelView(
            running,
            if (expand) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            phosphor = if (phosphorPanel) theme.base else null,
            glow = theme.glow,
        )
    }
    if (!expand) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoText(
                "Tap keys and buttons (several at once is fine); drag a knob up or down to turn it, MASTER for volume. Sound comes from this phone. Turn it sideways for a bigger panel.",
                size = 13,
            )
            MonoText(
                "If the sound gaps or crackles while the load is low, this firmware needs a faster emulated chip: raise CLOCK (a real FM-1 runs at 240 MHz). If the load goes over 100%, lower it.",
                size = 12,
            )
            MonoText(
                "This runs the exact .fwsc a flash would write, on Joakim Eriksson's FM-1 emulator compiled into the app. What you save on the emulated FM-1 is kept for this package.",
                size = 12,
                dim = true,
            )
        }
    }
}

private fun statusLine(status: Fm1Engine.Status, mhz: Int): String = when (status) {
    Fm1Engine.Status.Booting -> "BOOTING…"
    is Fm1Engine.Status.Running -> {
        val load = (status.load * 100).toInt()
        if (status.load > 0.95f) "RUNNING · $mhz MHz · LOAD $load% · TOO MUCH FOR THIS PHONE, LOWER THE CLOCK"
        else "RUNNING · $mhz MHz · LOAD $load%"
    }
    is Fm1Engine.Status.Stopped -> status.message
}
