package com.fmswitch.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.RescueMode
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
import com.fmswitch.theme.monoText
import com.fmswitch.theme.pixelText
import kotlinx.coroutines.launch

@Composable
fun CatalogueScreen(model: AppViewModel) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CatalogueEntry?>(null) }
    val theme = LocalPhosphor.current
    @Suppress("UNUSED_EXPRESSION")
    model.catalogueTick
    val visible = model.catalogue.entries.filter { entry ->
        (model.settings.showBeta || !entry.beta) &&
            (model.settings.showLinksOnly || entry.isDownloadable || model.library.items.any { it.catalogueID == entry.id }) &&
            (search.isBlank() ||
                entry.name.contains(search, true) ||
                (entry.what ?: "").contains(search, true) ||
                (entry.author ?: "").contains(search, true) ||
                entry.kinds.any { it.contains(search, true) })
    }
    if (selected != null) {
        val entry = model.catalogue.entry(selected!!.id) ?: selected!!
        FirmwareDetail(model, entry) { selected = null }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("FIRMWARE") {
            PixelButton(
                if (model.catalogue.isRefreshing) "LOADING" else "REFRESH",
                compact = true,
                enabled = !model.catalogue.isRefreshing,
            ) { model.refreshCatalogue() }
        }
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = search,
            onValueChange = { search = it },
            textStyle = monoText(14.sp, theme.ink),
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.surface)
                .padding(10.dp),
            decorationBox = { inner ->
                if (search.isEmpty()) MonoText("SEARCH", size = 14, dim = true)
                inner()
            },
        )
        model.catalogue.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            MonoText(it, size = 13)
        }
        if (visible.isEmpty() && !model.catalogue.isRefreshing) {
            Spacer(Modifier.height(16.dp))
            MonoText("No firmwares match. Refresh the list, or turn on LINKS-ONLY under SETUP if you want listings without a package.", size = 14, dim = true)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { entry ->
                val saved = model.library.stored(entry) != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = entry }
                        .background(theme.surface)
                        .padding(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(entry.name.uppercase(), style = pixelText(13.sp, bold = true, color = theme.bright))
                        MonoText("${entry.author ?: "unknown"}  ·  ${entry.version ?: ""}", size = 13, dim = true)
                    }
                    if (entry.isOfficial) PixelTag("OFFICIAL")
                    else if (entry.beta) PixelTag("BETA", inverted = false)
                    if (saved) {
                        Spacer(Modifier.padding(4.dp))
                        PixelTag("SAVED")
                    }
                }
            }
        }
        model.catalogue.catalogue?.hubUpdated?.let {
            NoteText("Hub rebuilt $it")
        }
    }
}

@Composable
fun FirmwareDetail(model: AppViewModel, entry: CatalogueEntry, onBack: () -> Unit) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<StoredFirmware?>(null) }
    val stored = model.library.stored(entry)
    val downloading = entry.id in model.downloadingIds
    @Suppress("UNUSED_EXPRESSION")
    model.libraryTick
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("◂ BACK", compact = true, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (entry.isOfficial) PixelTag("OFFICIAL")
            if (entry.beta) PixelTag("BETA", inverted = false)
        }
        BasicText(entry.name.uppercase(), style = pixelText(24.sp, bold = true, color = theme.bright, glow = true))
        MonoText("${entry.author ?: ""}  ·  ${entry.version ?: ""}  ·  ${entry.product ?: ""}", size = 14, dim = true)
        entry.what?.let { MonoText(it, size = 15) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stored == null && entry.isDownloadable) {
                PixelButton(if (downloading) "SAVING" else "SAVE", prominent = true, enabled = !downloading) {
                    scope.launch {
                        error = null
                        model.save(entry).onFailure { error = it.message }
                    }
                }
            }
            if (stored != null) {
                PixelButton("FLASH", prominent = true) { confirming = stored }
            }
        }
        error?.let { MonoText(it, size = 14) }
        PixelPanel(title = "DETAILS") {
            MonoText("Identity  ${entry.product ?: "unknown"}", size = 14)
            MonoText("Size  ${entry.size?.let { formattedBytes(it) } ?: "unknown"}", size = 14)
            entry.sha256?.let { MonoText("SHA-256  ${it.take(16)}…", size = 14) }
            if (entry.requiresOfficialFirmware) MonoText("Needs official firmware running first.", size = 14)
            RescueMode.summary(entry.id)?.let { MonoText("USB recovery  $it", size = 14) }
        }
        if (entry.links.isNotEmpty()) {
            PixelPanel(title = "LINKS") {
                entry.links.forEach { link ->
                    BasicText(
                        "▸ ${link.label.uppercase()}",
                        style = pixelText(11.sp, bold = true, color = theme.bright),
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                            },
                    )
                }
            }
        }
    }
    confirming?.let { item ->
        FlashConfirmSheet(model, item, entry.requiresOfficialFirmware) {
            confirming = null
            if (!model.isWriting) model.scan(force = true)
        }
    }
}
