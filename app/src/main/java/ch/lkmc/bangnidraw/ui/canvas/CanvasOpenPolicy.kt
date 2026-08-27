package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.StringRes
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ProjectStore

internal sealed interface CanvasOpenDecision {
    data class Open(val project: ProjectStore.LoadResult.Loaded) : CanvasOpenDecision

    data class Reject(@StringRes val message: Int) : CanvasOpenDecision
}

/** Keeps creation out of the Canvas load boundary. */
internal object CanvasOpenPolicy {

    fun decide(result: ProjectStore.LoadResult): CanvasOpenDecision = when (result) {
        is ProjectStore.LoadResult.Loaded -> CanvasOpenDecision.Open(result)
        is ProjectStore.LoadResult.Failed -> CanvasOpenDecision.Reject(
            message = when (result.reason) {
                ProjectStore.FailureReason.NEWER_VERSION -> R.string.canvas_newer_version
                ProjectStore.FailureReason.BAD_ID,
                ProjectStore.FailureReason.NOT_FOUND,
                ProjectStore.FailureReason.UNREADABLE,
                -> R.string.canvas_open_failed
            },
        )
    }
}
