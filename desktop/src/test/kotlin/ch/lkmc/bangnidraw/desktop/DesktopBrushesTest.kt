package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop shell's classpath wiring: the brush presets ship as desktop
 * resources from the same single copy the Android app packages as assets,
 * so loading them here proves both the resource root and the JSON parse on
 * a plain JVM.
 */
class DesktopBrushesTest {

    @Test
    fun `all shipped presets load and parse`() {
        val presets = DesktopBrushes.loadAll()

        assertTrue(presets.size >= MIN_PRESETS, "expected at least $MIN_PRESETS presets, got ${presets.size}")
        assertEquals(
            presets.map { it.id }.distinct().size,
            presets.size,
            "preset ids must be unique",
        )
        assertTrue(
            presets.any { it.id == BrushPresets.INK_PEN_ID },
            "the default preset must be present",
        )
    }

    private companion object {
        const val MIN_PRESETS = 10
    }
}
