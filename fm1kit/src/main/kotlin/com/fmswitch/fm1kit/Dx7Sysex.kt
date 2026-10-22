package com.fmswitch.fm1kit

/**
 * Yamaha DX7 bulk dumps, the same messages fm1-editor.com sends.
 * A bank file is F0 43 0n 09 20 00, 32 packed voices, a checksum, and F7.
 * A single patch for the edit buffer is F0 43 0n 00 01 1B, the 155-byte voice, a checksum, and F7.
 * FM-1+VA takes one parameter at a time: F0 43 10 gg pp vv F7.
 */
data class Dx7Bank(val voices: List<ByteArray>) {
    init {
        require(voices.size == VOICE_COUNT) { "A DX7 bank has $VOICE_COUNT voices." }
        voices.forEach { voice ->
            require(voice.size == Dx7Voice.PACKED_LENGTH) { "A packed DX7 voice is ${Dx7Voice.PACKED_LENGTH} bytes." }
            require(voice.all { it.toUInt8() <= 0x7F }) { "A packed DX7 voice stays in 7 bits." }
        }
    }

    fun name(index: Int): String = Dx7Voice.nameOf(voices[index])

    fun singleVoiceMessage(index: Int, channel: Int = CHANNEL): ByteArray {
        val data = Dx7Voice.unpack(voices[index])
        return frame(channel, byteArrayOf(0x00, 0x01, 0x1B), data)
    }

    fun bankMessage(channel: Int = CHANNEL): ByteArray {
        val data = ByteArray(VOICE_COUNT * Dx7Voice.PACKED_LENGTH)
        voices.forEachIndexed { index, voice ->
            voice.copyInto(data, index * Dx7Voice.PACKED_LENGTH)
        }
        return frame(channel, byteArrayOf(0x09, 0x20, 0x00), data)
    }

    /** One message per voice parameter. FM-1+VA holds these as an edit of the selected preset. */
    fun parameterMessages(index: Int): List<ByteArray> {
        val data = Dx7Voice.unpack(voices[index])
        return data.mapIndexed { parameter, value ->
            val amount = value.toUInt8()
            require(amount <= 0x7F)
            byteArrayOf(
                0xF0.toUByte(),
                0x43,
                0x10,
                (parameter / 128).toUByte(),
                (parameter % 128).toUByte(),
                amount.toUByte(),
                0xF7.toUByte(),
            )
        }
    }

    companion object {
        const val VOICE_COUNT = 32
        const val CHANNEL = 1
        const val FILE_LENGTH = 4104

        fun parse(file: ByteArray): Dx7Bank {
            if (file.size != FILE_LENGTH) {
                throw IllegalArgumentException("A DX7 bank file is $FILE_LENGTH bytes.")
            }
            if (
                file[0].toUInt8() != 0xF0 ||
                file[1].toUInt8() != 0x43 ||
                file[3].toUInt8() != 0x09 ||
                file[4].toUInt8() != 0x20 ||
                file[5].toUInt8() != 0x00 ||
                file.last().toUInt8() != 0xF7
            ) {
                throw IllegalArgumentException("This is not a DX7 32-voice bank.")
            }
            val data = file.copyOfRange(6, 6 + VOICE_COUNT * Dx7Voice.PACKED_LENGTH)
            if (data.any { it.toUInt8() > 0x7F }) {
                throw IllegalArgumentException("This DX7 bank has a byte outside the MIDI range.")
            }
            if (checksum(data) != file[file.size - 2].toUInt8()) {
                throw IllegalArgumentException("This DX7 bank's checksum does not match.")
            }
            val voices = List(VOICE_COUNT) { index ->
                data.copyOfRange(index * Dx7Voice.PACKED_LENGTH, (index + 1) * Dx7Voice.PACKED_LENGTH)
            }
            return Dx7Bank(voices)
        }

        fun checksum(data: ByteArray): Int = (128 - (data.sumOf { it.toUInt8() } and 0x7F)) and 0x7F

        private fun frame(channel: Int, format: ByteArray, data: ByteArray): ByteArray {
            require(channel in 1..16)
            val message = ByteArray(6 + data.size + 2)
            message[0] = 0xF0.toUByte()
            message[1] = 0x43
            message[2] = (channel - 1).toUByte()
            format.copyInto(message, 3)
            data.copyInto(message, 6)
            message[message.size - 2] = checksum(data).toUByte()
            message[message.size - 1] = 0xF7.toUByte()
            return message
        }
    }
}

sealed class PatchPlan {
    data class Ready(val messages: List<ByteArray>, val done: String) : PatchPlan()
    data class Refused(val reason: String) : PatchPlan()
}

fun planVoiceSend(identity: FM1Identity, bank: Dx7Bank, index: Int): PatchPlan {
    require(index in bank.voices.indices)
    if (identity.isLoader) return PatchPlan.Refused(LOADER)
    if (identity.supportsBaudGirlBackup) {
        return PatchPlan.Ready(
            bank.parameterMessages(index),
            "Sent to the preset that's selected. Save it on the FM-1 to keep it.",
        )
    }
    if (identity.isOpenFirmware) {
        return PatchPlan.Ready(
            listOf(bank.singleVoiceMessage(index)),
            "Sent to the DX7 track on channel 1.",
        )
    }
    if (identity.model == "FM-1" && identity.version == 15) {
        return PatchPlan.Ready(
            listOf(bank.singleVoiceMessage(index)),
            "Sent. Hold SAVE on the FM-1 to store it.",
        )
    }
    return PatchPlan.Refused(UNSUPPORTED)
}

fun planBankSend(identity: FM1Identity, bank: Dx7Bank): PatchPlan {
    if (identity.isLoader) return PatchPlan.Refused(LOADER)
    if (identity.isOpenFirmware) {
        return PatchPlan.Refused("Official Open takes one patch at a time, on its DX7 track.")
    }
    if (identity.supportsBaudGirlBackup || (identity.model == "FM-1" && identity.version == 15)) {
        return PatchPlan.Ready(
            listOf(bank.bankMessage()),
            "Sent. On the FM-1, choose the bank these 32 patches should replace.",
        )
    }
    return PatchPlan.Refused(UNSUPPORTED)
}

private const val LOADER = "The FM-1 is in its update loader. Start a firmware, then send the patch."
private const val UNSUPPORTED = "This firmware doesn't take these patches. Official V15, FM-1+VA, and Official Open do."
