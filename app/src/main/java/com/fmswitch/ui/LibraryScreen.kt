package com.fmswitch.ui

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
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.StoredFirmware
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.PixelPanel
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
        NoteText("Firmwares saved here stay on the phone for offline flashing. M-VAVE's V15 is bundled and kept even if you clear the rest.")
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
                    PixelButton("FLASH", compact = true, prominent = true) { flashing = item }
                    if (item.source != "official") {
                        PixelButton("REMOVE", compact = true) { model.remove(item) }
                    }
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
            if (switching) {
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
                    enabled = !missingOfficial && (!switching || backedUp),
                ) {
                    model.flash(steps, confirmsSwitchingFirmware = switching && backedUp, confirmsSameIdentity = sameIdentity)
                    onDismiss()
                }
            }
        }
    }
}
