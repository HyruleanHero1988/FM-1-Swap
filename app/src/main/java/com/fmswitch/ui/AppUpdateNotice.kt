package com.fmswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fmswitch.model.AppViewModel
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.PixelButton
import com.fmswitch.update.AppUpdatePhase
import com.fmswitch.update.UPDATE_PERMISSION_COPY
import com.fmswitch.update.displayVersion

@Composable
fun AppUpdateNotice(model: AppViewModel, inSettings: Boolean = false) {
    val offer = model.appUpdate ?: return
    val phase = model.appUpdatePhase
    val idleAndDismissed = !inSettings &&
        model.appUpdateDismissed == offer.version &&
        phase is AppUpdatePhase.Idle
    if (idleAndDismissed) return
    val theme = LocalPhosphor.current
    val message = updateMessage(offer.version, phase)
    val busy = phase is AppUpdatePhase.Installing || phase is AppUpdatePhase.Downloading
    if (inSettings) {
        MonoText(message, size = 14)
        Spacer(Modifier.height(8.dp))
        if (busy) {
            PixelButton("LATER") { model.dismissAppUpdate() }
        } else {
            UpdateActions(model, phase, fill = true)
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MonoText(message, size = 13)
        Spacer(Modifier.height(8.dp))
        UpdateActions(model, phase, fill = false)
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(theme.dim))
}

@Composable
private fun UpdateActions(model: AppViewModel, phase: AppUpdatePhase, fill: Boolean) {
    if (fill) {
        PrimaryUpdateButton(model, phase, compact = false)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryUpdateButton(model, phase, compact = true)
        PixelButton("LATER", compact = true) { model.dismissAppUpdate() }
    }
}

@Composable
private fun PrimaryUpdateButton(model: AppViewModel, phase: AppUpdatePhase, compact: Boolean) {
    when (phase) {
        AppUpdatePhase.NeedsPermission ->
            PixelButton("ALLOW", compact = compact) { model.startAppUpdate() }
        AppUpdatePhase.Downloading, AppUpdatePhase.Installing -> Unit
        else -> PixelButton("UPDATE", compact = compact) { model.startAppUpdate() }
    }
}

private fun updateMessage(version: String, phase: AppUpdatePhase): String {
    val shown = displayVersion(version)
    return when (phase) {
        AppUpdatePhase.Idle -> "$shown is ready."
        AppUpdatePhase.Downloading -> "Downloading $shown."
        AppUpdatePhase.NeedsPermission -> UPDATE_PERMISSION_COPY
        AppUpdatePhase.Installing -> "Confirm the install when Android asks."
        is AppUpdatePhase.Failed -> phase.message
    }
}
