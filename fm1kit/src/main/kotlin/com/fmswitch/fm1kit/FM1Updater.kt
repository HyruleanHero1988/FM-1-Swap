package com.fmswitch.fm1kit

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class FM1Updater(
    val transport: MidiTransport,
    val timing: UpdaterTiming = UpdaterTiming.Standard,
) {
    suspend fun find(filter: (FM1Identity) -> Boolean = { true }): FoundDevice? {
        for (port in transport.ports()) {
            if (!isProbeable(port)) continue
            val connection = try {
                transport.open(port)
            } catch (_: Exception) {
                continue
            }
            val identity = handshake(connection)
            if (identity != null && filter(identity)) return FoundDevice(connection, identity)
            connection.close()
        }
        return null
    }

    suspend fun findAll(filter: (FM1Identity) -> Boolean = { true }): List<FoundDevice> {
        val found = ArrayList<FoundDevice>()
        for (port in transport.ports()) {
            if (!isProbeable(port)) continue
            val connection = try {
                transport.open(port)
            } catch (_: Exception) {
                continue
            }
            val identity = handshake(connection)
            if (identity != null && filter(identity)) {
                found.add(FoundDevice(connection, identity))
            } else {
                connection.close()
            }
        }
        return found
    }

    private suspend fun findOnly(filter: (FM1Identity) -> Boolean): FoundDevice? {
        val found = findAll(filter)
        if (found.size > 1) {
            found.forEach { it.connection.close() }
            throw FM1UpdateError.MultipleDevices(found.size)
        }
        return found.firstOrNull()
    }

    suspend fun identify(): FM1Identity? {
        val device = find() ?: return null
        device.connection.close()
        return device.identity
    }

    suspend fun check(package_: FwscPackage, buildID: FM1BuildID? = null): InstallCheck {
        val device = findOnly { !it.isLoader } ?: throw FM1UpdateError.DeviceNotFound
        return try {
            check(package_, device, buildID ?: knownBuildID(package_))
        } finally {
            device.connection.close()
        }
    }

    private suspend fun check(package_: FwscPackage, device: FoundDevice, buildID: FM1BuildID?): InstallCheck {
        val capability = capabilityOf(device)
        if (device.identity.hardwareModel != package_.identity.model &&
            capability?.family != FM1Capability.FM1_FAMILY
        ) {
            throw FM1UpdateError.ModelMismatch(device.identity.text, package_.product)
        }
        return InstallCheck.of(device.identity, capability, package_, buildID)
    }

    suspend fun install(
        package_: FwscPackage,
        options: InstallOptions = InstallOptions(),
        events: (UpdateEvent) -> Unit = {},
    ): FM1Identity {
        val device = findOnly { !it.isLoader } ?: throw FM1UpdateError.DeviceNotFound
        val buildID = options.buildID ?: knownBuildID(package_)
        val staging: ServeOutcome
        try {
            val check = check(package_, device, buildID)
            when (check.kind) {
                InstallKind.REINSTALL -> throw FM1UpdateError.AlreadyInstalled(device.identity.text)
                InstallKind.SWITCHING_FIRMWARE ->
                    if (!options.confirmsSwitchingFirmware) {
                        throw FM1UpdateError.SwitchNotConfirmed(device.identity.text, package_.product)
                    }
                InstallKind.SAME_IDENTITY ->
                    if (!options.confirmsSameIdentity) {
                        throw FM1UpdateError.SameIdentityNotConfirmed(device.identity.text)
                    }
                InstallKind.UPDATE -> Unit
            }
            events(UpdateEvent.Connected(device.identity))
            if (check.capability != null) {
                arm(device.connection, check.capability, package_, buildID)
            }
            device.connection.send(FM1Message.UPGRADE_COMMAND)
            pause(timing.afterUpgradeCommandMs)
            staging = serve(
                device.connection,
                package_.logicalImage,
                FM1UpdateError.Phase.STAGING,
                timing.stagingIdleLimitMs,
            ) { _, requests -> events(UpdateEvent.Staging(requests)) }
        } finally {
            device.connection.close()
        }
        if (!staging.finished) {
            throw if (staging.lost) {
                FM1UpdateError.Disconnected(FM1UpdateError.Phase.STAGING, staging.requests)
            } else {
                FM1UpdateError.Stalled(FM1UpdateError.Phase.STAGING, staging.requests)
            }
        }

        events(UpdateEvent.WaitingForLoader)
        delay(timing.loaderBootMs)
        val model = package_.identity.model
        val armedPrefix = (buildID ?: FM1BuildID.knownHex(package_.sha256)).hex.take(16)
        val loader = waitFor(timing.loaderSearchMs) { identity ->
            identity.isLoaderFor(model) && (identity.armedBuildPrefix ?: armedPrefix) == armedPrefix
        } ?: throw FM1UpdateError.LoaderNotFound
        return write(package_, loader, buildID, events)
    }

    suspend fun resume(package_: FwscPackage, events: (UpdateEvent) -> Unit = {}): FM1Identity {
        val model = package_.identity.model
        val loader = findOnly { it.isLoaderFor(model) } ?: throw FM1UpdateError.LoaderNotFound
        return write(package_, loader, knownBuildID(package_), events)
    }

    private suspend fun write(
        package_: FwscPackage,
        loader: FoundDevice,
        expecting: FM1BuildID?,
        events: (UpdateEvent) -> Unit,
    ): FM1Identity {
        val writing: ServeOutcome
        try {
            events(UpdateEvent.LoaderFound(loader.identity))
            loader.connection.send(FM1Message.UPGRADE_COMMAND)
            pause(timing.afterUpgradeCommandMs)
            val progress = WriteProgress(package_.container.applicationRange)
            writing = serve(
                loader.connection,
                package_.logicalImage,
                FM1UpdateError.Phase.WRITING,
                timing.writeIdleLimitMs,
            ) { request, _ -> events(progress.record(request)) }
        } finally {
            loader.connection.close()
        }
        if (!writing.finished) {
            throw if (writing.lost) {
                FM1UpdateError.Disconnected(FM1UpdateError.Phase.WRITING, writing.requests)
            } else {
                FM1UpdateError.Stalled(FM1UpdateError.Phase.WRITING, writing.requests)
            }
        }

        events(UpdateEvent.Rebooting)
        delay(timing.firmwareBootMs)
        val returned = waitFor(timing.returnSearchMs) { !it.isLoader } ?: throw FM1UpdateError.DeviceDidNotReturn
        try {
            if (returned.identity != package_.identity) {
                throw FM1UpdateError.IdentityMismatch(package_.product, returned.identity.text)
            }
            if (expecting != null) {
                val capability = runCatching { capabilityOf(returned) }.getOrNull()
                if (capability != null && capability.buildID != expecting) {
                    throw FM1UpdateError.BuildMismatch(expecting, capability.buildID)
                }
            }
            events(UpdateEvent.Finished(returned.identity))
            return returned.identity
        } finally {
            returned.connection.close()
        }
    }

    private data class ServeOutcome(
        var requests: Int = 0,
        var finished: Boolean = false,
        var lost: Boolean = false,
    )

    private suspend fun serve(
        connection: MidiConnection,
        image: ByteArray,
        phase: FM1UpdateError.Phase,
        idleLimitMs: Long,
        progress: (FM1ReadRequest, Int) -> Unit,
    ): ServeOutcome {
        val finishAddress = if (phase == FM1UpdateError.Phase.STAGING) {
            FM1Message.STAGE_FINISH_ADDRESS
        } else {
            FM1Message.WRITE_FINISH_ADDRESS
        }
        val outcome = ServeOutcome()
        var lastActivity = System.nanoTime()
        while (true) {
            coroutineContext.ensureActive()
            val message = connection.receive(1_000)
            if (message == null) {
                if (connection.isLost) {
                    outcome.lost = true
                    return outcome
                }
                if (System.nanoTime() - lastActivity > idleLimitMs * 1_000_000L) return outcome
                continue
            }
            val request = FM1ReadRequest.parse(message) ?: continue
            lastActivity = System.nanoTime()
            if (request.isFinish) {
                if (request.address != finishAddress || request.length != FM1Message.FINISH_PAYLOAD.size) {
                    throw FM1UpdateError.UnexpectedFinish(phase)
                }
                runCatching { connection.send(request.finishResponse()) }
                settle(connection, request)
                outcome.finished = true
                return outcome
            }
            val start = request.address.toInt()
            if (request.length > FM1Message.MAXIMUM_READ_LENGTH || start + request.length > image.size) {
                throw FM1UpdateError.BadRequest(request.address, request.length)
            }
            pause(timing.responseDelayMs)
            try {
                connection.send(request.response(image.copyOfRange(start, start + request.length)))
            } catch (_: Exception) {
                outcome.lost = true
                return outcome
            }
            outcome.requests += 1
            progress(request, outcome.requests)
        }
    }

    private suspend fun settle(connection: MidiConnection, finish: FM1ReadRequest) {
        val deadline = System.nanoTime() + timing.finishSettleMs * 1_000_000L
        while (!connection.isLost && System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val message = connection.receive(remaining) ?: continue
            if (FM1ReadRequest.parse(message) == finish) {
                runCatching { connection.send(finish.finishResponse()) }
            }
        }
    }

    private suspend fun waitFor(limitMs: Long, filter: (FM1Identity) -> Boolean): FoundDevice? {
        val deadline = System.nanoTime() + limitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            findOnly(filter)?.let { return it }
            delay(timing.scanIntervalMs)
        }
        return null
    }

    suspend fun handshake(connection: MidiConnection): FM1Identity? {
        connection.drain()
        repeat(timing.handshakeAttempts) {
            try {
                connection.send(FM1Message.HANDSHAKE_QUERY)
            } catch (_: Exception) {
                return null
            }
            val deadline = System.nanoTime() + timing.handshakeWaitMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                val reply = connection.receive(remaining) ?: break
                FM1Identity.fromHandshakeReply(reply)?.let { return it }
            }
        }
        return null
    }

    private suspend fun query(connection: MidiConnection, command: Int, payload: ByteArray = ByteArray(0)): ByteArray? {
        connection.drain()
        try {
            connection.send(FM1Message.command(command, payload))
        } catch (_: Exception) {
            return null
        }
        val deadline = System.nanoTime() + timing.commandReplyWaitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val reply = connection.receive(remaining) ?: break
            val parsed = FM1Message.parseCommand(reply) ?: continue
            if (parsed.first == command) return parsed.second
        }
        return null
    }

    private suspend fun capabilityOf(device: FoundDevice): FM1Capability? {
        if (!device.identity.isOpenFirmware) return null
        val payload = query(device.connection, FM1Message.CAPABILITY_COMMAND)
            ?: throw FM1UpdateError.CapabilityUnavailable
        return FM1Capability.parse(payload) ?: throw FM1UpdateError.CapabilityUnavailable
    }

    private suspend fun arm(
        connection: MidiConnection,
        capability: FM1Capability,
        package_: FwscPackage,
        buildID: FM1BuildID?,
    ) {
        if (!capability.isReady) throw FM1UpdateError.DeviceBusy
        val payload = FM1Capability.armPayload(
            family = capability.family,
            imageLength = package_.logicalImage.size,
            buildID = buildID ?: FM1BuildID.knownHex(package_.sha256),
        )
        val reply = query(connection, FM1Message.ARM_COMMAND, payload) ?: throw FM1UpdateError.ArmRefused(null)
        if (!reply.contentEquals(FM1Capability.ARM_ACCEPTED)) {
            throw FM1UpdateError.ArmRefused(reply.firstOrNull()?.toUInt8() ?: -1)
        }
    }

    suspend fun restoreFactoryVoices(from: FwscPackage, progress: (Double) -> Unit = {}) {
        val voices = from.factoryVoices() ?: throw FM1UpdateError.FactoryVoicesUnavailable
        restoreVoices(voices, progress)
    }

    private suspend fun restoreVoices(voices: ByteArray, progress: (Double) -> Unit) {
        val device = findOnly { true } ?: throw FM1UpdateError.DeviceNotFound
        try {
            if (device.identity != FM1Identity.OFFICIAL_V15) {
                throw FM1UpdateError.VoiceRestoreNeedsV15(device.identity.text)
            }
            progress(0.0)
            val bankLength = FwscPackage.VOICE_BANK_LENGTH
            val writeLength = FM1Message.VOICE_WRITE_LENGTH
            var bank = 0
            while (bank < voices.size) {
                acknowledge(device.connection, FM1Message.VOICE_ERASE_COMMAND, voiceAddress(bank), bank)
                var offset = bank
                while (offset < bank + bankLength) {
                    val payload = voiceAddress(offset) +
                        littleEndian(writeLength.toLong(), 3) +
                        voices.copyOfRange(offset, offset + writeLength)
                    acknowledge(device.connection, FM1Message.VOICE_WRITE_COMMAND, payload, offset)
                    progress((offset + writeLength).toDouble() / voices.size)
                    offset += writeLength
                }
                bank += bankLength
            }
            if (handshake(device.connection) != FM1Identity.OFFICIAL_V15) {
                throw FM1UpdateError.VoiceRestoreNeedsV15("an unexpected identity after the restore")
            }
        } finally {
            device.connection.close()
        }
    }

    private suspend fun acknowledge(connection: MidiConnection, command: Int, payload: ByteArray, bytesWritten: Int) {
        coroutineContext.ensureActive()
        connection.drain()
        try {
            connection.send(FM1Message.command(command, payload))
        } catch (_: Exception) {
            throw FM1UpdateError.VoiceRestoreStalled(bytesWritten)
        }
        val deadline = System.nanoTime() + timing.voiceReplyWaitMs * 1_000_000L
        while (System.nanoTime() < deadline && !connection.isLost) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val reply = connection.receive(remaining) ?: continue
            val parsed = FM1Message.parseCommand(reply) ?: continue
            if (parsed.first != FM1Message.ACKNOWLEDGEMENT_COMMAND) continue
            if (!parsed.second.contentEquals(byteArrayOf(0))) {
                throw FM1UpdateError.VoiceRestoreRefused(
                    if (parsed.second.size == 1) parsed.second[0].toUInt8() else -1,
                    bytesWritten,
                )
            }
            return
        }
        throw FM1UpdateError.VoiceRestoreStalled(bytesWritten)
    }

    private suspend fun pause(ms: Long) {
        if (ms > 0) delay(ms)
    }

    companion object {
        val PROBEABLE_FRAGMENTS = listOf(
            "fm-1", "fm1", "felucca", "ota", "composite", "usb-midi", "simulated",
            "sloop", "clipper", "sinco",
        )

        fun isProbeable(port: MidiPortDescriptor): Boolean {
            val name = port.name.lowercase()
            return PROBEABLE_FRAGMENTS.any { name.contains(it) }
        }

        fun knownBuildID(package_: FwscPackage): FM1BuildID? = OfficialFirmware.matching(package_)?.buildID

        fun voiceAddress(address: Int): ByteArray =
            byteArrayOf(FM1Message.VOICE_AREA.toUByte()) + littleEndian(address.toLong(), 4)
    }
}
