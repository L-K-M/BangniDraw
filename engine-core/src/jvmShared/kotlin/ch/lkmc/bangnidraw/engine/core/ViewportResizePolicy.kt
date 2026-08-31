package ch.lkmc.bangnidraw.engine.core

internal enum class ViewportResizeOwner {
    INPUT,
    RENDERER,
}

internal data class ViewportResizeState(
    val view: ViewTransform,
    val fit: FitTransform,
)

/** Keeps viewport rebasing with input while both Android boundaries observe resize. */
internal object ViewportResizePolicy {

    fun resize(
        state: ViewportResizeState,
        fit: FitTransform,
        owner: ViewportResizeOwner,
    ): ViewportResizeState {
        if (state.fit == fit) return state

        val view = when (owner) {
            ViewportResizeOwner.INPUT -> state.view.rebase(state.fit, fit)
            ViewportResizeOwner.RENDERER -> state.view
        }

        return ViewportResizeState(view, fit)
    }
}
