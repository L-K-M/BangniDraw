package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushMixingPolicy
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStrokePolicyTest {

    @Test
    fun `secondary mouse source always erases`() {
        val brush = DesktopBrushes.loadAll().first { !it.eraseMode }

        assertEquals(
            StrokeMode.ERASE,
            DesktopStrokePolicy.mode(StrokeSource.ERASER_END, brush, RgbMixer),
        )
    }

    @Test
    fun `secondary mouse mode resolves without impersonating a stylus eraser`() {
        assertEquals(
            StrokeSource.ERASER_END,
            DesktopStrokePolicy.source(StrokeSource.MOUSE, DesktopMouseMode.Erase),
        )
        assertEquals(
            StrokeSource.MOUSE,
            DesktopStrokePolicy.source(StrokeSource.MOUSE, DesktopMouseMode.Draw),
        )
    }

    @Test
    fun `erase mouse mode remaps only mouse input`() {
        for (source in StrokeSource.entries) {
            val expected =
                if (source == StrokeSource.MOUSE) StrokeSource.ERASER_END else source

            assertEquals(expected, DesktopStrokePolicy.source(source, DesktopMouseMode.Erase))
        }
    }

    @Test
    fun `an eraser preset erases without changing mouse source`() {
        val eraser = DesktopBrushes.loadAll().first { it.eraseMode }

        assertEquals(
            StrokeMode.ERASE,
            DesktopStrokePolicy.mode(StrokeSource.MOUSE, eraser, RgbMixer),
        )
    }

    @Test
    fun `ordinary mouse source retains brush mixing policy`() {
        val brush = DesktopBrushes.loadAll().first { !it.eraseMode }

        assertEquals(
            BrushMixingPolicy.mode(brush, RgbMixer),
            DesktopStrokePolicy.mode(StrokeSource.MOUSE, brush, RgbMixer),
        )
    }
}
