package com.fmswitch.emu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * One emulated FM-1: a machine thread runs ahead into an [AudioRing], and a separate audio
 * thread drains that ring into an [AudioTrack] (low-latency / AAudio-backed when the device
 * offers it). The panel's input is queued to the machine thread; the screen and LEDs come back
 * as [Fm1PanelSource.Frame]s. Flash the firmware wrote is kept in [saveFile].
 *
 * With [autoClock], a sustained shortfall of real-time steps the emulated clock down
 * (240 → 200 → 160 → 120) and reboots from the current flash instead of stuttering.
 */
class Fm1Engine(
    private val pkg: ByteArray,
    private val saveFile: File?,
    val mhz: Int = EmulatorClock.DEFAULT,
    private val playInBackground: Boolean = false,
    /** A flash probe boots silently. It still renders, so a start is heard by the engine and not the room. */
    private val audible: Boolean = true,
    /** A DX7 32-voice bank written into slots 1–32 after any saved flash is restored. */
    private val bank: ByteArray? = null,
    /** When true, step the clock down automatically if this phone cannot keep real time. */
    private val autoClock: Boolean = false,
    private val appContext: Context? = null,
) : Fm1PanelSource, AutoCloseable {

    sealed class Status {
        data object Booting : Status()
        /** [live] is set once the firmware has made sound or drawn past its boot frame. */
        data class Running(val load: Float, val live: Boolean = false, val mhz: Int = EmulatorClock.DEFAULT) : Status()
        data class Stopped(val message: String) : Status()
    }

    @Volatile var status: Status = Status.Booting
        private set
    override val frame = AtomicReference<Fm1PanelSource.Frame?>(null)

    /** The clock this session is actually running at (may be lower than the requested [mhz] after auto steps). */
    @Volatile var runningMhz: Int = mhz
        private set

    private sealed class Input {
        data class Key(val id: Int, val down: Boolean) : Input()
        data class Enc(val enc: Int, val clicks: Int) : Input()
        data class Pot(val value: Int) : Input()
        class Midi(val bytes: ByteArray) : Input()
    }

    private val inputs = ConcurrentLinkedQueue<Input>()
    private val ring = AudioRing(RING_FRAMES)
    @Volatile private var running = true
    /** The engine this one replaced: stopped now, waited for before this one boots. */
    @Volatile private var previous: Fm1Engine? = takeOver()
    private val hint = appContext?.let { EngineHint(it) }
    private val machineThread = Thread(::machineLoop, "fm1-emulator").apply { start() }
    private var audioThread: Thread? = null

    override fun key(id: Int, down: Boolean) { inputs += Input.Key(id, down) }
    override fun turn(encoder: Int, clicks: Int) { if (clicks != 0) inputs += Input.Enc(encoder, clicks) }
    override fun pot(value: Int) { inputs += Input.Pot(value.coerceIn(0, Fm1Panel.POT_ADC_MAX)) }
    fun midi(bytes: ByteArray) {
        if (!running || bytes.isEmpty()) return
        inputs += Input.Midi(bytes.copyOf())
    }

    override fun close() {
        running = false
        machineThread.join(2_000)
        audioThread?.join(1_000)
        hint?.close()
    }

    private fun takeOver(): Fm1Engine? = synchronized(Companion) {
        val old = live
        live = this
        old?.running = false
        old
    }

    private fun machineLoop() {
        previous?.machineThread?.join()
        previous?.audioThread?.join()
        previous = null
        try {
            if (running) boot()
        } finally {
            synchronized(Companion) { if (live === this) live = null }
        }
    }

    private fun boot() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        hint?.attach(Process.myTid(), HINT_TARGET_NANOS)
        if (!Fm1Native.available) {
            status = Status.Stopped("This phone can't run the virtual FM-1.")
            return
        }
        var saved = saveFile?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        val bankBytes = bank?.takeIf { it.isNotEmpty() }
        var clock = if (autoClock) EmulatorClock.DEFAULT else mhz
        runningMhz = clock
        while (running) {
            ring.clear()
            var handle = bootNative(saved, bankBytes, clock)
            if (handle == 0L && saved != null) {
                saved = null
                handle = bootNative(null, bankBytes, clock)
            }
            if (handle == 0L) {
                status = Status.Stopped("This firmware couldn't be opened.")
                return
            }
            val track = openTrack()
            if (!audible) track.setVolume(0f)
            try {
                Fm1Native.pot(handle, Fm1Panel.POT_START)
                track.play()
                val audio = Thread({ drain(track) }, "fm1-audio").also {
                    audioThread = it
                    it.priority = Thread.MAX_PRIORITY
                    it.start()
                }
                val next = runMachine(handle, saved, clock)
                // close() clears [running]; only reboot for an auto step when the user didn't stop us.
                val step = next?.takeIf { running }
                running = false
                audio.join(1_000)
                if (step != null) {
                    saved = step.first
                    clock = step.second
                    runningMhz = clock
                    running = true
                    continue
                }
            } catch (error: Throwable) {
                status = Status.Stopped(error.message ?: "The virtual FM-1 stopped.")
            } finally {
                runCatching { track.stop() }
                track.release()
                persist(handle, null)
                Fm1Native.release(handle)
                audioThread = null
            }
            return
        }
    }

    private fun bootNative(saved: ByteArray?, bankBytes: ByteArray?, clock: Int): Long = if (bankBytes != null) {
        Fm1Native.bootWithBank(pkg, saved, bankBytes, clock, true)
    } else {
        Fm1Native.boot(pkg, saved, clock, true)
    }

    /**
     * Run until stopped. Returns `(flash, nextMhz)` when the automatic clock should reboot
     * at a lower rate; null when the session ends for any other reason.
     */
    private fun runMachine(handle: Long, saved: ByteArray?, clock: Int): Pair<ByteArray?, Int>? {
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
        var emulatedNanos = 0L
        var slowSince = 0L
        val bootAt = System.nanoTime()
        val budget = clock * INSNS_PER_MHZ_PER_BLOCK
        var started = false
        val bootRevision = Fm1Native.lcd(handle, scratch, -1)
        status = Status.Running(0f, mhz = clock)
        while (running) {
            while (true) {
                when (val input = inputs.poll() ?: break) {
                    is Input.Key -> Fm1Native.key(handle, input.id, input.down)
                    is Input.Enc -> Fm1Native.enc(handle, input.enc, input.clicks)
                    is Input.Pot -> Fm1Native.pot(handle, input.value)
                    is Input.Midi -> input.bytes.forEach { Fm1Native.midi(handle, it.toInt() and 0xff) }
                }
            }
            // Cap lead so a key press is heard soon; keep RING_FRAMES larger only for bursts.
            if (ring.available() >= RING_TARGET_FRAMES) {
                Thread.sleep(1)
                continue
            }
            val t0 = System.nanoTime()
            val made = Fm1Native.render(handle, block, budget)
            val worked = System.nanoTime() - t0
            busyNanos += worked
            hint?.report(worked)
            if (made < 0) {
                status = Status.Stopped("This firmware stopped. The virtual FM-1 can't run this part of it.")
                return null
            }
            val frames = if (made > 0) made else BLOCK_FRAMES
            ring.push(block, frames)
            windowFrames += frames
            emulatedNanos += frames * 1_000_000_000L / SAMPLE_RATE
            if (!started) {
                started = made > 0 || lastRevision.let { it != -1 && it != bootRevision }
                if (started) status = Status.Running(0f, live = true, mhz = clock)
                if (!started && Fm1Native.ms(handle) >= NEVER_STARTED_MS) {
                    status = Status.Stopped(NEVER_STARTED)
                    return null
                }
            }
            val now = System.nanoTime()
            if (now - lastFrameAt >= FRAME_NANOS) {
                lastFrameAt = now
                Fm1Native.leds(handle, leds)
                val previous = frame.get()
                val known = if (previous != null) lastRevision else -1
                val revision = Fm1Native.lcd(handle, scratch, known)
                val lcd = if (revision == known && previous != null) previous.lcd else scratch.copyOf()
                frame.set(Fm1PanelSource.Frame(lcd, revision, leds.copyOf()))
                lastRevision = revision
            }
            if (now - windowStart >= 1_000_000_000L) {
                val audioNanos = windowFrames * 1_000_000_000L / SAMPLE_RATE
                val load = busyNanos.toFloat() / audioNanos.coerceAtLeast(1)
                status = Status.Running(load, live = started, mhz = clock)
                if (autoClock && started && now - bootAt >= EmulatorClock.WARMUP_MS * 1_000_000L) {
                    val speed = emulatedNanos.toFloat() / busyNanos.coerceAtLeast(1)
                    if (speed < EmulatorClock.SLOW_SPEED && ring.available() < BLOCK_FRAMES) {
                        if (slowSince == 0L) slowSince = now
                        if (now - slowSince >= EmulatorClock.SLOW_MS * 1_000_000L) {
                            val next = EmulatorClock.nextAuto(clock) ?: return null
                            val flash = Fm1Native.flashChanges(handle) ?: lastSaved
                            return flash to next
                        }
                    } else {
                        slowSince = 0L
                    }
                }
                busyNanos = 0
                emulatedNanos = 0
                windowFrames = 0
                windowStart = now
            }
            if (now - lastSaveAt >= SAVE_NANOS && !Fm1Native.flashBusy(handle)) {
                lastSaveAt = now
                lastSaved = persist(handle, lastSaved)
            }
        }
        return null
    }

    private fun drain(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ShortArray(2 * BLOCK_FRAMES)
        while (running) {
            val have = ring.pull(block)
            if (have == 0) {
                // Stay silent without spinning the CPU while the machine is catching up.
                Thread.sleep(1)
                continue
            }
            // Write only real frames; pull() zero-pads the rest of [block].
            track.write(block, 0, have * 2, AudioTrack.WRITE_BLOCKING)
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
                    .setUsage(if (playInBackground) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_GAME)
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
        /** The newest engine; at most one emulated FM-1 runs at a time. */
        private var live: Fm1Engine? = null

        const val SAMPLE_RATE = 44_100
        private const val BLOCK_FRAMES = 256
        /** AudioTrack size floor (~12 ms); device min may be larger. */
        private const val BUFFER_FRAMES = 512
        /** Burst capacity (~93 ms). Steady lead is [RING_TARGET_FRAMES], not this. */
        private const val RING_FRAMES = 4_096
        /** Steady-state lead (~23 ms). Full RING_FRAMES would delay key→sound by ~90 ms+. */
        private const val RING_TARGET_FRAMES = 1_024
        /** At most 20 ms of emulated time per block, so a firmware that stalls its audio costs
         *  bounded work instead of the whole CPU. */
        private const val INSNS_PER_MHZ_PER_BLOCK = 20_000
        private const val NEVER_STARTED_MS = 6_000L
        /** Target ~6 ms of work per ~10 ms of audio for the ADPF hint session. */
        private const val HINT_TARGET_NANOS = 6_000_000L
        const val NEVER_STARTED =
            "This firmware didn't start here. It made no sound and drew nothing. A real FM-1 may still run it. " +
                "If the listing has a browser player, that link is on the firmware's page."
        private const val FRAME_NANOS = 33_000_000L
        private const val SAVE_NANOS = 3_000_000_000L

        fun saveFileFor(filesDir: File, packageSha256: String): File =
            File(File(filesDir, "emulator-saves"), "$packageSha256.fm1flash")
    }
}
