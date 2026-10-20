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
import com.fmswitch.catalogue.SafetyNotice
import com.fmswitch.fm1kit.BaudGirlLibrarian
import com.fmswitch.fm1kit.BaudGirlSnapshot
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.fm1kit.FM1UpdateError
import com.fmswitch.fm1kit.FM1Updater
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
import com.fmswitch.midi.AndroidMidiTransport
import com.fmswitch.theme.PhosphorTheme
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab(val title: String, val glyph: String) {
    DEVICE("DEVICE", "▣"),
    FIRMWARE("FIRMWARE", "≡"),
    LIBRARY("SAVED", "▼"),
    SETTINGS("SETUP", "◆"),
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
            is Staging -> "CHECKING PACKAGE"
            WaitingForLoader -> "WAITING FOR LOADER"
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

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
    val settings = AppSettings(prefs)
    val library = FirmwareLibrary(application)
    val catalogue = CatalogueStore(application)
    val history = FlashHistory(prefs)
    val backups = BackupStore(application)

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
    var catalogueTick by mutableIntStateOf(0)
    var libraryTick by mutableIntStateOf(0)
    var themeTick by mutableIntStateOf(0)
    var glow by mutableFloatStateOf(settings.glow)
    var downloadingIds by mutableStateOf(setOf<String>())
        private set

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
    private val mainHandler = Handler(Looper.getMainLooper())
    var simulated: SimulatedFM1? = null
        private set

    init {
        configureTransport()
        viewModelScope.launch { catalogue.refresh(); catalogueTick++ }
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

    fun flash(steps: List<StoredFirmware>, confirmsSwitchingFirmware: Boolean, confirmsSameIdentity: Boolean) {
        if (steps.isEmpty() || isWriting) return
        log = emptyList()
        lastBackup = null
        val autoBackup = identity?.supportsBaudGirlBackup == true && identity?.isLoader != true
        job = FlashJob(
            steps,
            confirmsSwitchingFirmware = confirmsSwitchingFirmware || autoBackup,
            confirmsSameIdentity = confirmsSameIdentity,
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
                appendLog("✕ ${error.userMessage()}")
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
            appendLog("▸ ${item.name} ${item.version} (${item.product})")
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
                appendLog("✕ ${error.userMessage()}")
                job = job?.copy(phase = FlashPhase.Failed(error.userMessage(), isResumable(error)))
                return
            }
        }
        job = job?.copy(phase = FlashPhase.Succeeded(identity?.text.orEmpty()))
        appendLog("✓ Finished.")
        lastBackup?.let { appendLog("Backup kept on this phone: ${it.summary}.") }
    }

    private suspend fun captureBackup() {
        val current = identity
        if (current == null || current.isLoader) {
            appendLog("No running firmware to back up (update loader).")
            return
        }
        job = job?.copy(phase = FlashPhase.BackingUp(0, 1))
        val snapshot = if (current.supportsBaudGirlBackup) {
            appendLog("Backing up 128 presets and 16 patterns from ${current.text}.")
            val found = held ?: updater.find()?.also { held = it } ?: throw FM1UpdateError.DeviceNotFound
            withContext(Dispatchers.Default) {
                BaudGirlLibrarian().dump(found.connection, current) { done, total ->
                    mainHandler.post {
                        if (job?.phase is FlashPhase.BackingUp) {
                            job = job?.copy(phase = FlashPhase.BackingUp(done, total))
                        }
                    }
                }
            }
        } else {
            appendLog("${current.text} has no FM-1+VA preset dump; keeping a firmware copy if this phone has one.")
            null
        }
        val firmware = library.items.firstOrNull { it.product == current.text }
        val bytes = firmware?.let { item ->
            withContext(Dispatchers.IO) { runCatching { library.file(item).readBytes() }.getOrNull() }
        }
        if (snapshot == null && (firmware == null || bytes == null)) {
            appendLog("Nothing to keep: no matching .fwsc is saved on this phone.")
            lastBackup = null
            return
        }
        lastBackup = withContext(Dispatchers.IO) {
            backups.save(current.text, snapshot, firmware, bytes)
        }
        backupTick++
        val saved = lastBackup!!
        appendLog(
            buildString {
                append("Saved backup of ${saved.identity}")
                if (saved.presetCount > 0) append(" (${saved.presetCount} presets, ${saved.patternCount} patterns)")
                if (saved.firmwareName != null) append(" plus ${saved.firmwareName}")
                append(".")
            },
        )
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
                appendLog("Package accepted; rebooting into the update loader.")
            }
            is UpdateEvent.LoaderFound -> appendLog("Loader: ${event.identity.text}")
            is UpdateEvent.Writing -> job = job?.copy(phase = FlashPhase.Writing(event.fraction))
            is UpdateEvent.Checking -> {
                if (job?.phase !is FlashPhase.Verifying) {
                    appendLog("Written; the loader is reading it back to check it.")
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
            catalogue.refresh()
            catalogueTick++
        }
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
            if (OpenFirmware.isOpen(identity.text)) append("M-VAVE's open-source firmware (FM1 Open), or a build of it. ")
            if (isEmpty()) append("No saved or catalogue firmware reports this ID.")
        }
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
        watch?.close()
        releaseHeld()
        simulated?.shutdown()
    }
}

fun switchingKind(kind: InstallKind?): Boolean = kind == InstallKind.SWITCHING_FIRMWARE
fun sameIdentityKind(kind: InstallKind?): Boolean = kind == InstallKind.SAME_IDENTITY
