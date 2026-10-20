package com.fmswitch.emu

import kotlin.math.exp

/** How the phosphor panel's lines brighten instantly and fade like a scope's phosphor. */
object Afterglow {
    /** Seconds for a released key or dark LED to fall to about a third of its glow. */
    const val PERSISTENCE = 0.22f

    /** Seconds for a knob's sweep trail to catch up with the pointer. */
    const val SWEEP = 0.25f

    fun glow(previous: Float, target: Float, dt: Float): Float =
        if (target >= previous) target else maxOf(target, previous * exp(-dt / PERSISTENCE))

    fun follow(trail: Float, target: Float, dt: Float): Float =
        trail + (target - trail) * (1f - exp(-dt / SWEEP))
}
