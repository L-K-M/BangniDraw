package ch.lkmc.bangnidraw.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.CanvasShortcuts
import ch.lkmc.bangnidraw.engine.core.KeyModifiers
import ch.lkmc.bangnidraw.engine.core.KeyPhase
import ch.lkmc.bangnidraw.engine.core.ShortcutKey

/**
 * Compose key events → the shared [CanvasShortcuts] table.
 *
 * The table itself is `engine/core`'s and is the same one the Android
 * activity translates into: B for the brush, E for the eraser, `[`/`]` for
 * size, Tab for focus, 0 to reset the view, Alt held for a temporary
 * eyedropper. Only the translation is platform work, and it is deliberately
 * the *whole* of it — a desktop-only chord would be a shortcut the Settings
 * sheet's catalogue does not list.
 */
internal object DesktopShortcuts {

    /** The action [event] runs, or null when it is not in the table. */
    fun resolve(event: KeyEvent): CanvasShortcut? = resolve(
        key = event.key,
        type = event.type,
        // macOS puts the undo chord on Command, everything else on Control.
        // The shared table speaks in `CTRL`, so the host's own modifier maps
        // onto it rather than the table growing a platform arm.
        primary = if (DesktopPlatform.isMacOs) event.isMetaPressed else event.isCtrlPressed,
        shift = event.isShiftPressed,
    )

    /**
     * The translation itself, as plain values.
     *
     * Separate from the [KeyEvent] overload because a Compose key event
     * cannot be constructed outside the toolkit — it wraps an internal type —
     * so this is the half a test can drive.
     */
    fun resolve(
        key: Key,
        type: KeyEventType,
        primary: Boolean,
        shift: Boolean,
    ): CanvasShortcut? {
        val shortcut = shortcutKey(key) ?: return null
        val phase = when (type) {
            KeyEventType.KeyDown -> KeyPhase.DOWN
            KeyEventType.KeyUp -> KeyPhase.UP
            else -> return null
        }
        val modifiers = when {
            primary && shift -> KeyModifiers.CTRL_SHIFT
            primary -> KeyModifiers.CTRL
            else -> KeyModifiers.NONE
        }
        return CanvasShortcuts.resolve(shortcut, phase, modifiers)
    }

    private fun shortcutKey(key: Key): ShortcutKey? = when (key) {
        Key.Z -> ShortcutKey.Z
        Key.Y -> ShortcutKey.Y
        Key.LeftBracket -> ShortcutKey.LEFT_BRACKET
        Key.RightBracket -> ShortcutKey.RIGHT_BRACKET
        Key.B -> ShortcutKey.B
        Key.E -> ShortcutKey.E
        Key.S -> ShortcutKey.S
        Key.W -> ShortcutKey.W
        Key.G -> ShortcutKey.G
        Key.I -> ShortcutKey.I
        Key.Zero -> ShortcutKey.DIGIT_ZERO
        Key.Tab -> ShortcutKey.TAB
        Key.L -> ShortcutKey.L
        Key.C -> ShortcutKey.C
        Key.AltLeft, Key.AltRight -> ShortcutKey.ALT
        else -> null
    }
}
