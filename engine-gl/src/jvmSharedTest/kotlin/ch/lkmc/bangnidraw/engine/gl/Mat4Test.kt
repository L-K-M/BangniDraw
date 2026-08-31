package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BufferScissor
import ch.lkmc.bangnidraw.engine.core.IntRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mat4Test {

    @Test
    fun `y up projection writes top first hardware buffer rows`() {
        val matrix = Mat4.orthoYUp(WIDTH, HEIGHT)

        assertContentEquals(
            floatArrayOf(
                2f / WIDTH, 0f, 0f, 0f,
                0f, 2f / HEIGHT, 0f, 0f,
                0f, 0f, -1f, 0f,
                -1f, -1f, 0f, 1f,
            ),
            matrix,
        )
        assertEquals(-1f, projectX(matrix, 0f))
        assertEquals(-1f, projectY(matrix, 0f))
        assertEquals(1f, projectX(matrix, WIDTH))
        assertEquals(1f, projectY(matrix, HEIGHT))
    }

    @Test
    fun `identity present keeps the known good Huawei pixel row`() {
        val sourceV = 1f - POINT_Y / HEIGHT
        val presentVertexY = (1f - sourceV) * HEIGHT
        val presentRow = unproject(
            projectY(Mat4.orthoYUp(WIDTH, HEIGHT), presentVertexY),
            HEIGHT,
        )
        val legacyVertexY = sourceV * HEIGHT
        val legacyRow = unproject(
            projectY(Mat4.orthoYDown(WIDTH, HEIGHT), legacyVertexY),
            HEIGHT,
        )

        assertNear(POINT_Y, presentRow)
        assertNear(POINT_Y, legacyRow)
    }

    @Test
    fun `presented pixels and damage agree for every buffer rotation`() {
        val sourceU = POINT_X / WIDTH
        val sourceV = 1f - POINT_Y / HEIGHT
        val vertexX = sourceU * WIDTH
        val vertexY = (1f - sourceV) * HEIGHT
        val damage = IntRect(1099, 299, 1102, 302)
        val cases = listOf(
            RotationCase(identity(), WIDTH.toInt(), HEIGHT.toInt(), 1100f, 300f),
            RotationCase(rotate90(HEIGHT), HEIGHT.toInt(), WIDTH.toInt(), 2260f, 1100f),
            RotationCase(halfTurn(WIDTH, HEIGHT), WIDTH.toInt(), HEIGHT.toInt(), 500f, 2260f),
            RotationCase(rotate270(WIDTH), HEIGHT.toInt(), WIDTH.toInt(), 300f, 500f),
        )

        for (case in cases) {
            val bufferX = transformX(case.transform, vertexX, vertexY)
            val bufferY = transformY(case.transform, vertexX, vertexY)
            val projection = Mat4.orthoYUp(
                case.bufferWidth.toFloat(),
                case.bufferHeight.toFloat(),
            )
            val rasterX = unproject(projectX(projection, bufferX), case.bufferWidth.toFloat())
            val rasterY = unproject(projectY(projection, bufferY), case.bufferHeight.toFloat())
            val scissor = BufferScissor.bounds(
                damage,
                case.transform,
                case.bufferWidth,
                case.bufferHeight,
            )

            assertNear(case.expectedX, bufferX)
            assertNear(case.expectedY, bufferY)
            assertNear(bufferX, rasterX)
            assertNear(bufferY, rasterY)
            assertTrue(bufferX >= scissor.left && bufferX < scissor.right)
            assertTrue(bufferY >= scissor.top && bufferY < scissor.bottom)
        }
    }

    private fun projectX(matrix: FloatArray, x: Float): Float = matrix[0] * x + matrix[12]

    private fun projectY(matrix: FloatArray, y: Float): Float = matrix[5] * y + matrix[13]

    private fun transformX(matrix: FloatArray, x: Float, y: Float): Float =
        matrix[0] * x + matrix[4] * y + matrix[12]

    private fun transformY(matrix: FloatArray, x: Float, y: Float): Float =
        matrix[1] * x + matrix[5] * y + matrix[13]

    private fun unproject(ndc: Float, size: Float): Float = (ndc + 1f) * size / 2f

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= EPSILON, "expected $expected, was $actual")
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun rotate90(sourceHeight: Float) = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        sourceHeight, 0f, 0f, 1f,
    )

    private fun halfTurn(sourceWidth: Float, sourceHeight: Float) = floatArrayOf(
        -1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        sourceWidth, sourceHeight, 0f, 1f,
    )

    private fun rotate270(sourceWidth: Float) = floatArrayOf(
        0f, -1f, 0f, 0f,
        1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, sourceWidth, 0f, 1f,
    )

    private data class RotationCase(
        val transform: FloatArray,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val expectedX: Float,
        val expectedY: Float,
    )

    private companion object {
        const val WIDTH = 1600f
        const val HEIGHT = 2560f
        const val POINT_X = 1100f
        const val POINT_Y = 300f
        const val EPSILON = 0.001f
    }
}
