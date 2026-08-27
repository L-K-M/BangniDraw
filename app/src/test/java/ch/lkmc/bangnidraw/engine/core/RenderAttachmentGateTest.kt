package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderAttachmentGateTest {

    @Test
    fun `every actionable plan retains its gate generation`() {
        val gate = RenderAttachmentGate()

        assertEquals(plan(0L, RenderDispatch.NONE), gate.requestScene())
        val first = gate.surfaceChanged()
        val firstCommit = gate.surfaceReady(first)

        val second = gate.surfaceChanged()

        assertEquals(plan(first, RenderDispatch.COMMIT), firstCommit)
        assertEquals(plan(second, RenderDispatch.NONE), gate.requestFront())
        assertEquals(plan(second, RenderDispatch.NONE), gate.endStroke())
    }

    @Test
    fun `startup scene waits for the current attachment`() {
        val gate = RenderAttachmentGate()

        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)
        val generation = gate.surfaceChanged()

        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(RenderDispatch.NONE, gate.surfaceReady(generation).dispatch)
    }

    @Test
    fun `only the current surface generation can become ready`() {
        val gate = RenderAttachmentGate()
        gate.requestScene()
        val stale = gate.surfaceChanged()
        val current = gate.surfaceChanged()

        assertEquals(RenderDispatch.NONE, gate.surfaceReady(stale).dispatch)
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(current).dispatch)
        assertEquals(accepted(current, RenderDispatch.NONE), gate.multiDrawCompleted(current))
        assertEquals(RenderDispatch.COMMIT, gate.requestScene().dispatch)

        gate.surfaceChanged()
        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)
    }

    @Test
    fun `destroy invalidates an in-flight ready marker`() {
        val gate = RenderAttachmentGate()
        gate.requestScene()
        val stale = gate.surfaceChanged()

        gate.surfaceDestroyed()

        assertEquals(RenderDispatch.NONE, gate.surfaceReady(stale).dispatch)
        val current = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(current).dispatch)
    }

    @Test
    fun `driver acceptance follows the current ready generation`() {
        val gate = RenderAttachmentGate()
        val first = gate.surfaceChanged()

        assertTrue(gate.isCurrentGeneration(first))
        assertFalse(gate.acceptsDriverCallback(first))

        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(first).dispatch)
        assertTrue(gate.isCurrentGeneration(first))
        assertTrue(gate.acceptsDriverCallback(first))

        val second = gate.surfaceChanged()
        assertFalse(gate.isCurrentGeneration(first))
        assertFalse(gate.acceptsDriverCallback(first))
        assertTrue(gate.isCurrentGeneration(second))
        assertFalse(gate.acceptsDriverCallback(second))

        gate.surfaceReady(second)
        gate.release()

        assertFalse(gate.isCurrentGeneration(second))
        assertFalse(gate.acceptsDriverCallback(second))
    }

    @Test
    fun `front work waits behind the attachment scene commit`() {
        val gate = RenderAttachmentGate()
        gate.requestScene()
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)

        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)

        assertEquals(accepted(generation, RenderDispatch.FRONT), gate.multiDrawCompleted(generation))
        assertEquals(RenderDispatch.FRONT, gate.requestFront().dispatch)
    }

    @Test
    fun `stale completion cannot clear the current commit`() {
        val gate = RenderAttachmentGate()
        val stale = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(stale).dispatch)

        val current = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(current).dispatch)

        assertEquals(AttachmentCompletion.Ignored, gate.multiDrawCompleted(stale))
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)
        assertEquals(accepted(current, RenderDispatch.FRONT), gate.multiDrawCompleted(current))
    }

    @Test
    fun `duplicate completion is ignored atomically`() {
        val gate = RenderAttachmentGate()
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)

        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
        assertEquals(AttachmentCompletion.Ignored, gate.multiDrawCompleted(generation))
    }

    @Test
    fun `scene requests during a commit coalesce behind it`() {
        val gate = RenderAttachmentGate()
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)

        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)

        assertEquals(accepted(generation, RenderDispatch.COMMIT), gate.multiDrawCompleted(generation))
        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
    }

    @Test
    fun `pen up drops deferred front work and latches one scene`() {
        val gate = RenderAttachmentGate()
        gate.requestFront()

        assertEquals(RenderDispatch.NONE, gate.endStroke().dispatch)
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
        assertEquals(RenderDispatch.FRONT, gate.requestFront().dispatch)
    }

    @Test
    fun `pen up commits immediately on a ready attachment`() {
        val gate = readyGate()

        assertEquals(RenderDispatch.COMMIT, gate.endStroke().dispatch)
    }

    @Test
    fun `pen up during a commit queues a scene and drops its front`() {
        val gate = RenderAttachmentGate()
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)

        assertEquals(RenderDispatch.NONE, gate.endStroke().dispatch)

        assertEquals(accepted(generation, RenderDispatch.COMMIT), gate.multiDrawCompleted(generation))
        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
    }

    @Test
    fun `cancel drops front work queued behind a commit`() {
        val gate = RenderAttachmentGate()
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)

        gate.cancelFront()

        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
    }

    @Test
    fun `release absorbs every event`() {
        val gate = RenderAttachmentGate()
        gate.requestScene()
        val stale = gate.surfaceChanged()
        gate.release()

        assertEquals(RenderDispatch.NONE, gate.requestScene().dispatch)
        assertEquals(RenderDispatch.NONE, gate.requestFront().dispatch)
        assertEquals(RenderDispatch.NONE, gate.endStroke().dispatch)
        assertEquals(RenderDispatch.NONE, gate.surfaceReady(stale).dispatch)
        val afterRelease = gate.surfaceChanged()
        assertEquals(RenderDispatch.NONE, gate.surfaceReady(afterRelease).dispatch)
        gate.surfaceDestroyed()
        assertEquals(AttachmentCompletion.Ignored, gate.multiDrawCompleted(stale))
    }

    private fun readyGate(): RenderAttachmentGate = RenderAttachmentGate().also { gate ->
        gate.requestScene()
        val generation = gate.surfaceChanged()
        assertEquals(RenderDispatch.COMMIT, gate.surfaceReady(generation).dispatch)
        assertEquals(accepted(generation, RenderDispatch.NONE), gate.multiDrawCompleted(generation))
    }

    private fun accepted(generation: Long, dispatch: RenderDispatch): AttachmentCompletion =
        AttachmentCompletion.Accepted(plan(generation, dispatch))

    private fun plan(generation: Long, dispatch: RenderDispatch): AttachmentRenderPlan =
        AttachmentRenderPlan(generation, dispatch)
}
