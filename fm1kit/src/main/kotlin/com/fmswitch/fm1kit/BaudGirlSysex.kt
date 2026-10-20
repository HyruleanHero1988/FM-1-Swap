package com.fmswitch.fm1kit

/**
 * FM-1+VA (Baud Girl) librarian SysEx: preset dump/restore and sequencer
 * pattern dump/restore. Wire format is Yamaha ID `F0 43 00 7D … F7`.
 *
 * Preset read `7D 10` exists from FM-1_079. Whole-step pattern messages `7D 22`
 * and lock messages `7D 21` are what Device Manager writes into a `.syx` backup.
 */
object BaudGirlSysex {
    const val SUB_ID = 0x7D
    const val PRESET_COUNT = 128
    const val PATTERN_COUNT = 16
    const val RECORD_LENGTH = 59
    const val PRESET_REPLY_DATA = Dx7Voice.PACKED_LENGTH + RECORD_LENGTH
    const val PRESET_WRITE_LENGTH = 231
    const val STEP_BYTES = 32
    const val STEPS_PER_PATTERN = 64
    const val PATTERN_STEP_BYTES = STEPS_PER_PATTERN * STEP_BYTES
    const val LOCK_BYTES = 512
    const val SETTINGS_BYTES = 5
    const val SETTINGS_BLOCK = 137
    const val STEP_PARTS = 16
    const val LOCK_EIGHTHS = 8
    const val STEP_MESSAGE_LENGTH = 162
    const val LOCK_MESSAGE_LENGTH = 74

    const val KIND_PRESET = 0x50
    const val KIND_MEMORY = 0x51
    const val KIND_PATTERN = 0x52
    const val CMD_PRESET_WRITE = 0x04
    const val CMD_PRESET_READ = 0x10
    const val CMD_MEMORY_READ = 0x11
    const val CMD_LOCKS = 0x21
    const val CMD_STEPS = 0x22

    const val SEQ_STEPS_RAM = 0x01C14CD0L
    const val SEQ_EXT_RAM = 0x01C79D30L
    const val GSET_RAM = 0x01C0E840L + 5816
    const val LOCK_MARK_RAM = 0x01C76CA0L
    const val LOCK_TABLE_RAM = LOCK_MARK_RAM + 0x60
    val SETTINGS_OFFSETS = intArrayOf(18, 34, 50, 98, 118)

