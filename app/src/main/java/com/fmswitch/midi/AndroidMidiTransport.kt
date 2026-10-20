package com.fmswitch.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.fmswitch.fm1kit.MidiConnection
import com.fmswitch.fm1kit.MidiPortDescriptor
import com.fmswitch.fm1kit.MidiTransport
import com.fmswitch.fm1kit.MidiTransportError
import com.fmswitch.fm1kit.SysexInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidMidiTransport(context: Context) : MidiTransport {
    private val midi = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        ?: throw MidiTransportError.Unavailable("MidiManager")
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<() -> Unit>()
    private val openById = mutableMapOf<Int, AndroidMidiConnection>()
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = notifyObservers()
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            synchronized(openById) {
                openById.remove(device.id)?.markLost()
            }
            notifyObservers()
        }
        // Opening or closing a port flips this. Treating it as unplug/replug
        // made the DEVICE screen scan in a loop on the FM-1's JieLi chip.
    }

    init {
        midi.registerDeviceCallback(callback, handler)
    }

    override suspend fun ports(): List<MidiPortDescriptor> {
        return midi.devices.map { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
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
        val inputIndex = portIndex(info, MidiDeviceInfo.PortInfo.TYPE_INPUT)
        val outputIndex = portIndex(info, MidiDeviceInfo.PortInfo.TYPE_OUTPUT)
        try {
            val input = device.openInputPort(inputIndex)
                ?: throw MidiTransportError.Unavailable("no input port")
            try {
                val output = device.openOutputPort(outputIndex)
                    ?: throw MidiTransportError.Unavailable("no output port")
                try {
                    // The AC791N drops USB if SysEx goes out before the gadget finishes enumerating.
                    delay(250)
                    val connection = AndroidMidiConnection(port, info.id, device, input, output) {
                        synchronized(openById) { openById.remove(info.id) }
                    }
                    synchronized(openById) { openById[info.id] = connection }
                    return connection
                } catch (error: Throwable) {
                    runCatching { output.close() }
                    throw error
                }
            } catch (error: Throwable) {
                runCatching { input.close() }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { device.close() }
            throw error
        }
    }

    override fun changes(onChange: () -> Unit): AutoCloseable {
        synchronized(observers) { observers.add(onChange) }
        return AutoCloseable { synchronized(observers) { observers.remove(onChange) } }
    }

    private fun notifyObservers() {
        synchronized(observers) { observers.toList() }.forEach { it() }
    }

    private fun portIndex(info: MidiDeviceInfo, type: Int): Int {
        val match = info.ports.indexOfFirst { it.type == type }
        return if (match >= 0) match else 0
    }
}

private class AndroidMidiConnection(
    override val port: MidiPortDescriptor,
    val deviceId: Int,
    private val device: MidiDevice,
    private val input: MidiInputPort,
    private val output: MidiOutputPort,
    private val onClosed: () -> Unit,
) : MidiConnection {
    private val inbox = SysexInbox()
    private val assembler = SysexAssembler { inbox.deliver(it) }
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            assembler.accept(msg, offset, count)
        }
    }
    private var closed = false
    private var lost = false

    override val isLost: Boolean get() = closed || lost

    init {
        output.connect(receiver)
    }

    fun markLost() {
        lost = true
        inbox.close()
    }

    override fun send(sysex: ByteArray) {
        if (closed || lost) throw MidiTransportError.PortGone
        try {
            input.send(sysex, 0, sysex.size)
        } catch (error: Exception) {
            lost = true
            throw MidiTransportError.SendFailed(error.message ?: "send")
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) { inbox.next(timeoutMs) }

    override fun drain() {
        inbox.drain()
    }

    override fun close() {
        if (closed) return
        closed = true
        inbox.close()
        runCatching { output.disconnect(receiver) }
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { device.close() }
        onClosed()
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
