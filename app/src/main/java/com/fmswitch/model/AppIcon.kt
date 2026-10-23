package com.fmswitch.model

import androidx.compose.ui.graphics.Color

/**
 * Hardware body colours for the home-screen icon, sampled from M-VAVE's FM-1
 * plastic variants. Independent of phosphor COLOUR. [BLACK] is the shipped icon.
 */
enum class AppIcon(
    val id: String,
    val alias: String,
    val body: Color,
    val pad: Color,
    val key: Color,
    val isDefault: Boolean = false,
) {
    BLACK("black", "Black", Color(0xFF2A2C2B), Color(0xFF1A1C1C), Color(0xFFE8EFE9), isDefault = true),
    PURPLE("purple", "Purple", Color(0xFFBC94D8), Color(0xFF986DB7), Color(0xFFC9A5E6)),
    GREEN("green", "Green", Color(0xFF232223), Color(0xFF74F7AE), Color(0xFF74F7AE)),
    ORANGE("orange", "Orange", Color(0xFFD06840), Color(0xFF985040), Color(0xFF985040)),
    NAVY("navy", "Navy", Color(0xFFF0E8E0), Color(0xFF507090), Color(0xFF507090)),
    BROWN("brown", "Brown", Color(0xFFF8F9F4), Color(0xFF813A21), Color(0xFF813A21)),
    GRAY("gray", "Gray", Color(0xFFFFFFFF), Color(0xFF545160), Color(0xFF373646));

    /** Fully-qualified activity-alias in the manifest (`com.fmswitch.icon.Gray`). */
    val component: String get() = "com.fmswitch.icon.$alias"

    companion object {
        private val migratedIds = mapOf(
            "white" to GRAY.id,
            "cream" to BROWN.id,
            "rust" to BROWN.id,
            "mint" to GREEN.id,
            "slate" to NAVY.id,
        )

        fun fromId(id: String?): AppIcon {
            if (id.isNullOrBlank()) return BLACK
            val key = migratedIds[id.lowercase()] ?: id
            return entries.firstOrNull { it.id.equals(key, ignoreCase = true) } ?: BLACK
        }
    }
}

enum class IconComponentState { ENABLED, DISABLED, DEFAULT }

object AppIconSwitch {
    fun isOn(icon: AppIcon, state: IconComponentState): Boolean = when (state) {
        IconComponentState.ENABLED -> true
        IconComponentState.DISABLED -> false
        IconComponentState.DEFAULT -> icon.isDefault
    }

    fun enabledComponents(states: Map<AppIcon, IconComponentState>): Set<String> =
        AppIcon.entries
            .filter { isOn(it, states[it] ?: IconComponentState.DEFAULT) }
            .map { it.component }
            .toSet()

    /**
     * Enable [selected] and disable the other aliases. Null when the launcher
     * already matches, so a tap does not bounce the process.
     */
    fun plan(selected: AppIcon, enabled: Set<String>): Pair<String, List<String>>? {
        val wanted = selected.component
        if (enabled == setOf(wanted)) return null
        return wanted to AppIcon.entries.map { it.component }.filter { it != wanted }
    }
}
