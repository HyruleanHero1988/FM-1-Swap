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
    WHITE("white", "White", Color(0xFFE6E4DE), Color(0xFF2A2C2E), Color(0xFF242426)),
    CREAM("cream", "Cream", Color(0xFFD5C9B4), Color(0xFF2C2824), Color(0xFF2A2622)),
    RUST("rust", "Rust", Color(0xFF7A4336), Color(0xFF2E201C), Color(0xFFEEE4DC)),
    MINT("mint", "Mint", Color(0xFF6BC8B6), Color(0xFF2A5852), Color(0xFF1A3230)),
    ORANGE("orange", "Orange", Color(0xFFC05634), Color(0xFF3A2218), Color(0xFFF2E6D8)),
    PURPLE("purple", "Purple", Color(0xFFA57AB8), Color(0xFF583E74), Color(0xFFF2EAF6)),
    SLATE("slate", "Slate", Color(0xFF56728C), Color(0xFF2A3A4C), Color(0xFFE8ECF0));

    /** Fully-qualified activity-alias in the manifest (`com.fmswitch.icon.White`). */
    val component: String get() = "com.fmswitch.icon.$alias"

    companion object {
        fun fromId(id: String?): AppIcon =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BLACK
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
