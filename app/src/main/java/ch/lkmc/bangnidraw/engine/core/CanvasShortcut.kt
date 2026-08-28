package ch.lkmc.bangnidraw.engine.core

internal enum class ShortcutKey {
    Z,
    Y,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    B,
    E,
    S,
    W,
    G,
    I,
    DIGIT_ZERO,
    TAB,
    L,
    C,
    ALT,
}

internal enum class KeyPhase { DOWN, UP }

internal enum class KeyModifiers {
    NONE,
    CTRL,
    CTRL_SHIFT,
}

internal enum class CanvasShortcut {
    UNDO,
    REDO,
    SIZE_DOWN,
    SIZE_UP,
    BRUSH,
    ERASER,
    SMUDGE,
    WATER,
    FILL,
    EYEDROPPER,
    BEGIN_EYEDROPPER,
    END_EYEDROPPER,
    RESET_VIEW,
    TOGGLE_FOCUS,
    TOGGLE_LAYERS,
    TOGGLE_COLOR,
}

internal enum class ShortcutContext { CANVAS, TEXT_INPUT }

internal enum class SizeAdjustment { DECREASE, INCREASE }

/** Pure keyboard table; MainActivity only translates KeyEvent fields. */
internal object CanvasShortcuts {

    fun resolve(
        key: ShortcutKey,
        phase: KeyPhase,
        modifiers: KeyModifiers,
    ): CanvasShortcut? {
        if (key == ShortcutKey.ALT) {
            return if (phase == KeyPhase.DOWN) {
                CanvasShortcut.BEGIN_EYEDROPPER
            } else {
                CanvasShortcut.END_EYEDROPPER
            }
        }
        if (phase == KeyPhase.UP) return null

        if (key == ShortcutKey.Z && modifiers == KeyModifiers.CTRL) {
            return CanvasShortcut.UNDO
        }
        if (key == ShortcutKey.Z && modifiers == KeyModifiers.CTRL_SHIFT) {
            return CanvasShortcut.REDO
        }
        if (key == ShortcutKey.Y && modifiers == KeyModifiers.CTRL) {
            return CanvasShortcut.REDO
        }
        if (modifiers != KeyModifiers.NONE) return null

        return when (key) {
            ShortcutKey.LEFT_BRACKET -> CanvasShortcut.SIZE_DOWN
            ShortcutKey.RIGHT_BRACKET -> CanvasShortcut.SIZE_UP
            ShortcutKey.B -> CanvasShortcut.BRUSH
            ShortcutKey.E -> CanvasShortcut.ERASER
            ShortcutKey.S -> CanvasShortcut.SMUDGE
            ShortcutKey.W -> CanvasShortcut.WATER
            ShortcutKey.G -> CanvasShortcut.FILL
            ShortcutKey.I -> CanvasShortcut.EYEDROPPER
            ShortcutKey.DIGIT_ZERO -> CanvasShortcut.RESET_VIEW
            ShortcutKey.TAB -> CanvasShortcut.TOGGLE_FOCUS
            ShortcutKey.L -> CanvasShortcut.TOGGLE_LAYERS
            ShortcutKey.C -> CanvasShortcut.TOGGLE_COLOR
            ShortcutKey.Z, ShortcutKey.Y, ShortcutKey.ALT -> null
        }
    }
}
