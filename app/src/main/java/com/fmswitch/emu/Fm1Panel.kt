package com.fmswitch.emu

/**
 * The FM-1's front panel in fm1-emu's coordinates (crates/fm1-live/src/panel.rs): a
 * 1280 x 820 board, the 240 x 240 screen shown at 2x. Ids are the key matrix's.
 */
object Fm1Panel {
    const val W = 1280f
    const val H = 820f
    const val LCD_X = 370f
    const val LCD_Y = 42f
    const val LCD_SIZE = 480f
    const val POT_ADC_MAX = 1023
    const val POT_START = 900

    data class Knob(val label: String, val encoder: Int, val cx: Float, val cy: Float, val r: Float)
    data class Button(val label: String, val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)
    data class Key(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val black: Boolean)

    sealed class Hit {
        data class Encoder(val index: Int) : Hit()
        data object Pot : Hit()
        data class Press(val id: Int) : Hit()
    }

    val pot = Knob("MASTER", -1, 110f, 135f, 44f)

    val encoders = listOf(
        Knob("SELECT", 0, 250f, 135f, 44f),
        Knob("PRESETS", 6, 110f, 295f, 44f),
        Knob("ALGORITHM", 1, 250f, 295f, 44f),
        Knob("KNOB1", 2, 935f, 120f, 36f),
        Knob("KNOB2", 3, 1027f, 120f, 36f),
        Knob("KNOB3", 4, 1119f, 120f, 36f),
        Knob("KNOB4", 5, 1211f, 120f, 36f),
    )

    val buttons: List<Button> = buildList {
        add(Button("OCT-", 0, 62f, 398f, 112f, 44f))
        add(Button("OCT+", 1, 186f, 398f, 112f, 44f))
        val rows = listOf(
            listOf("FX" to 2, "SCL" to 3, "ENV" to 4, "LFO" to 5, "EDIT" to 6, "GLO" to 7),
            listOf("HOME" to 8, "SAVE" to 9, "ARP" to 10, "SEQ" to 11, "PLAY" to 12, "REC" to 13),
        )
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, (label, id) ->
                add(Button(label, id, 906f + i * 58, 232f + r * 76, 50f, 64f))
            }
        }
    }

    /** F3..G5: 16 white pills along the bottom, 11 black ones above the gaps. */
    val keys: List<Key> = buildList {
        val kw = 62
        val gap = 11
        val x0 = 61
        val whites = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
        whites.forEachIndexed { i, n -> add(Key(14 + n, (x0 + i * (kw + gap)).toFloat(), 680f, kw.toFloat(), 106f, false)) }
        val blacks = listOf(1 to 0, 3 to 1, 5 to 2, 8 to 4, 10 to 5, 13 to 7, 15 to 8, 17 to 9, 20 to 11, 22 to 12, 25 to 14)
        for ((n, after) in blacks) {
            val cx = x0 + (after + 1) * (kw + gap) - gap / 2
            add(Key(14 + n, (cx - kw / 2).toFloat(), 574f, kw.toFloat(), 94f, true))
        }
    }

    private val keysBlackFirst = keys.sortedBy { !it.black }

    /** What a point (in panel units) lands on; touch targets are a little larger than drawn. */
    fun hit(x: Float, y: Float, slop: Float = 8f): Hit? {
        encoders.forEachIndexed { i, k ->
            if (inCircle(x, y, k, slop)) return Hit.Encoder(i)
        }
        if (inCircle(x, y, pot, slop)) return Hit.Pot
        for (b in buttons) {
            if (x >= b.x - slop && x < b.x + b.w + slop && y >= b.y - slop && y < b.y + b.h + slop) return Hit.Press(b.id)
        }
        for (k in keysBlackFirst) {
            if (x >= k.x && x < k.x + k.w && y >= k.y && y < k.y + k.h) return Hit.Press(k.id)
        }
        return null
    }

    private fun inCircle(x: Float, y: Float, k: Knob, slop: Float): Boolean {
        val dx = x - k.cx
        val dy = y - k.cy
        val r = k.r + slop
        return dx * dx + dy * dy <= r * r
    }
}
