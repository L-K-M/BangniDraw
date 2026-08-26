package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/plan/03-canvas-engine.md` §8.1 step 3's second half: window px to
 * buffer px through graphics-core's transform.
 *
 * The identity case is the one that would pass with the whole class deleted,
 * so the rotations carry the weight here — they are also the only case that
 * differs from doing nothing, and the only one that reaches a user, on any
 * device whose compositor hands back a pre-rotated buffer.
 */
class BufferScissorTest {

    /** Column-major, like `Mat4` and like GL. */
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * A rotation by 90° clockwise in a y-down pixel space, then translated so
     * the image lands back in `[0, w) × [0, h)`.
     *
     * `(x, y) -> (H - y, x)` where H is the source height: column 0 is the
     * image of the x axis, column 1 the image of the y axis.
     */
    private fun rotate90(sourceHeight: Float) = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        sourceHeight, 0f, 0f, 1f,
    )

    @Test
    fun `the identity maps a rect to itself`() {
        val r = IntRect(10, 20, 40, 50)
        assertEquals(r, BufferScissor.bounds(r, identity(), 100, 100))
    }

    @Test
    fun `a 90 degree buffer rotation moves the rect, and this is the whole point`() {
        // A 200x100 window rect at (10,20)..(40,50) into a buffer whose axes
        // are swapped. Under (x, y) -> (100 - y, x): the corners are
        // (80,10) (80,40) (50,40) (50,10), so the box is (50,10)..(80,40).
        val mapped = BufferScissor.bounds(IntRect(10, 20, 40, 50), rotate90(100f), 100, 200)
        assertEquals(IntRect(50, 10, 80, 40), mapped)
        // And it is genuinely different from doing nothing — the assertion that
        // makes the rest of this file worth having.
        assertTrue(
            mapped != IntRect(10, 20, 40, 50),
            "a rotated buffer must not reuse the window-space rect",
        )
    }

    @Test
    fun `all four corners are used, not two opposite ones`() {
        // A NEGATIVE shear, chosen so the two extremes lie OFF the main
        // diagonal. The obvious positive shear does not distinguish the two
        // implementations at all — its extremes happen to be the corners a
        // two-corner version already looks at — and a first draft of this test
        // used one and passed against a deliberately broken bounds().
        //
        // (x, y) -> (x - y, y) maps the corners of (0,0)..(10,10) to
        // (0,0) (10,0) (0,10) (-10,10). The true x range is [-10, 10], which
        // clips to [0, 10]; the main diagonal alone sees only x = 0 and x = 0,
        // giving an EMPTY rect — a front frame that draws nothing.
        val shear = floatArrayOf(
            1f, 0f, 0f, 0f,
            -1f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val mapped = BufferScissor.bounds(IntRect(0, 0, 10, 10), shear, 100, 100)
        assertEquals(IntRect(0, 0, 10, 10), mapped)
        assertTrue(!mapped.isEmpty, "two opposite corners would collapse this to nothing")
    }

    @Test
    fun `the result is clipped to the buffer`() {
        val mapped = BufferScissor.bounds(IntRect(-50, -50, 500, 500), identity(), 100, 80)
        assertEquals(IntRect(0, 0, 100, 80), mapped)
    }

    @Test
    fun `a rect entirely outside the buffer is empty, not negative`() {
        val mapped = BufferScissor.bounds(IntRect(200, 200, 300, 300), identity(), 100, 100)
        assertTrue(mapped.isEmpty, "off-buffer must be empty, was $mapped")
    }

    @Test
    fun `an empty rect or a zero-sized buffer is empty`() {
        assertTrue(BufferScissor.bounds(IntRect.EMPTY, identity(), 100, 100).isEmpty)
        assertTrue(BufferScissor.bounds(IntRect(0, 0, 10, 10), identity(), 0, 100).isEmpty)
        assertTrue(BufferScissor.bounds(IntRect(0, 0, 10, 10), identity(), 100, 0).isEmpty)
    }

    @Test
    fun `a non-finite transform yields empty rather than a garbage scissor`() {
        val bad = identity()
        bad[0] = Float.NaN
        assertTrue(
            BufferScissor.bounds(IntRect(0, 0, 10, 10), bad, 100, 100).isEmpty,
            "NaN must not become a coerced-to-zero scissor that hides the frame",
        )
    }

    @Test
    fun `toGlScissor refuses the inputs GL would mishandle`() {
        val out = IntArray(4)
        // An INVERTED rect is the one glScissor genuinely rejects: it raises
        // GL_INVALID_VALUE for a negative width or height, and leaves the
        // previous box in force. Caught here rather than silently installed.
        assertFailsWith<IllegalArgumentException> {
            BufferScissor.toGlScissor(IntRect(50, 10, 10, 40), bufferHeight = 100, out = out)
        }
        assertFailsWith<IllegalArgumentException> {
            BufferScissor.toGlScissor(IntRect(10, 40, 50, 10), bufferHeight = 100, out = out)
        }
        // And an unclipped rect, which GL would ACCEPT with a negative y and
        // quietly scissor the wrong rows — the failure the guard exists for.
        assertFailsWith<IllegalArgumentException> {
            BufferScissor.toGlScissor(IntRect(0, 0, 50, 150), bufferHeight = 100, out = out)
        }
        // Everything `bounds` produces passes, or the guard would be a bug of
        // its own: this is its output for a whole-buffer rect.
        BufferScissor.toGlScissor(
            BufferScissor.bounds(IntRect(-9, -9, 999, 999), identity(), 100, 100),
            bufferHeight = 100,
            out = out,
        )
        assertEquals(listOf(0, 0, 100, 100), out.toList())
    }

    @Test
    fun `toGlScissor flips y to GL's bottom-left origin`() {
        val out = IntArray(4)
        // Top 10 px of a 100-tall buffer: y-down (0,0)..(50,10) is y-up y = 90.
        BufferScissor.toGlScissor(IntRect(0, 0, 50, 10), bufferHeight = 100, out = out)
        assertEquals(listOf(0, 90, 50, 10), out.toList())
        // And the bottom band, where a missing flip would look identical if the
        // rect happened to be centred — so this pins the asymmetric case.
        BufferScissor.toGlScissor(IntRect(0, 90, 50, 100), bufferHeight = 100, out = out)
        assertEquals(listOf(0, 0, 50, 10), out.toList())
    }
}
