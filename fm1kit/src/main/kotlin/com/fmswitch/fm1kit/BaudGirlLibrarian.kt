package com.fmswitch.fm1kit

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class BaudGirlLibrarian(
    private val timeoutMs: Long = 1_500,
    private val attempts: Int = 3,
    private val writePaceMs: Long = 3_000,
) {
    suspend fun dump(
        connection: MidiConnection,
        identity: FM1Identity,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): BaudGirlSnapshot {
        if (!identity.supportsBaudGirlBackup) {
            throw FM1UpdateError.BackupUnavailable(identity.text)
        }
        val reads = BaudGirlSysex.memoryReadsForBank()
        val total = BaudGirlSysex.PRESET_COUNT + reads.size
        val presets = ArrayList<BaudGirlPreset>(BaudGirlSysex.PRESET_COUNT)
        for (slot in 0 until BaudGirlSysex.PRESET_COUNT) {
            coroutineContext.ensureActive()
            progress(slot, total)
            presets += readPreset(connection, slot)
        }
        val replies = ArrayList<ByteArray>(reads.size)
        for ((index, read) in reads.withIndex()) {
            coroutineContext.ensureActive()
            progress(BaudGirlSysex.PRESET_COUNT + index, total)
            replies += readMemory(connection, read.first, read.second)
        }
        progress(total, total)
        return BaudGirlSnapshot(identity.text, presets, bankFromMemory(replies))
    }

    suspend fun restorePresets(
        connection: MidiConnection,
        presets: List<BaudGirlPreset>,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        for ((index, preset) in presets.withIndex()) {
            coroutineContext.ensureActive()
            progress(index, presets.size)
            val started = System.nanoTime()
            connection.send(BaudGirlSysex.encodePresetWrite(preset.slot, preset.voice, preset.record))
            val back = readPreset(connection, preset.slot)
            if (!back.voice.contentEquals(preset.voice) || !back.record.contentEquals(preset.record)) {
                throw FM1UpdateError.BackupRestoreMismatch("preset ${preset.slot + 1}")
            }
            val wait = writePaceMs - (System.nanoTime() - started) / 1_000_000L
            if (index < presets.lastIndex && wait > 0) delay(wait)
        }
        progress(presets.size, presets.size)
    }

    suspend fun restorePatterns(
        connection: MidiConnection,
        bank: BaudGirlPatternBank,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val messages = ArrayList<ByteArray>()
        for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
            val steps = bank.stepSlice(pattern)
            val set = bank.settingsSlice(pattern)
            for (part in 0 until BaudGirlSysex.STEP_PARTS) {
                val last = pattern == 15 && part == 15 && !bank.hasLocks
                messages += BaudGirlSysex.encodeStepPart(
                    set,
                    steps.copyOfRange(part * 128, part * 128 + 128),
                    pattern,
                    part,
                    save = last,
                )
            }
        }
        if (bank.hasLocks) {
            for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
                val locks = bank.locksSlice(pattern)
                for (eighth in 0 until BaudGirlSysex.LOCK_EIGHTHS) {
                    messages += BaudGirlSysex.encodeLockEighth(
                        locks.copyOfRange(eighth * 64, eighth * 64 + 64),
                        pattern,
                        eighth,
                        save = pattern == 15 && eighth == 7,
                    )
                }
            }
        }
        for ((index, message) in messages.withIndex()) {
            coroutineContext.ensureActive()
            progress(index, messages.size)
            connection.drain()
            connection.send(message)
            val reply = waitReply(connection)
            if (reply.status == 3) {
                delay(400)
                connection.drain()
                connection.send(message)
                val retry = waitReply(connection)
                if (retry.status != 0) {
                    throw FM1UpdateError.BackupRestoreMismatch("pattern write status ${retry.status}")
                }
            } else if (reply.status != 0) {
                throw FM1UpdateError.BackupRestoreMismatch("pattern write status ${reply.status}")
            }
        }
        progress(messages.size, messages.size)
    }

    private suspend fun readPreset(connection: MidiConnection, slot: Int): BaudGirlPreset {
        val reply = request(connection, BaudGirlSysex.encodePresetRead(slot)) { decoded ->
            decoded.kind == BaudGirlSysex.KIND_PRESET && decoded.argument == slot
        }
        if (reply.status != 0) throw FM1UpdateError.BackupFailed("preset ${slot + 1} was refused (status ${reply.status})")
        if (reply.data.size != BaudGirlSysex.PRESET_REPLY_DATA) {
            throw FM1UpdateError.BackupFailed("preset ${slot + 1} came back the wrong size")
        }
        return BaudGirlPreset(
            slot = slot,
            voice = reply.data.copyOfRange(0, Dx7Voice.PACKED_LENGTH),
            record = reply.data.copyOfRange(Dx7Voice.PACKED_LENGTH, reply.data.size),
        )
    }

    private suspend fun readMemory(connection: MidiConnection, address: Long, length: Int): ByteArray {
        val reply = request(connection, BaudGirlSysex.encodeMemoryRead(address, length)) { decoded ->
            decoded.kind == BaudGirlSysex.KIND_MEMORY
        }
        if (reply.status != 0) throw FM1UpdateError.BackupFailed("a memory read was refused (status ${reply.status})")
        if (reply.data.size != length) throw FM1UpdateError.BackupFailed("a memory read came back the wrong size")
        return reply.data
    }

    private suspend fun request(
        connection: MidiConnection,
        message: ByteArray,
        accept: (BaudGirlSysex.Reply) -> Boolean,
    ): BaudGirlSysex.Reply {
        repeat(attempts) { attempt ->
            coroutineContext.ensureActive()
            if (connection.isLost) throw FM1UpdateError.BackupFailed("the FM-1 disconnected during the backup")
            connection.drain()
            try {
                connection.send(message)
            } catch (_: Exception) {
                throw FM1UpdateError.BackupFailed("the FM-1 disconnected during the backup")
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                val sysex = connection.receive(remaining) ?: continue
                val decoded = runCatching { BaudGirlSysex.decodeReply(sysex) }.getOrNull() ?: continue
                if (accept(decoded)) {
                    if (decoded.status == 2 && attempt < attempts - 1) break
                    return decoded
                }
            }
        }
        throw FM1UpdateError.BackupFailed("the FM-1 didn't answer a librarian request")
    }

    private suspend fun waitReply(connection: MidiConnection): BaudGirlSysex.Reply {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val sysex = connection.receive(remaining) ?: continue
            val decoded = runCatching { BaudGirlSysex.decodeReply(sysex) }.getOrNull() ?: continue
            return decoded
        }
        throw FM1UpdateError.BackupFailed("the FM-1 didn't answer a pattern write")
    }

    companion object {
        fun bankFromMemory(replies: List<ByteArray>): BaudGirlPatternBank {
            val reads = BaudGirlSysex.memoryReadsForBank()
            require(replies.size == reads.size) { "Incomplete pattern dump." }
            val bank = BaudGirlPatternBank()
            val settings = replies[0]
            require(settings.size == BaudGirlSysex.SETTINGS_BLOCK)
            val mark = replies[1]
            bank.hasLocks = mark.size >= 4 && String(mark, 0, 4, Charsets.US_ASCII) == "FMLK"
            var index = 2
            for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
                val dest = pattern * BaudGirlSysex.PATTERN_STEP_BYTES
                repeat(8) { part ->
                    System.arraycopy(replies[index], 0, bank.steps, dest + part * 256, 256)
                    index += 1
                }
                BaudGirlSysex.SETTINGS_OFFSETS.forEachIndexed { setting, offset ->
                    bank.settings[pattern * BaudGirlSysex.SETTINGS_BYTES + setting] = settings[offset + pattern]
                }
                val lock0 = replies[index]
                val lock1 = replies[index + 1]
                index += 2
                if (bank.hasLocks) {
                    System.arraycopy(lock0, 0, bank.locks, pattern * BaudGirlSysex.LOCK_BYTES, 256)
                    System.arraycopy(lock1, 0, bank.locks, pattern * BaudGirlSysex.LOCK_BYTES + 256, 256)
                }
            }
            return bank
        }
    }
}
