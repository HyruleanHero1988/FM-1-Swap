package com.fmswitch.fm1kit

/**
 * Messages of the FM-1 USB-MIDI update protocol, as documented in AL-255/FM-1-RE
 * (`docs/io/11-ota-protocol.md`).
 */
object FM1Message {
    val HANDSHAKE_QUERY = byteArrayOf(
        0xF0.toUByte(), 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7F, 0xF7.toUByte(),
    )
    val UPGRADE_COMMAND = byteArrayOf(0xF0.toUByte(), 0x22, 0x24, 0x35, 0x7F, 0xF7.toUByte())

    const val STAGE_FINISH_ADDRESS = 0xE0000000L
    const val WRITE_FINISH_ADDRESS = 0xF0000000L
    val FINISH_PAYLOAD: ByteArray = "success".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    const val MAXIMUM_READ_LENGTH = 512

    val PREFIX = byteArrayOf(0x00, 0x59)
    const val READ_COMMAND = 0x30
    const val IDENTITY_COMMAND = 0x11
    const val CAPABILITY_COMMAND = 0x31
    const val ARM_COMMAND = 0x32
    const val VOICE_ERASE_COMMAND = 0x21
    const val VOICE_WRITE_COMMAND = 0x22
    const val ACKNOWLEDGEMENT_COMMAND = 0x00
    const val VOICE_AREA = 5
    const val VOICE_WRITE_LENGTH = 512
    const val FIXED_FIELDS_LENGTH = 8
    const val HEADER_LENGTH = 6

    object Offset {
        const val COMMAND = 2
        const val BODY_LENGTH = 3
        const val FLASH_TYPE = 6
        const val ADDRESS = 7
        const val LENGTH = 11
        const val DATA = 14
    }

    fun frame(unpacked: ByteArray): ByteArray =
        byteArrayOf(0xF0.toUByte()) + SevenBit.pack(unpacked) + byteArrayOf(0xF7.toUByte())

    fun unframe(sysex: ByteArray): ByteArray? {
        if (sysex.size < 2 || sysex.first().toUInt8() != 0xF0 || sysex.last().toUInt8() != 0xF7) return null
        return SevenBit.unpack(sysex.copyOfRange(1, sysex.size - 1))
    }

    fun command(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = header(command, payload.size) + payload + byteArrayOf(checksum(payload, 0, payload.size))
        return frame(body)
    }

    fun parseCommand(sysex: ByteArray): Pair<Int, ByteArray>? {
        val body = unframe(sysex) ?: return null
        if (body.size <= HEADER_LENGTH) return null
        if (!body.startsWithPrefix(PREFIX)) return null
        val declared = readLittleEndian(body, Offset.BODY_LENGTH, 3).toInt()
        if (declared != body.size - HEADER_LENGTH - 1) return null
        if (checksum(body, HEADER_LENGTH, body.size - 1) != body[body.size - 1]) return null
        return body[Offset.COMMAND].toUInt8() to body.copyOfRange(HEADER_LENGTH, body.size - 1)
    }

    fun header(command: Int, bodyLength: Int): ByteArray =
        PREFIX + byteArrayOf(command.toUByte()) + littleEndian(bodyLength.toLong(), 3)
}

data class FM1ReadRequest(
    val flashType: Int = 0,
    val address: Long,
    val length: Int,
) {
    val isFinish: Boolean
        get() = address == FM1Message.STAGE_FINISH_ADDRESS || address == FM1Message.WRITE_FINISH_ADDRESS

    fun encoded(): ByteArray = FM1Message.frame(body(flashType, address, length, ByteArray(0)))

    fun response(data: ByteArray): ByteArray = FM1Message.frame(body(flashType, address, data.size, data))

    fun finishResponse(): ByteArray = response(FM1Message.FINISH_PAYLOAD)

    companion object {
        fun parse(sysex: ByteArray): FM1ReadRequest? {
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size != FM1Message.HEADER_LENGTH + FM1Message.FIXED_FIELDS_LENGTH + 1) return null
            if (!body.startsWithPrefix(FM1Message.PREFIX)) return null
            if (body[FM1Message.Offset.COMMAND].toUInt8() != FM1Message.READ_COMMAND) return null
            if (checksum(body, FM1Message.Offset.FLASH_TYPE, FM1Message.Offset.DATA) != body[FM1Message.Offset.DATA]) {
                return null
            }
            return FM1ReadRequest(
                flashType = body[FM1Message.Offset.FLASH_TYPE].toUInt8(),
                address = readLittleEndian(body, FM1Message.Offset.ADDRESS, 4),
                length = readLittleEndian(body, FM1Message.Offset.LENGTH, 3).toInt(),
            )
        }

        fun parseResponse(sysex: ByteArray): Pair<FM1ReadRequest, ByteArray>? {
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size < FM1Message.HEADER_LENGTH + FM1Message.FIXED_FIELDS_LENGTH + 1) return null
            if (!body.startsWithPrefix(FM1Message.PREFIX)) return null
            if (body[FM1Message.Offset.COMMAND].toUInt8() != FM1Message.READ_COMMAND) return null
            val bodyLength = readLittleEndian(body, FM1Message.Offset.BODY_LENGTH, 3).toInt()
            val length = readLittleEndian(body, FM1Message.Offset.LENGTH, 3).toInt()
            val checksumIndex = FM1Message.Offset.DATA + length
            if (bodyLength != length + FM1Message.FIXED_FIELDS_LENGTH) return null
            if (body.size != checksumIndex + 1) return null
            if (checksum(body, FM1Message.Offset.FLASH_TYPE, checksumIndex) != body[checksumIndex]) return null
            val request = FM1ReadRequest(
                flashType = body[FM1Message.Offset.FLASH_TYPE].toUInt8(),
                address = readLittleEndian(body, FM1Message.Offset.ADDRESS, 4),
                length = length,
            )
            return request to body.copyOfRange(FM1Message.Offset.DATA, checksumIndex)
        }

        private fun body(flashType: Int, address: Long, length: Int, data: ByteArray): ByteArray {
            val parts = ArrayList<Byte>()
            parts.addAll(FM1Message.header(FM1Message.READ_COMMAND, data.size + FM1Message.FIXED_FIELDS_LENGTH).toList())
            parts.add(flashType.toUByte())
            parts.addAll(littleEndian(address, 4).toList())
            parts.addAll(littleEndian(length.toLong(), 3).toList())
            parts.addAll(data.toList())
            val soFar = parts.toByteArray()
            parts.add(checksum(soFar, FM1Message.Offset.FLASH_TYPE, soFar.size))
            return parts.toByteArray()
        }
    }
}
