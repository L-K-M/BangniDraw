package ch.lkmc.bangnidraw.engine.core

enum class RedrawDecision { DRAW, DEFER, COVERED }

enum class OverlayRedrawDecision { SCENE, FRONT, DEFER, COVERED }

private enum class FrontDirtySource { INCREMENTAL, CUMULATIVE }

data class FrontFrameDirty(
    val composite: IntRect,
    val present: IntRect,
)

enum class FrontFramePlan(
    private val compositeSource: FrontDirtySource,
    private val presentSource: FrontDirtySource,
) {
    INCREMENTAL(FrontDirtySource.INCREMENTAL, FrontDirtySource.INCREMENTAL),
    RECOVER(FrontDirtySource.CUMULATIVE, FrontDirtySource.CUMULATIVE),
    ;

    fun dirty(incremental: IntRect, preview: IntRect): FrontFrameDirty {
        val cumulative = preview.union(incremental)

        return FrontFrameDirty(
            composite = compositeSource.select(incremental, cumulative),
            present = presentSource.select(incremental, cumulative),
        )
    }

    private fun FrontDirtySource.select(incremental: IntRect, cumulative: IntRect): IntRect =
        when (this) {
            FrontDirtySource.INCREMENTAL -> incremental
            FrontDirtySource.CUMULATIVE -> cumulative
        }
}

enum class MultiDrawCompletion { NONE, RESUME_FRONT }

enum class StrokeFinish { COMMIT, CANCEL_BUFFERED, CANCEL_READ_MODIFY_WRITE }

/** Recovers a live stroke once after a multi-buffer transition. */
class EngineRenderPolicy {

    private var released = false
    private var strokeActive = false
    private var deferredRedraw = false
    private var recoverCumulative = false
    private var resumeQueued = false
    private var rmwCancelPending = false

    @Synchronized
    fun beginStroke() {
        if (released) return

        strokeActive = true
        recoverCumulative = false
        resumeQueued = false
    }

    @Synchronized
    fun requestRedraw(): RedrawDecision {
        if (released) return RedrawDecision.COVERED
        if (!strokeActive && !rmwCancelPending) return RedrawDecision.DRAW

        deferredRedraw = true
        return RedrawDecision.DEFER
    }

    /** Keeps transient presentation on the cancellable front buffer mid-stroke. */
    @Synchronized
    fun requestOverlayRedraw(): OverlayRedrawDecision {
        if (released) return OverlayRedrawDecision.COVERED
        if (rmwCancelPending) {
            deferredRedraw = true
            return OverlayRedrawDecision.DEFER
        }
        if (!strokeActive) return OverlayRedrawDecision.SCENE

        // Cancel exposes the older multi-buffer scene, so it still needs a redraw.
        deferredRedraw = true
        return OverlayRedrawDecision.FRONT
    }

    @Synchronized
    fun finishStroke(finish: StrokeFinish): RedrawDecision {
        if (released) return RedrawDecision.COVERED

        strokeActive = false
        recoverCumulative = false
        resumeQueued = false
        val redrawWasDeferred = deferredRedraw

        return when (finish) {
            StrokeFinish.COMMIT -> {
                deferredRedraw = false
                RedrawDecision.COVERED
            }
            StrokeFinish.CANCEL_BUFFERED -> {
                deferredRedraw = false
                if (redrawWasDeferred) RedrawDecision.DRAW else RedrawDecision.COVERED
            }
            StrokeFinish.CANCEL_READ_MODIFY_WRITE -> {
                rmwCancelPending = true
                if (redrawWasDeferred) RedrawDecision.DEFER else RedrawDecision.COVERED
            }
        }
    }

    @Synchronized
    fun completeRmwCancel(): RedrawDecision {
        if (released) return RedrawDecision.COVERED
        if (!rmwCancelPending) return RedrawDecision.COVERED

        rmwCancelPending = false
        val redrawWasDeferred = deferredRedraw
        deferredRedraw = false

        return if (redrawWasDeferred) RedrawDecision.DRAW else RedrawDecision.COVERED
    }

    @Synchronized
    fun onMultiDrawCompleted(): MultiDrawCompletion {
        if (released) return MultiDrawCompletion.NONE
        if (!strokeActive) return MultiDrawCompletion.NONE

        recoverCumulative = true
        if (resumeQueued) return MultiDrawCompletion.NONE

        resumeQueued = true
        return MultiDrawCompletion.RESUME_FRONT
    }

    @Synchronized
    fun onFrontInvalidated(): MultiDrawCompletion {
        if (released || !strokeActive) return MultiDrawCompletion.NONE

        recoverCumulative = true
        return MultiDrawCompletion.RESUME_FRONT
    }

    @Synchronized
    fun resumeFront(): MultiDrawCompletion {
        if (released) return MultiDrawCompletion.NONE
        if (!strokeActive || !resumeQueued) return MultiDrawCompletion.NONE

        resumeQueued = false
        return MultiDrawCompletion.RESUME_FRONT
    }

    @Synchronized
    fun frontFrame(): FrontFramePlan {
        if (released) return FrontFramePlan.INCREMENTAL
        if (!strokeActive) return FrontFramePlan.INCREMENTAL
        return if (recoverCumulative) FrontFramePlan.RECOVER else FrontFramePlan.INCREMENTAL
    }

    @Synchronized
    fun frontFramePresented(plan: FrontFramePlan) {
        if (released) return
        if (plan == FrontFramePlan.RECOVER) recoverCumulative = false
    }

    @Synchronized
    fun release() {
        released = true
        strokeActive = false
        deferredRedraw = false
        recoverCumulative = false
        resumeQueued = false
        rmwCancelPending = false
    }
}
