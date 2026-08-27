package ch.lkmc.bangnidraw.engine.core

internal data class ViewportResizeState(
    val view: ViewTransform,
    val fit: FitTransform,
)

/** Rebases a view once even when both Android owners report one resize. */
internal object ViewportResizePolicy {

    fun resize(state: ViewportResizeState, fit: FitTransform): ViewportResizeState {
        if (state.fit == fit) return state

        return ViewportResizeState(
            view = state.view.rebase(state.fit, fit),
            fit = fit,
        )
    }
}
