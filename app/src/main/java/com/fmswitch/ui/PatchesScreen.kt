package com.fmswitch.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.model.AppViewModel
import com.fmswitch.model.LoadedPatchBank
import com.fmswitch.model.loadPatchBanks
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.ScreenHeader
import com.fmswitch.theme.pixelText

@Composable
fun PatchesScreen(model: AppViewModel) {
    val context = LocalContext.current
    val loaded = remember { runCatching { loadPatchBanks(context.assets) } }
    var open by remember { mutableStateOf<LoadedPatchBank?>(null) }
    var confirming by remember { mutableStateOf(false) }
    BackHandler(enabled = open != null) {
        if (confirming) confirming = false else open = null
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val current = open
        if (current == null) {
            ScreenHeader("PATCHES")
            NoteText("SEND plays one patch on a connected FM-1. SEND BANK sends all 32, and you choose which bank on the FM-1 they replace. Official V15, FM-1+VA, and Official Open take them.")
            model.patchStatus?.let { MonoText(it, size = 14) }
            val banks = loaded.getOrNull()
            if (banks == null) {
                MonoText("The patch list couldn't be read.", size = 14)
            } else {
                var category = ""
                banks.forEach { item ->
                    if (item.info.category != category) {
                        category = item.info.category
                        MonoText(category.uppercase(), size = 13, dim = true)
                    }
                    BankRow(item.info.name, item.info.about) {
                        confirming = false
                        open = item
                    }
                }
                NoteText("Banks 1–4 are the FM-1's own presets, captured by KingParamount and shared as DX7 banks under CC0. The voices come from Yamaha ROM and VRC cartridges and from BlackWinny's Dexed_cart 1.0 collection, as M-VAVE selected and renamed them. Aquanode's banks are CC0, from aquanode-soundscapes. Yamaha's cartridge banks are on fm1-editor.com.")
                CreditLink("KINGPARAMOUNT", "https://github.com/KingParamount/fm1-factory-presets")
                CreditLink("AQUANODE", "https://github.com/aquanodemusic/aquanode-soundscapes")
                CreditLink("FM1-EDITOR.COM", "https://fm1-editor.com/")
            }
        } else {
            ScreenHeader(current.info.name) {
                PixelButton("BACK", compact = true) {
                    confirming = false
                    open = null
                }
            }
            NoteText(current.info.about)
            if (confirming) {
                MonoText("This sends all 32 patches in ${current.info.name}. On the FM-1, pick the bank they should replace. The patches in that bank are replaced.", size = 14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("SEND", compact = true, enabled = !model.patchSending) {
                        confirming = false
                        model.sendBank(current.bank)
                    }
                    PixelButton("CANCEL", compact = true) { confirming = false }
                }
            } else {
                PixelButton("SEND BANK", enabled = !model.patchSending) { confirming = true }
            }
            model.patchStatus?.let { MonoText(it, size = 14) }
            current.bank.voices.indices.forEach { index ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MonoText("${index + 1}.  ${current.bank.name(index)}", size = 14, modifier = Modifier.weight(1f))
                    PixelButton("SEND", compact = true, enabled = !model.patchSending && !confirming) {
                        model.sendVoice(current.bank, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditLink(label: String, url: String) {
    val context = LocalContext.current
    val theme = LocalPhosphor.current
    BasicText(
        "▸ $label",
        style = pixelText(11.sp, bold = true, color = theme.bright),
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
    )
}

@Composable
private fun BankRow(name: String, about: String, onClick: () -> Unit) {
    val theme = LocalPhosphor.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(theme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicText(name, style = pixelText(16.sp, bold = true, color = theme.bright))
        Spacer(Modifier.height(4.dp))
        MonoText(about, size = 13, dim = true)
    }
}
