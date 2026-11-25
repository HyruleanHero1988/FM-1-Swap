package com.fmswitch.emu

import java.util.concurrent.atomic.AtomicInteger

/**
 * A lock-free stereo PCM ring: the emulator fills it ahead of the audio drain.
 * Frames are interleaved left/right i16. One producer and one consumer.
 */
class AudioRing(capacityFrames: Int) {
    private val capacity = capacityFrames.coerceAtLeast(2)
    private val data = ShortArray(capacity * 2)
    private val write = AtomicInteger(0)
    private val read = AtomicInteger(0)

    /** How many frames the producer may still push. */
    fun free(): Int {
        val w = write.get()
        val r = read.get()
        return capacity - 1 - ((w - r + capacity) % capacity)
    }

    /** How many frames the consumer may still pull. */
    fun available(): Int {
        val w = write.get()
        val r = read.get()
        return (w - r + capacity) % capacity
    }

    /** Push up to [frames] stereo frames from [src] (interleaved). Returns frames written. */
    fun push(src: ShortArray, frames: Int): Int {
        val n = frames.coerceAtMost(free()).coerceAtMost(src.size / 2)
        if (n <= 0) return 0
        var w = write.get()
        for (i in 0 until n) {
            val o = w * 2
            val s = i * 2
            data[o] = src[s]
            data[o + 1] = src[s + 1]
            w++
            if (w == capacity) w = 0
        }
        write.set(w)
        return n
    }

    /**
     * Pull up to [out].size/2 frames into [out]. Missing frames are silence.
     * Returns how many frames were real audio (the rest of [out] is zeroed).
     */
    fun pull(out: ShortArray): Int {
        val want = out.size / 2
        val have = available().coerceAtMost(want)
        var r = read.get()
        for (i in 0 until have) {
            val o = i * 2
            val s = r * 2
            out[o] = data[s]
            out[o + 1] = data[s + 1]
            r++
            if (r == capacity) r = 0
        }
        for (i in have until want) {
            out[i * 2] = 0
            out[i * 2 + 1] = 0
        }
        read.set(r)
        return have
    }

    fun clear() {
        read.set(0)
        write.set(0)
    }
}
