package com.fmswitch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import com.fmswitch.emu.EmulatorFrame
import com.fmswitch.emu.EmulatorRun
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
    chooseClock: Boolean,
    runInBackground: Boolean,
    canSave: Boolean,
    saving: Boolean,
    onSave: suspend () -> Result<StoredFirmware>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onTry: (sha: String, started: Boolean) -> Unit,
) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val authorEmulator = remember(entry.id, entry.links) { FirmwareEmulator.authorEmulator(entry) }
    var saveError by remember(entry.id) { mutableStateOf<String?>(null) }
    var mhz by remember(entry.id, chooseClock) {
        mutableIntStateOf(EmulatorClock.get(context, entry, chooseClock))
    }
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
    // One session for both frames. Calling RunningEmulator from each layout would
    // dispose it on rotate, and that dispose closes the engine.
    var liveStatus by remember { mutableStateOf("") }
    val reportStatus = remember<(String) -> Unit> { { liveStatus = it } }
    val running = remember {
        movableContentOf<RunningSlot> { slot ->
            RunningEmulator(
                prepared = slot.prepared,
                mhz = slot.mhz,
                expand = slot.expand,
                phosphorPanel = slot.phosphorPanel,
                chooseClock = slot.chooseClock,
                runInBackground = slot.runInBackground,
                onStatus = slot.onStatus,
                onTry = slot.onTry,
            )
        }
    }
    val slot = (prepared as? EmulatorPackage.Ready)?.let {
        RunningSlot(
            prepared = it,
            mhz = mhz,
            expand = landscape,
            phosphorPanel = phosphorPanel,
            chooseClock = chooseClock,
            runInBackground = runInBackground,
            onStatus = reportStatus,
            onTry = onTry,
        )
    }

    if (landscape) {
        // The panel keeps the FM-1's shape and sits against the far edge. Every other
        // control is clipped to the leftover strip, so it cannot land on the knobs or the screen.
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.background)) {
            // 88dp holds BACK and AUTHOR'S in the pixel font, plus the button's own padding.
            val fit = EmulatorFrame.fit(maxWidth.value, maxHeight.value, minChrome = 88f)
            val panel = EmulatorFrame.panelRect(maxWidth.value, maxHeight.value, fit)
            val chrome = EmulatorFrame.chromeRect(maxWidth.value, maxHeight.value, fit)
            Box(
                Modifier
                    .offset(panel.x.dp, panel.y.dp)
                    .width(panel.width.dp)
                    .height(panel.height.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (prepared) {
                    EmulatorPackage.Preparing -> MonoText(
                        "GETTING THIS FIRMWARE…",
                        size = 13,
                        dim = true,
                        modifier = Modifier.padding(24.dp),
                    )
                    is EmulatorPackage.Failed -> Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MonoText("Couldn't get this firmware: ${prepared.message}", size = 14)
                        PixelButton("RETRY", compact = true, onClick = onRetry)
                    }
                    is EmulatorPackage.Ready -> slot?.let { running(it) }
                }
            }
            Column(
                Modifier
                    .offset(chrome.x.dp, chrome.y.dp)
                    .width(chrome.width.dp)
                    .height(chrome.height.dp)
                    .clipToBounds()
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ‹ is in the pixel font. The other arrow is not, and a fallback glyph
                // was wide enough to push BACK out of this strip.
                PixelButton("‹ BACK", compact = true, wrap = true, modifier = Modifier.fillMaxWidth(), onClick = onBack)
                if (canSave) {
                    PixelButton(
                        if (saving) "SAVING" else "SAVE",
                        compact = true,
                        wrap = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = ::keep,
                    )
                }
                saveError?.let { MonoText(it, size = 11) }
                BasicText(entry.name.uppercase(), style = pixelText(13.sp, bold = true, color = theme.bright, glow = true))
                if (authorEmulator != null) {
                    PixelButton("AUTHOR'S EMU", compact = true, wrap = true, modifier = Modifier.fillMaxWidth()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorEmulator)))
                    }
                }
                if (chooseClock && prepared is EmulatorPackage.Ready) ClockRow(mhz, ::clock, narrow = true)
                if (liveStatus.isNotEmpty()) MonoText(liveStatus, size = 11, dim = true)
            }
        }
    } else {
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
                EmulatorPackage.Preparing -> MonoText("GETTING THIS FIRMWARE…", size = 13, dim = true)
                is EmulatorPackage.Failed -> {
                    MonoText("Couldn't get this firmware: ${prepared.message}", size = 14)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PixelButton("RETRY", compact = true, onClick = onRetry)
                    }
                }
                is EmulatorPackage.Ready -> {
                    if (chooseClock) ClockRow(mhz, ::clock)
                    slot?.let { running(it) }
                }
            }
        }
    }
}

