package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30

internal enum class FenceWaitPhase { FIRST, LATER }

internal object GlFenceWaitPolicy {
    /** An idle producer has no later swap to submit its readback commands. */
    fun flags(phase: FenceWaitPhase): Int = when (phase) {
        FenceWaitPhase.FIRST -> GLES30.GL_SYNC_FLUSH_COMMANDS_BIT
        FenceWaitPhase.LATER -> 0
    }
}
