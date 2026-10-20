package com.fmswitch.fm1kit

data class MidiPortDescriptor(
    val id: String,
    val name: String,
)

interface MidiTransport {
    suspend fun ports(): List<MidiPortDescriptor>
    suspend fun open(port: MidiPortDescriptor): MidiConnection
    fun changes(onChange: () -> Unit): AutoCloseable
}

interface MidiConnection {
    val port: MidiPortDescriptor
    val isLost: Boolean
    fun send(sysex: ByteArray)
    suspend fun receive(timeoutMs: Long): ByteArray?
    fun drain()
    fun close()
}

sealed class MidiTransportError(message: String) : Exception(message) {
    data class Unavailable(val reason: String) : MidiTransportError("MIDI isn't available: $reason.")
    data object PortGone : MidiTransportError("The MIDI port has gone.")
    data class SendFailed(val detail: String) : MidiTransportError("Sending MIDI failed ($detail).")
}

class SysexInbox {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var closed = false

    fun deliver(sysex: ByteArray) {
        synchronized(lock) {
            if (closed) return
            if (queue.size >= CAPACITY) queue.removeFirst()
            queue.addLast(sysex)
            (lock as Object).notifyAll()
        }
    }

    fun next(timeoutMs: Long): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (queue.isEmpty() && !closed) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) return null
                (lock as Object).wait(remaining.coerceAtLeast(1))
            }
            if (queue.isEmpty()) return null
            return queue.removeFirst()
        }
    }

    fun drain() {
        synchronized(lock) { queue.clear() }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
            (lock as Object).notifyAll()
        }
    }

    companion object {
        const val CAPACITY = 256
    }
}

object UnavailableTransport : MidiTransport {
    override suspend fun ports(): List<MidiPortDescriptor> = emptyList()
    override suspend fun open(port: MidiPortDescriptor): MidiConnection = throw MidiTransportError.PortGone
    override fun changes(onChange: () -> Unit): AutoCloseable = AutoCloseable { }
}
