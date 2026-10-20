package com.fmswitch.fm1kit

/**
 * Short MIDI channel-voice messages for checking a live USB-MIDI cable.
 *
 * Stock M-VAVE firmware listens on channel 2 in the chip emulator; Felucca
 * and most community firmwares listen on 1–4. Grooveboxes such as SLOOP may
 * ignore a plain note-on, so a handshake after the phrase is what proves the
 * port is still up.
 */
object MidiNotes {
    val TEST_NOTES = intArrayOf(60, 64, 67, 72) // C4 E4 G4 C5
    val TEST_CHANNELS = intArrayOf(1, 2)
    const val NOTE_MS = 140L
    const val GAP_MS = 40L
    const val VELOCITY = 100

    data class Event(val bytes: ByteArray, val waitAfterMs: Long)

    data class Heard(
        val channel: Int,
        val note: Int,
        val velocity: Int,
        val on: Boolean,
    )

    fun noteOn(channel: Int, note: Int, velocity: Int = VELOCITY): ByteArray {
        require(channel in 1..16)
        require(note in 0..127)
        require(velocity in 0..127)
        return byteArrayOf((0x90 + (channel - 1)).toByte(), note.toByte(), velocity.toByte())
    }

    fun noteOff(channel: Int, note: Int): ByteArray {
        require(channel in 1..16)
        require(note in 0..127)
        return byteArrayOf((0x80 + (channel - 1)).toByte(), note.toByte(), 0)
    }

    fun isChannelVoice(message: ByteArray): Boolean {
        if (message.isEmpty()) return false
        val status = message[0].toUInt8()
        return status in 0x80..0xEF
    }

    fun parse(message: ByteArray): Heard? {
        if (message.size < 3 || !isChannelVoice(message)) return null
        val status = message[0].toUInt8()
        val kind = status and 0xF0
        if (kind != 0x80 && kind != 0x90) return null
        val velocity = message[2].toUInt8()
        val on = kind == 0x90 && velocity > 0
        return Heard(
            channel = (status and 0x0F) + 1,
            note = message[1].toUInt8(),
            velocity = velocity,
            on = on,
        )
    }

    fun testPhrase(
        notes: IntArray = TEST_NOTES,
        channels: IntArray = TEST_CHANNELS,
    ): List<Event> {
        val events = ArrayList<Event>(notes.size * channels.size * 2)
        for (note in notes) {
            for ((index, channel) in channels.withIndex()) {
                val last = index == channels.lastIndex
                events += Event(noteOn(channel, note), if (last) NOTE_MS else 0L)
            }
            for ((index, channel) in channels.withIndex()) {
                val last = index == channels.lastIndex
                events += Event(noteOff(channel, note), if (last) GAP_MS else 0L)
            }
        }
        return events
    }
}

data class ConnectionToneResult(
    val notesSent: Int,
    val identity: FM1Identity?,
    val stillAnswering: Boolean,
)

object ConnectionTone {
    suspend fun play(
        connection: MidiConnection,
        handshake: suspend (MidiConnection) -> FM1Identity?,
    ): ConnectionToneResult {
        var sent = 0
        for (event in MidiNotes.testPhrase()) {
            connection.send(event.bytes)
            sent += 1
            if (event.waitAfterMs > 0) kotlinx.coroutines.delay(event.waitAfterMs)
        }
        val identity = handshake(connection)
        return ConnectionToneResult(
            notesSent = sent,
            identity = identity,
            stillAnswering = identity != null,
        )
    }
}
