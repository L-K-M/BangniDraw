package ch.lkmc.bangnidraw.engine.core

enum class CanvasPanel {
    LAYERS,
    COLOR,
    BRUSH_SETTINGS,
    FILL_SETTINGS,
    REFERENCE,
    OVERFLOW,
}

sealed interface CanvasDialog {
    data class MergeLayers(val index: Int) : CanvasDialog
    data object FlattenLayers : CanvasDialog
    data class ClearLayer(val index: Int) : CanvasDialog
    data class RenameLayer(val index: Int, val currentName: String) : CanvasDialog
    data object RenamePainting : CanvasDialog
    data object RemoveTracingReference : CanvasDialog
}

enum class FocusMode { CHROME, FOCUSED }

enum class HintVisibility { HIDDEN, VISIBLE }

enum class StrokeActivity { IDLE, ACTIVE }

data class CanvasChromeState(
    val openPanel: CanvasPanel? = null,
    val focusMode: FocusMode = FocusMode.CHROME,
    val strokeActivity: StrokeActivity = StrokeActivity.IDLE,
    val dialog: CanvasDialog? = null,
    val pendingDialog: CanvasDialog? = null,
    val hint: HintVisibility = HintVisibility.HIDDEN,
)

enum class CanvasTapEffect {
    DRAW,
    DISMISS_PANEL,
    DISMISS_HINT,
}

data class CanvasTapResult(
    val state: CanvasChromeState,
    val effect: CanvasTapEffect,
)

enum class CanvasBackEffect {
    CLOSE_DIALOG,
    CLOSE_PANEL,
    EXIT_FOCUS,
    LEAVE,
}

data class CanvasBackResult(
    val state: CanvasChromeState,
    val effect: CanvasBackEffect,
)

/** Pure state transitions behind Canvas chrome ordering and input consumption. */
object CanvasUiPolicy {

    fun togglePanel(state: CanvasChromeState, panel: CanvasPanel): CanvasChromeState {
        val next = if (state.openPanel == panel) null else panel
        return state.copy(openPanel = next)
    }

    fun dismissPanel(state: CanvasChromeState): CanvasChromeState =
        state.copy(openPanel = null)

    fun enterFocus(state: CanvasChromeState): CanvasChromeState = state.copy(
        openPanel = null,
        focusMode = FocusMode.FOCUSED,
    )

    fun exitFocus(state: CanvasChromeState): CanvasChromeState =
        state.copy(focusMode = FocusMode.CHROME)

    fun onStrokeBegin(state: CanvasChromeState): CanvasChromeState =
        state.copy(strokeActivity = StrokeActivity.ACTIVE)

    fun onStrokeEnd(state: CanvasChromeState): CanvasChromeState {
        val promoted = if (state.dialog == null) state.pendingDialog else null
        return state.copy(
            strokeActivity = StrokeActivity.IDLE,
            dialog = state.dialog ?: promoted,
            pendingDialog = if (promoted == null) state.pendingDialog else null,
        )
    }

    fun requestDialog(state: CanvasChromeState, dialog: CanvasDialog): CanvasChromeState {
        if (state.strokeActivity == StrokeActivity.ACTIVE) {
            return state.copy(pendingDialog = dialog)
        }
        return state.copy(dialog = dialog)
    }

    fun dismissDialog(state: CanvasChromeState): CanvasChromeState =
        state.copy(dialog = null)

    fun showHint(state: CanvasChromeState): CanvasChromeState =
        state.copy(hint = HintVisibility.VISIBLE)

    fun canvasTap(state: CanvasChromeState): CanvasTapResult {
        if (state.hint == HintVisibility.VISIBLE) {
            return CanvasTapResult(
                state.copy(hint = HintVisibility.HIDDEN),
                CanvasTapEffect.DISMISS_HINT,
            )
        }
        if (state.openPanel != null) {
            return CanvasTapResult(
                state.copy(openPanel = null),
                CanvasTapEffect.DISMISS_PANEL,
            )
        }
        return CanvasTapResult(state, CanvasTapEffect.DRAW)
    }

    fun back(state: CanvasChromeState): CanvasBackResult {
        if (state.dialog != null) {
            return CanvasBackResult(
                state.copy(dialog = null),
                CanvasBackEffect.CLOSE_DIALOG,
            )
        }
        if (state.openPanel != null) {
            return CanvasBackResult(
                state.copy(openPanel = null),
                CanvasBackEffect.CLOSE_PANEL,
            )
        }
        if (state.focusMode == FocusMode.FOCUSED) {
            return CanvasBackResult(
                state.copy(focusMode = FocusMode.CHROME),
                CanvasBackEffect.EXIT_FOCUS,
            )
        }
        return CanvasBackResult(state, CanvasBackEffect.LEAVE)
    }
}
