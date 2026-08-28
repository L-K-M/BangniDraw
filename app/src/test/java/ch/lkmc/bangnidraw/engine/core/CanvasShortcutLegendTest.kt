package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasShortcutLegendTest {

    @Test
    fun `every advertised binding resolves through the dispatcher`() {
        for (entry in ShortcutLegend.entries) {
            assertEquals(
                entry.shortcut,
                CanvasShortcuts.resolve(entry.key, entry.phase, entry.modifiers),
                "legend advertises $entry but resolve disagrees",
            )
        }
    }

    @Test
    fun `every shortcut is advertised`() {
        val advertised = ShortcutLegend.entries.map { it.shortcut }.toSet()

        // Ctrl+Y is REDO's unadvertised alias; everything else is listed.
        val unadvertised = CanvasShortcut.entries.toSet() - advertised
        assertTrue(unadvertised.isEmpty(), "shortcuts missing from the legend: $unadvertised")
    }

    @Test
    fun `key labels read as physical keys`() {
        val byShortcut = ShortcutLegend.entries.associateBy { it.shortcut }

        assertEquals("Ctrl+Z", ShortcutLegend.keyLabel(byShortcut.getValue(CanvasShortcut.UNDO)))
        assertEquals("Ctrl+Shift+Z", ShortcutLegend.keyLabel(byShortcut.getValue(CanvasShortcut.REDO)))
        assertEquals("[", ShortcutLegend.keyLabel(byShortcut.getValue(CanvasShortcut.SIZE_DOWN)))
        assertEquals("Alt", ShortcutLegend.keyLabel(byShortcut.getValue(CanvasShortcut.BEGIN_EYEDROPPER)))
    }
}
