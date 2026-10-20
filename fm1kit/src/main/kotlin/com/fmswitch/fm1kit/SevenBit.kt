package com.fmswitch.fm1kit

/**
 * The FM-1 update protocol's 8-to-7-bit packing.
 *
 * The whole unpacked message is treated as one continuous bit stream, least significant bit
 * first, and cut into 7-bit SysEx data bytes. This is not the common "MSB byte + 7 bytes"
 * scheme, so it must not be swapped for one.
 */
object SevenBit {
    fun pack(bytes: ByteArray): ByteArray {
        val packed = ArrayList<Byte>(bytes.size * 8 / 7 + 1)
        var accumulator = 0
        var bitCount = 0
        for (byte in bytes) {
            accumulator = accumulator or (byte.toUInt8() shl bitCount)
            bitCount += 8
            while (bitCount >= 7) {
                packed.add((accumulator and 0x7F).toUByte())
                accumulator = accumulator ushr 7
                bitCount -= 7
            }
        }
        if (bitCount > 0) {
            packed.add((accumulator and 0x7F).toUByte())
        }
        return packed.toByteArray()
    }

    fun unpack(septets: ByteArray): ByteArray {
        val unpacked = ArrayList<Byte>(septets.size * 7 / 8)
        var accumulator = 0
        var bitCount = 0
        for (septet in septets) {
            accumulator = accumulator or ((septet.toUInt8() and 0x7F) shl bitCount)
            bitCount += 7
            while (bitCount >= 8) {
                unpacked.add((accumulator and 0xFF).toUByte())
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
        }
        return unpacked.toByteArray()
    }
}
