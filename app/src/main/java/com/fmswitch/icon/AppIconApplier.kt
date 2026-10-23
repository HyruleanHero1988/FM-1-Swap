package com.fmswitch.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.fmswitch.model.AppIcon
import com.fmswitch.model.AppIconSwitch
import com.fmswitch.model.IconComponentState

/**
 * Turns one launcher activity-alias on and the others off. [MainActivity]
 * stays enabled; only the aliases that carry MAIN/LAUNCHER change.
 */
object AppIconApplier {
    fun apply(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        val pkg = context.packageName
        val states = AppIcon.entries.associateWith { entry ->
            when (pm.getComponentEnabledSetting(ComponentName(pkg, entry.component))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> IconComponentState.ENABLED
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> IconComponentState.DISABLED
                else -> IconComponentState.DEFAULT
            }
        }
        val plan = AppIconSwitch.plan(icon, AppIconSwitch.enabledComponents(states)) ?: return
        val flags = PackageManager.DONT_KILL_APP
        pm.setComponentEnabledSetting(
            ComponentName(pkg, plan.first),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            flags,
        )
        for (name in plan.second) {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, name),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                flags,
            )
        }
    }
}
