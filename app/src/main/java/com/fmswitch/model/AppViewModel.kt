package com.fmswitch.model

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.FirmwareSort
import com.fmswitch.catalogue.SafetyNotice
import com.fmswitch.fm1kit.BaudGirlLibrarian
import com.fmswitch.fm1kit.BaudGirlSnapshot
import com.fmswitch.fm1kit.EditorArchive
import com.fmswitch.fm1kit.EditorBackupKind
import com.fmswitch.fm1kit.EditorLibrarian
import com.fmswitch.fm1kit.mightHaveEditorBackup
import com.fmswitch.fm1kit.ConnectionTone
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.fm1kit.FM1UpdateError
import com.fmswitch.fm1kit.FM1Updater
import com.fmswitch.fm1kit.MidiNotes
import com.fmswitch.fm1kit.supportsBaudGirlBackup
import com.fmswitch.fm1kit.FoundDevice
import com.fmswitch.fm1kit.InstallCheck
import com.fmswitch.fm1kit.InstallKind
import com.fmswitch.fm1kit.InstallOptions
import com.fmswitch.fm1kit.MidiPortDescriptor
import com.fmswitch.fm1kit.MidiTransport
import com.fmswitch.fm1kit.OpenFirmware
import com.fmswitch.fm1kit.SimulatedFM1
import com.fmswitch.fm1kit.SimulatedOptions
import com.fmswitch.fm1kit.UnavailableTransport
import com.fmswitch.fm1kit.UpdateEvent
import com.fmswitch.fm1kit.isOpen
import com.fmswitch.emu.EmulatorClock
import com.fmswitch.emu.Fm1Engine
import com.fmswitch.midi.AndroidMidiTransport
import com.fmswitch.theme.PhosphorTheme
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class AppTab(val title: String, val glyph: String) {
    DEVICE("DEVICE", "\u25a3"),
    FIRMWARE("FIRMWARE", "\u2261"),
    LIBRARY("SAVED", "\u25bc"),
    SETTINGS("SETUP", "\u25c6"),
}

sealed class FlashPhase {
    data object Preparing : FlashPhase()
    data class BackingUp(val done: Int, val total: Int) : FlashPhase()
    data class Staging(val requests: Int) : FlashPhase()
    data object WaitingForLoader : FlashPhase()
    data class Writing(val amount: Double) : FlashPhase()
    data class Verifying(val amount: Double) : FlashPhase()
    data object Rebooting : FlashPhase()
    data class Succeeded(val identity: String) : FlashPhase()
    data class Failed(val message: String, val canResume: Boolean) : FlashPhase()

    val label: String
        get() = when (this) {
            Preparing -> "CONNECTING"
            is BackingUp -> "BACKING UP"
            is Staging -> "CHECKING THE FILE"
            WaitingForLoader -> "RESTARTING"
            is Writing -> "WRITING"
            is Verifying -> "VERIFYING"
            Rebooting -> "REBOOTING"
            is Succeeded -> "DONE"
            is Failed -> "STOPPED"
        }

    val isRunning: Boolean get() = this !is Succeeded && this !is Failed
    val fraction: Float?
        get() = when (this) {
            is BackingUp -> if (total == 0) 0f else done.toFloat() / total
            is Staging -> (requests / 75.0).coerceAtMost(1.0).toFloat()
            is Writing -> amount.toFloat()
            is Verifying -> amount.toFloat()
            is Succeeded -> 1f
            else -> null
        }
}

data class FlashJob(
    val steps: List<StoredFirmware>,
    val confirmsSwitchingFirmware: Boolean = false,
    val confirmsSameIdentity: Boolean = false,
    val index: Int = 0,
    val phase: FlashPhase = FlashPhase.Preparing,
    /** Firmware this step replaces, one entry per step, captured when the flash starts. */
    val fromLabels: List<String> = emptyList(),
)

sealed class VoiceRestore {
    data object Idle : VoiceRestore()
    data class Writing(val fraction: Double) : VoiceRestore()
    data object Succeeded : VoiceRestore()
    data class Failed(val message: String) : VoiceRestore()
    val isRunning: Boolean get() = this is Writing
}

sealed class BackupRestore {
    data object Idle : BackupRestore()
    data class Writing(val label: String, val fraction: Double) : BackupRestore()
    data object Succeeded : BackupRestore()
    data class Failed(val message: String) : BackupRestore()
    val isRunning: Boolean get() = this is Writing
}

