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
    fun `every shortcut is listed and only redo repeats`() {
        // END_EYEDROPPER is the release half of Alt's hold — BEGIN carries it.
        val expected = CanvasShortcut.entries.toSet() - CanvasShortcut.END_EYEDROPPER
        val listed = CanvasShortcutCatalog.rows.map { it.action }
        assertEquals(expected, listed.toSet(), "the catalog must list every shortcut and nothing else")
        val repeats = listed.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals(
            mapOf(CanvasShortcut.REDO to 2),
            repeats,
            "only redo's two chords may repeat an action",
        )
    }

    @Test
    fun `chord labels are unique`() {
        val chords = CanvasShortcutCatalog.rows.map { it.chord }
        val duplicates = chords.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicates.toSet())
    }

    @Test
    fun `no chord is blank`() {
        assertTrue(CanvasShortcutCatalog.rows.all { it.chord.isNotBlank() })
    }
}
