package com.fmswitch.emu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * One emulated FM-1 on a thread of its own: it boots the package, then renders audio in
 * small blocks into an [AudioTrack], whose blocking writes pace the machine at real time.
 * The panel's input is queued to that thread; the screen and LEDs come back as [Fm1PanelSource.Frame]s.
 * What the firmware writes to its flash (patches, patterns, settings) is kept in [saveFile]
 * and restored the next time the same package boots.
 */
class Fm1Engine(
    private val pkg: ByteArray,
    private val saveFile: File?,
    private val mhz: Int = 100,
) : Fm1PanelSource, AutoCloseable {

    sealed class Status {
        data object Booting : Status()
        data class Running(val load: Float) : Status()
        data class Stopped(val message: String) : Status()
    }

    @Volatile var status: Status = Status.Booting
        private set
    override val frame = AtomicReference<Fm1PanelSource.Frame?>(null)

    private sealed class Input {
        data class Key(val id: Int, val down: Boolean) : Input()
        data class Enc(val enc: Int, val clicks: Int) : Input()
        data class Pot(val value: Int) : Input()
        class Midi(val bytes: ByteArray) : Input()
    }

    private val inputs = ConcurrentLinkedQueue<Input>()
    @Volatile private var running = true
    private val thread = Thread(::loop, "fm1-emulator").apply { start() }

    override fun key(id: Int, down: Boolean) { inputs += Input.Key(id, down) }
    override fun turn(encoder: Int, clicks: Int) { if (clicks != 0) inputs += Input.Enc(encoder, clicks) }
    override fun pot(value: Int) { inputs += Input.Pot(value.coerceIn(0, Fm1Panel.POT_ADC_MAX)) }
    fun midi(bytes: ByteArray) { inputs += Input.Midi(bytes.copyOf()) }

    override fun close() {
        running = false
        thread.join(2_000)
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (!Fm1Native.available) {
            status = Status.Stopped("This build has no native emulator for this phone's CPU.")
            return
        }
        val saved = saveFile?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        var handle = Fm1Native.boot(pkg, saved, mhz, true)
        if (handle == 0L && saved != null) handle = Fm1Native.boot(pkg, null, mhz, true)
        if (handle == 0L) {
            status = Status.Stopped("The emulator could not unpack this package.")
            return
        }
        val track = openTrack()
        try {
            Fm1Native.pot(handle, Fm1Panel.POT_START)
            track.play()
            run(handle, track, saved)
        } catch (error: Throwable) {
            status = Status.Stopped(error.message ?: "The emulator stopped.")
        } finally {
            runCatching { track.stop() }
            track.release()
            persist(handle, null)
            Fm1Native.release(handle)
        }
    }

    private fun run(handle: Long, track: AudioTrack, saved: ByteArray?) {
        val block = ShortArray(2 * BLOCK_FRAMES)
        val leds = FloatArray(41)
        val scratch = IntArray(240 * 240)
        var lastRevision = -1
        var lastFrameAt = 0L
        var lastSaveAt = System.nanoTime()
        var lastSaved = saved
        var busyNanos = 0L
        var windowStart = System.nanoTime()
        var windowFrames = 0
        status = Status.Running(0f)
        while (running) {
            while (true) {
                when (val input = inputs.poll() ?: break) {
                    is Input.Key -> Fm1Native.key(handle, input.id, input.down)
                    is Input.Enc -> Fm1Native.enc(handle, input.enc, input.clicks)
                    is Input.Pot -> Fm1Native.pot(handle, input.value)
                    is Input.Midi -> input.bytes.forEach { Fm1Native.midi(handle, it.toInt() and 0xff) }
                }
            }
            val t0 = System.nanoTime()
            val made = Fm1Native.render(handle, block, MAX_INSNS_PER_BLOCK)
            busyNanos += System.nanoTime() - t0
            if (made < 0) {
                status = Status.Stopped("The firmware stopped (the emulator hit something it can't run).")
                return
            }
            track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            windowFrames += BLOCK_FRAMES
            val now = System.nanoTime()
            if (now - lastFrameAt >= FRAME_NANOS) {
                lastFrameAt = now
                Fm1Native.leds(handle, leds)
                val previous = frame.get()
                val revision = Fm1Native.lcd(handle, scratch)
                val lcd = if (revision == lastRevision && previous != null) previous.lcd else scratch.copyOf()
                frame.set(Fm1PanelSource.Frame(lcd, revision, leds.copyOf()))
                lastRevision = revision
            }
            if (now - windowStart >= 1_000_000_000L) {
                val audioNanos = windowFrames * 1_000_000_000L / SAMPLE_RATE
                status = Status.Running(busyNanos.toFloat() / audioNanos.coerceAtLeast(1))
                busyNanos = 0
                windowFrames = 0
                windowStart = now
            }
            if (now - lastSaveAt >= SAVE_NANOS && !Fm1Native.flashBusy(handle)) {
                lastSaveAt = now
                lastSaved = persist(handle, lastSaved)
            }
        }
    }

    /** Write the flash the firmware changed, if it differs from [previous]; returns what is saved. */
    private fun persist(handle: Long, previous: ByteArray?): ByteArray? {
        val file = saveFile ?: return previous
        val changes = Fm1Native.flashChanges(handle) ?: return previous
        if (changes.isEmpty() || (previous != null && previous.contentEquals(changes))) return previous
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(changes)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            changes
        }.getOrDefault(previous)
    }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, BUFFER_FRAMES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val BLOCK_FRAMES = 256
        private const val BUFFER_FRAMES = 2_048
        private const val MAX_INSNS_PER_BLOCK = 50_000_000
        private const val FRAME_NANOS = 33_000_000L
        private const val SAVE_NANOS = 3_000_000_000L

        fun saveFileFor(filesDir: File, packageSha256: String): File =
            File(File(filesDir, "emulator-saves"), "$packageSha256.fm1flash")
    }
}
