package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs

internal enum class BufferPresentationDecision {
    USE_LIBRARY_TRANSFORM,
    NEUTRALIZE_HALF_TURN,
}

/** Avoids the unreliable axis-preserving pre-rotation path on some devices. */
internal object BufferPresentationPolicy {

    fun decide(
        transform: FloatArray,
        logicalWidth: Int,
        logicalHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
    ): BufferPresentationDecision {
        if (transform.size < MATRIX_SIZE) return BufferPresentationDecision.USE_LIBRARY_TRANSFORM
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            return BufferPresentationDecision.USE_LIBRARY_TRANSFORM
        }
        if (bufferWidth != logicalWidth || bufferHeight != logicalHeight) {
            return BufferPresentationDecision.USE_LIBRARY_TRANSFORM
        }

        // Column-major AndroidX ROTATE_180: (-x + width, -y + height).
        val isHalfTurn = near(transform[0], -1f) && near(transform[1], 0f) &&
            near(transform[2], 0f) && near(transform[3], 0f) &&
            near(transform[4], 0f) && near(transform[5], -1f) &&
            near(transform[6], 0f) && near(transform[7], 0f) &&
            near(transform[8], 0f) && near(transform[9], 0f) &&
            near(transform[10], 1f) && near(transform[11], 0f) &&
            near(transform[12], logicalWidth.toFloat()) &&
            near(transform[13], logicalHeight.toFloat()) &&
            near(transform[14], 0f) && near(transform[15], 1f)

        return if (isHalfTurn) {
            BufferPresentationDecision.NEUTRALIZE_HALF_TURN
        } else {
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM
        }
    }

    private fun near(actual: Float, expected: Float): Boolean =
        actual.isFinite() && abs(actual - expected) <= MATRIX_EPSILON

    private const val MATRIX_SIZE = 16
    private const val MATRIX_EPSILON = 0.001f
}