sealed class MidiCheck {
    data object Idle : MidiCheck()
    data class Played(
        val stillAnswering: Boolean,
        val identity: String?,
        val simulated: Boolean,
        val loader: Boolean,
        val notesSent: Int,
    ) : MidiCheck()
    data class Failed(val message: String) : MidiCheck()
}

/** The package TRY hands to the bundled emulator: the same file a flash would write. */
sealed class EmulatorPackage {
    data object Preparing : EmulatorPackage()
    data class Ready(val file: File, val sha256: String) : EmulatorPackage()
    data class Failed(val message: String) : EmulatorPackage()
}

/** What a flash can save off the running FM-1 before it writes. */
sealed class BackupOffer {
    data object Unknown : BackupOffer()
    data object Checking : BackupOffer()
    data object BaudGirl : BackupOffer()
    data object Felucca : BackupOffer()
    data object Sloop : BackupOffer()
    data object None : BackupOffer()

    val auto: Boolean
        get() = this is BaudGirl || this is Felucca || this is Sloop
}

/** A silent TRY run before a flash, for every package that has never been tried. */
sealed class FlashProbe {
    data object Idle : FlashProbe()
    data class Checking(val name: String) : FlashProbe()
    data class Settled(val failed: List<String>, val note: String? = null) : FlashProbe()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
    val settings = AppSettings(prefs)
    val library = FirmwareLibrary(application)
    val catalogue = CatalogueStore(application)
    val history = FlashHistory(prefs)
    val backups = BackupStore(application)
    private val tryLedger = TryLedger(
        initial = decodeTryVerdicts(prefs.getString(TRY_VERDICTS, null)),
        persist = { prefs.edit().putString(TRY_VERDICTS, encodeTryVerdicts(it)).apply() },
    )

    var tab by mutableStateOf(AppTab.DEVICE)
    var identity by mutableStateOf<FM1Identity?>(null)
        private set
    var isScanning by mutableStateOf(false)
        private set
    var ports by mutableStateOf<List<MidiPortDescriptor>>(emptyList())
        private set
    var job by mutableStateOf<FlashJob?>(null)
        private set
    var voiceRestore by mutableStateOf<VoiceRestore>(VoiceRestore.Idle)
        private set
    var backupRestore by mutableStateOf<BackupRestore>(BackupRestore.Idle)
        private set
    var lastBackup by mutableStateOf<DeviceBackup?>(null)
        private set
    var backupTick by mutableIntStateOf(0)
    var log by mutableStateOf<List<String>>(emptyList())
        private set
    var transportName by mutableStateOf("")
        private set
    var transportError by mutableStateOf<String?>(null)
        private set
    var probeProblem by mutableStateOf<String?>(null)
        private set
    var catalogueTick by mutableIntStateOf(0)
    var listTick by mutableIntStateOf(0)
    var libraryTick by mutableIntStateOf(0)
    var themeTick by mutableIntStateOf(0)
    var glow by mutableFloatStateOf(settings.glow)
    var downloadingIds by mutableStateOf(setOf<String>())
        private set
    var midiCheck by mutableStateOf<MidiCheck>(MidiCheck.Idle)
    var isCheckingMidi by mutableStateOf(false)
        private set
    var playingFirmware by mutableStateOf<CatalogueEntry?>(null)
        private set
    var emulatorPackage by mutableStateOf<EmulatorPackage>(EmulatorPackage.Preparing)
        private set
    var flashProbe by mutableStateOf<FlashProbe>(FlashProbe.Idle)
    var backupOffer by mutableStateOf<BackupOffer>(BackupOffer.Unknown)
        private set
    private var emulatorJob: Job? = null
    private var probeJob: Job? = null

    val theme: PhosphorTheme get() = settings.theme.also { themeTick }
    val isWriting: Boolean get() = job?.phase?.isRunning == true || voiceRestore.isRunning || backupRestore.isRunning
    val needsSafetyNotice: Boolean get() = settings.acknowledgedNoticeVersion < SafetyNotice.VERSION

    private var transport: MidiTransport = UnavailableTransport
    private var updater = FM1Updater(UnavailableTransport)
    private var watch: AutoCloseable? = null
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private var held: FoundDevice? = null
    private var ignorePortChangesUntil = 0L
    private var backupRestoreSeq = 0
    private var backupProbeSeq = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    var simulated: SimulatedFM1? = null
        private set

