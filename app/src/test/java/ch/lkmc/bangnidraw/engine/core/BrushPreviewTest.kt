package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertTrue

class BrushPreviewTest {

    @Test
    fun `preview paints the requested color over paper`() {
        val pixels = BrushPreview.render(
            preset = BrushPresets.INK_PEN.copy(size = 12f),
            brushColor = RED,
            paperColor = WHITE,
            width = WIDTH,
            height = HEIGHT,
        )

        assertTrue(pixels.any { Composite.red(it) > Composite.green(it) })
        assertTrue(pixels.any { it == WHITE })
    }

    @Test
    fun `a larger preset covers more pixels`() {
        val small = BrushPreview.render(
            BrushPresets.INK_PEN.copy(size = 2f),
            BLACK,
            WHITE,
            WIDTH,
            HEIGHT,
        )
        val large = BrushPreview.render(
            BrushPresets.INK_PEN.copy(size = 24f),
            BLACK,
            WHITE,
            WIDTH,
            HEIGHT,
        )

        assertTrue(large.count { it != WHITE } > small.count { it != WHITE })
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 72
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
