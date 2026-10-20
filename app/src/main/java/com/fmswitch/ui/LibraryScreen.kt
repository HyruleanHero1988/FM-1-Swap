package com.fmswitch.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fmswitch.fm1kit.supportsBaudGirlBackup
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.BackupRestore
import com.fmswitch.model.StoredFirmware
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.PixelProgressBar
import com.fmswitch.theme.PixelTag
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.formattedBytes
import com.fmswitch.theme.pixelText

@Composable
fun LibraryScreen(model: AppViewModel) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var flashing by remember { mutableStateOf<StoredFirmware?>(null) }
    @Suppress("UNUSED_EXPRESSION")
    model.libraryTick
    @Suppress("UNUSED_EXPRESSION")
    model.backupTick
    BackHandler(enabled = confirmingClear) { confirmingClear = false }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".fwsc") ?: "Imported"
        model.importFirmware(bytes, name).fold(
            onSuccess = { item ->
                message = if (item.source == "official") {
                    "Official firmware verified and saved."
                } else {
                    "Saved ${item.name} (${item.product}). It isn't in the catalogue, so check where it came from."
                }
            },
            onFailure = { message = it.message },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("SAVED") {
            PixelButton("IMPORT", compact = true) {
                picker.launch(arrayOf("*/*"))
            }
        }
        NoteText("TRY plays a saved package on the virtual FM-1. FLASH writes it to a connected FM-1. Packages stay on the phone. M-VAVE's V15 is bundled and kept even if you clear the rest.")
        message?.let { MonoText(it, size = 14) }
        model.library.sortedItems.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(theme.surface)
                    .padding(12.dp),
            ) {
                Row {
                    BasicText(item.name.uppercase(), style = pixelText(13.sp, bold = true, color = theme.bright), modifier = Modifier.weight(1f))
                    when (item.source) {
                        "official" -> PixelTag("OFFICIAL")
                        "imported" -> PixelTag("IMPORT", inverted = false)
                        else -> PixelTag("SAVED")
                    }
                }
                MonoText("${item.version}  →  ${item.product}  ·  ${formattedBytes(item.size)}", size = 13, dim = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("TRY", compact = true, prominent = true) { model.trySaved(item) }
                    PixelButton("FLASH", compact = true, prominent = true) { flashing = item }
                    if (item.source != "official") {
                        PixelButton("REMOVE", compact = true) { model.remove(item) }
                    }
                }
            }
        }
        if (model.backups.items.isNotEmpty()) {
            PixelPanel(title = "BACKUPS") {
                NoteText("Taken automatically before a flash: presets, patterns, and a copy of the running firmware when this phone already had that .fwsc.")
                model.backups.items.forEach { backup ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(theme.surface)
                            .padding(10.dp),
                    ) {
                        BasicText(backup.title.uppercase(), style = pixelText(12.sp, bold = true, color = theme.bright))
                        MonoText(backup.summary, size = 13, dim = true)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (backup.presetsFile != null) {
                                PixelButton("RESTORE", compact = true, prominent = true, enabled = !model.isWriting) {
                                    model.restoreBackup(backup, presets = true, patterns = true)
                                }
                            }
                            PixelButton("REMOVE", compact = true) { model.removeBackup(backup) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        PixelPanel(title = "CACHE") {
            MonoText("${model.library.items.size} packages  ·  ${formattedBytes(model.library.items.sumOf { it.size })}", size = 14)
            PixelButton("CLEAR SAVED FIRMWARES") { confirmingClear = true }
            if (confirmingClear) {
                NoteText("This keeps bundled V15 and deletes the rest.")
                PixelButton("CONFIRM CLEAR", prominent = true) {
                    model.clearLibrary()
                    confirmingClear = false
                }
            }
        }
    }
    if (model.backupRestore !is BackupRestore.Idle) {
        BackupRestoreSheet(model) { model.dismissBackupRestore() }
    }
    flashing?.let { item ->
        val requires = item.catalogueID?.let { model.catalogue.entry(it)?.requiresOfficialFirmware } ?: false
        FlashConfirmSheet(model, item, requires) {
            flashing = null
            if (!model.isWriting) model.scan(force = true)
        }
    }
}

@Composable
fun FlashConfirmSheet(
    model: AppViewModel,
    target: StoredFirmware,
    requiresOfficial: Boolean,
    onDismiss: () -> Unit,
) {
    val theme = LocalPhosphor.current
    val steps = model.plan(target, requiresOfficial)
    var checkedKind by remember { mutableStateOf<com.fmswitch.fm1kit.InstallKind?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var backedUp by remember { mutableStateOf(false) }
    val missingOfficial = (requiresOfficial || model.settings.alwaysViaOfficial) &&
        target.source != "official" && model.library.official == null
    androidx.compose.runtime.LaunchedEffect(steps.firstOrNull()?.id, model.identity?.text) {
        val first = steps.firstOrNull() ?: return@LaunchedEffect
        if (model.identity?.isLoader == true) {
            checkedKind = null
            return@LaunchedEffect
        }
        runCatching { model.check(first) }
            .onSuccess { checkedKind = it.kind }
            .onFailure { checkError = it.message }
    }
    val switching = checkedKind == com.fmswitch.fm1kit.InstallKind.SWITCHING_FIRMWARE
    val sameIdentity = checkedKind == com.fmswitch.fm1kit.InstallKind.SAME_IDENTITY
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(theme.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                when {
                    switching -> "SWITCH FIRMWARE?"
                    sameIdentity -> "REINSTALL OR UPDATE?"
                    else -> "FLASH?"
                },
            )
            steps.forEachIndexed { index, step ->
                MonoText("${index + 1}. ${step.name} ${step.version}", size = 17)
            }
            if (missingOfficial) {
                MonoText("This needs the official firmware first, and none is saved. Add it under SAVED.", size = 14)
            }
            if (model.identity?.isLoader == true) {
                NoteText("The FM-1 is in its update loader, so this finishes that update.")
            }
            checkError?.let { MonoText(it, size = 14) }
            val autoBackup = model.identity?.supportsBaudGirlBackup == true && model.identity?.isLoader != true
            if (autoBackup) {
                NoteText("Before writing, FM-SWITCH will save the 128 presets and 16 patterns, the same dump Baud Girl's Device Manager uses, plus a copy of this firmware if this phone already has that .fwsc.")
            } else if (switching) {
                MonoText("Switching firmware can erase the FM-1's projects and samples. Back them up first.", size = 14)
                com.fmswitch.theme.PixelToggle("I'VE BACKED UP", backedUp) { backedUp = it }
            }
            NoteText("Takes a minute or two per step. Stay in the app until it finishes.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                PixelButton(
                    when {
                        switching -> "SWITCH"
                        sameIdentity -> "INSTALL"
                        else -> "START"
                    },
                    modifier = Modifier.weight(1f),
                    prominent = true,
                    enabled = !missingOfficial && (!switching || backedUp || autoBackup),
                ) {
                    model.flash(
                        steps,
                        confirmsSwitchingFirmware = switching && (backedUp || autoBackup),
                        confirmsSameIdentity = sameIdentity,
                    )
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun BackupRestoreSheet(model: AppViewModel, onDismiss: () -> Unit) {
    val theme = LocalPhosphor.current
    Dialog(
        onDismissRequest = {
            if (!model.backupRestore.isRunning) {
                model.dismissBackupRestore()
                onDismiss()
            }
        },
    ) {
        Column(
            Modifier
                .background(theme.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("RESTORE BACKUP")
            when (val state = model.backupRestore) {
                BackupRestore.Idle -> MonoText("Nothing to restore.", size = 14)
                is BackupRestore.Writing -> {
                    MonoText("Writing ${state.label.lowercase()} back to the FM-1…", size = 15)
                    PixelProgressBar(state.fraction.toFloat())
                    NoteText("Keep the cable connected. Preset writes are paced so the FM-1 can store each one.")
                }
                BackupRestore.Succeeded -> MonoText(
                    "The FM-1 now holds the presets and patterns from that backup.",
                    size = 15,
                )
                is BackupRestore.Failed -> MonoText(state.message, size = 15)
            }
            if (!model.backupRestore.isRunning) {
                PixelButton("CLOSE", prominent = true) {
                    model.dismissBackupRestore()
                    onDismiss()
                }
            }
        }
    }
}
