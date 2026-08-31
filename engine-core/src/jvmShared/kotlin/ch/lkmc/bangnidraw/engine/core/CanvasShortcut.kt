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

/** One Settings row: the chord as printed on a keyboard, and what it runs. */
internal data class ShortcutCatalogEntry(
    val key: ShortcutKey,
    val modifiers: KeyModifiers,
    val chord: String,
    val action: CanvasShortcut,
)

/**
 * The display side of [CanvasShortcuts]: the same table, laid out for the
 * Settings sheet. Entries are cross-checked against `resolve` by
 * `CanvasShortcutCatalogTest`, so help text cannot drift from behaviour.
 */
internal object CanvasShortcutCatalog {

    val rows: List<ShortcutCatalogEntry> = listOf(
        entry(ShortcutKey.Z, KeyModifiers.CTRL, "Ctrl + Z", CanvasShortcut.UNDO),
        entry(ShortcutKey.Z, KeyModifiers.CTRL_SHIFT, "Ctrl + Shift + Z", CanvasShortcut.REDO),
        entry(ShortcutKey.Y, KeyModifiers.CTRL, "Ctrl + Y", CanvasShortcut.REDO),
        entry(ShortcutKey.LEFT_BRACKET, KeyModifiers.NONE, "[", CanvasShortcut.SIZE_DOWN),
        entry(ShortcutKey.RIGHT_BRACKET, KeyModifiers.NONE, "]", CanvasShortcut.SIZE_UP),
        entry(ShortcutKey.B, KeyModifiers.NONE, "B", CanvasShortcut.BRUSH),
        entry(ShortcutKey.E, KeyModifiers.NONE, "E", CanvasShortcut.ERASER),
        entry(ShortcutKey.S, KeyModifiers.NONE, "S", CanvasShortcut.SMUDGE),
        entry(ShortcutKey.W, KeyModifiers.NONE, "W", CanvasShortcut.WATER),
        entry(ShortcutKey.G, KeyModifiers.NONE, "G", CanvasShortcut.FILL),
        entry(ShortcutKey.I, KeyModifiers.NONE, "I", CanvasShortcut.EYEDROPPER),
        entry(ShortcutKey.ALT, KeyModifiers.NONE, "Alt", CanvasShortcut.BEGIN_EYEDROPPER),
        entry(ShortcutKey.DIGIT_ZERO, KeyModifiers.NONE, "0", CanvasShortcut.RESET_VIEW),
        entry(ShortcutKey.TAB, KeyModifiers.NONE, "Tab", CanvasShortcut.TOGGLE_FOCUS),
        entry(ShortcutKey.L, KeyModifiers.NONE, "L", CanvasShortcut.TOGGLE_LAYERS),
        entry(ShortcutKey.C, KeyModifiers.NONE, "C", CanvasShortcut.TOGGLE_COLOR),
    )

    private fun entry(
        key: ShortcutKey,
        modifiers: KeyModifiers,
        chord: String,
        action: CanvasShortcut,
    ) = ShortcutCatalogEntry(key, modifiers, chord, action)
}

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
