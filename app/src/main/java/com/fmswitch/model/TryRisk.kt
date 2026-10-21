package com.fmswitch.model

/**
 * Whether a package has ever started in TRY, keyed by the sha256 of the `.fwsc` a flash would write.
 * The latest conclusive try wins: a failure flags the package, and a later try that does start clears it.
 * Backing out while the emulator is still booting leaves the package unknown.
 */
enum class TryVerdict { UNKNOWN, STARTED, FAILED }

class TryLedger(
    initial: Map<String, String> = emptyMap(),
    private val persist: (Map<String, String>) -> Unit = {},
) {
    private val stored = initial.mapKeys { it.key.lowercase() }.toMutableMap()

    fun verdict(sha: String): TryVerdict = when (stored[sha.lowercase()]) {
        STARTED_MARK -> TryVerdict.STARTED
        FAILED_MARK -> TryVerdict.FAILED
        else -> TryVerdict.UNKNOWN
    }

    /** No-ops when the stored mark is already [started], so a polling TRY screen can call this freely. */
    fun note(sha: String, started: Boolean) {
        val key = sha.lowercase()
        val mark = if (started) STARTED_MARK else FAILED_MARK
        if (stored[key] == mark) return
        stored[key] = mark
        persist(stored.toMap())
    }

    companion object {
        const val STARTED_MARK = "started"
        const val FAILED_MARK = "failed"
    }
}

fun encodeTryVerdicts(map: Map<String, String>): String =
    map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }

fun decodeTryVerdicts(text: String?): Map<String, String> =
    text.orEmpty().lineSequence().mapNotNull { line ->
        val cut = line.indexOf(' ')
        if (cut <= 0) null
        else {
            val sha = line.substring(0, cut).lowercase()
            val mark = line.substring(cut + 1)
            if (mark == TryLedger.STARTED_MARK || mark == TryLedger.FAILED_MARK) sha to mark else null
        }
    }.toMap()

/**
 * Packages a flash is about to write. Unknown ones still need a background TRY.
 * [failedNames] already failed a try and stay on the warning even before the probe finishes.
 */
data class FlashTryPlan(
    val probe: List<String>,
    val failedNames: List<String>,
)

fun planFlashTry(steps: List<Pair<String, String>>, verdict: (String) -> TryVerdict): FlashTryPlan {
    val probe = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for ((sha, name) in steps) {
        val key = sha.lowercase()
        if (!seen.add(key)) continue
        when (verdict(key)) {
            TryVerdict.UNKNOWN -> probe += key
            TryVerdict.FAILED -> failed += name
            TryVerdict.STARTED -> Unit
        }
    }
    return FlashTryPlan(probe, failed)
}

/** CONTINUE stays disabled until a risky package is acknowledged. A check still running cannot be skipped. */
fun flashCanProceed(
    checking: Boolean,
    risky: Boolean,
    understood: Boolean,
    missingOfficial: Boolean,
    switching: Boolean,
    backedUp: Boolean,
    autoBackup: Boolean,
    backupPending: Boolean = false,
): Boolean {
    if (checking || missingOfficial || backupPending) return false
    if (switching && !backedUp && !autoBackup) return false
    if (risky && !understood) return false
    return true
}

fun riskWarning(names: List<String>): String {
    val unique = names.distinct()
    val subject = when (unique.size) {
        0 -> "This firmware"
        1 -> unique.first()
        else -> unique.dropLast(1).joinToString(", ") + " and " + unique.last()
    }
    val pronoun = if (unique.size > 1) "them" else "it"
    return "$subject failed to start in the emulator, so it is extremely risky to flash $pronoun to your FM-1. Proceed at your own risk."
}
