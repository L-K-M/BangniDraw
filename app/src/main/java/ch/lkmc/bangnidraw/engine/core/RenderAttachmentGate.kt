package ch.lkmc.bangnidraw.engine.core

internal enum class RenderDispatch {
    NONE,
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

    private enum class State { WAITING, READY, RELEASED }

    private var state = State.WAITING
    private var generation = 0L
    private var scenePending = false
    private var frontPending = false
    private var commitInFlight = false

    @Synchronized
    fun requestScene(): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (state != State.READY || commitInFlight) {
            scenePending = true
            return plan(RenderDispatch.NONE)
        }

        commitInFlight = true
        return plan(RenderDispatch.COMMIT)
    }

    @Synchronized
    fun requestFront(): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (state != State.READY || commitInFlight || scenePending) {
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
        commitInFlight = false
        return generation
    }

    @Synchronized
    fun surfaceDestroyed() {
        if (state == State.RELEASED) return

        generation += 1
        state = State.WAITING
        scenePending = true
        commitInFlight = false
    }

    @Synchronized
    fun isCurrentGeneration(candidate: Long): Boolean =
        state != State.RELEASED && candidate == generation

    @Synchronized
    fun acceptsDriverCallback(candidate: Long): Boolean =
        state == State.READY && candidate == generation

    @Synchronized
    fun surfaceReady(readyGeneration: Long): AttachmentRenderPlan {
        if (state == State.RELEASED) return plan(RenderDispatch.NONE)
        if (readyGeneration != generation || state == State.READY) {
            return plan(RenderDispatch.NONE)
        }

        state = State.READY
        if (scenePending) {
            scenePending = false
            commitInFlight = true
            return plan(RenderDispatch.COMMIT)
        }
        if (!frontPending) return plan(RenderDispatch.NONE)

        frontPending = false
        return plan(RenderDispatch.FRONT)
    }

    @Synchronized
    fun multiDrawCompleted(completedGeneration: Long): AttachmentCompletion {
        if (
            state != State.READY ||
            completedGeneration != generation ||
            !commitInFlight
        ) {
            return AttachmentCompletion.Ignored
        }

        commitInFlight = false
        if (scenePending) {
            scenePending = false
            commitInFlight = true
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
        commitInFlight = false
    }

    private fun plan(dispatch: RenderDispatch): AttachmentRenderPlan =
        AttachmentRenderPlan(generation, dispatch)
}