/** The running TRY session. The same instance is moved between the portrait and landscape frames. */
private data class RunningSlot(
    val prepared: EmulatorPackage.Ready,
    val mhz: Int,
    val expand: Boolean,
    val phosphorPanel: Boolean,
    val chooseClock: Boolean,
    val runInBackground: Boolean,
    val onStatus: (String) -> Unit,
    val onTry: (String, Boolean) -> Unit,
)

@Composable
private fun ClockRow(mhz: Int, onClock: (Int) -> Unit, narrow: Boolean = false) {
    val buttons = @Composable {
        EmulatorClock.CHOICES.forEach { choice ->
            PixelButton("$choice", compact = true, prominent = choice == mhz) { if (choice != mhz) onClock(choice) }
        }
    }
    if (narrow) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoText("CLOCK · MHZ", size = 11, dim = true)
            EmulatorClock.CHOICES.forEach { choice ->
                PixelButton(
                    "$choice",
                    compact = true,
                    wrap = true,
                    prominent = choice == mhz,
                    modifier = Modifier.fillMaxWidth(),
                ) { if (choice != mhz) onClock(choice) }
            }
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
    chooseClock: Boolean,
    runInBackground: Boolean,
    onStatus: (String) -> Unit = {},
    onTry: (String, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val theme = LocalPhosphor.current
    // Leaving TRY removes this composable, and the effect below always closes the engine.
    // Rotating does not: the screen moves this composable between frames instead of removing it.
    // Losing the app's focus only stops it when RUN EMULATOR IN BACKGROUND is off.
    val foreground by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val activityStarted = foreground.isAtLeast(Lifecycle.State.STARTED)
    val playing = EmulatorRun.keepEngine(activityStarted, runInBackground)
    // One engine at a time: the next boot reads the flash the previous one saved as it closed.
    val current = remember { arrayOfNulls<Fm1Engine>(1) }
    DisposableEffect(Unit) { onDispose { current[0]?.close(); current[0] = null } }
    val engine by produceState<Fm1Engine?>(null, prepared.file, prepared.sha256, mhz, playing, runInBackground) {
        value = null
        current[0]?.close()
        current[0] = null
        if (!playing) return@produceState
        val bytes = withContext(Dispatchers.IO) { runCatching { prepared.file.readBytes() }.getOrNull() }
        val next = bytes?.let {
            Fm1Engine(it, Fm1Engine.saveFileFor(context.filesDir, prepared.sha256), mhz, runInBackground)
        }
        current[0] = next
        value = next
    }
    val runningForHold = engine
    DisposableEffect(runningForHold, runInBackground) {
        if (!runInBackground || runningForHold == null) return@DisposableEffect onDispose {}
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .build()
        audio.requestAudioFocus(focus)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fmswitch:emulator")
        lock.setReferenceCounted(false)
        lock.acquire()
        onDispose {
            runCatching { audio.abandonAudioFocusRequest(focus) }
            if (lock.isHeld) lock.release()
        }
    }
    val running = engine
    if (running == null) {
        MonoText(if (playing) "BOOTING…" else "PAUSED", size = 13, dim = true)
        return
    }
    var status by remember(running) { mutableStateOf(running.status) }
    val currentOnStatus by rememberUpdatedState(onStatus)
    val currentOnTry by rememberUpdatedState(onTry)
    LaunchedEffect(running) {
        var reported: Boolean? = null
        while (true) {
            status = running.status
            currentOnStatus(statusLine(status))
            val started = when (val now = status) {
                is Fm1Engine.Status.Running -> if (now.live) true else null
                is Fm1Engine.Status.Stopped -> false
                Fm1Engine.Status.Booting -> null
            }
            if (started != null && started != reported) {
                reported = started
                currentOnTry(prepared.sha256, started)
            }
            delay(300)
        }
    }
    val line = statusLine(status)
    if (!expand && line.isNotEmpty()) {
        MonoText(line, size = 12, dim = status !is Fm1Engine.Status.Stopped)
    }
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
                if (chooseClock) {
                    "If the sound gaps or crackles, raise CLOCK. If the phone struggles to keep up, lower it."
                } else {
                    "This runs at the same speed as a real FM-1. If the sound gaps, or the phone struggles, SETUP ▸ CHOOSE EMULATOR CLOCK turns on 100, 160 and 240."
                },
                size = 12,
            )
            MonoText(
                "What you save here is kept for this firmware.",
                size = 12,
                dim = true,
            )
        }
    }
}

/** Booting and a stopped engine still say why. A running engine does not print its load. */
private fun statusLine(status: Fm1Engine.Status): String = when (status) {
    Fm1Engine.Status.Booting -> "BOOTING…"
    is Fm1Engine.Status.Running -> ""
    is Fm1Engine.Status.Stopped -> status.message
}
