package com.fmswitch.model

import com.fmswitch.catalogue.NewChipSupport

/**
 * Which FM-1 the user says they have. The flash chip cannot be read over USB MIDI (see the README), so the
 * app cannot tell by itself. [UNSURE] is the default and is treated like [NEWER]: warn.
 */
enum class UnitGeneration(val id: String, val label: String) {
    UNSURE("unsure", "NOT SURE"),
    NEWER("newer", "NEWER"),
    OLDER("older", "OLDER"),
    ;

    /** Warnings about the newer chip are for everyone who has not said their FM-1 is one of the first units. */
    val needsNewChipCare: Boolean get() = this != OLDER

    companion object {
        fun fromId(id: String?): UnitGeneration = entries.firstOrNull { it.id == id } ?: UNSURE
    }
}

/**
 * What a flash is about to write, as far as newer FM-1s go. [bricking] names the packages known to fail on the
 * newer chip: those need the user to say they accept the risk. [untested] names packages nobody has confirmed
 * there: those get a warning, nothing more.
 */
data class NewChipGate(
    val bricking: List<String> = emptyList(),
    val untested: List<String> = emptyList(),
) {
    val needsAcknowledgement: Boolean get() = bricking.isNotEmpty()
    val warns: Boolean get() = bricking.isNotEmpty() || untested.isNotEmpty()
}

fun newChipGate(steps: List<Pair<String, NewChipSupport>>, unit: UnitGeneration): NewChipGate {
    if (!unit.needsNewChipCare) return NewChipGate()
    val bricking = steps.filter { it.second == NewChipSupport.BRICKS }.map { it.first }.distinct()
    val untested = steps.filter { it.second == NewChipSupport.UNKNOWN }.map { it.first }.distinct()
    return NewChipGate(bricking, untested)
}

/** False while a package known to fail on newer FM-1s is waiting for the user's explicit yes. */
fun newChipFlashAllowed(gate: NewChipGate, acknowledged: Boolean): Boolean =
    !gate.needsAcknowledgement || acknowledged

private fun joinNames(names: List<String>): String {
    val unique = names.distinct()
    return when (unique.size) {
        0 -> "This firmware"
        1 -> unique.first()
        else -> unique.dropLast(1).joinToString(", ") + " and " + unique.last()
    }
}

fun newChipBrickWarning(names: List<String>): String {
    val plural = names.distinct().size > 1
    val subject = joinNames(names)
    val verb = if (plural) "are" else "is"
    val pronoun = if (plural) "They" else "It"
    val object_ = if (plural) "them" else "it"
    return "$subject $verb known to fail on newer FM-1s (flash chip 0xB36014). $pronoun won't save there, " +
        "and often can't take another update, which can leave the FM-1 stuck until you recover it from a computer. " +
        "If your FM-1 is a newer one, don't flash $object_."
}

fun newChipUntestedWarning(names: List<String>): String {
    val plural = names.distinct().size > 1
    val subject = joinNames(names)
    return "$subject ${if (plural) "have" else "has"} not been confirmed on newer FM-1s (flash chip 0xB36014). " +
        "On a newer FM-1 a firmware that does not know that chip loses its saves and can refuse updates. " +
        "Prefer one marked SAVES ON NEWER FM-1S if yours is a newer one."
}
