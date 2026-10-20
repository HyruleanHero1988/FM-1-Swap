package com.fmswitch.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.fmswitch.fm1kit.MidiConnection
import com.fmswitch.fm1kit.MidiPortDescriptor
import com.fmswitch.fm1kit.MidiTransport
import com.fmswitch.fm1kit.MidiTransportError
import com.fmswitch.fm1kit.SysexInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidMidiTransport(context: Context) : MidiTransport {
    private val midi = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        ?: throw MidiTransportError.Unavailable("MidiManager")
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<() -> Unit>()
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = notifyObservers()
        override fun onDeviceRemoved(device: MidiDeviceInfo) = notifyObservers()
        override fun onDeviceStatusChanged(status: android.media.midi.MidiDeviceStatus) = notifyObservers()
    }

    init {
        midi.registerDeviceCallback(callback, handler)
    }

    override suspend fun ports(): List<MidiPortDescriptor> {
        return midi.devices.map { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: "MIDI ${info.id}"
            MidiPortDescriptor(info.id.toString(), name)
        }
    }

    override suspend fun open(port: MidiPortDescriptor): MidiConnection {
        val info = midi.devices.firstOrNull { it.id.toString() == port.id }
            ?: throw MidiTransportError.PortGone
        val device = suspendCancellableCoroutine { cont ->
            midi.openDevice(info, { opened ->
                if (opened != null) cont.resume(opened) else cont.resumeWithException(MidiTransportError.PortGone)
            }, handler)
        }
        val input = device.openInputPort(0) ?: run {
            device.close()
            throw MidiTransportError.Unavailable("no input port")
        }
        val output = device.openOutputPort(0) ?: run {
            input.close()
            device.close()
            throw MidiTransportError.Unavailable("no output port")
        }
        return AndroidMidiConnection(port, device, input, output)
    }

    override fun changes(onChange: () -> Unit): AutoCloseable {
        synchronized(observers) { observers.add(onChange) }
        return AutoCloseable { synchronized(observers) { observers.remove(onChange) } }
    }

    private fun notifyObservers() {
        synchronized(observers) { observers.toList() }.forEach { it() }
    }
}

private class AndroidMidiConnection(
    override val port: MidiPortDescriptor,
    private val device: MidiDevice,
    private val input: android.media.midi.MidiInputPort,
    output: android.media.midi.MidiOutputPort,
) : MidiConnection {
    private val inbox = SysexInbox()
    private val assembler = SysexAssembler { inbox.deliver(it) }
    private var closed = false

    override val isLost: Boolean get() = closed

    init {
        output.connect(object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                assembler.accept(msg, offset, count)
            }
        })
    }

    override fun send(sysex: ByteArray) {
        if (closed) throw MidiTransportError.PortGone
        try {
            input.send(sysex, 0, sysex.size)
        } catch (error: Exception) {
            throw MidiTransportError.SendFailed(error.message ?: "send")
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) { inbox.next(timeoutMs) }

    override fun drain() {
        inbox.drain()
    }

    override fun close() {
        closed = true
        inbox.close()
        runCatching { input.close() }
        runCatching { device.close() }
    }
}

internal class SysexAssembler(private val onMessage: (ByteArray) -> Unit) {
    private val buffer = ArrayList<Byte>(64)
    private var inMessage = false

    @Synchronized
    fun accept(msg: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val byte = msg[i]
            val value = byte.toInt() and 0xFF
            when {
                value == 0xF0 -> {
                    buffer.clear()
                    buffer.add(byte)
                    inMessage = true
                }
                inMessage -> {
                    buffer.add(byte)
                    if (value == 0xF7) {
                        onMessage(buffer.toByteArray())
                        buffer.clear()
                        inMessage = false
                    }
                }
            }
        }
    }
}