    fun yamahaSum(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Byte {
        var sum = 0
        for (i in start until end) sum = (sum + (bytes[i].toUInt8() xor 0xFF)) and 0xFF
        return (sum and 0x7F).toUByte()
    }

    fun packHighBits(bytes: ByteArray): ByteArray {
        val groups = (bytes.size + 6) / 7
        val out = ArrayList<Byte>(groups * 8)
        var offset = 0
        while (offset < bytes.size) {
            val remaining = minOf(7, bytes.size - offset)
            var high = 0
            val lows = ByteArray(remaining)
            for (k in 0 until remaining) {
                val value = bytes[offset + k].toUInt8()
                if (value and 0x80 != 0) high = high or (1 shl k)
                lows[k] = (value and 0x7F).toUByte()
            }
            out.add(high.toUByte())
            out.addAll(lows.toList())
            offset += remaining
        }
        return out.toByteArray()
    }

    fun unpackHighBits(groups: ByteArray, count: Int): ByteArray {
        val out = ByteArray(count)
        for (i in 0 until count) {
            val group = (i / 7) * 8
            val bit = i % 7
            val low = groups[group + 1 + bit].toUInt8()
            val high = (groups[group].toUInt8() shr bit) and 1
            out[i] = (low or (high shl 7)).toUByte()
        }
        return out
    }

    fun frameYamaha(body: ByteArray): ByteArray =
        byteArrayOf(0xF0.toUByte(), 0x43, 0x00, SUB_ID.toUByte()) + body +
            byteArrayOf(yamahaSum(body), 0xF7.toUByte())

    fun encodePresetRead(slot: Int): ByteArray {
        require(slot in 0 until PRESET_COUNT)
        return frameYamaha(byteArrayOf(CMD_PRESET_READ.toUByte(), slot.toUByte()))
    }

    fun encodePresetWrite(slot: Int, packedVoice: ByteArray, record: ByteArray): ByteArray {
        require(slot in 0 until PRESET_COUNT)
        require(packedVoice.size == Dx7Voice.PACKED_LENGTH)
        require(record.size == RECORD_LENGTH)
        val payload = Dx7Voice.unpack(packedVoice) + packHighBits(record)
        val message = byteArrayOf(0xF0.toUByte(), 0x43, 0x00, SUB_ID.toUByte(), CMD_PRESET_WRITE.toUByte(), slot.toUByte()) +
            payload + byteArrayOf(yamahaSum(payload), 0xF7.toUByte())
        require(message.size == PRESET_WRITE_LENGTH)
        return message
    }

    fun decodePresetWrite(message: ByteArray): Triple<Int, ByteArray, ByteArray>? {
        if (message.size != PRESET_WRITE_LENGTH) return null
        if (message[0].toUInt8() != 0xF0 || message[1].toUInt8() != 0x43) return null
        if (message[3].toUInt8() != SUB_ID || message[4].toUInt8() != CMD_PRESET_WRITE) return null
        if (message.last().toUInt8() != 0xF7) return null
        val payload = message.copyOfRange(6, PRESET_WRITE_LENGTH - 2)
        if (yamahaSum(payload) != message[PRESET_WRITE_LENGTH - 2]) {
            error("Preset backup checksum is wrong.")
        }
        val voice = Dx7Voice.pack(payload.copyOfRange(0, Dx7Voice.EDIT_LENGTH))
        val record = unpackHighBits(payload.copyOfRange(Dx7Voice.EDIT_LENGTH, payload.size), RECORD_LENGTH)
        return Triple(message[5].toUInt8(), voice, record)
    }

    fun encodeMemoryRead(address: Long, length: Int): ByteArray {
        require(length in 1..256)
        val body = ByteArray(8)
        body[0] = CMD_MEMORY_READ.toUByte()
        for (i in 0 until 5) body[1 + i] = ((address ushr (7 * i)) and 0x7F).toInt().toUByte()
        body[6] = (length and 0x7F).toUByte()
        body[7] = ((length shr 7) and 0x7F).toUByte()
        return frameYamaha(body)
    }

    data class Reply(
        val kind: Int,
        val status: Int,
        val argument: Int,
        val data: ByteArray,
    ) {
        val kindName: String
            get() = when (kind) {
                KIND_PRESET -> "preset"
                KIND_MEMORY -> "memory"
                KIND_PATTERN -> "pattern"
                else -> "unknown"
            }
    }

    fun encodeReply(kind: Int, status: Int, argument: Int, data: ByteArray): ByteArray {
        val body = ArrayList<Byte>(10 + data.size)
        body.add(SUB_ID.toUByte())
        body.add(kind.toUByte())
        body.add(status.toUByte())
        body.addAll(littleEndian(argument.toLong(), 4).toList())
        body.addAll(littleEndian(data.size.toLong(), 2).toList())
        body.addAll(data.toList())
        var sum = 0
        for (b in body) sum = (sum + b.toUInt8()) and 0xFF
        body.add((sum.inv() and 0xFF).toUByte())
        return byteArrayOf(0xF0.toUByte()) + SevenBit.pack(body.toByteArray()) + byteArrayOf(0xF7.toUByte())
    }

    fun decodeReply(sysex: ByteArray): Reply? {
        if (sysex.size < 3 || sysex.first().toUInt8() != 0xF0 || sysex.last().toUInt8() != 0xF7) return null
        if (sysex[1].toUInt8() != SUB_ID) return null
        val packed = sysex.copyOfRange(1, sysex.size - 1)
        if (packed.any { it.toUInt8() >= 0x80 }) return null
        val body = SevenBit.unpack(packed)
        if (body.size < 10 || body[0].toUInt8() != SUB_ID) return null
        val length = body[7].toUInt8() or (body[8].toUInt8() shl 8)
        if (body.size < 10 + length) error("FM-1+VA reply is short.")
        var sum = 0
        for (i in 0 until 9 + length) sum = (sum + body[i].toUInt8()) and 0xFF
        if ((sum.inv() and 0xFF) != body[9 + length].toUInt8()) error("FM-1+VA reply checksum is wrong.")
        return Reply(
            kind = body[1].toUInt8(),
            status = body[2].toUInt8(),
            argument = readLittleEndian(body, 3, 4).toInt(),
            data = body.copyOfRange(9, 9 + length),
        )
    }

    fun encodeStepPart(settings: ByteArray, steps: ByteArray, pattern: Int, part: Int, save: Boolean): ByteArray {
        require(settings.size == SETTINGS_BYTES)
        require(steps.size == 4 * STEP_BYTES)
        val body = byteArrayOf(
            CMD_STEPS.toUByte(),
            (pattern and 15).toUByte(),
            (part and 15).toUByte(),
            (if (save) 1 else 0).toUByte(),
        ) + packHighBits(settings + steps)
        val message = byteArrayOf(0xF0.toUByte(), 0x43, 0x00, SUB_ID.toUByte()) + body +
            byteArrayOf(yamahaSum(body), 0xF7.toUByte())
        require(message.size == STEP_MESSAGE_LENGTH)
        return message
    }

    fun encodeLockEighth(locks: ByteArray, pattern: Int, eighth: Int, save: Boolean): ByteArray {
        require(locks.size == 64)
        val body = ArrayList<Byte>(4 + 64)
        body.add(CMD_LOCKS.toUByte())
        body.add((pattern and 15).toUByte())
        body.add((eighth and 7).toUByte())
        body.add((if (save) 1 else 0).toUByte())
        var i = 0
        while (i < 64) {
            val none = locks[i].toUInt8() == 0xFF
            body.add(if (none) 0x7F.toUByte() else locks[i])
            body.add(if (none) 0x7F.toUByte() else locks[i + 1])
            i += 2
        }
        val message = byteArrayOf(0xF0.toUByte(), 0x43, 0x00, SUB_ID.toUByte()) + body.toByteArray() +
            byteArrayOf(yamahaSum(body.toByteArray()), 0xF7.toUByte())
        require(message.size == LOCK_MESSAGE_LENGTH)
        return message
    }

    fun decodeStepPart(message: ByteArray): StepPart? {
        if (message.size != STEP_MESSAGE_LENGTH) return null
        if (message[0].toUInt8() != 0xF0 || message[1].toUInt8() != 0x43) return null
        if (message[3].toUInt8() != SUB_ID || message[4].toUInt8() != CMD_STEPS) return null
        if (message.last().toUInt8() != 0xF7) return null
        val body = message.copyOfRange(4, STEP_MESSAGE_LENGTH - 2)
        if (yamahaSum(body) != message[STEP_MESSAGE_LENGTH - 2]) error("Pattern backup checksum is wrong.")
        val data = unpackHighBits(message.copyOfRange(8, STEP_MESSAGE_LENGTH - 2), SETTINGS_BYTES + 4 * STEP_BYTES)
        return StepPart(
            pattern = message[5].toUInt8(),
            part = message[6].toUInt8(),
            save = message[7].toUInt8() and 1 != 0,
            settings = data.copyOfRange(0, SETTINGS_BYTES),
            steps = data.copyOfRange(SETTINGS_BYTES, data.size),
        )
    }

    fun decodeLockEighth(message: ByteArray): LockEighth? {
        if (message.size != LOCK_MESSAGE_LENGTH) return null
        if (message[0].toUInt8() != 0xF0 || message[1].toUInt8() != 0x43) return null
        if (message[3].toUInt8() != SUB_ID || message[4].toUInt8() != CMD_LOCKS) return null
        if (message.last().toUInt8() != 0xF7) return null
        val body = message.copyOfRange(4, LOCK_MESSAGE_LENGTH - 2)
        if (yamahaSum(body) != message[LOCK_MESSAGE_LENGTH - 2]) error("Pattern lock checksum is wrong.")
        val locks = ByteArray(64)
        var i = 0
        while (i < 64) {
            val none = message[8 + i].toUInt8() == 0x7F
            locks[i] = if (none) 0xFF.toUByte() else message[8 + i]
            locks[i + 1] = if (none) 0xFF.toUByte() else message[9 + i]
            i += 2
        }
        return LockEighth(
            pattern = message[5].toUInt8(),
            eighth = message[6].toUInt8(),
            save = message[7].toUInt8() and 1 != 0,
            locks = locks,
        )
    }

    data class StepPart(
        val pattern: Int,
        val part: Int,
        val save: Boolean,
        val settings: ByteArray,
        val steps: ByteArray,
    )

    data class LockEighth(
        val pattern: Int,
        val eighth: Int,
        val save: Boolean,
        val locks: ByteArray,
    )

    fun memoryReadsForBank(): List<Pair<Long, Int>> {
        val reads = ArrayList<Pair<Long, Int>>(2 + PATTERN_COUNT * 10)
        reads += GSET_RAM to SETTINGS_BLOCK
        reads += LOCK_MARK_RAM to 4
        for (pattern in 0 until PATTERN_COUNT) {
            val base = SEQ_STEPS_RAM + pattern * 512
            val extra = SEQ_EXT_RAM + pattern * 1536L
            reads += base to 256
            reads += (base + 256) to 256
            for (k in 0 until 6) reads += (extra + 256L * k) to 256
            val locks = LOCK_TABLE_RAM + pattern * 512
            reads += locks to 256
            reads += (locks + 256) to 256
        }
        return reads
    }
}

data class BaudGirlPreset(
    val slot: Int,
    val voice: ByteArray,
    val record: ByteArray,
) {
    val name: String get() = Dx7Voice.nameOf(voice)
}

class BaudGirlPatternBank {
    val steps = ByteArray(BaudGirlSysex.PATTERN_COUNT * BaudGirlSysex.PATTERN_STEP_BYTES)
    val settings = ByteArray(BaudGirlSysex.PATTERN_COUNT * BaudGirlSysex.SETTINGS_BYTES)
    val locks = ByteArray(BaudGirlSysex.PATTERN_COUNT * BaudGirlSysex.LOCK_BYTES) { 0xFF.toByte() }
    var hasLocks: Boolean = true