    init {
        configureTransport()
    }

    fun bumpTheme() {
        glow = settings.glow
        themeTick++
    }

    fun acknowledgeNotice() {
        settings.acknowledgedNoticeVersion = SafetyNotice.VERSION
        themeTick++
    }

    fun configureTransport() {
        if (isWriting) return
        watch?.close()
        releaseHeld()
        simulated?.shutdown()
        simulated = null
        transportError = null
        probeProblem = null
        transport = when (settings.transportKind) {
            TransportKind.USB_MIDI -> try {
                AndroidMidiTransport(getApplication())
            } catch (error: Exception) {
                transportError = error.message
                UnavailableTransport
            }
            TransportKind.SIMULATED -> SimulatedFM1(
                SimulatedOptions(
                    startsInRecoveryMode = settings.simulatedRecoveryMode,
                    unplugDuringWriteAt = if (settings.simulatedCablePull) 0.45 else null,
                    writesBackwards = settings.simulatedReadsBackwards,
                    checksAfterWriting = settings.simulatedVerifies,
                ),
            ).also { simulated = it }
        }
        transportName = settings.transportKind.label
        updater = FM1Updater(transport)
        identity = null
        ports = emptyList()
        watch = transport.changes {
            viewModelScope.launch {
                delay(800)
                if (isWriting) return@launch
                if (System.currentTimeMillis() < ignorePortChangesUntil) return@launch
                scan(force = false)
            }
        }
        viewModelScope.launch { scan(force = true) }
    }

    fun scan(force: Boolean = false) {
        if (isWriting) return
        if (isScanning && !force) return
        scanJob?.cancel()
        val generation = ++scanGeneration
        scanJob = viewModelScope.launch {
            isScanning = true
            try {
                ports = transport.ports()
                val current = held
                if (current != null && current.connection.isLost) {
                    releaseHeld()
                }
                if (!force && held != null && identity != null && ports.any { it.id == held!!.connection.port.id }) {
                    return@launch
                }
                releaseHeld()
                ignorePortChangesUntil = System.currentTimeMillis() + 2_000
                val found = updater.find()
                if (isWriting) {
                    found?.connection?.close()
                    return@launch
                }
                probeProblem = if (found == null) updater.lastProbeProblem else null
                if (found != null) {
                    identity = found.identity
                    held = found
                    ignorePortChangesUntil = System.currentTimeMillis() + 2_000
                } else if (force || ports.isEmpty()) {
                    identity = null
                }
            } finally {
                if (generation == scanGeneration) isScanning = false
            }
        }
    }

    private fun releaseHeld() {
        held?.connection?.close()
        held = null
    }

    fun plan(forTarget: StoredFirmware, requiresOfficial: Boolean): List<StoredFirmware> {
        val official = library.official
        return if (forTarget.source != "official" &&
            (requiresOfficial || settings.alwaysViaOfficial) &&
            official != null &&
            identity?.text != official.product
        ) {
            listOf(official, forTarget)
        } else {
            listOf(forTarget)
        }
    }

    suspend fun check(item: StoredFirmware): InstallCheck {
        releaseHeld()
        ignorePortChangesUntil = System.currentTimeMillis() + 2_000
        return withContext(Dispatchers.Default) {
            updater.check(library.loadPackage(item))
        }
    }

