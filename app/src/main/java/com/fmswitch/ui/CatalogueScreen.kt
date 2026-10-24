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
import androidx.compose.foundation.lazy.LazyRow
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
import com.fmswitch.catalogue.httpFailure
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.CatalogueOrder
import com.fmswitch.catalogue.CatalogueSearch
import com.fmswitch.catalogue.FirmwareKind
import com.fmswitch.catalogue.FirmwareSort
import com.fmswitch.catalogue.RescueMode
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.CurationSeed
import com.fmswitch.model.StoredFirmware
import com.fmswitch.model.lastInstalledMarker
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
    @Suppress("UNUSED_EXPRESSION")
    model.historyTick
    val curation = model.settings.curation
    val curatedIds = curation.toSet()
    val matching = model.catalogue.entries.filter { entry ->
        (model.settings.showBeta || !entry.beta || entry.id in curatedIds) &&
            (model.settings.showLinksOnly || entry.isDownloadable || model.library.items.any { it.catalogueID == entry.id }) &&
            (!model.settings.curatedOnly || entry.id in curatedIds) &&
            CatalogueSearch.matches(entry, search)
    }
    val kind = model.settings.firmwareKind
    val kindCounts = FirmwareKind.entries.associateWith { group -> matching.count { group.matches(it) } }
    val visible = CatalogueOrder.apply(
        matching.filter { kind.matches(it) },
        model.settings.firmwareSort,
        curation,
        CurationSeed.IDS,
    )
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
                if (curation.isEmpty()) "FAVORITES" else "FAVORITES ${curation.size}",
                compact = true,
                prominent = model.settings.curatedOnly,
            ) { model.setCuratedOnly(!model.settings.curatedOnly) }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips = FirmwareKind.entries.filter { group ->
                group == FirmwareKind.ALL || group == kind || (kindCounts[group] ?: 0) > 0
            }
            items(chips, key = { it.id }) { group ->
                PixelButton(
                    "${group.label} ${kindCounts[group] ?: 0}",
                    compact = true,
                    prominent = group == kind,
                ) { model.setFirmwareKind(group) }
            }
        }
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
                        "No favorites yet. Press FAVORITE on a firmware, or turn FAVORITES off."
                    model.settings.curatedOnly ->
                        "Nothing in your favorites matches. Turn FAVORITES off to search the whole list."
                    kind != FirmwareKind.ALL ->
                        "No firmwares in this group. Press ALL, or clear the search."
                    else ->
                        "No firmwares match. Refresh the list, or under SETUP turn on SHOW LISTINGS WITHOUT A PACKAGE."
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
                        model.history.lastFor(entry)?.let { installed ->
                            MonoText(lastInstalledMarker(installed, entry.version), size = 13)
                        }
                        val rankBits = buildList {
                            entry.stars?.takeIf { it > 0 }?.let { add("★ $it") }
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
                            if (entry.id in curatedIds) "FAVORITED" else "FAVORITE",
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
                NoteText("This list came with the app. REFRESH gets a newer one.")
            shipped?.hubUpdated != null ->
                NoteText("Updated ${shipped.hubUpdated}. REFRESH gets a newer one.")
            else ->
                NoteText("Screenshots come from each firmware's own page.")
        }
    }
}

@Composable
fun FirmwareDetail(model: AppViewModel, entry: CatalogueEntry, onBack: () -> Unit) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var errorUrl by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<StoredFirmware?>(null) }
    var preparingFlash by remember { mutableStateOf(false) }
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
        model.history.lastFor(entry)?.let { installed ->
            MonoText(lastInstalledMarker(installed, entry.version), size = 14)
        }
        FirmwarePhoto(
            url = entry.photoURL,
            alt = entry.photoAlt ?: entry.name,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            placeholder = "NO SCREENSHOT YET",
        )
        entry.what?.let { MonoText(it, size = 15) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("TRY", prominent = true, wrap = true, modifier = Modifier.weight(1f)) {
                model.openEmulator(entry)
            }
            if (stored == null && entry.isDownloadable) {
                PixelButton(
                    if (downloading && !preparingFlash) "SAVING" else "SAVE",
                    wrap = true,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch {
                        error = null
                        model.save(entry).onFailure {
                            error = it.message
                            errorUrl = entry.packageURL
                        }
                    }
                }
            }
            if (stored != null || entry.isDownloadable) {
                PixelButton(
                    if (downloading && preparingFlash) "SAVING" else "FLASH",
                    prominent = true,
                    wrap = true,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f),
                ) {
                    val ready = stored
                    if (ready != null) {
                        confirming = ready
                    } else {
                        preparingFlash = true
                        scope.launch {
                            error = null
                            model.save(entry)
                                .onSuccess { confirming = it }
                                .onFailure {
                                    error = it.message
                                    errorUrl = entry.packageURL
                                }
                            preparingFlash = false
                        }
                    }
                }
            }
        }
        NoteText(
            when {
                stored == null && !entry.isDownloadable ->
                    "TRY plays this on the virtual FM-1. This listing has no file to save or flash."
                model.settings.autoSaveTried ->
                    "SAVE keeps a copy on the phone. FLASH writes a connected FM-1. Neither opens TRY. TRY plays it here, and also keeps the copy."
                else ->
                    "SAVE keeps a copy on the phone. FLASH writes a connected FM-1. Neither opens TRY. TRY plays it here without keeping the copy."
            },
        )
        if (keptAt >= 0) {
            MonoText("Favorite ${keptAt + 1} of ${curation.size}.", size = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("EARLIER", compact = true, enabled = keptAt > 0) { model.moveCuration(entry.id, earlier = true) }
                PixelButton("LATER", compact = true, enabled = keptAt < curation.lastIndex) { model.moveCuration(entry.id, earlier = false) }
                PixelButton("FAVORITED", compact = true, prominent = true) { model.toggleCurated(entry.id) }
            }
        } else {
            PixelButton("FAVORITE", compact = true, prominent = true) { model.toggleCurated(entry.id) }
            NoteText("FAVORITE marks this firmware. Marked ones stay at the top, and FAVORITES shows only those.")
        }
        if (error != null && errorUrl == entry.packageURL) {
            MonoText(error!!, size = 14)
            if (error == httpFailure(404)) {
                PixelButton(
                    if (model.isRefreshingCatalogue) "REFRESHING" else "REFRESH",
                    compact = true,
                    prominent = true,
                    enabled = !model.isRefreshingCatalogue,
                ) { model.refreshCatalogue() }
            }
        }
        PixelPanel(title = "DETAILS") {
            MonoText("Reports  ${entry.product ?: "unknown"}", size = 14)
            MonoText("Size  ${entry.size?.let { formattedBytes(it) } ?: "unknown"}", size = 14)
            entry.sha256?.let { MonoText("Checksum  ${it.take(16)}…", size = 14) }
            if (entry.requiresOfficialFirmware) MonoText("Needs official firmware running first.", size = 14)
            RescueMode.summary(entry.id)?.let { MonoText("USB recovery  $it", size = 14) }
            entry.stars?.takeIf { it > 0 }?.let { MonoText("Stars  $it", size = 14) }
            entry.installs?.takeIf { it > 0 }?.let { MonoText("Installs  $it", size = 14) }
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