    init {
        for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
            for (step in 0 until BaudGirlSysex.STEPS_PER_PATTERN) emptyStep(pattern, step)
            settings[pattern * BaudGirlSysex.SETTINGS_BYTES] = 50
            settings[pattern * BaudGirlSysex.SETTINGS_BYTES + 1] = 50
            settings[pattern * BaudGirlSysex.SETTINGS_BYTES + 2] = 6
            settings[pattern * BaudGirlSysex.SETTINGS_BYTES + 3] = 16
            settings[pattern * BaudGirlSysex.SETTINGS_BYTES + 4] = 0
        }
    }

    fun emptyStep(pattern: Int, step: Int) {
        val start = pattern * BaudGirlSysex.PATTERN_STEP_BYTES + step * BaudGirlSysex.STEP_BYTES
        for (i in 0 until BaudGirlSysex.STEP_BYTES) steps[start + i] = 0xFF.toByte()
        steps[start + 30] = 0
        steps[start + 31] = 0
    }

    fun stepSlice(pattern: Int): ByteArray =
        steps.copyOfRange(
            pattern * BaudGirlSysex.PATTERN_STEP_BYTES,
            (pattern + 1) * BaudGirlSysex.PATTERN_STEP_BYTES,
        )

    fun settingsSlice(pattern: Int): ByteArray =
        settings.copyOfRange(
            pattern * BaudGirlSysex.SETTINGS_BYTES,
            (pattern + 1) * BaudGirlSysex.SETTINGS_BYTES,
        )

