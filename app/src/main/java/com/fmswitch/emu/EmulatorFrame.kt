package com.fmswitch.emu

/**
 * Where the TRY panel sits, and the only place other controls are allowed to sit.
 *
 * The panel keeps the FM-1's shape and is centered in the space it is given.
 * Controls take the larger leftover strip (the side margins when the phone is
 * sideways, the top bar when it is taller than the panel). That strip shares
 * an edge with the panel and does not cross it.
 */
object EmulatorFrame {
    data class Fit(
        val panelWidth: Float,
        val panelHeight: Float,
        /** Empty width on each side of the centered panel. */
        val side: Float,
        /** Empty height above and below the centered panel. */
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

    fun fit(spaceWidth: Float, spaceHeight: Float): Fit {
        if (spaceWidth <= 0f || spaceHeight <= 0f) return Fit(0f, 0f, 0f, 0f)
        val aspect = Fm1Panel.W / Fm1Panel.H
        val widthAtFullHeight = spaceHeight * aspect
        val panelWidth: Float
        val panelHeight: Float
        if (widthAtFullHeight <= spaceWidth) {
            panelWidth = widthAtFullHeight
            panelHeight = spaceHeight
        } else {
            panelWidth = spaceWidth
            panelHeight = spaceWidth / aspect
        }
        return Fit(
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            side = ((spaceWidth - panelWidth) / 2f).coerceAtLeast(0f),
            bar = ((spaceHeight - panelHeight) / 2f).coerceAtLeast(0f),
        )
    }

    fun panelRect(spaceWidth: Float, spaceHeight: Float, fit: Fit = fit(spaceWidth, spaceHeight)): Rect =
        Rect(
            x = ((spaceWidth - fit.panelWidth) / 2f).coerceAtLeast(0f),
            y = ((spaceHeight - fit.panelHeight) / 2f).coerceAtLeast(0f),
            width = fit.panelWidth,
            height = fit.panelHeight,
        )

    /** Side strip when that margin is the larger leftover, otherwise the top bar. */
    fun chromeRect(spaceWidth: Float, spaceHeight: Float, fit: Fit = fit(spaceWidth, spaceHeight)): Rect =
        if (fit.side >= fit.bar) {
            Rect(0f, 0f, fit.side, spaceHeight.coerceAtLeast(0f))
        } else {
            Rect(0f, 0f, spaceWidth.coerceAtLeast(0f), fit.bar)
        }
}
