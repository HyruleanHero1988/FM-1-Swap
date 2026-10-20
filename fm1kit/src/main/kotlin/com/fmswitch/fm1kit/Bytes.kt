package com.fmswitch.fm1kit

import java.security.MessageDigest

internal fun Byte.toUInt8(): Int = toInt() and 0xFF

internal fun Int.toUByte(): Byte = (this and 0xFF).toByte()

internal fun littleEndian(value: Long, byteCount: Int): ByteArray =
    ByteArray(byteCount) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

internal fun readLittleEndian(bytes: ByteArray, offset: Int, byteCount: Int): Long {
    var value = 0L
    for (i in 0 until byteCount) {
        value = value or ((bytes[offset + i].toUInt8().toLong()) shl (8 * i))
    }
    return value
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toUInt8()) }
}

internal fun crc16(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
    var value = 0
    for (i in start until end) {
        value = value xor (bytes[i].toUInt8() shl 8)
        repeat(8) {
            value = if (value and 0x8000 == 0) (value shl 1) and 0xFFFF else ((value shl 1) xor 0x1021) and 0xFFFF
        }
    }
    return value
}

internal fun decipher(bytes: ByteArray, start: Int, end: Int, startKey: Int = 0xFFFF): ByteArray {
    var key = startKey
    return ByteArray(end - start) { i ->
        val out = (bytes[start + i].toUInt8() xor (key and 0xFF)).toUByte()
        key = if (key and 0x8000 == 0) (key shl 1) and 0xFFFF else ((key shl 1) xor 0x1021) and 0xFFFF
        out
    }
}

internal fun checksum(bytes: ByteArray, start: Int, end: Int): Byte {
    var sum = 0
    for (i in start until end) {
        sum = (sum + bytes[i].toUInt8()) and 0xFF
    }
    return (sum.inv() and 0xFF).toUByte()
}

internal fun asciiText(bytes: ByteArray, start: Int, end: Int): String {
    val slice = bytes.copyOfRange(start, end)
    val length = slice.indexOfFirst { it.toUInt8() == 0 }.let { if (it < 0) slice.size else it }
    return String(slice, 0, length, Charsets.US_ASCII)
}
