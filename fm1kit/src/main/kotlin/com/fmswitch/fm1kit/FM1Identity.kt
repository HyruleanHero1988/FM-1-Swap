package com.fmswitch.fm1kit

/**
 * What a device (or a `.fwsc` package) says it is, e.g. `FM-1_916` or `ota-FM-1_009`.
 */
data class FM1Identity(
    val text: String,
    val model: String,
    val version: Int,
    val armedBuildPrefix: String? = null,
) {
    val isLoader: Boolean
        get() = model.lowercase().startsWith(LOADER_PREFIX)

    val isOpenFirmware: Boolean
        get() = (model == "FM-1" && version in OPEN_VERSIONS) || model == "FM1_COMMUNITY"

    val hardwareModel: String
        get() = when {
            armedBuildPrefix != null -> FwscPackage.EXPECTED_MODEL
            isLoader -> model.drop(LOADER_PREFIX.length)
            else -> model
        }

    fun isLoaderFor(model: String): Boolean = isLoader && hardwareModel == model

    fun handshakeReply(): ByteArray {
        val textLength = REPLY_LENGTH - FM1Message.HEADER_LENGTH - 1
        val body = ArrayList<Byte>(REPLY_LENGTH)
        body.addAll(FM1Message.header(FM1Message.IDENTITY_COMMAND, textLength).toList())
        val encoded = text.toByteArray(Charsets.US_ASCII).take(textLength)
        body.addAll(encoded)
        while (body.size < REPLY_LENGTH - 1) body.add(0)
        body.add(checksum(body.toByteArray(), FM1Message.HEADER_LENGTH, body.size))
        return FM1Message.frame(body.toByteArray())
    }

    companion object {
        const val LOADER_PREFIX = "ota-"
        val OFFICIAL_V15: FM1Identity = parse("FM-1_015")!!
        const val ARMED_LOADER_MODEL = "ota-FM1C"
        const val ARMED_BUILD_PREFIX_LENGTH = 16
        val OPEN_VERSIONS = 8_000_000..8_999_999
        const val REPLY_LENGTH = 34

        fun parse(text: String): FM1Identity? {
            val separator = text.lastIndexOf('_')
            if (separator <= 0) return null
            val model = text.substring(0, separator)
            val digits = text.substring(separator + 1)
            if (model.lowercase() == ARMED_LOADER_MODEL.lowercase()) {
                if (digits.length != ARMED_BUILD_PREFIX_LENGTH || !digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    return null
                }
                return FM1Identity(text, model, 0, digits.lowercase())
            }
            if (text.any { it.code !in 0x21..0x7E }) return null
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            val version = digits.toIntOrNull() ?: return null
            return FM1Identity(text, model, version, null)
        }

        fun of(model: String, version: Int): FM1Identity {
            require(model.isNotEmpty() && model.all { it.code in 0x21..0x7E })
            require(version >= 0)
            return FM1Identity("${model}_$version", model, version, null)
        }

        fun fromHandshakeReply(sysex: ByteArray): FM1Identity? {
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size != REPLY_LENGTH) return null
            if (!body.startsWithPrefix(FM1Message.PREFIX)) return null
            if (body[FM1Message.Offset.COMMAND].toUInt8() != FM1Message.IDENTITY_COMMAND) return null
            if (checksum(body, FM1Message.HEADER_LENGTH, REPLY_LENGTH - 1) != body[REPLY_LENGTH - 1]) return null
            val textBytes = body.copyOfRange(FM1Message.HEADER_LENGTH, REPLY_LENGTH - 1)
            val length = textBytes.indexOfFirst { it.toUInt8() == 0 }.let { if (it < 0) textBytes.size else it }
            if ((0 until length).any { textBytes[it].toUInt8() >= 0x80 }) return null
            return parse(String(textBytes, 0, length, Charsets.US_ASCII))
        }
    }
}

internal fun ByteArray.startsWithPrefix(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}

fun OpenFirmware.isOpen(identity: String): Boolean =
    identity == "FM1_OFFICIAL_OPEN" ||
        identity.matches(Regex("FM1_COMMUNITY_\\d+")) ||
        identity.matches(Regex("FM-1_8\\d{6}"))

object OpenFirmware
