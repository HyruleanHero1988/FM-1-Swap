package com.fmswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fmswitch.catalogue.NewChipSupport
import com.fmswitch.model.NewChipGate
import com.fmswitch.model.UnitGeneration
import com.fmswitch.model.newChipBrickWarning
import com.fmswitch.model.newChipUntestedWarning
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.NoteText
import com.fmswitch.theme.PixelBox
import com.fmswitch.theme.PixelTag
import com.fmswitch.theme.PixelToggle
import com.fmswitch.theme.monoText
import com.fmswitch.theme.pixelText

private val NewChipRed = Color(0xFFFF3B30)
private val NewChipWash = Color(0xFF3A0C0C)
private val NewChipAmber = Color(0xFFFFB020)
private val NewChipAmberWash = Color(0xFF3A2A06)

/** The row and detail tag. Hidden for people who said their FM-1 is one of the first units. */
@Composable
fun NewChipTag(support: NewChipSupport, unit: UnitGeneration) {
    if (!unit.needsNewChipCare) return
    when (support) {
        NewChipSupport.WORKS -> PixelTag("SAVES ON NEWER FM-1S")
        NewChipSupport.BRICKS -> PixelTag("FAILS ON NEWER FM-1S", inverted = false)
        NewChipSupport.UNKNOWN -> PixelTag("UNTESTED ON NEWER FM-1S", inverted = false)
    }
}

/** On a firmware's page: the warning that goes with the tag. Nothing for a confirmed firmware. */
@Composable
fun NewChipBanner(name: String, support: NewChipSupport, unit: UnitGeneration) {
    if (!unit.needsNewChipCare) return
    when (support) {
        NewChipSupport.WORKS -> NoteText(
            "Saves on newer FM-1s: this package is read as knowing the newer flash chip (0xB36014). Source: The Firmware Hub.",
        )
        NewChipSupport.BRICKS -> Notice("NEWER FM-1 WARNING", newChipBrickWarning(listOf(name)), NewChipRed, NewChipWash)
        NewChipSupport.UNKNOWN -> Notice("NOT TESTED ON NEWER FM-1S", newChipUntestedWarning(listOf(name)), NewChipAmber, NewChipAmberWash)
    }
}

/** At the top of the flash sheet. A package known to fail needs the toggle before CONTINUE comes on. */
@Composable
fun NewChipFlashNotice(gate: NewChipGate, acknowledged: Boolean, onAcknowledged: (Boolean) -> Unit) {
    if (gate.bricking.isNotEmpty()) {
        Notice("NEWER FM-1 WARNING", newChipBrickWarning(gate.bricking), NewChipRed, NewChipWash) {
            PixelToggle("MY FM-1 IS NOT A NEWER ONE, OR I ACCEPT THE RISK", acknowledged, onAcknowledged)
        }
    }
    if (gate.untested.isNotEmpty()) {
        Notice("NOT TESTED ON NEWER FM-1S", newChipUntestedWarning(gate.untested), NewChipAmber, NewChipAmberWash)
    }
}

@Composable
private fun Notice(
    title: String,
    text: String,
    colour: Color,
    wash: Color,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(wash, PixelBox())
            .border(3.dp, colour, PixelBox())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(title, style = pixelText(16.sp, bold = true, color = colour))
        BasicText(text, style = monoText(15.sp, colour))
        extra()
    }
}

/** SETUP copy: how to tell the two FM-1s apart, as far as anyone has found. */
object NewChipHelp {
    const val INTRO =
        "FM-1s sold since autumn 2026 have a different flash chip (UC25HQ80, ID 0xB36014). The first units have a Puya (ID 0x856014). " +
            "A firmware written before that only knows the old ID, so on a newer FM-1 it loses its saves at power-off and often can't take another update."
    const val HOW_TO_TELL =
        "This app can't read the chip: as far as anyone has found it is not reported over USB MIDI. To be sure, connect the FM-1 to a computer in download mode " +
            "with M-Flasher, which shows the flash ID. The signs: saves vanish at power-off with a firmware that isn't marked SAVES ON NEWER FM-1S, " +
            "or you see \"save error\", while M-VAVE's V15 keeps them. A unit bought from autumn 2026 is likely newer, but that is not proof."
    const val SOURCE = "Data: The Firmware Hub, thefirmwarehub.com (CC BY 4.0)."
}

@Composable
fun UnitGenerationHelp() {
    MonoText(NewChipHelp.INTRO, size = 14)
    NoteText(NewChipHelp.HOW_TO_TELL)
    NoteText(NewChipHelp.SOURCE)
}
