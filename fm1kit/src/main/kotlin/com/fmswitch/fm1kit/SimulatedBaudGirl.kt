package com.fmswitch.fm1kit

internal class SimulatedBaudGirl {
    private val presets = Array(BaudGirlSysex.PRESET_COUNT) { slot -> defaultPreset(slot) }
    private val bank = BaudGirlPatternBank()
    private val settings = ByteArray(BaudGirlSysex.SETTINGS_BLOCK)
    private val lockMark = "FMLK".toByteArray(Charsets.US_ASCII)

    init {
        for (pattern in 0 until BaudGirlSysex.PATTERN_COUNT) {
            val set = bank.settingsSlice(pattern)
            BaudGirlSysex.SETTINGS_OFFSETS.forEachIndexed { index, offset ->
                settings[offset + pattern] = set[index]
            }
            val tempo = 120
            settings[66 + 2 * pattern] = (tempo and 0xFF).toByte()
            settings[67 + 2 * pattern] = ((tempo shr 8) and 0xFF).toByte()
        }
        // Distinct notes so a dump can be checked.
        val first = 0
        bank.steps[first] = 60
        bank.steps[first + 20] = 100
        bank.steps[first + 30] = 1
        bank.steps[first + 31] = 0
        presets[0] = BaudGirlPreset(0, namedVoice("ORGAN 3"), defaultRecord(0x03))
        presets[1] = BaudGirlPreset(1, namedVoice("BASS"), defaultRecord(0x5A))
    }

    fun preset(slot: Int): BaudGirlPreset = presets[slot]

    fun writePreset(preset: BaudGirlPreset) {
        presets[preset.slot] = preset
    }

    fun writeStepPart(part: BaudGirlSysex.StepPart) {
        val dest = part.pattern * BaudGirlSysex.PATTERN_STEP_BYTES + part.part * 128
        System.arraycopy(part.steps, 0, bank.steps, dest, 128)
        System.arraycopy(
            part.settings,
            0,
            bank.settings,
            part.pattern * BaudGirlSysex.SETTINGS_BYTES,
            BaudGirlSysex.SETTINGS_BYTES,
        )
        BaudGirlSysex.SETTINGS_OFFSETS.forEachIndexed { index, offset ->
            settings[offset + part.pattern] = part.settings[index]
        }
    }

    fun writeLockEighth(part: BaudGirlSysex.LockEighth) {
        val dest = part.pattern * BaudGirlSysex.LOCK_BYTES + part.eighth * 64
        System.arraycopy(part.locks, 0, bank.locks, dest, 64)
        bank.hasLocks = true
    }

    fun readMemory(address: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        when {
            address == BaudGirlSysex.GSET_RAM -> copyInto(settings, 0, out)
            address == BaudGirlSysex.LOCK_MARK_RAM -> copyInto(lockMark, 0, out)
            address >= BaudGirlSysex.SEQ_STEPS_RAM &&
                address < BaudGirlSysex.SEQ_STEPS_RAM + BaudGirlSysex.PATTERN_COUNT * 512 -> {
                val pattern = ((address - BaudGirlSysex.SEQ_STEPS_RAM) / 512).toInt()
                val offset = ((address - BaudGirlSysex.SEQ_STEPS_RAM) % 512).toInt()
                copyInto(bank.steps, pattern * BaudGirlSysex.PATTERN_STEP_BYTES + offset, out)
            }
            address >= BaudGirlSysex.SEQ_EXT_RAM &&
                address < BaudGirlSysex.SEQ_EXT_RAM + BaudGirlSysex.PATTERN_COUNT * 1536 -> {
                val pattern = ((address - BaudGirlSysex.SEQ_EXT_RAM) / 1536).toInt()
                val offset = ((address - BaudGirlSysex.SEQ_EXT_RAM) % 1536).toInt()
                copyInto(bank.steps, pattern * BaudGirlSysex.PATTERN_STEP_BYTES + 512 + offset, out)
            }
            address >= BaudGirlSysex.LOCK_TABLE_RAM &&
                address < BaudGirlSysex.LOCK_TABLE_RAM + BaudGirlSysex.PATTERN_COUNT * 512 -> {
                val pattern = ((address - BaudGirlSysex.LOCK_TABLE_RAM) / 512).toInt()
                val offset = ((address - BaudGirlSysex.LOCK_TABLE_RAM) % 512).toInt()
                copyInto(bank.locks, pattern * BaudGirlSysex.LOCK_BYTES + offset, out)
            }
            else -> out.fill(0)
        }
        return out
    }

    private fun copyInto(source: ByteArray, offset: Int, dest: ByteArray) {
        val available = (source.size - offset).coerceAtLeast(0)
        val n = minOf(dest.size, available)
        if (n > 0) System.arraycopy(source, offset, dest, 0, n)
    }

    companion object {
        fun namedVoice(name: String): ByteArray {
            val voice = ByteArray(Dx7Voice.PACKED_LENGTH)
            val padded = name.padEnd(10).take(10)
            for (i in 0 until 10) voice[118 + i] = padded[i].code.toByte()
            return voice
        }

        fun defaultRecord(marker: Int): ByteArray {
            val record = ByteArray(BaudGirlSysex.RECORD_LENGTH)
            record[0] = 0x50
            for (i in 1..17) record[i] = 0x03
            record[18] = marker.toByte()
            return record
        }

        fun defaultPreset(slot: Int): BaudGirlPreset =
            BaudGirlPreset(slot, namedVoice("VOICE ${slot + 1}"), defaultRecord(0x03))
    }
}
