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
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.fm1kit.FM1UpdateError
import com.fmswitch.fm1kit.FM1Updater
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

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
    val settings = AppSettings(prefs)
    val library = FirmwareLibrary(application)
    val catalogue = CatalogueStore(application)
    val history = FlashHistory(prefs)

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
    val isWriting: Boolean get() = job?.phase?.isRunning == true || voiceRestore.isRunning
    val needsSafetyNotice: Boolean get() = settings.acknowledgedNoticeVersion < SafetyNotice.VERSION

    private var transport: MidiTransport = UnavailableTransport
    private var updater = FM1Updater(UnavailableTransport)
    private var watch: AutoCloseable? = null
    private var scanJob: Job? = null
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
                delay(400)
                if (!isWriting) scan()
            }
        }
        viewModelScope.launch { scan() }
    }

    fun scan() {
        if (isScanning || isWriting) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            isScanning = true
            try {
                ports = transport.ports()
                identity = updater.identify()
            } finally {
                isScanning = false
            }
        }
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

    suspend fun check(item: StoredFirmware): InstallCheck = withContext(Dispatchers.Default) {
        updater.check(library.loadPackage(item))
    }

    fun flash(steps: List<StoredFirmware>, confirmsSwitchingFirmware: Boolean, confirmsSameIdentity: Boolean) {
        if (steps.isEmpty() || isWriting) return
        log = emptyList()
        job = FlashJob(steps, confirmsSwitchingFirmware, confirmsSameIdentity)
        viewModelScope.launch { runFlash(0) }
    }

    fun resumeFlash() {
        val current = job ?: return
        if (current.phase.isRunning) return
        job = current.copy(phase = FlashPhase.Preparing)
        viewModelScope.launch { runFlash(current.index) }
    }

    fun dismissFlash() {
        if (job?.phase?.isRunning == true) return
        job = null
        scan()
    }

    private suspend fun runFlash(startIndex: Int) {
        val current = job ?: return
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
                val isLoaderRunning = updater.identify()?.isLoader == true
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
            }
        }
    }

    fun dismissVoiceRestore() {
        if (!voiceRestore.isRunning) voiceRestore = VoiceRestore.Idle
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
        simulated?.shutdown()
    }
}

fun switchingKind(kind: InstallKind?): Boolean = kind == InstallKind.SWITCHING_FIRMWARE
fun sameIdentityKind(kind: InstallKind?): Boolean = kind == InstallKind.SAME_IDENTITY
