package com.fmswitch.emu

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import java.util.concurrent.atomic.AtomicReference

/**
 * ADPF [PerformanceHintManager] session for the emulator thread (API 31+).
 * Asks the system to keep that thread on a big core while TRY is making audio.
 */
class EngineHint(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val session = AtomicReference<PerformanceHintManager.Session?>(null)

    fun attach(threadId: Int, targetWorkNanos: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        val manager = app.getSystemService(PerformanceHintManager::class.java) ?: return
        runCatching {
            val created = manager.createHintSession(intArrayOf(threadId), targetWorkNanos)
            session.getAndSet(created)?.close()
        }
    }

    fun report(actualWorkNanos: Long) {
        if (actualWorkNanos <= 0) return
        session.get()?.reportActualWorkDuration(actualWorkNanos)
    }

    override fun close() {
        session.getAndSet(null)?.close()
    }
}
