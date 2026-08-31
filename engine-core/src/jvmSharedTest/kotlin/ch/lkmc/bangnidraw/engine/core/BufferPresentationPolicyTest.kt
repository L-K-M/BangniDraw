package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BufferPresentationPolicyTest {

    @Test
    fun `canonical half turn is neutralized when buffer axes are unchanged`() {
        val matrix = halfTurn(LOGICAL_WIDTH.toFloat(), LOGICAL_HEIGHT.toFloat())

        assertEquals(
            BufferPresentationDecision.NEUTRALIZE_HALF_TURN,
            decide(matrix, LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )

        // Android's sin(pi) and matrix translation may carry float noise.
        matrix[0] = -0.999_999_94f
        matrix[1] = -0.000_000_09f
        matrix[4] = 0.000_000_09f
        matrix[12] += 0.000_1f
        assertEquals(
            BufferPresentationDecision.NEUTRALIZE_HALF_TURN,
            decide(matrix, LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )
    }

    @Test
    fun `identity and quarter turns keep graphics core presentation`() {
        assertEquals(
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM,
            decide(identity(), LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )
        assertEquals(
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM,
            decide(quarterTurn(LOGICAL_HEIGHT.toFloat()), LOGICAL_HEIGHT, LOGICAL_WIDTH),
        )
    }

    @Test
    fun `noncanonical half turns are not neutralized`() {
        val wrongTranslation = halfTurn(LOGICAL_WIDTH.toFloat(), LOGICAL_HEIGHT.toFloat()).also {
            it[12] = 0f
        }

        assertEquals(
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM,
            decide(wrongTranslation, LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )
        assertEquals(
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM,
            BufferPresentationPolicy.decide(
                transform = halfTurn(LOGICAL_WIDTH.toFloat(), LOGICAL_HEIGHT.toFloat()),
                logicalWidth = LOGICAL_WIDTH,
                logicalHeight = LOGICAL_HEIGHT,
                bufferWidth = LOGICAL_HEIGHT,
                bufferHeight = LOGICAL_WIDTH,
            ),
        )
        assertEquals(
            BufferPresentationDecision.USE_LIBRARY_TRANSFORM,
            decide(FloatArray(MALFORMED_MATRIX_SIZE), LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )
    }

    private fun decide(
        transform: FloatArray,
        bufferWidth: Int,
        bufferHeight: Int,
    ): BufferPresentationDecision = BufferPresentationPolicy.decide(
        transform = transform,
        logicalWidth = LOGICAL_WIDTH,
        logicalHeight = LOGICAL_HEIGHT,
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
    )

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun halfTurn(width: Float, height: Float) = floatArrayOf(
        -1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        width, height, 0f, 1f,
    )

    private fun quarterTurn(sourceHeight: Float) = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        sourceHeight, 0f, 0f, 1f,
    )

    private companion object {
        const val LOGICAL_WIDTH = 1600
        const val LOGICAL_HEIGHT = 2560
        const val MALFORMED_MATRIX_SIZE = 8
    }
}
