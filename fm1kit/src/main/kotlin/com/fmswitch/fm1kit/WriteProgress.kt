package com.fmswitch.fm1kit

import java.util.BitSet

internal class WriteProgress(private val region: IntRange) {
    private val written = BitSet()
    private val checked = BitSet()
    private val rereads = ArrayList<IntRange>()
    private var requests = 0
    private var isChecking = false
    private var lastEvent: UpdateEvent? = null

    fun record(request: FM1ReadRequest): UpdateEvent {
        val start = request.address.toInt()
        val rawEnd = start + request.length
        val rangeStart = maxOf(start, region.first)
        val rangeEnd = minOf(rawEnd, region.last + 1)
        requests += 1
        if (rangeStart >= rangeEnd) return repeatedEvent()

        val isNew = !containsAll(written, rangeStart, rangeEnd)
        if (isChecking && isNew) {
            isChecking = false
            checked.clear()
            requests = 1
        }
        if (isChecking) {
            checked.set(rangeStart, rangeEnd)
            return report(checkingEvent())
        }

        val mightBeChecking = !isNew &&
            request.length == FM1Message.MAXIMUM_READ_LENGTH &&
            writtenFraction() >= CHECKING_THRESHOLD
        if (mightBeChecking) {
            rereads.add(rangeStart until rangeEnd)
        } else {
            rereads.clear()
        }
        if (rereads.size >= REREADS_BEFORE_CHECKING) {
            isChecking = true
            requests = rereads.size
            for (reread in rereads) checked.set(reread.first, reread.last + 1)
            rereads.clear()
            return report(checkingEvent())
        }
        written.set(rangeStart, rangeEnd)
        return report(UpdateEvent.Writing(writtenFraction(), requests))
    }

    private fun writtenFraction(): Double {
        val count = region.count()
        if (count == 0) return 1.0
        return minOf(written.cardinality().toDouble() / count, 1.0)
    }

    private fun checkingEvent(): UpdateEvent {
        val writtenCount = written.cardinality().coerceAtLeast(1)
        return UpdateEvent.Checking(minOf(checked.cardinality().toDouble() / writtenCount, 1.0), requests)
    }

    private fun repeatedEvent(): UpdateEvent = when (val last = lastEvent) {
        is UpdateEvent.Checking -> UpdateEvent.Checking(last.fraction, requests)
        is UpdateEvent.Writing -> UpdateEvent.Writing(last.fraction, requests)
        else -> UpdateEvent.Writing(0.0, requests)
    }

    private fun report(event: UpdateEvent): UpdateEvent {
        lastEvent = event
        return event
    }

    private fun containsAll(bits: BitSet, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            if (!bits.get(i)) return false
            i++
        }
        return true
    }

    companion object {
        const val CHECKING_THRESHOLD = 0.5
        const val REREADS_BEFORE_CHECKING = 3
    }
}
