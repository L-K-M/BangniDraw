package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.R
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The legend→row merge, pinned behaviorally: the size pair shares one row's
 * two caps, the eyedropper hold pair is one key in two phases and shows one
 * cap, and no row ever duplicates a cap (the "Alt  Alt" regression).
 */
class ShortcutRowsTest {

    @Test
    fun `paired bindings merge without duplicating a cap`() {
        val rows = shortcutRows()

        val size = rows.filter { it.label == R.string.brush_size }
        assertEquals(1, size.size)
        assertEquals("[" + KEY_PAIR_GAP + "]", size.single().keys)

        val hold = rows.filter { it.label == R.string.shortcut_hold_eyedropper }
        assertEquals(1, hold.size)
        assertEquals("Alt", hold.single().keys)

        for (row in rows) {
            val caps = row.keys.split(KEY_PAIR_GAP)
            assertEquals(caps.size, caps.distinct().size, "row ${row.keys} duplicates a cap")
        }
    }
}
