package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings shortcuts table is data, and it must be the *same* data
 * `CanvasShortcuts.resolve` answers to — otherwise the help lies about the
 * app. Pinned here because both sides are pure JVM.
 */
class CanvasShortcutCatalogTest {

    @Test
    fun `every catalog entry resolves to its own action`() {
        for (entry in CanvasShortcutCatalog.rows) {
            assertEquals(
                entry.action,
                CanvasShortcuts.resolve(entry.key, KeyPhase.DOWN, entry.modifiers),
                "chord ${entry.chord} does not run ${entry.action}",
            )
        }
    }

    @Test
    fun `every shortcut is listed exactly once`() {
        // END_EYEDROPPER is the release half of Alt's hold — BEGIN carries it.
        val expected = CanvasShortcut.entries.toList() - CanvasShortcut.END_EYEDROPPER
        assertEquals(
            expected,
            CanvasShortcutCatalog.rows.map { it.action }.distinct(),
            "the catalog must list every shortcut, and only whole duplicates (redo's two chords) repeat",
        )
    }

    @Test
    fun `chords are unique except redo's deliberate pair`() {
        val chords = CanvasShortcutCatalog.rows.map { it.chord }
        val duplicates = chords.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicates.toSet())
    }

    @Test
    fun `no chord is blank`() {
        assertTrue(CanvasShortcutCatalog.rows.all { it.chord.isNotBlank() })
    }
}
