package com.fmswitch.emu

/**
 * `libfm1emu.so` (native/fm1-jni): fm1-emu's machine. A handle belongs to the one thread
 * that booted it; [Fm1Engine] is that thread.
 */
object Fm1Native {
    val available: Boolean = runCatching { System.loadLibrary("fm1emu") }.isSuccess

    @JvmStatic external fun boot(pkg: ByteArray, saved: ByteArray?, mhz: Int, jit: Boolean): Long
    @JvmStatic external fun release(handle: Long)
    @JvmStatic external fun render(handle: Long, out: ShortArray, maxInsns: Int): Int
    @JvmStatic external fun lcd(handle: Long, out: IntArray): Int
    @JvmStatic external fun leds(handle: Long, out: FloatArray)
    @JvmStatic external fun key(handle: Long, id: Int, down: Boolean)
    @JvmStatic external fun enc(handle: Long, enc: Int, clicks: Int)
    @JvmStatic external fun pot(handle: Long, value: Int)
    @JvmStatic external fun midi(handle: Long, byte: Int)
    @JvmStatic external fun board(handle: Long): Int
    @JvmStatic external fun ms(handle: Long): Long
    @JvmStatic external fun flashBusy(handle: Long): Boolean
    @JvmStatic external fun flashChanges(handle: Long): ByteArray?
}
