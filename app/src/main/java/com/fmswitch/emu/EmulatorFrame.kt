package com.fmswitch.emu

/**
 * Where the TRY panel sits, and the only place other controls are allowed to sit.
 *
 * The panel keeps the FM-1's shape. Controls take the whole leftover strip on
 * one edge: the start side when the phone is sideways (the panel sits against
 * the other edge), or the top when the space is taller than the panel. That
 * strip shares an edge with the panel and does not cross it. [minChrome] on
 * [fit] widens the strip, shrinking the panel, when the natural gap is too
 * narrow for a label.
 */
object EmulatorFrame {
    data class Fit(
        val panelWidth: Float,
        val panelHeight: Float,
        /** Width of the start-side strip. 0 when the controls sit on top. */
        val side: Float,
        /** Height of the top strip. 0 when the controls sit beside the panel. */
        val bar: Float,
    )

    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height

        fun intersects(other: Rect): Boolean {
            if (width <= 0f || height <= 0f || other.width <= 0f || other.height <= 0f) return false
            return x < other.right && right > other.x && y < other.bottom && bottom > other.y
        }
    }

    fun fit(spaceWidth: Float, spaceHeight: Float, minChrome: Float = 0f): Fit {
        if (spaceWidth <= 0f || spaceHeight <= 0f) return Fit(0f, 0f, 0f, 0f)
        val aspect = Fm1Panel.W / Fm1Panel.H
        val widthAtFullHeight = spaceHeight * aspect
        var panelWidth: Float
        var panelHeight: Float
        if (widthAtFullHeight <= spaceWidth) {
            panelWidth = widthAtFullHeight
            panelHeight = spaceHeight
        } else {
            panelWidth = spaceWidth
            panelHeight = spaceWidth / aspect
        }
        val horizontalLeftover = (spaceWidth - panelWidth).coerceAtLeast(0f)
        val verticalLeftover = (spaceHeight - panelHeight).coerceAtLeast(0f)
        // Equal leftovers (including none) keep the controls beside the panel.
        if (horizontalLeftover >= verticalLeftover) {
            val chrome = horizontalLeftover.coerceAtLeast(minChrome).coerceAtMost(spaceWidth)
            if (chrome > horizontalLeftover) {
                panelWidth = (spaceWidth - chrome).coerceAtLeast(0f)
                panelHeight = (panelWidth / aspect).coerceAtMost(spaceHeight)
            }
            return Fit(
                panelWidth = panelWidth,
                panelHeight = panelHeight,
                side = (spaceWidth - panelWidth).coerceAtLeast(0f),
                bar = 0f,
            )
        }
        val chrome = verticalLeftover.coerceAtLeast(minChrome).coerceAtMost(spaceHeight)
        if (chrome > verticalLeftover) {
            panelHeight = (spaceHeight - chrome).coerceAtLeast(0f)
            panelWidth = (panelHeight * aspect).coerceAtMost(spaceWidth)
        }
        return Fit(
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            side = 0f,
            bar = (spaceHeight - panelHeight).coerceAtLeast(0f),
        )
    }

    fun panelRect(spaceWidth: Float, spaceHeight: Float, fit: Fit = fit(spaceWidth, spaceHeight)): Rect {
        val beside = fit.side >= fit.bar
        return Rect(
            x = if (beside) (spaceWidth - fit.panelWidth).coerceAtLeast(0f)
            else ((spaceWidth - fit.panelWidth) / 2f).coerceAtLeast(0f),
            y = if (beside) ((spaceHeight - fit.panelHeight) / 2f).coerceAtLeast(0f)
            else (spaceHeight - fit.panelHeight).coerceAtLeast(0f),
            width = fit.panelWidth,
            height = fit.panelHeight,
        )
    }

    /** The one strip controls are allowed to draw in. */
    fun chromeRect(spaceWidth: Float, spaceHeight: Float, fit: Fit = fit(spaceWidth, spaceHeight)): Rect =
        if (fit.side >= fit.bar) {
            Rect(0f, 0f, fit.side, spaceHeight.coerceAtLeast(0f))
        } else {
            Rect(0f, 0f, spaceWidth.coerceAtLeast(0f), fit.bar)
        }
}
