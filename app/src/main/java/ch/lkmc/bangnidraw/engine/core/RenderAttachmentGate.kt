package ch.lkmc.bangnidraw.engine.core

internal enum class RenderDispatch {
    NONE,
    BOOTSTRAP,
    COMMIT,
    FRONT,
}

internal data class AttachmentRenderPlan(
    val generation: Long,
    val dispatch: RenderDispatch,
)

internal sealed interface AttachmentCompletion {
    data object Ignored : AttachmentCompletion

    data class Accepted(val plan: AttachmentRenderPlan) : AttachmentCompletion
}

/** Serializes rendering behind the current SurfaceView attachment. */
internal class RenderAttachmentGate {

    private enum class State { WAITING, BOOTSTRAPPING, READY, RELEASED }

    private var state = State.WAITING
    private var generation = 0L
    private var scenePending = false
    private var frontPending = false
    private var multiDrawInFlight = false

    @Synchronized
    fun requestScene(): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (state != State.READY || multiDrawInFlight) {
            scenePending = true
            return plan(RenderDispatch.NONE)
        }

        multiDrawInFlight = true
        return plan(RenderDispatch.COMMIT)
    }

    @Synchronized
    fun requestFront(): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (state != State.READY || multiDrawInFlight || scenePending) {
            frontPending = true
            return plan(RenderDispatch.NONE)
        }

        frontPending = false
        return plan(RenderDispatch.FRONT)
    }

    @Synchronized
    fun endStroke(): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)

        frontPending = false
        return requestScene()
    }

    @Synchronized
    fun cancelFront() {
        frontPending = false
    }

    @Synchronized
    fun surfaceChanged(): Long {
        if (state == State.RELEASED) return generation

        generation += 1
        state = State.WAITING
        scenePending = true
        multiDrawInFlight = false
        return generation
    }

    @Synchronized
    fun surfaceDestroyed() {
        if (state == State.RELEASED) return

        generation += 1
        state = State.WAITING
        scenePending = true
        multiDrawInFlight = false
    }

    @Synchronized
    fun isCurrentGeneration(candidate: Long): Boolean =
        state != State.RELEASED && candidate == generation

    @Synchronized
    fun acceptsDriverCallback(candidate: Long): Boolean =
        (state == State.BOOTSTRAPPING || state == State.READY) && candidate == generation

    @Synchronized
    fun surfaceReady(readyGeneration: Long): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (readyGeneration != generation || state != State.WAITING) {
            return plan(RenderDispatch.NONE)
        }

        // graphics-core 1.0.4 decrements commitCount only when the previously
        // displayed multi buffer is replaced. The first frame has no previous
        // buffer, so commit() would strand its own count and every front draw.
        // Seed each attachment directly; all later scene draws use commit().
        state = State.BOOTSTRAPPING
        scenePending = false
        multiDrawInFlight = true
        return plan(RenderDispatch.BOOTSTRAP)
    }

    @Synchronized
    fun multiDrawCompleted(completedGeneration: Long): AttachmentCompletion {
        if (
            (state != State.BOOTSTRAPPING && state != State.READY) ||
            completedGeneration != generation ||
            !multiDrawInFlight
        ) {
            return AttachmentCompletion.Ignored
        }

        if (state == State.BOOTSTRAPPING) state = State.READY
        multiDrawInFlight = false
        if (scenePending) {
            scenePending = false
            multiDrawInFlight = true
            return AttachmentCompletion.Accepted(plan(RenderDispatch.COMMIT))
        }
        if (!frontPending) {
            return AttachmentCompletion.Accepted(plan(RenderDispatch.NONE))
        }

        frontPending = false
        return AttachmentCompletion.Accepted(plan(RenderDispatch.FRONT))
    }

    @Synchronized
    fun release() {
        state = State.RELEASED
        scenePending = false
        frontPending = false
        multiDrawInFlight = false
    }

    private fun plan(dispatch: RenderDispatch): AttachmentRenderPlan =
        AttachmentRenderPlan(generation, dispatch)
}
