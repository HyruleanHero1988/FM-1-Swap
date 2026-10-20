package com.fmswitch.fm1kit

data class FM1BuildID(val bytes: ByteArray) {
    init {
        require(bytes.size == BYTE_COUNT) { "A build ID is 32 bytes." }
    }

    val hex: String
        get() = bytes.joinToString("") { "%02x".format(it.toUInt8()) }

    override fun equals(other: Any?): Boolean = other is FM1BuildID && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = hex

    companion object {
        const val BYTE_COUNT = 32

        fun fromHex(hex: String): FM1BuildID? {
            if (hex.length != BYTE_COUNT * 2) return null
            val out = ByteArray(BYTE_COUNT)
            for (i in 0 until BYTE_COUNT) {
                val high = nibble(hex[i * 2]) ?: return null
                val low = nibble(hex[i * 2 + 1]) ?: return null
                out[i] = ((high shl 4) or low).toUByte()
            }
            return FM1BuildID(out)
        }

        fun knownHex(hex: String): FM1BuildID =
            fromHex(hex) ?: error("$hex isn't a build ID.")

        private fun nibble(digit: Char): Int? = when (digit) {
            in '0'..'9' -> digit - '0'
            in 'a'..'f' -> digit - 'a' + 10
            in 'A'..'F' -> digit - 'A' + 10
            else -> null
        }
    }
}

data class FM1Capability(
    val family: Int = FM1_FAMILY,
    val state: Int = 0,
    val buildID: FM1BuildID,
    val errorCode: Int = 0,
) {
    val isReady: Boolean get() = state == 0

    fun payload(): ByteArray {
        val payload = ArrayList<Byte>(PAYLOAD_LENGTH)
        payload.addAll(MAGIC.toList())
        payload.add(VERSION.toUByte())
        payload.add(family.toUByte())
        payload.add(state.toUByte())
        payload.add(LOADER_KIND.toUByte())
        payload.addAll(littleEndian(FM1Message.MAXIMUM_READ_LENGTH.toLong(), 2).toList())
        payload.addAll(littleEndian(FLASH_LENGTH, 4).toList())
        payload.addAll(buildID.bytes.toList())
        payload.add(errorCode.toUByte())
        return payload.toByteArray()
    }

    companion object {
        const val FM1_FAMILY = 7
        const val PAYLOAD_LENGTH = 47
        val MAGIC = "FMC1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val MAXIMUM_FAMILY = 7
        const val LOADER_KIND = 1
        const val FLASH_LENGTH = 1_048_576L
        val ARM_ACCEPTED = byteArrayOf(0)

        fun parse(payload: ByteArray): FM1Capability? {
            if (payload.size != PAYLOAD_LENGTH) return null
            if (!payload.startsWithPrefix(MAGIC)) return null
            if (payload[4].toUInt8() != VERSION) return null
            if (payload[5].toUInt8() > MAXIMUM_FAMILY) return null
            if (payload[7].toUInt8() != LOADER_KIND) return null
            if (readLittleEndian(payload, 8, 2) != FM1Message.MAXIMUM_READ_LENGTH.toLong()) return null
            if (readLittleEndian(payload, 10, 4) != FLASH_LENGTH) return null
            return FM1Capability(
                family = payload[5].toUInt8(),
                state = payload[6].toUInt8(),
                buildID = FM1BuildID(payload.copyOfRange(14, 46)),
                errorCode = payload[46].toUInt8(),
            )
        }

        fun armPayload(family: Int, imageLength: Int, buildID: FM1BuildID): ByteArray =
            byteArrayOf(1, family.toUByte()) + littleEndian(imageLength.toLong(), 4) + buildID.bytes
    }
}
