package com.fmswitch.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * While TRY is open, a USB MIDI keyboard's notes are forwarded into the virtual FM-1.
 * Only the first output port is opened, so a device that echoes the same notes on a second
 * port does not play them twice. The FM-1 this app is already talking to is left alone.
 */
class UsbMidiTap(
    context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val skipIds: () -> Set<Int>,
    private val onLine: (String) -> Unit,
) : AutoCloseable {
    private val midi = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<Int>()
    private val opened = mutableMapOf<Int, Opened>()
    private val retired = mutableSetOf<Int>()
    private val failed = mutableMapOf<Int, String>()
    private var closed = false
    private var syncing = false
    private var syncAgain = false
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            failed.remove(device.id)
            sync()
        }
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            failed.remove(device.id)
            forget(device.id)
            publish()
        }
    }

    init {
        midi?.registerDeviceCallback(callback, handler)
        handler.post { sync() }
    }

    fun refresh() {
        handler.post { sync() }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            midi?.unregisterDeviceCallback(callback)
            opened.keys.toList().forEach { forget(it) }
            pending.clear()
            retired.clear()
            failed.clear()
            onLine("")
        }
    }

    private fun sync() {
        if (closed) return
        if (syncing) {
            syncAgain = true
            return
        }
        syncing = true
        try {
            val manager = midi ?: return
            val skip = skipIds()
            val targets = usbMidiListenTargets(
                manager.devices.map { it.toCandidate() },
                skip,
                MidiDeviceInfo.TYPE_USB,
            )
            val want = targets.associateBy { it.id }
            (opened.keys + pending).filter { it !in want }.forEach { forget(it) }
            failed.keys.filter { id -> targets.none { it.id == id } }.toList().forEach { failed.remove(it) }
            for (target in targets) {
                if (target.id in opened || target.id in pending) continue
                val info = manager.devices.firstOrNull { it.id == target.id } ?: continue
                pending += target.id
                manager.openDevice(info, { device -> onOpened(target, device) }, handler)
            }
            publish()
        } finally {
            syncing = false
            if (syncAgain) {
                syncAgain = false
                sync()
            }
        }
    }

    private fun onOpened(target: UsbMidiCandidate, device: MidiDevice?) {
        pending.remove(target.id)
        if (closed || device == null || target.id in retired || target.id in skipIds()) {
            retired.remove(target.id)
            if (device == null && !closed && target.id !in skipIds()) failed[target.id] = target.name
            device?.close()
            publish()
            return
        }
        val receiver = Forwarder(target.id)
        val ports = target.outputPorts.take(1).mapNotNull { number ->
            device.openOutputPort(number)?.also { it.connect(receiver) }
        }
        if (ports.isEmpty()) {
            device.close()
            failed[target.id] = target.name
        } else {
            failed.remove(target.id)
            opened[target.id] = Opened(device, ports, target.name)
        }
        publish()
    }

    private fun forget(id: Int) {
        if (id in pending) retired += id
        val open = opened.remove(id) ?: return
        open.close()
        onBytes(usbMidiSilence())
    }

    private fun publish() {
        onLine(usbMidiLine(opened.values.map { it.name }.sorted(), failed.values.toList().sorted()))
    }

    private inner class Forwarder(val deviceId: Int) : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (closed || count <= 0 || deviceId in skipIds()) return
            val end = (offset + count).coerceAtMost(msg.size)
            if (end <= offset) return
            onBytes(msg.copyOfRange(offset, end))
        }
    }

    private class Opened(
        val device: MidiDevice,
        val ports: List<MidiOutputPort>,
        val name: String,
    ) {
        fun close() {
            ports.forEach { port ->
                runCatching { port.close() }
            }
            runCatching { device.close() }
        }
    }
}

internal data class UsbMidiCandidate(
    val id: Int,
    val type: Int,
    val name: String,
    val outputPorts: List<Int>,
)

internal fun usbMidiListenTargets(
    devices: List<UsbMidiCandidate>,
    skip: Set<Int>,
    usbType: Int,
): List<UsbMidiCandidate> =
    devices.filter { it.type == usbType && it.id !in skip && it.outputPorts.isNotEmpty() }

internal fun midiProductName(name: String?, product: String?, manufacturer: String?, id: Int): String =
    product?.trim()?.takeIf { it.isNotEmpty() }
        ?: name?.trim()?.takeIf { it.isNotEmpty() }
        ?: manufacturer?.trim()?.takeIf { it.isNotEmpty() }
        ?: "MIDI $id"

internal fun usbMidiLine(names: List<String>, failed: List<String>): String = when {
    names.isNotEmpty() -> "USB MIDI · " + names.joinToString(", ")
    failed.isNotEmpty() -> "Couldn't open " + failed.joinToString(", ") + "."
    else -> ""
}

/** All Sound Off and All Notes Off on every channel, so an unplugged key does not drone. */
internal fun usbMidiSilence(): ByteArray {
    val out = ByteArray(16 * 6)
    var index = 0
    for (channel in 0 until 16) {
        val status = (0xB0 or channel).toByte()
        out[index++] = status
        out[index++] = 120
        out[index++] = 0
        out[index++] = status
        out[index++] = 123
        out[index++] = 0
    }
    return out
}

private fun MidiDeviceInfo.toCandidate(): UsbMidiCandidate {
    val outputs = ports
        .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        .map { it.portNumber }
        .distinct()
        .sorted()
    val properties = this.properties
    return UsbMidiCandidate(
        id,
        type,
        midiProductName(
            properties.getString(MidiDeviceInfo.PROPERTY_NAME),
            properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
            id,
        ),
        outputs,
    )
}
