package com.fmswitch.fm1kit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

data class SimulatedOptions(
    val installedIdentity: FM1Identity = FM1Identity.of("FM-1", 900),
    val startsInRecoveryMode: Boolean = false,
    val refusesRunningFirmware: Boolean = true,
    val stagingRequestCount: Int = 74,
    val writeChunkLength: Int = 512,
    val writesBackwards: Boolean = false,
    val checksAfterWriting: Boolean = false,
    val requestIntervalMs: Long = 4,
    val rebootDurationMs: Long = 200,
    val responseTimeoutMs: Long = 5_000,
    val unplugDuringWriteAt: Double? = null,
    val installedBuildID: FM1BuildID = FM1BuildID(ByteArray(FM1BuildID.BYTE_COUNT)),
    val capabilityState: Int = 0,
    val armReply: Int = 0,
    val initialVoices: ByteArray? = null,
    val voiceReply: Int = 0,
    val stopsAnsweringVoicesAfter: Int? = null,
)

class SimulatedFM1(
    val options: SimulatedOptions = SimulatedOptions(),
) : MidiTransport {
    private enum class Mode { FIRMWARE, LOADER, OFFLINE }

    private val lock = Any()
    private var mode: Mode = if (options.startsInRecoveryMode) Mode.LOADER else Mode.FIRMWARE
    private var firmwareIdentity: FM1Identity = options.installedIdentity
    private var generation = 0
    private var expected: FwscPackage? = null
    private var session: Job? = null
    private var hasUnpluggedOnce = false
    private var buildID: FM1BuildID = options.installedBuildID
    private var armedBuildID: FM1BuildID? = null
    private var voices: ByteArray = options.initialVoices
        ?: ByteArray(FwscPackage.FACTORY_VOICES_LENGTH) { i -> ((i * 7) and 0xFF).toByte() }
    private var voiceSteps = 0
    private val baudGirl = SimulatedBaudGirl()
    private val eventLog = CopyOnWriteArrayList<String>()
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val events: List<String> get() = eventLog.toList()

    var currentVoices: ByteArray
        get() = synchronized(lock) { voices.copyOf() }
        private set(_) {}

    val currentBuildID: FM1BuildID get() = synchronized(lock) { buildID }

    val currentIdentity: FM1Identity?
        get() = synchronized(lock) {
            when (mode) {
                Mode.FIRMWARE -> firmwareIdentity
                Mode.LOADER -> armedBuildID?.let { FM1Identity.parse("ota-FM1C_${it.hex.take(16)}") } ?: LOADER_IDENTITY
                Mode.OFFLINE -> null
            }
        }

    fun prepare(forPackage: FwscPackage) {
        synchronized(lock) { expected = forPackage }
    }

    override suspend fun ports(): List<MidiPortDescriptor> {
        val (currentMode, gen) = synchronized(lock) { mode to generation }
        if (currentMode == Mode.OFFLINE) return emptyList()
        return listOf(MidiPortDescriptor("simulated-$gen", PORT_NAME))
    }

    override suspend fun open(port: MidiPortDescriptor): MidiConnection {
        val gen = synchronized(lock) { generation }
        if (port.id != "simulated-$gen" || synchronized(lock) { mode } == Mode.OFFLINE) {
            throw MidiTransportError.PortGone
        }
        return SimulatedConnection(this, port, gen)
    }

    override fun changes(onChange: () -> Unit): AutoCloseable {
        observers.add(onChange)
        return AutoCloseable { observers.remove(onChange) }
    }

    fun isCurrent(generation: Int): Boolean = synchronized(lock) { this.generation == generation && mode != Mode.OFFLINE }

    internal fun handle(sysex: ByteArray, from: SimulatedConnection) {
        when {
            sysex.contentEquals(FM1Message.HANDSHAKE_QUERY) -> {
                currentIdentity?.let { from.toHost.deliver(it.handshakeReply()) }
            }
            sysex.contentEquals(FM1Message.UPGRADE_COMMAND) -> {
                if (currentIdentity?.isOpenFirmware == true && synchronized(lock) { armedBuildID } == null) {
                    log("Open firmware ignores the upgrade command until it's armed.")
                    return
                }
                startSession(from)
            }
            else -> {
                val parsed = FM1Message.parseCommand(sysex)
                if (parsed != null &&
                    (parsed.first == FM1Message.CAPABILITY_COMMAND || parsed.first == FM1Message.ARM_COMMAND)
                ) {
                    handleOpenFirmwareCommand(parsed.first, parsed.second, from)
                } else if (parsed != null &&
                    (parsed.first == FM1Message.VOICE_ERASE_COMMAND || parsed.first == FM1Message.VOICE_WRITE_COMMAND)
                ) {
                    handleVoiceCommand(parsed.first, parsed.second, from)
                } else if (isBaudGirlLibrarian(sysex)) {
                    handleBaudGirl(sysex, from)
                } else {
                    from.fromHost.deliver(sysex)
                }
            }
        }
    }

    private fun startSession(connection: SimulatedConnection) {
        synchronized(lock) {
            if (session != null) return
            val expected = expected ?: return
            val isLoader = mode == Mode.LOADER
            session = scope.launch {
                if (isLoader) write(expected, connection) else stage(expected, connection)
                synchronized(lock) { session = null }
            }
        }
    }

    private suspend fun stage(package_: FwscPackage, connection: SimulatedConnection) {
        val image = package_.logicalImage
        val length = minOf(FM1Message.MAXIMUM_READ_LENGTH, image.size)
        for (index in 0 until options.stagingRequestCount) {
            val address = (index * length) % maxOf(image.size - length, 1)
            val request = FM1ReadRequest(address = address.toLong(), length = length)
            if (!exchange(request, image.copyOfRange(address, address + length), connection)) return
        }
        if (options.refusesRunningFirmware &&
            currentIdentity == package_.identity &&
            currentIdentity == FM1Identity.OFFICIAL_V15
        ) {
            log("${package_.product} is already running, so the package is refused.")
            return
        }
        val finish = FM1ReadRequest(address = FM1Message.STAGE_FINISH_ADDRESS, length = 8)
        if (!exchange(finish, FM1Message.FINISH_PAYLOAD, connection)) return
        log("Staged ${package_.product}; rebooting into the loader.")
        reboot(Mode.LOADER)
    }

    private suspend fun write(package_: FwscPackage, connection: SimulatedConnection) {
        val image = package_.logicalImage
        var addresses = (0 until image.size step options.writeChunkLength).toList()
        if (options.writesBackwards) addresses = addresses.reversed()
        val passes = if (options.checksAfterWriting) listOf(addresses, addresses) else listOf(addresses)
        for (pass in passes) {
            for ((index, address) in pass.withIndex()) {
                val unplugAt = options.unplugDuringWriteAt
                if (unplugAt != null &&
                    index.toDouble() / pass.size >= unplugAt &&
                    synchronized(lock) { !hasUnpluggedOnce }
                ) {
                    synchronized(lock) { hasUnpluggedOnce = true }
                    log("Cable pulled at ${(unplugAt * 100).toInt()}%; the loader stays in update mode.")
                    reboot(Mode.LOADER)
                    return
                }
                val length = minOf(options.writeChunkLength, image.size - address)
                val request = FM1ReadRequest(address = address.toLong(), length = length)
                if (!exchange(request, image.copyOfRange(address, address + length), connection)) return
            }
        }
        val finish = FM1ReadRequest(address = FM1Message.WRITE_FINISH_ADDRESS, length = 8)
        if (!exchange(finish, FM1Message.FINISH_PAYLOAD, connection)) return
        log("Wrote ${package_.product}; rebooting.")
        val packageBuildID = FM1Updater.knownBuildID(package_) ?: FM1BuildID.knownHex(package_.sha256)
        synchronized(lock) { buildID = armedBuildID ?: packageBuildID }
        reboot(Mode.FIRMWARE, package_.identity)
    }

    private suspend fun exchange(request: FM1ReadRequest, expected: ByteArray, connection: SimulatedConnection): Boolean {
        delay(options.requestIntervalMs)
        if (!isCurrent(connection.generation)) return false
        connection.toHost.deliver(request.encoded())
        while (true) {
            val reply = withContext(Dispatchers.IO) { connection.fromHost.next(options.responseTimeoutMs) } ?: break
            val parsed = FM1ReadRequest.parseResponse(reply) ?: continue
            if (parsed.first.address != request.address || !parsed.second.contentEquals(expected)) {
                log("Bad response at 0x${request.address.toString(16)}; the device stops.")
                return false
            }
            return true
        }
        log("No response at 0x${request.address.toString(16)}; the device stops.")
        return false
    }

    private suspend fun reboot(into: Mode, identity: FM1Identity? = null) {
        synchronized(lock) {
            mode = Mode.OFFLINE
            generation += 1
            if (into == Mode.FIRMWARE) armedBuildID = null
            if (identity != null) firmwareIdentity = identity
        }
        observers.forEach { it() }
        delay(options.rebootDurationMs)
        synchronized(lock) {
            mode = into
            generation += 1
        }
        observers.forEach { it() }
    }

    private fun handleOpenFirmwareCommand(command: Int, payload: ByteArray, from: SimulatedConnection) {
        if (currentIdentity?.isOpenFirmware != true) return
        if (command == FM1Message.CAPABILITY_COMMAND) {
            val capability = FM1Capability(state = options.capabilityState, buildID = currentBuildID)
            from.toHost.deliver(FM1Message.command(command, capability.payload()))
            return
        }
        val expectedLength = synchronized(lock) { expected?.logicalImage?.size }
        if (options.armReply != 0 ||
            payload.size != 6 + FM1BuildID.BYTE_COUNT ||
            payload[0].toUInt8() != 1 ||
            payload[1].toUInt8() != FM1Capability.FM1_FAMILY ||
            readLittleEndian(payload, 2, 4).toInt() != expectedLength
        ) {
            log("Arming refused.")
            from.toHost.deliver(FM1Message.command(command, byteArrayOf(maxOf(options.armReply, 1).toUByte())))
            return
        }
        synchronized(lock) { armedBuildID = FM1BuildID(payload.copyOfRange(6, payload.size)) }
        log("Armed for build ${FM1BuildID(payload.copyOfRange(6, payload.size)).hex.take(16)}.")
        from.toHost.deliver(FM1Message.command(command, FM1Capability.ARM_ACCEPTED))
    }

    private fun handleVoiceCommand(command: Int, payload: ByteArray, from: SimulatedConnection) {
        if (currentIdentity != FM1Identity.OFFICIAL_V15 || payload.size < 5 || payload[0].toUInt8() != FM1Message.VOICE_AREA) {
            return
        }
        val address = readLittleEndian(payload, 1, 4).toInt()
        val reply = synchronized(lock) {
            val limit = options.stopsAnsweringVoicesAfter
            if (limit != null && voiceSteps >= limit) return@synchronized null
            voiceSteps += 1
            if (options.voiceReply != 0) return@synchronized options.voiceReply
            if (command == FM1Message.VOICE_ERASE_COMMAND) {
                val end = minOf(address + FwscPackage.VOICE_BANK_LENGTH, voices.size)
                for (i in address until end) voices[i] = 0xFF.toByte()
                0
            } else {
                val length = if (payload.size >= 8) readLittleEndian(payload, 5, 3).toInt() else -1
                if (length != payload.size - 8 || address + length > voices.size) return@synchronized 1
                System.arraycopy(payload, 8, voices, address, length)
                0
            }
        }
        if (reply == null) {
            log("Stopped answering the voice restore.")
            return
        }
        from.toHost.deliver(FM1Message.command(FM1Message.ACKNOWLEDGEMENT_COMMAND, byteArrayOf(reply.toUByte())))
    }

    private fun isBaudGirlLibrarian(sysex: ByteArray): Boolean =
        sysex.size >= 6 &&
            sysex[0].toUInt8() == 0xF0 &&
            sysex[1].toUInt8() == 0x43 &&
            sysex[3].toUInt8() == BaudGirlSysex.SUB_ID &&
            sysex.last().toUInt8() == 0xF7

    private fun handleBaudGirl(sysex: ByteArray, from: SimulatedConnection) {
        val command = sysex[4].toUInt8()
        val reply = synchronized(lock) {
            when (command) {
                BaudGirlSysex.CMD_PRESET_READ -> {
                    val slot = sysex[5].toUInt8()
                    val preset = baudGirl.preset(slot)
                    BaudGirlSysex.encodeReply(
                        BaudGirlSysex.KIND_PRESET,
                        0,
                        slot,
                        preset.voice + preset.record,
                    )
                }
                BaudGirlSysex.CMD_PRESET_WRITE -> {
                    val decoded = BaudGirlSysex.decodePresetWrite(sysex)
                        ?: return@synchronized BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PRESET, 1, 0, ByteArray(0))
                    baudGirl.writePreset(BaudGirlPreset(decoded.first, decoded.second, decoded.third))
                    BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PRESET, 0, decoded.first, ByteArray(0))
                }
                BaudGirlSysex.CMD_MEMORY_READ -> {
                    val body = sysex.copyOfRange(4, sysex.size - 2)
                    var address = 0L
                    for (i in 0 until 5) address = address or ((body[1 + i].toUInt8().toLong()) shl (7 * i))
                    val length = body[6].toUInt8() or (body[7].toUInt8() shl 7)
                    BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_MEMORY, 0, 0, baudGirl.readMemory(address, length))
                }
                BaudGirlSysex.CMD_STEPS -> {
                    val decoded = BaudGirlSysex.decodeStepPart(sysex)
                        ?: return@synchronized BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PATTERN, 1, 0, ByteArray(0))
                    baudGirl.writeStepPart(decoded)
                    BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PATTERN, 0, 0, ByteArray(0))
                }
                BaudGirlSysex.CMD_LOCKS -> {
                    val decoded = BaudGirlSysex.decodeLockEighth(sysex)
                        ?: return@synchronized BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PATTERN, 1, 0, ByteArray(0))
                    baudGirl.writeLockEighth(decoded)
                    BaudGirlSysex.encodeReply(BaudGirlSysex.KIND_PATTERN, 0, 0, ByteArray(0))
                }
                else -> null
            }
        }
        if (reply != null) from.toHost.deliver(reply)
    }

    fun log(text: String) {
        eventLog.add(text)
    }

    fun shutdown() {
        synchronized(lock) { session?.cancel() }
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val PORT_NAME = "FM-1 Midi (simulated)"
        val LOADER_IDENTITY: FM1Identity = FM1Identity.of("ota-FM-1", 9)
    }
}

internal class SimulatedConnection(
    private val device: SimulatedFM1,
    override val port: MidiPortDescriptor,
    val generation: Int,
) : MidiConnection {
    val toHost = SysexInbox()
    val fromHost = SysexInbox()

    override val isLost: Boolean get() = !device.isCurrent(generation)

    override fun send(sysex: ByteArray) {
        if (!device.isCurrent(generation)) throw MidiTransportError.PortGone
        device.handle(sysex, this)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) { toHost.next(timeoutMs) }

    override fun drain() {
        toHost.drain()
    }

    override fun close() {
        toHost.close()
    }
}
