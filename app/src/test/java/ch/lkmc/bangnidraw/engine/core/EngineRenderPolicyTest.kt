package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineRenderPolicyTest {

    @Test
    fun `a completion during a stroke requests one cumulative recovery`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()

        assertEquals(
            MultiDrawCompletion.RESUME_FRONT,
            policy.onMultiDrawCompleted(),
        )
        assertEquals(FrontFramePlan.RECOVER, policy.frontFrame())
        policy.frontFramePresented(FrontFramePlan.RECOVER)
        assertEquals(FrontFramePlan.PROTECTED, policy.frontFrame())
        assertEquals(MultiDrawCompletion.RESUME_FRONT, policy.resumeFront())
    }

    @Test
    fun `a completion before the stroke protects its front buffer`() {
        val policy = EngineRenderPolicy()

        assertEquals(MultiDrawCompletion.NONE, policy.onMultiDrawCompleted())
        policy.beginStroke()

        assertEquals(MultiDrawCompletion.NONE, policy.resumeFront())
        assertEquals(FrontFramePlan.PROTECTED, policy.frontFrame())
    }

    @Test
    fun `protected present includes an old tail outside the current preview`() {
        val incremental = IntRect(10, 20, 30, 40)
        val preview = IntRect(100, 120, 130, 140)
        val cumulative = incremental.union(preview)

        assertEquals(
            FrontFrameDirty(composite = incremental, present = incremental),
            FrontFramePlan.INCREMENTAL.dirty(incremental, preview),
        )
        assertEquals(
            FrontFrameDirty(composite = cumulative, present = cumulative),
            FrontFramePlan.RECOVER.dirty(incremental, preview),
        )
        assertEquals(
            FrontFrameDirty(composite = incremental, present = cumulative),
            FrontFramePlan.PROTECTED.dirty(incremental, preview),
        )
    }

    @Test
    fun `failed recovery remains pending`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.onMultiDrawCompleted()

        assertEquals(FrontFramePlan.RECOVER, policy.frontFrame())
        assertEquals(FrontFramePlan.RECOVER, policy.frontFrame())

        policy.frontFramePresented(FrontFramePlan.RECOVER)

        assertEquals(FrontFramePlan.PROTECTED, policy.frontFrame())
    }

    @Test
    fun `multiple completions coalesce before the main thread resumes`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()

        assertEquals(MultiDrawCompletion.RESUME_FRONT, policy.onMultiDrawCompleted())
        assertEquals(MultiDrawCompletion.NONE, policy.onMultiDrawCompleted())
        assertEquals(MultiDrawCompletion.RESUME_FRONT, policy.resumeFront())
        assertEquals(MultiDrawCompletion.NONE, policy.resumeFront())
    }

    @Test
    fun `finishing a stroke cancels its queued resume and cumulative mode`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.onMultiDrawCompleted()

        policy.finishStroke(StrokeFinish.COMMIT)

        assertEquals(MultiDrawCompletion.NONE, policy.resumeFront())
        assertEquals(FrontFramePlan.INCREMENTAL, policy.frontFrame())
    }

    @Test
    fun `an active completion also protects the next stroke`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.onMultiDrawCompleted()
        policy.finishStroke(StrokeFinish.COMMIT)

        policy.beginStroke()

        assertEquals(FrontFramePlan.PROTECTED, policy.frontFrame())
    }

    @Test
    fun `redraw during a committed stroke is covered by commit`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()

        assertEquals(RedrawDecision.DEFER, policy.requestRedraw())
        assertEquals(RedrawDecision.COVERED, policy.finishStroke(StrokeFinish.COMMIT))
        assertEquals(RedrawDecision.DRAW, policy.requestRedraw())
    }

    @Test
    fun `redraw during a buffered cancel runs after cancel`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()

        assertEquals(RedrawDecision.DEFER, policy.requestRedraw())
        assertEquals(
            RedrawDecision.DRAW,
            policy.finishStroke(StrokeFinish.CANCEL_BUFFERED),
        )
    }

    @Test
    fun `redraw during RMW cancel waits for pixel restoration`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()

        assertEquals(RedrawDecision.DEFER, policy.requestRedraw())
        assertEquals(
            RedrawDecision.DEFER,
            policy.finishStroke(StrokeFinish.CANCEL_READ_MODIFY_WRITE),
        )
        assertEquals(RedrawDecision.DEFER, policy.requestRedraw())
        assertEquals(RedrawDecision.DRAW, policy.completeRmwCancel())
        assertEquals(RedrawDecision.DRAW, policy.requestRedraw())
    }

    @Test
    fun `RMW completion without a deferred redraw is covered`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.finishStroke(StrokeFinish.CANCEL_READ_MODIFY_WRITE)

        assertEquals(RedrawDecision.COVERED, policy.completeRmwCancel())
    }

    @Test
    fun `redraw requested during RMW restoration flushes after restoration`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.finishStroke(StrokeFinish.CANCEL_READ_MODIFY_WRITE)

        assertEquals(RedrawDecision.DEFER, policy.requestRedraw())
        assertEquals(RedrawDecision.DRAW, policy.completeRmwCancel())
    }

    @Test
    fun `release discards queued rendering state`() {
        val policy = EngineRenderPolicy()
        policy.beginStroke()
        policy.requestRedraw()
        policy.onMultiDrawCompleted()

        policy.release()

        assertEquals(MultiDrawCompletion.NONE, policy.onMultiDrawCompleted())
        assertEquals(MultiDrawCompletion.NONE, policy.resumeFront())
        assertEquals(FrontFramePlan.INCREMENTAL, policy.frontFrame())
        assertEquals(RedrawDecision.COVERED, policy.requestRedraw())
    }
}