    /**
     * Boot every selected package that TRY has never settled, silently, at the real FM-1 clock.
     * A package that fails to start is flagged. One that already failed a TRY, foreground or not, is named
     * without being booted again. Leaving the sheet cancels the probe and does not record a result.
     */
    fun beginFlashProbe(steps: List<StoredFirmware>) {
        probeJob?.cancel()
        val plan = planFlashTry(steps.map { it.sha256 to it.name }, tryLedger::verdict)
        if (plan.probe.isEmpty()) {
            flashProbe = FlashProbe.Settled(plan.failedNames)
            probeJob = null
            return
        }
        val bySha = steps.associateBy { it.sha256.lowercase() }
        probeJob = viewModelScope.launch {
            val failed = plan.failedNames.toMutableList()
            for (sha in plan.probe) {
                val item = bySha[sha] ?: continue
                flashProbe = FlashProbe.Checking(item.name)
                val started = try {
                    val bytes = withContext(Dispatchers.IO) { library.file(item).readBytes() }
                    emulateForRisk(bytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    flashProbe = FlashProbe.Settled(failed, error.userMessage())
                    return@launch
                }
                if (started) tryLedger.note(sha, started = true) else {
                    tryLedger.note(sha, started = false)
                    failed += item.name
                }
            }
            flashProbe = FlashProbe.Settled(failed)
        }
    }

    fun cancelFlashProbe() {
        probeJob?.cancel()
        probeJob = null
        flashProbe = FlashProbe.Idle
    }

    /** Decides the confirm-sheet copy before the MIDI probe. Baud Girl is known from the identity alone. */
    fun prepareBackupOffer() {
        backupProbeSeq++
        val id = identity
        backupOffer = when {
            id == null || id.isLoader -> BackupOffer.None
            id.supportsBaudGirlBackup -> BackupOffer.BaudGirl
            id.mightHaveEditorBackup -> BackupOffer.Checking
            else -> BackupOffer.None
        }
    }

    /** Asks a Felucca-family or SLOOP device which backup it actually answers. */
    suspend fun finishBackupOffer() {
        if (backupOffer != BackupOffer.Checking) return
        val seq = backupProbeSeq
        val kind = try {
            withContext(Dispatchers.Default) {
                val found = updater.find() ?: return@withContext null
                try {
                    EditorLibrarian().detect(found.connection)
                } finally {
                    found.connection.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (seq != backupProbeSeq) return
        backupOffer = when (kind) {
            EditorBackupKind.FELUCCA -> BackupOffer.Felucca
            EditorBackupKind.SLOOP -> BackupOffer.Sloop
            null -> BackupOffer.None
        }
    }

    fun cancelBackupProbe() {
        backupProbeSeq++
        backupOffer = BackupOffer.Unknown
    }

    /** A foreground TRY. A stop flags the package; a start clears that flag. */
    fun noteTry(sha: String, started: Boolean) {
        tryLedger.note(sha, started)
    }

    fun flash(steps: List<StoredFirmware>, confirmsSwitchingFirmware: Boolean, confirmsSameIdentity: Boolean) {
        if (steps.isEmpty() || isWriting) return
        log = emptyList()
        lastBackup = null
        val autoBackup = identity?.supportsBaudGirlBackup == true || backupOffer.auto
        val running = deviceFirmwareLabel(
            identityText = identity?.text,
            loader = identity?.isLoader == true,
            saved = library.items,
            catalogue = catalogue.entries,
            lastInstall = identity?.text?.let { history.lastInstall(it) },
        )
        job = FlashJob(
            steps,
            confirmsSwitchingFirmware = confirmsSwitchingFirmware || autoBackup,
            confirmsSameIdentity = confirmsSameIdentity,
            fromLabels = flashFromLabels(running, steps),
        )
        viewModelScope.launch {
            runFlash(0)
        }
    }

    fun resumeFlash() {
        val current = job ?: return
        if (current.phase.isRunning) return
        job = current.copy(phase = FlashPhase.Preparing)
        viewModelScope.launch {
            releaseHeld()
            runFlash(current.index)
        }
    }

    fun dismissFlash() {
        if (job?.phase?.isRunning == true) return
        job = null
        scan()
    }

    private suspend fun runFlash(startIndex: Int) {
        val current = job ?: return
        if (startIndex == 0) {
            try {
                captureBackup()
            } catch (error: Exception) {
                appendLog("\u2715 ${error.userMessage()}")
                job = job?.copy(phase = FlashPhase.Failed(error.userMessage(), canResume = false))
                return
            }
        }
        releaseHeld()
        ignorePortChangesUntil = System.currentTimeMillis() + 2_000
        val options = InstallOptions(
            confirmsSwitchingFirmware = current.confirmsSwitchingFirmware,
            confirmsSameIdentity = current.confirmsSameIdentity,
        )
        for (index in startIndex until current.steps.size) {
            val item = current.steps[index]
            job = job?.copy(index = index, phase = FlashPhase.Preparing)
            appendLog("\u25b8 ${item.name} ${item.version} (${item.product})")
            try {
                val package_ = withContext(Dispatchers.IO) { library.loadPackage(item) }
                simulated?.prepare(package_)
                val isLoaderRunning = identity?.isLoader == true
                val installed = withContext(Dispatchers.Default) {
                    if (isLoaderRunning) {
                        updater.resume(package_) { applyEvent(it) }
                    } else {
                        updater.install(package_, options) { applyEvent(it) }
                    }
                }
                identity = installed
                history.record(item)
            } catch (error: Exception) {
                appendLog("\u2715 ${error.userMessage()}")
                job = job?.copy(phase = FlashPhase.Failed(error.userMessage(), isResumable(error)))
                return
            }
        }
        job = job?.copy(phase = FlashPhase.Succeeded(identity?.text.orEmpty()))
        appendLog("\u2713 Finished.")
        lastBackup?.let { appendLog("Backup kept on this phone: ${it.summary}.") }
    }

    private suspend fun captureBackup() {
        val current = identity
        if (current == null || current.isLoader) {
            appendLog("Nothing to back up. The FM-1 is in its update loader.")
            return
        }
        job = job?.copy(phase = FlashPhase.BackingUp(0, 1))
        val snapshot: BaudGirlSnapshot?
        val archive: EditorArchive?
        if (current.supportsBaudGirlBackup) {
            appendLog("Backing up 128 presets and 16 patterns from ${current.text}.")
            snapshot = dumpBaudGirl(current)
            archive = null
        } else if (current.mightHaveEditorBackup) {
            snapshot = null
            archive = dumpEditor(current.text)
        } else {
            snapshot = null
            archive = null
            appendLog("${current.text} has nothing this app can save from it. Keeping a firmware copy if this phone has one.")
        }
        val firmware = library.items.firstOrNull { it.product == current.text }
        val bytes = firmware?.let { item ->
            withContext(Dispatchers.IO) { runCatching { library.file(item).readBytes() }.getOrNull() }
        }
        if (snapshot == null && archive == null && (firmware == null || bytes == null)) {
            appendLog("Nothing to keep: this phone doesn't have a copy of that firmware.")
            lastBackup = null
            return
        }
        archive?.firmware?.let { name ->
            val running = job
            if (running != null) job = running.copy(fromLabels = withDeviceFirmwareName(running.fromLabels, name))
        }
        lastBackup = withContext(Dispatchers.IO) {
            backups.save(current.text, snapshot, firmware, bytes, archive)
        }
        backupTick++
        val saved = lastBackup!!
        appendLog(
            buildString {
                append("Saved backup of ${saved.identity}")
                if (saved.presetCount > 0) append(" (${saved.presetCount} presets, ${saved.patternCount} patterns)")
                if (saved.archiveObjects > 0) append(" (${saved.archiveObjects} parts)")
                if (saved.firmwareName != null) append(" plus ${saved.firmwareName}")
                append(".")
            },
        )
    }

    private suspend fun dumpBaudGirl(current: FM1Identity): BaudGirlSnapshot {
        val found = held ?: updater.find()?.also { held = it } ?: throw FM1UpdateError.DeviceNotFound
        return withContext(Dispatchers.Default) {
            BaudGirlLibrarian().dump(found.connection, current) { done, total -> postBackupProgress(done, total) }
        }
    }

    private suspend fun dumpEditor(identityText: String): EditorArchive? {
        appendLog("Looking for a Felucca or SLOOP backup on $identityText.")
        val found = held ?: updater.find()?.also { held = it } ?: throw FM1UpdateError.DeviceNotFound
        val archive = withContext(Dispatchers.Default) {
            EditorLibrarian().capture(found.connection, identityText) { done, total -> postBackupProgress(done, total) }
        }
        if (archive == null) {
            appendLog("$identityText has no backup this app can take. Keeping a firmware copy if this phone has one.")
        } else {
            val samples = if (archive.objects.any { it.id >= 32 && it.bytes.isNotEmpty() }) ", including samples" else ""
            appendLog("Backing up ${archive.firmware} (${archive.kind.title} backup$samples).")
        }
        return archive
    }

    private fun postBackupProgress(done: Int, total: Int) {
        mainHandler.post {
            if (job?.phase is FlashPhase.BackingUp) {
                job = job?.copy(phase = FlashPhase.BackingUp(done, total))
            }
        }
    }

    private fun applyEvent(event: UpdateEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyEvent(event) }
            return
        }
        when (event) {
            is UpdateEvent.Connected -> appendLog("Connected: ${event.identity.text}")
            is UpdateEvent.Staging -> job = job?.copy(phase = FlashPhase.Staging(event.requests))
            UpdateEvent.WaitingForLoader -> {
                job = job?.copy(phase = FlashPhase.WaitingForLoader)
                appendLog("The FM-1 accepted it and is restarting into update mode.")
            }
            is UpdateEvent.LoaderFound -> appendLog("Loader: ${event.identity.text}")
            is UpdateEvent.Writing -> job = job?.copy(phase = FlashPhase.Writing(event.fraction))
            is UpdateEvent.Checking -> {
                if (job?.phase !is FlashPhase.Verifying) {
                    appendLog("Written. The FM-1 is reading it back to check it.")
                }
                job = job?.copy(phase = FlashPhase.Verifying(event.fraction))
            }
            UpdateEvent.Rebooting -> {
                job = job?.copy(phase = FlashPhase.Rebooting)
                appendLog("Rebooting into the new firmware.")
            }
            is UpdateEvent.Finished -> appendLog("Device reports ${event.identity.text}.")
        }
    }

    fun restoreFactoryVoices() {
        if (isWriting) return
        voiceRestore = VoiceRestore.Writing(0.0)
        viewModelScope.launch {
            releaseHeld()
            try {
                val official = library.official ?: throw FM1UpdateError.FactoryVoicesUnavailable
                val package_ = withContext(Dispatchers.IO) { library.loadPackage(official) }
                withContext(Dispatchers.Default) {
                    updater.restoreFactoryVoices(package_) { fraction ->
                        voiceRestore = VoiceRestore.Writing(fraction)
                    }
                }
                voiceRestore = VoiceRestore.Succeeded
            } catch (error: Exception) {
                voiceRestore = VoiceRestore.Failed(error.userMessage())
            } finally {
                scan(force = true)
            }
        }
    }

    fun dismissVoiceRestore() {
        if (!voiceRestore.isRunning) voiceRestore = VoiceRestore.Idle
    }

    fun restoreBackup(backup: DeviceBackup, presets: Boolean, patterns: Boolean) {
        if (isWriting) return
        if (backup.archiveFile != null) {
            restoreEditorBackup(backup)
            return
        }
        if (!presets && !patterns) return
        val identity = identity
        if (identity == null || !identity.supportsBaudGirlBackup) {
            backupRestore = BackupRestore.Failed("Restoring presets and patterns needs FM-1+VA running (FM-1_079 or later).")
            return
        }
        val restoreSeq = ++backupRestoreSeq
        backupRestore = BackupRestore.Writing("CONNECTING", 0.0)
        viewModelScope.launch {
            releaseHeld()
            try {
                val snapshot = withContext(Dispatchers.IO) { backups.loadPresets(backup) }
                    ?: throw FM1UpdateError.BackupFailed("the backup files are missing")
                val found = withContext(Dispatchers.Default) { updater.find() }
                    ?: throw FM1UpdateError.DeviceNotFound
                try {
                    if (!found.identity.supportsBaudGirlBackup) {
                        throw FM1UpdateError.BackupUnavailable(found.identity.text)
                    }
                    val librarian = BaudGirlLibrarian()
                    if (presets) {
                        withContext(Dispatchers.Default) {
                            librarian.restorePresets(found.connection, snapshot.presets) { done, total ->
                                postRestoreProgress(restoreSeq, "PRESETS", done, total)
                            }
                        }
                    }
                    if (patterns) {
                        val bank = snapshot.patterns
                        withContext(Dispatchers.Default) {
                            librarian.restorePatterns(found.connection, bank) { done, total ->
                                postRestoreProgress(restoreSeq, "PATTERNS", done, total)
                            }
                        }
                    }
                    if (restoreSeq == backupRestoreSeq) backupRestore = BackupRestore.Succeeded
                } finally {
                    found.connection.close()
                }
            } catch (error: Exception) {
                if (restoreSeq == backupRestoreSeq) backupRestore = BackupRestore.Failed(error.userMessage())
            } finally {
                scan(force = true)
            }
        }
    }

    private fun restoreEditorBackup(backup: DeviceBackup) {
        val restoreSeq = ++backupRestoreSeq
        backupRestore = BackupRestore.Writing("BACKUP", 0.0)
        viewModelScope.launch {
            releaseHeld()
            try {
                val archive = withContext(Dispatchers.IO) { backups.loadArchive(backup) }
                    ?: throw FM1UpdateError.BackupFailed("the backup file is missing")
                val found = withContext(Dispatchers.Default) { updater.find() }
                    ?: throw FM1UpdateError.DeviceNotFound
                try {
                    withContext(Dispatchers.Default) {
                        EditorLibrarian().restore(found.connection, archive) { done, total ->
                            postRestoreProgress(restoreSeq, archive.kind.title, done, total)
                        }
                    }
                    if (restoreSeq == backupRestoreSeq) backupRestore = BackupRestore.Succeeded
                } finally {
                    found.connection.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (restoreSeq == backupRestoreSeq) backupRestore = BackupRestore.Failed(error.userMessage())
            } finally {
                scan(force = true)
            }
        }
    }

    private fun postRestoreProgress(seq: Int, label: String, done: Int, total: Int) {
        mainHandler.post {
            if (seq != backupRestoreSeq || backupRestore !is BackupRestore.Writing) return@post
            backupRestore = BackupRestore.Writing(label, if (total == 0) 0.0 else done.toDouble() / total)
        }
    }

    fun dismissBackupRestore() {
        if (!backupRestore.isRunning) backupRestore = BackupRestore.Idle
    }

    fun removeBackup(backup: DeviceBackup) {
        backups.remove(backup)
        if (lastBackup?.id == backup.id) lastBackup = null
        backupTick++
    }

    suspend fun save(entry: CatalogueEntry): Result<StoredFirmware> = runCatching {
        downloadingIds = downloadingIds + entry.id
        try {
            val item = library.download(entry)
            libraryTick++
            item
        } finally {
            downloadingIds = downloadingIds - entry.id
        }
    }

    fun importFirmware(bytes: ByteArray, name: String): Result<StoredFirmware> = runCatching {
        val item = library.importBytes(bytes, name)
        libraryTick++
        item
    }

    fun remove(item: StoredFirmware) {
        library.remove(item)
        libraryTick++
    }

    fun clearLibrary() {
        library.clear()
        libraryTick++
    }

    fun refreshCatalogue() {
        viewModelScope.launch {
            catalogue.refresh { catalogueTick++ }
            catalogueTick++
        }
    }

    fun setFirmwareSort(sort: FirmwareSort) {
        settings.firmwareSort = sort
        listTick++
    }

    fun setCuratedOnly(on: Boolean) {
        settings.curatedOnly = on
        listTick++
    }

    fun toggleCurated(id: String) {
        settings.curation = if (id in settings.curation) {
            settings.curation.filterNot { it == id }
        } else {
            settings.curation + id
        }
        listTick++
    }

    fun moveCuration(id: String, earlier: Boolean) {
        val current = settings.curation.toMutableList()
        val index = current.indexOf(id)
        val target = index + if (earlier) -1 else 1
        if (index < 0 || target !in current.indices) return
        current.add(target, current.removeAt(index))
        settings.curation = current
        listTick++
    }

    fun playTestNotes() {
        if (isWriting || isCheckingMidi) return
        isCheckingMidi = true
        midiCheck = MidiCheck.Idle
        viewModelScope.launch {
            try {
                val found = held ?: updater.find()?.also { held = it }
                    ?: throw FM1UpdateError.DeviceNotFound
                identity = found.identity
                val result = withContext(Dispatchers.Default) {
                    ConnectionTone.play(found.connection) { updater.handshake(it) }
                }
                if (result.identity != null) identity = result.identity
                midiCheck = MidiCheck.Played(
                    stillAnswering = result.stillAnswering,
                    identity = (result.identity ?: found.identity).text,
                    simulated = simulated != null,
                    loader = (result.identity ?: found.identity).isLoader,
                    notesSent = result.notesSent,
                )
            } catch (error: Exception) {
                midiCheck = MidiCheck.Failed(error.userMessage())
            } finally {
                isCheckingMidi = false
            }
        }
    }

    fun sendLiveNote(note: Int, on: Boolean) {
        val connection = held?.connection ?: return
        if (connection.isLost || isWriting) return
        for (channel in MidiNotes.TEST_CHANNELS) {
            val bytes = if (on) MidiNotes.noteOn(channel, note) else MidiNotes.noteOff(channel, note)
            runCatching { connection.send(bytes) }
        }
    }

    fun openEmulator(entry: CatalogueEntry) {
        emulatorJob?.cancel()
        playingFirmware = entry
        emulatorPackage = EmulatorPackage.Preparing
        val keep = settings.autoSaveTried && entry.isDownloadable
        emulatorJob = viewModelScope.launch {
            if (keep) downloadingIds = downloadingIds + entry.id
            val prepared = try {
                val staged = library.stageForPlay(entry, keep)
                if (staged.savedNow) libraryTick++
                EmulatorPackage.Ready(staged.file, staged.sha256)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EmulatorPackage.Failed(error.userMessage())
            } finally {
                if (keep) downloadingIds = downloadingIds - entry.id
            }
            if (playingFirmware?.id == entry.id) emulatorPackage = prepared
        }
    }

    /** Play a package already on the SAVED screen, including imports that are not in the list. */
    fun trySaved(item: StoredFirmware) {
        val listed = item.catalogueID?.let { catalogue.entry(it) }
        val entry = listed?.takeIf {
            it.sha256 == null || it.sha256.equals(item.sha256, ignoreCase = true)
        } ?: run {
            CatalogueEntry(
                id = listed?.id ?: "saved-${item.sha256.take(16)}",
                name = item.name,
                author = item.author ?: listed?.author,
                version = item.version,
                product = item.product,
                size = item.size,
                sha256 = item.sha256,
                packageURL = item.packageURL ?: listed?.packageURL,
                links = listed?.links ?: emptyList(),
                photoURL = listed?.photoURL,
                photoAlt = listed?.photoAlt,
                what = listed?.what,
                beta = listed?.beta ?: false,
                isOfficial = item.source == "official" || listed?.isOfficial == true,
            )
        }
        openEmulator(entry)
    }

    fun closeEmulator() {
        emulatorJob?.cancel()
        emulatorJob = null
        playingFirmware = null
        emulatorPackage = EmulatorPackage.Preparing
    }

    fun probablyRunning(identity: FM1Identity): String {
        val matches = catalogue.entriesMatching(identity.text)
        val last = history.lastInstall(identity.text)
        val official = library.official?.takeIf { it.product == identity.text }
        return buildString {
            if (last != null) append("Last flashed from this phone: ${last.name} ${last.version}. ")
            if (official != null) append("Matches official firmware ${official.name}. ")
            if (matches.isNotEmpty()) {
                append(matches.joinToString { "${it.name} ${it.version ?: ""}" }.trim())
                append(". ")
            }
            if (OpenFirmware.isOpen(identity.text)) append("M-VAVE's FM1 Open, or a build of it. ")
            if (isEmpty()) append("This app doesn't know a firmware that reports that.")
        }
    }

    /**
     * Silent boot used only to learn whether this package starts. No save file: a probe must not
     * restore or overwrite what a real TRY kept, and a bad save must not condemn the package.
     */
    private suspend fun emulateForRisk(bytes: ByteArray): Boolean {
        val engine = Fm1Engine(bytes, null, EmulatorClock.DEFAULT, audible = false)
        var started = false
        try {
            withTimeout(PROBE_WALL_MS) {
                while (true) {
                    when (val status = engine.status) {
                        is Fm1Engine.Status.Stopped -> return@withTimeout
                        is Fm1Engine.Status.Running -> if (status.live) {
                            started = true
                            return@withTimeout
                        }
                        Fm1Engine.Status.Booting -> Unit
                    }
                    delay(50)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            started = false
        } finally {
            withContext(NonCancellable) { engine.close() }
        }
        return started
    }

    private fun appendLog(line: String) {
        log = log + line
    }

    private fun isResumable(error: Throwable): Boolean = when (error) {
        is FM1UpdateError.Disconnected -> error.phase == FM1UpdateError.Phase.WRITING
        is FM1UpdateError.Stalled -> error.phase == FM1UpdateError.Phase.WRITING
        is FM1UpdateError.LoaderNotFound -> true
        else -> false
    }

    override fun onCleared() {
        probeJob?.cancel()
        watch?.close()
        releaseHeld()
        simulated?.shutdown()
    }

    private companion object {
        const val TRY_VERDICTS = "tryVerdicts"
        /** Six emulated seconds, with room for a phone running the probe at a few times realtime. */
        const val PROBE_WALL_MS = 30_000L
    }
}

fun switchingKind(kind: InstallKind?): Boolean = kind == InstallKind.SWITCHING_FIRMWARE
fun sameIdentityKind(kind: InstallKind?): Boolean = kind == InstallKind.SAME_IDENTITY
