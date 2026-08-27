package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasShortcutTest {

    @Test
    fun `history shortcuts honor ctrl and shift`() {
        assertEquals(
            CanvasShortcut.UNDO,
            CanvasShortcuts.resolve(ShortcutKey.Z, KeyPhase.DOWN, KeyModifiers.CTRL),
        )
        assertEquals(
            CanvasShortcut.REDO,
            CanvasShortcuts.resolve(ShortcutKey.Z, KeyPhase.DOWN, KeyModifiers.CTRL_SHIFT),
        )
        assertEquals(
            CanvasShortcut.REDO,
            CanvasShortcuts.resolve(ShortcutKey.Y, KeyPhase.DOWN, KeyModifiers.CTRL),
        )
        assertNull(CanvasShortcuts.resolve(ShortcutKey.Z, KeyPhase.DOWN, KeyModifiers.NONE))
    }

    @Test
    fun `tool view panel and size keys map on key down`() {
        val expected = mapOf(
            ShortcutKey.LEFT_BRACKET to CanvasShortcut.SIZE_DOWN,
            ShortcutKey.RIGHT_BRACKET to CanvasShortcut.SIZE_UP,
            ShortcutKey.B to CanvasShortcut.BRUSH,
            ShortcutKey.E to CanvasShortcut.ERASER,
            ShortcutKey.S to CanvasShortcut.SMUDGE,
            ShortcutKey.G to CanvasShortcut.FILL,
            ShortcutKey.I to CanvasShortcut.EYEDROPPER,
            ShortcutKey.DIGIT_ZERO to CanvasShortcut.RESET_VIEW,
            ShortcutKey.TAB to CanvasShortcut.TOGGLE_FOCUS,
            ShortcutKey.L to CanvasShortcut.TOGGLE_LAYERS,
            ShortcutKey.C to CanvasShortcut.TOGGLE_COLOR,
        )

        for ((key, action) in expected) {
            assertEquals(action, CanvasShortcuts.resolve(key, KeyPhase.DOWN, KeyModifiers.NONE))
            assertNull(CanvasShortcuts.resolve(key, KeyPhase.UP, KeyModifiers.NONE))
        }
    }

    @Test
    fun `alt is a held eyedropper`() {
        assertEquals(
            CanvasShortcut.BEGIN_EYEDROPPER,
            CanvasShortcuts.resolve(ShortcutKey.ALT, KeyPhase.DOWN, KeyModifiers.NONE),
        )
        assertEquals(
            CanvasShortcut.END_EYEDROPPER,
            CanvasShortcuts.resolve(ShortcutKey.ALT, KeyPhase.UP, KeyModifiers.NONE),
        )
    }
}
