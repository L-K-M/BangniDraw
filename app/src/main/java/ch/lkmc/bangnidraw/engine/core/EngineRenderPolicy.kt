package ch.lkmc.bangnidraw.engine.core

internal enum class RedrawDecision { DRAW, DEFER, COVERED }

private enum class FrontDirtySource { INCREMENTAL, CUMULATIVE }

internal data class FrontFrameDirty(
    val composite: IntRect,
    val present: IntRect,
)

internal enum class FrontFramePlan(
    private val compositeSource: FrontDirtySource,
    private val presentSource: FrontDirtySource,
) {
    INCREMENTAL(FrontDirtySource.INCREMENTAL, FrontDirtySource.INCREMENTAL),
    RECOVER(FrontDirtySource.CUMULATIVE, FrontDirtySource.CUMULATIVE),
    PROTECTED(FrontDirtySource.INCREMENTAL, FrontDirtySource.CUMULATIVE),
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

internal enum class MultiDrawCompletion { NONE, RESUME_FRONT }

internal enum class StrokeFinish { COMMIT, CANCEL_BUFFERED, CANCEL_READ_MODIFY_WRITE }

/** Keeps a multi-buffer completion from erasing a newer front-buffer stroke. */
internal class EngineRenderPolicy {

    private var released = false
    private var strokeActive = false
    private var deferredRedraw = false
    private var recoverCumulative = false
    private var protectCurrentStroke = false
    private var protectNextStroke = false
    private var resumeQueued = false
    private var rmwCancelPending = false

    @Synchronized
    fun beginStroke() {
        if (released) return

        strokeActive = true
        recoverCumulative = false
        protectCurrentStroke = protectNextStroke
        protectNextStroke = false
        resumeQueued = false
    }

    @Synchronized
    fun requestRedraw(): RedrawDecision {
        if (released) return RedrawDecision.COVERED
        if (!strokeActive && !rmwCancelPending) return RedrawDecision.DRAW

        deferredRedraw = true
        return RedrawDecision.DEFER
    }

    @Synchronized
    fun finishStroke(finish: StrokeFinish): RedrawDecision {
        if (released) return RedrawDecision.COVERED

        strokeActive = false
        recoverCumulative = false
        protectCurrentStroke = false
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

        // Release can clear the front later, after a new stroke has begun.
        protectNextStroke = true
        if (!strokeActive) return MultiDrawCompletion.NONE

        recoverCumulative = true
        protectCurrentStroke = true
        if (resumeQueued) return MultiDrawCompletion.NONE

        resumeQueued = true
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
        if (!recoverCumulative) {
            return if (protectCurrentStroke) {
                FrontFramePlan.PROTECTED
            } else {
                FrontFramePlan.INCREMENTAL
            }
        }

        return FrontFramePlan.RECOVER
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
        protectCurrentStroke = false
        protectNextStroke = false
        resumeQueued = false
        rmwCancelPending = false
    }
}
