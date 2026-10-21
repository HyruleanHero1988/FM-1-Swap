package com.fmswitch.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fmswitch.fm1kit.mightHaveEditorBackup
import com.fmswitch.fm1kit.supportsBaudGirlBackup
import com.fmswitch.model.BackupOffer
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.BackupRestore
import com.fmswitch.model.FlashProbe
import com.fmswitch.model.StoredFirmware
import com.fmswitch.model.flashCanProceed
import com.fmswitch.model.riskWarning
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelBox
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
import com.fmswitch.theme.PixelProgressBar
import com.fmswitch.theme.PixelTag
import com.fmswitch.theme.PixelToggle
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.formattedBytes
import com.fmswitch.theme.monoText
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
                NoteText("Taken automatically before a flash, when the running firmware allows it: FM-1+VA presets and patterns, or a Felucca or SLOOP editor backup, plus a copy of that firmware when this phone already had the .fwsc.")
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
                            if (backup.presetsFile != null || backup.archiveFile != null) {
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
    val stepKey = steps.joinToString("|") { it.sha256.lowercase() }
    androidx.compose.runtime.DisposableEffect(stepKey, model.identity?.text) {
        model.prepareBackupOffer()
        model.beginFlashProbe(steps)
        onDispose {
            model.cancelFlashProbe()
            model.cancelBackupProbe()
        }
    }
    val probe = model.flashProbe
    val failed = (probe as? FlashProbe.Settled)?.failed.orEmpty()
    var understood by remember(failed) { mutableStateOf(false) }
    val backupPending = model.backupOffer is BackupOffer.Checking ||
        (model.backupOffer is BackupOffer.Unknown && model.identity?.mightHaveEditorBackup == true)
    val checking = probe is FlashProbe.Idle || probe is FlashProbe.Checking || backupPending
    androidx.compose.runtime.LaunchedEffect(stepKey, model.identity?.text) {
        val first = steps.firstOrNull()
        if (model.identity?.isLoader == true) {
            checkedKind = null
        } else if (first != null) {
            runCatching { model.check(first) }
                .onSuccess { checkedKind = it.kind }
                .onFailure { checkError = it.message }
        }
        model.finishBackupOffer()
    }
    val switching = checkedKind == com.fmswitch.fm1kit.InstallKind.SWITCHING_FIRMWARE
    val sameIdentity = checkedKind == com.fmswitch.fm1kit.InstallKind.SAME_IDENTITY
    val sheetCap = (LocalConfiguration.current.screenHeightDp - 48).dp
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetCap)
                .background(theme.background)
                .verticalScroll(rememberScrollState())
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
            val autoBackup = model.identity?.supportsBaudGirlBackup == true || model.backupOffer.auto
            when {
                model.backupOffer is BackupOffer.Checking || backupPending -> MonoText(
                    "Seeing whether this firmware can be backed up from the FM-1…",
                    size = 15,
                )
                model.identity?.supportsBaudGirlBackup == true -> NoteText(
                    "Before writing, FM-SWITCH will save the 128 presets and 16 patterns, the same dump Baud Girl's Device Manager uses, plus a copy of this firmware if this phone already has that .fwsc.",
                )
                model.backupOffer is BackupOffer.Felucca -> NoteText(
                    "Before writing, FM-SWITCH will save a Felucca-style backup: the music now playing, the projects, user presets, settings and samples. That backup includes the FM6 voice bank only when the firmware lists it. Felucca itself leaves the bank out, so keep its .syx if you use one.",
                )
                model.backupOffer is BackupOffer.Sloop -> NoteText(
                    "Before writing, FM-SWITCH will save a SLOOP-style backup: projects, presets, the FM6 bank, samples and settings, the same file the SLOOP web editor writes.",
                )
                switching -> {
                    MonoText(
                        "This firmware has no backup this app can take. Switching can erase its projects and samples. Back them up in its own editor first.",
                        size = 14,
                    )
                    com.fmswitch.theme.PixelToggle("I'VE BACKED UP", backedUp) { backedUp = it }
                }
            }
            NoteText("Takes a minute or two per step. Stay in the app until it finishes.")
            when (probe) {
                FlashProbe.Idle, is FlashProbe.Checking -> {
                    MonoText(
                        if (probe is FlashProbe.Checking) "Checking ${probe.name} in the emulator…"
                        else "Checking this firmware in the emulator…",
                        size = 15,
                    )
                    PixelProgressBar(null)
                    NoteText("A package TRY has never settled is booted silently before it can be written. Cancel if you don't want to wait.")
                }
                is FlashProbe.Settled -> {
                    probe.note?.let { MonoText(it, size = 14) }
                    if (probe.failed.isNotEmpty()) {
                        RiskNotice(probe.failed, understood) { understood = it }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton("CANCEL", modifier = Modifier.weight(1f), onClick = onDismiss)
                PixelButton(
                    when {
                        checking -> "CHECKING"
                        failed.isNotEmpty() -> "CONTINUE"
                        switching -> "SWITCH"
                        sameIdentity -> "INSTALL"
                        else -> "START"
                    },
                    modifier = Modifier.weight(1f),
                    prominent = true,
                    enabled = flashCanProceed(
                        checking = checking,
                        risky = failed.isNotEmpty(),
                        understood = understood,
                        missingOfficial = missingOfficial,
                        switching = switching,
                        backedUp = backedUp,
                        autoBackup = autoBackup,
                        backupPending = backupPending,
                    ),
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
private fun RiskNotice(names: List<String>, understood: Boolean, onUnderstood: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RiskWash, PixelBox())
            .border(3.dp, RiskRed, PixelBox())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("RISK", style = pixelText(34.sp, bold = true, color = RiskRed))
        BasicText(riskWarning(names), style = monoText(17.sp, RiskRed))
        BasicText(
            "A later TRY that gets this firmware running clears this warning.",
            style = monoText(14.sp, RiskSoft),
        )
        PixelToggle("I UNDERSTAND", understood, onUnderstood)
    }
}

private val RiskRed = Color(0xFFFF3B30)
private val RiskSoft = Color(0xFFFF8A80)
private val RiskWash = Color(0xFF3A0C0C)

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
                    NoteText("Keep the cable connected, and stop playback on the FM-1. A restore replaces what it is holding.")
                }
                BackupRestore.Succeeded ->                 MonoText(
                    "The FM-1 now holds that backup.",
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
