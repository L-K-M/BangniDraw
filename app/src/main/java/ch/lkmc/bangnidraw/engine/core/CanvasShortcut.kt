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

/** One advertised binding: the shortcut, its key, and the modifiers it needs. */
internal data class ShortcutLegendEntry(
    val shortcut: CanvasShortcut,
    val key: ShortcutKey,
    val modifiers: KeyModifiers,
    /** DOWN for everything but the eyedropper's release half. */
    val phase: KeyPhase = KeyPhase.DOWN,
)

/**
 * The shortcut table as the UI advertises it (the Settings sheet's legend).
 * Single-sourced here so the help cannot silently contradict the dispatcher:
 * `CanvasShortcutLegendTest` replays every entry through
 * [CanvasShortcuts.resolve], and the sheet renders from this list.
 *
 * REDO is advertised once (Ctrl+Shift+Z); its Ctrl+Y alias is the power
 * user's. The size pair and the eyedropper hold pair share one row each in
 * the sheet — their two entries carry the same action.
 */
internal object ShortcutLegend {

    val entries: List<ShortcutLegendEntry> = listOf(
        ShortcutLegendEntry(CanvasShortcut.UNDO, ShortcutKey.Z, KeyModifiers.CTRL),
        ShortcutLegendEntry(CanvasShortcut.REDO, ShortcutKey.Z, KeyModifiers.CTRL_SHIFT),
        ShortcutLegendEntry(CanvasShortcut.SIZE_DOWN, ShortcutKey.LEFT_BRACKET, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.SIZE_UP, ShortcutKey.RIGHT_BRACKET, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.BRUSH, ShortcutKey.B, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.ERASER, ShortcutKey.E, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.SMUDGE, ShortcutKey.S, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.WATER, ShortcutKey.W, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.FILL, ShortcutKey.G, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.EYEDROPPER, ShortcutKey.I, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.BEGIN_EYEDROPPER, ShortcutKey.ALT, KeyModifiers.NONE),
        ShortcutLegendEntry(
            CanvasShortcut.END_EYEDROPPER,
            ShortcutKey.ALT,
            KeyModifiers.NONE,
            KeyPhase.UP,
        ),
        ShortcutLegendEntry(CanvasShortcut.RESET_VIEW, ShortcutKey.DIGIT_ZERO, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.TOGGLE_FOCUS, ShortcutKey.TAB, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.TOGGLE_LAYERS, ShortcutKey.L, KeyModifiers.NONE),
        ShortcutLegendEntry(CanvasShortcut.TOGGLE_COLOR, ShortcutKey.C, KeyModifiers.NONE),
    )

    /** The key cap's text — physical key labels, not localized wording. */
    fun keyLabel(entry: ShortcutLegendEntry): String {
        val key = when (entry.key) {
            ShortcutKey.Z -> "Z"
            ShortcutKey.Y -> "Y"
            ShortcutKey.LEFT_BRACKET -> "["
            ShortcutKey.RIGHT_BRACKET -> "]"
            ShortcutKey.B -> "B"
            ShortcutKey.E -> "E"
            ShortcutKey.S -> "S"
            ShortcutKey.W -> "W"
            ShortcutKey.G -> "G"
            ShortcutKey.I -> "I"
            ShortcutKey.DIGIT_ZERO -> "0"
            ShortcutKey.TAB -> "Tab"
            ShortcutKey.L -> "L"
            ShortcutKey.C -> "C"
            ShortcutKey.ALT -> "Alt"
        }
        val modifiers = when (entry.modifiers) {
            KeyModifiers.NONE -> ""
            KeyModifiers.CTRL -> "Ctrl+"
            KeyModifiers.CTRL_SHIFT -> "Ctrl+Shift+"
        }
        return modifiers + key
    }
}
