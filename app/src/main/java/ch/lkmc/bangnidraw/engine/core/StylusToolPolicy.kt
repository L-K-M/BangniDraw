package ch.lkmc.bangnidraw.engine.core

enum class PenButtonAction {
    Eraser,
    Eyedropper,
    None,
    ;

    companion object {
        fun fromStored(value: String?): PenButtonAction =
            entries.firstOrNull { it.name == value } ?: Eraser
    }
}

enum class ButtonState {
    Released,
    Pressed,
}

enum class TemporaryToolTarget {
    Eraser,
    Eyedropper,
}

data class TemporaryToolRequest(
    val target: TemporaryToolTarget,
    val reason: TemporaryReason,
)

/** Resolves eraser-end and barrel-button precedence before a stroke starts. */
object StylusToolPolicy {

    fun resolve(
        pointer: PointerTool,
        button: ButtonState,
        buttonAction: PenButtonAction,
    ): TemporaryToolRequest? {
        if (pointer == PointerTool.ERASER) {
            return TemporaryToolRequest(TemporaryToolTarget.Eraser, TemporaryReason.EraserEnd)
        }
        if (pointer != PointerTool.STYLUS || button != ButtonState.Pressed) return null

        return when (buttonAction) {
            PenButtonAction.Eraser ->
                TemporaryToolRequest(TemporaryToolTarget.Eraser, TemporaryReason.PenButton)
            PenButtonAction.Eyedropper ->
                TemporaryToolRequest(TemporaryToolTarget.Eyedropper, TemporaryReason.PenButton)
            PenButtonAction.None -> null
        }
    }
}