    fun locksSlice(pattern: Int): ByteArray =
        locks.copyOfRange(
            pattern * BaudGirlSysex.LOCK_BYTES,
            (pattern + 1) * BaudGirlSysex.LOCK_BYTES,
        )
}

data class BaudGirlSnapshot(
    val identity: String,
    val presets: List<BaudGirlPreset>,
    val patterns: BaudGirlPatternBank,
) {
    fun presetsFile(): ByteArray {
        val parts = presets.map { BaudGirlSysex.encodePresetWrite(it.slot, it.voice, it.record) }
        return joinSysex(parts)
    }

    fun patternsFile(): ByteArray {
        val parts = ArrayList<ByteArray>(BaudGirlSysex.PATTERN_COUNT * (BaudGirlSysex.STEP_PARTS + BaudGirlSysex.LOCK_EIGHTHS))
        for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
            val steps = patterns.stepSlice(pattern)
            val set = patterns.settingsSlice(pattern)
            for (part in 0 until BaudGirlSysex.STEP_PARTS) {
                parts += BaudGirlSysex.encodeStepPart(
                    set,
                    steps.copyOfRange(part * 128, part * 128 + 128),
                    pattern,
                    part,
                    save = false,
                )
            }
        }
        for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
            val locks = patterns.locksSlice(pattern)
            for (eighth in 0 until BaudGirlSysex.LOCK_EIGHTHS) {
                parts += BaudGirlSysex.encodeLockEighth(
                    locks.copyOfRange(eighth * 64, eighth * 64 + 64),
                    pattern,
                    eighth,
                    save = pattern == 15 && eighth == 7,
                )
            }
        }
        return joinSysex(parts)
    }

    companion object {
        fun parsePresets(bytes: ByteArray): List<BaudGirlPreset> {
            val presets = ArrayList<BaudGirlPreset>()
            splitSysex(bytes).forEach { message ->
                val decoded = BaudGirlSysex.decodePresetWrite(message)
                    ?: error("This isn't an FM-1+VA presets backup.")
                presets += BaudGirlPreset(decoded.first, decoded.second, decoded.third)
            }
            if (presets.isEmpty()) error("This presets backup is empty.")
            return presets
        }

        fun parsePatterns(bytes: ByteArray): BaudGirlPatternBank {
            val bank = BaudGirlPatternBank()
            var found = false
            splitSysex(bytes).forEach { message ->
                val steps = BaudGirlSysex.decodeStepPart(message)
                val locks = if (steps == null) BaudGirlSysex.decodeLockEighth(message) else null
                when {
                    steps != null && steps.pattern in 0 until BaudGirlSysex.PATTERN_COUNT &&
                        steps.part in 0 until BaudGirlSysex.STEP_PARTS -> {
                        found = true
                        val dest = steps.pattern * BaudGirlSysex.PATTERN_STEP_BYTES + steps.part * 128
                        System.arraycopy(steps.steps, 0, bank.steps, dest, 128)
                        System.arraycopy(steps.settings, 0, bank.settings, steps.pattern * BaudGirlSysex.SETTINGS_BYTES, BaudGirlSysex.SETTINGS_BYTES)
                    }
                    locks != null && locks.pattern in 0 until BaudGirlSysex.PATTERN_COUNT &&
                        locks.eighth in 0 until BaudGirlSysex.LOCK_EIGHTHS -> {
                        found = true
                        val dest = locks.pattern * BaudGirlSysex.LOCK_BYTES + locks.eighth * 64
                        System.arraycopy(locks.locks, 0, bank.locks, dest, 64)
                    }
                    else -> error("This isn't an FM-1+VA patterns backup.")
                }
            }
            if (!found) error("This patterns backup is empty.")
            return bank
        }

        private fun joinSysex(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                System.arraycopy(part, 0, out, offset, part.size)
                offset += part.size
            }
            return out
        }

        private fun splitSysex(bytes: ByteArray): List<ByteArray> {
            val messages = ArrayList<ByteArray>()
            var start = -1
            for (i in bytes.indices) {
                when (bytes[i].toUInt8()) {
                    0xF0 -> start = i
                    0xF7 -> if (start >= 0) {
                        messages += bytes.copyOfRange(start, i + 1)
                        start = -1
                    }
                }
            }
            return messages
        }
    }
}

val FM1Identity.supportsBaudGirlBackup: Boolean
    get() = !isLoader && model == "FM-1" && version in 79..799
