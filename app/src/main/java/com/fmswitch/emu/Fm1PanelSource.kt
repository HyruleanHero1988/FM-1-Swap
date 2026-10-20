package com.fmswitch.emu

import java.util.concurrent.atomic.AtomicReference

/** What the panel draws from and plays into: [Fm1Engine], or a still frame in tests. */
interface Fm1PanelSource {
    class Frame(val lcd: IntArray, val revision: Int, val leds: FloatArray)

    val frame: AtomicReference<Frame?>
    fun key(id: Int, down: Boolean)
    fun turn(encoder: Int, clicks: Int)
    fun pot(value: Int)
}
