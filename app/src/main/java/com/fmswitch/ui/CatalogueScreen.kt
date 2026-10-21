package com.fmswitch.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.catalogue.CatalogueBlurb
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.CatalogueOrder
import com.fmswitch.catalogue.CatalogueSearch
import com.fmswitch.catalogue.FirmwareSort
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
    var sortOpen by remember { mutableStateOf(false) }
    val theme = LocalPhosphor.current
    @Suppress("UNUSED_EXPRESSION")
    model.catalogueTick
    @Suppress("UNUSED_EXPRESSION")
    model.listTick
    val curation = model.settings.curation
    val curatedIds = curation.toSet()
    val matching = model.catalogue.entries.filter { entry ->
        (model.settings.showBeta || !entry.beta) &&
            (model.settings.showLinksOnly || entry.isDownloadable || model.library.items.any { it.catalogueID == entry.id }) &&
            (!model.settings.curatedOnly || entry.id in curatedIds) &&
            CatalogueSearch.matches(entry, search)
    }
    val visible = CatalogueOrder.apply(matching, model.settings.firmwareSort, curation)
    if (selected != null) {
        val entry = model.catalogue.entry(selected!!.id) ?: selected!!
        FirmwareDetail(model, entry) { selected = null }
        return
    }
    BackHandler(enabled = sortOpen || search.isNotEmpty()) {
        if (sortOpen) sortOpen = false else search = ""
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SortMenu(
                sort = model.settings.firmwareSort,
                expanded = sortOpen,
                onToggle = { sortOpen = !sortOpen },
                onSelect = {
                    model.setFirmwareSort(it)
                    sortOpen = false
                },
                modifier = Modifier.weight(1f),
            )
            PixelButton(
                if (curation.isEmpty()) "CURATED" else "CURATED ${curation.size}",
                compact = true,
                prominent = model.settings.curatedOnly,
            ) { model.setCuratedOnly(!model.settings.curatedOnly) }
        }
        Spacer(Modifier.height(8.dp))
        NoteText("KEEP adds a firmware to your list. MY CURATION puts that list first, in the order you set. CURATED hides everything else.")
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = search,
            onValueChange = { search = it },
            textStyle = monoText(14.sp, theme.ink),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
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
            MonoText(
                when {
                    model.settings.curatedOnly && curation.isEmpty() ->
                        "Your list is empty. Open a firmware and press KEEP, or turn CURATED off."
                    model.settings.curatedOnly ->
                        "Nothing in your list matches. Turn CURATED off to search the whole catalogue."
                    else ->
                        "No firmwares match. Refresh the list, or turn on LINKS-ONLY under SETUP if you want listings without a package."
                },
                size = 14,
                dim = true,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { entry ->
                val saved = model.library.stored(entry) != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = entry }
                        .background(theme.surface)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FirmwarePhoto(
                        url = entry.photoURL,
                        alt = entry.photoAlt ?: entry.name,
                        modifier = Modifier.size(76.dp, 56.dp),
                        placeholder = entry.name.take(4),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(entry.name.uppercase(), style = pixelText(13.sp, bold = true, color = theme.bright))
                        CatalogueBlurb.line(entry)?.let { MonoText(it, size = 12, dim = true, maxLines = 2) }
                        MonoText("${entry.author ?: "unknown"}  ·  ${entry.version ?: ""}", size = 13, dim = true)
                        val rankBits = buildList {
                            entry.stars?.takeIf { it > 0 }?.let { add("★ $it") }
                            if (entry.pinned) add("PINNED")
                        }
                        if (rankBits.isNotEmpty()) MonoText(rankBits.joinToString("  ·  "), size = 12, dim = true)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (entry.isOfficial) PixelTag("OFFICIAL")
                        else if (entry.beta) PixelTag("BETA", inverted = false)
                        if (saved) {
                            Spacer(Modifier.height(4.dp))
                            PixelTag("SAVED")
                        }
                        Spacer(Modifier.height(4.dp))
                        PixelButton(
                            if (entry.id in curatedIds) "KEPT" else "KEEP",
                            compact = true,
                            prominent = entry.id in curatedIds,
                        ) { model.toggleCurated(entry.id) }
                    }
                }
            }
        }
        val shipped = model.catalogue.catalogue
        when {
            shipped?.bundled == true ->
                NoteText("List shipped with the app. REFRESH pulls the latest from the hub.")
            shipped?.hubUpdated != null ->
                NoteText("Hub rebuilt ${shipped.hubUpdated}. REFRESH pulls again.")
            else ->
                NoteText("Listed by GitHub stars, official V15 first. Shots from each project's GitHub page, or from fm1-editor.com and the publishers.")
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
    @Suppress("UNUSED_EXPRESSION")
    model.listTick
    val curation = model.settings.curation
    val keptAt = curation.indexOf(entry.id)
    BackHandler { onBack() }
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
        FirmwarePhoto(
            url = entry.photoURL,
            alt = entry.photoAlt ?: entry.name,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            placeholder = "NO SCREENSHOT YET",
        )
        entry.what?.let { MonoText(it, size = 15) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("TRY", prominent = true) {
                model.openEmulator(entry)
            }
            if (stored == null && entry.isDownloadable) {
                PixelButton(if (downloading) "SAVING" else "SAVE", enabled = !downloading) {
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
        NoteText(
            if (model.settings.autoSaveTried) {
                "TRY plays this package on the virtual FM-1 and saves it under SAVED, ready to flash later (SETUP ▸ SAVE WHAT YOU TRY). Neither TRY nor SAVE writes to a connected FM-1."
            } else {
                "TRY plays this package on the virtual FM-1 and does not keep it. SAVE puts the same file under SAVED so you can flash it later. Neither one writes to a connected FM-1."
            },
        )
        if (keptAt >= 0) {
            MonoText("In your list, place ${keptAt + 1} of ${curation.size}.", size = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("EARLIER", compact = true, enabled = keptAt > 0) { model.moveCuration(entry.id, earlier = true) }
                PixelButton("LATER", compact = true, enabled = keptAt < curation.lastIndex) { model.moveCuration(entry.id, earlier = false) }
                PixelButton("DROP", compact = true) { model.toggleCurated(entry.id) }
            }
        } else {
            PixelButton("KEEP", compact = true, prominent = true) { model.toggleCurated(entry.id) }
            NoteText("KEEP puts this on your list. SORT ▸ MY CURATION shows that list first. CURATED hides the rest.")
        }
        error?.let { MonoText(it, size = 14) }
        PixelPanel(title = "DETAILS") {
            MonoText("Identity  ${entry.product ?: "unknown"}", size = 14)
            MonoText("Size  ${entry.size?.let { formattedBytes(it) } ?: "unknown"}", size = 14)
            entry.sha256?.let { MonoText("SHA-256  ${it.take(16)}…", size = 14) }
            if (entry.requiresOfficialFirmware) MonoText("Needs official firmware running first.", size = 14)
            RescueMode.summary(entry.id)?.let { MonoText("USB recovery  $it", size = 14) }
            entry.stars?.takeIf { it > 0 }?.let { MonoText("GitHub stars  $it", size = 14) }
            entry.installs?.takeIf { it > 0 }?.let { MonoText("Hub installs  $it", size = 14) }
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

@Composable
private fun SortMenu(
    sort: FirmwareSort,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (FirmwareSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhosphor.current
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, theme.bright)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText("SORT", size = 12, dim = true)
            Spacer(Modifier.width(8.dp))
            BasicText(sort.label, style = pixelText(11.sp, bold = true, color = theme.bright), modifier = Modifier.weight(1f))
            BasicText(if (expanded) "▴" else "▾", style = pixelText(12.sp, color = theme.bright))
        }
        if (expanded) {
            FirmwareSort.entries.forEach { option ->
                val selected = option == sort
                BasicText(
                    option.label,
                    style = pixelText(11.sp, bold = true, color = if (selected) theme.background else theme.bright),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) theme.bright else theme.surface)
                        .border(1.dp, theme.dim)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}
