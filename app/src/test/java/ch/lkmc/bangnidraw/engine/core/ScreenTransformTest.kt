package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.1: `view ∘ fit` equals the composed similarity
 * for random views, `invert` round-trips, and `effectiveScale = fs·s`.
 *
 * This is the one piece of roadmap 2.3b a JVM test can pin end to end — the
 * rest of the row is GL — so it is pinned against the *definition* (apply the
 * two transforms in turn) rather than against a restatement of the composed
 * formula, which would only prove the formula equals itself.
 */
class ScreenTransformTest {

    private val eps = 1e-3f

    private val fit = FitTransform(
        viewWidth = 1200f,
        viewHeight = 800f,
        imageWidth = 1024f,
        imageHeight = 1024f,
    )

    private fun randomViews(seed: Int, n: Int): List<ViewTransform> {
        val random = Random(seed)
        return List(n) {
            ViewTransform(
                scale = ViewTransform.MIN_SCALE +
                    random.nextFloat() * (4f - ViewTransform.MIN_SCALE),
                rotation = (random.nextFloat() - 0.5f) * 6.2f,
                tx = (random.nextFloat() - 0.5f) * 800f,
                ty = (random.nextFloat() - 0.5f) * 800f,
            )
        }
    }

    @Test
    fun `the composed transform is view applied to fit, for random views`() {
        // Against the DEFINITION, not against the closed form: applying fit
        // then view is what "the screen shows view of fit of the canvas"
        // means, and the composed similarity is an optimization of it. Pinning
        // the closed form against itself would pass with the wrong formula.
        for (view in randomViews(seed = 7, n = 200)) {
            val screen = ScreenTransform.of(fit, view)
            for (p in listOf(0f to 0f, 1024f to 0f, 0f to 1024f, 1024f to 1024f, 511f to 733f)) {
                val fitted = Pair(
                    fit.offsetX + p.first * fit.scale,
                    fit.offsetY + p.second * fit.scale,
                )
                val (ex, ey) = view.apply(fitted.first, fitted.second)
                val (ax, ay) = screen.apply(p.first, p.second)
                assertEquals(ex, ax, eps, "x at $p under $view")
                assertEquals(ey, ay, eps, "y at $p under $view")
            }
        }
    }

    @Test
    fun `invert round-trips every point`() {
        for (view in randomViews(seed = 11, n = 200)) {
            val screen = ScreenTransform.of(fit, view)
            for (p in listOf(0f to 0f, 1024f to 1024f, 300f to 700f, -50f to 20f)) {
                val (sx, sy) = screen.apply(p.first, p.second)
                val (cx, cy) = screen.invert(sx, sy)
                // Tolerance scaled by the zoom: at 0.25x a screen pixel is four
                // canvas px, so a fixed epsilon would be testing the float
                // format rather than the algebra.
                val tol = eps / screen.effectiveScale
                assertTrue(abs(cx - p.first) < tol, "x $p -> ($sx,$sy) -> $cx under $view")
                assertTrue(abs(cy - p.second) < tol, "y $p -> ($sx,$sy) -> $cy under $view")
            }
        }
    }

    @Test
    fun `effectiveScale is fit scale times view scale`() {
        for (view in randomViews(seed = 13, n = 100)) {
            val screen = ScreenTransform.of(fit, view)
            assertEquals(
                fit.scale * view.scale,
                screen.effectiveScale,
                eps,
                "effectiveScale under $view",
            )
            // And it is what the matrix actually does to a unit vector, which
            // is the property the shader depends on — derived, so it cannot
            // drift from a and b.
            val (x0, y0) = screen.apply(0f, 0f)
            val (x1, y1) = screen.apply(1f, 0f)
            assertEquals(screen.effectiveScale, hypot(x1 - x0, y1 - y0), eps)
        }
    }

    @Test
    fun `canvasPerScreen is the reciprocal, and survives a degenerate transform`() {
        val screen = ScreenTransform.of(fit, ViewTransform(scale = 2f))
        assertEquals(1f / screen.effectiveScale, screen.canvasPerScreen, 1e-6f)
        // Not reachable from a real fit and view — both bound the scale — but
        // this value reaches a uniform, and 1/0 in the supersample step is an
        // infinite offset and a NaN fetch.
        val degenerate = ScreenTransform(0f, 0f, 10f, 10f)
        assertEquals(0f, degenerate.canvasPerScreen)
        assertEquals(Pair(0f, 0f), degenerate.invert(5f, 5f))
    }

    @Test
    fun `an identity view shows the fitted canvas exactly`() {
        val screen = ScreenTransform.of(fit, ViewTransform())
        val (x0, y0) = screen.apply(0f, 0f)
        assertEquals(fit.offsetX, x0, eps)
        assertEquals(fit.offsetY, y0, eps)
        val (x1, y1) = screen.apply(1024f, 1024f)
        assertEquals(fit.offsetX + fit.fittedWidth, x1, eps)
        assertEquals(fit.offsetY + fit.fittedHeight, y1, eps)
    }

    @Test
    fun `the translation carries the fit offset through the view, not the view's own`() {
        // The natural-looking mistake is t' = (view.tx, view.ty). It is right
        // for a canvas that exactly fills the viewport — where the fit offset
        // is zero — and wrong for every letterboxed one, which is every canvas
        // whose aspect differs from the window's. A square canvas in a 3:2
        // viewport letterboxes by 88 px, so this test would catch it.
        val view = ViewTransform(scale = 1.5f, rotation = 0.4f, tx = 30f, ty = -12f)
        val screen = ScreenTransform.of(fit, view)
        assertTrue(fit.offsetX != 0f, "the fixture must letterbox or this proves nothing")
        val (expectedTx, expectedTy) = view.apply(fit.offsetX, fit.offsetY)
        assertEquals(expectedTx, screen.tx, eps)
        assertEquals(expectedTy, screen.ty, eps)
        assertTrue(
            abs(screen.tx - view.tx) > 1f,
            "the composed translation must not be the view's own",
        )
    }

    @Test
    fun `a rotated dirty rect scissors to the bounding box of all four corners`() {
        // Two opposite corners are not enough under rotation: the image of an
        // axis-aligned rect is not axis-aligned, and a scissor is. Taking
        // min/max of two corners clips the other two, which shows up as wedges
        // of a stroke going undrawn at any angle off a right angle.
        val view = ViewTransform(scale = 1f, rotation = 0.6f)
        val screen = ScreenTransform.of(fit, view)
        val rect = IntRect(100, 100, 300, 200)
        val bounds = screen.screenBoundsOf(rect, 4000, 4000)
        val corners = listOf(
            screen.apply(100f, 100f), screen.apply(300f, 100f),
            screen.apply(300f, 200f), screen.apply(100f, 200f),
        )
        for ((cx, cy) in corners) {
            assertTrue(cx >= bounds.left && cx <= bounds.right, "corner x $cx outside $bounds")
            assertTrue(cy >= bounds.top && cy <= bounds.bottom, "corner y $cy outside $bounds")
        }
        // And it is a genuine box, not the degenerate two-corner span: under a
        // 0.6 rad rotation the box is strictly taller than the two-corner one.
        val twoCornerTop = minOf(corners[0].second, corners[2].second)
        assertTrue(
            bounds.top < twoCornerTop,
            "the box must reach above the two-corner span ($twoCornerTop)",
        )
    }

    @Test
    fun `the scissor rect is inflated by a pixel and clipped to the viewport`() {
        val screen = ScreenTransform.of(fit, ViewTransform())
        val bounds = screen.screenBoundsOf(IntRect(0, 0, 1024, 1024), 1200, 800)
        // Clipped: the fitted canvas is 800 px tall in an 800 px viewport, so
        // the inflated box would run past the bottom edge.
        assertEquals(0, bounds.top)
        assertEquals(800, bounds.bottom)
        assertTrue(bounds.left >= 0 && bounds.right <= 1200)
        // Inflated: a small rect well inside the viewport gains a pixel a side.
        // Asserted against the UN-inflated box, not against the float corner —
        // `left < sx` is satisfied by the floor alone, so it passed with the
        // inflation removed and proved nothing.
        val small = screen.screenBoundsOf(IntRect(200, 200, 300, 300), 1200, 800)
        val (minX, minY) = screen.apply(200f, 200f)
        val (maxX, maxY) = screen.apply(300f, 300f)
        assertTrue(
            small.left <= floor(minX).toInt() - 1,
            "left ${small.left} is not a pixel outside floor($minX)",
        )
        assertTrue(
            small.top <= floor(minY).toInt() - 1,
            "top ${small.top} is not a pixel outside floor($minY)",
        )
        assertTrue(
            small.right >= ceil(maxX).toInt() + 1,
            "right ${small.right} is not a pixel outside ceil($maxX)",
        )
        assertTrue(
            small.bottom >= ceil(maxY).toInt() + 1,
            "bottom ${small.bottom} is not a pixel outside ceil($maxY)",
        )
    }

    @Test
    fun `an empty rect or viewport scissors to nothing`() {
        val screen = ScreenTransform.of(fit, ViewTransform())
        assertEquals(IntRect.EMPTY, screen.screenBoundsOf(IntRect.EMPTY, 1200, 800))
        assertEquals(IntRect.EMPTY, screen.screenBoundsOf(IntRect(10, 10, 20, 20), 0, 800))
        // Entirely off-screen: clipping collapses it rather than producing an
        // inverted rect, which glScissor would reject with GL_INVALID_VALUE.
        val far = screen.screenBoundsOf(IntRect(-9000, -9000, -8000, -8000), 1200, 800)
        assertEquals(IntRect.EMPTY, far)
    }

    @Test
    fun `the filter policy follows the zoom table`() {
        // docs/plan/03-canvas-engine.md §3.4, boundaries included: the table's
        // rows are half-open upward, so 4.0 is nearest and 0.5 is one tap.
        assertTrue(FilterPolicy.nearest(4f))
        assertTrue(FilterPolicy.nearest(64f))
        assertTrue(!FilterPolicy.nearest(3.999f))
        assertEquals(1, FilterPolicy.taps(4f))
        assertEquals(1, FilterPolicy.taps(1f))
        assertEquals(1, FilterPolicy.taps(0.5f))
        assertEquals(2, FilterPolicy.taps(0.499f))
        assertEquals(2, FilterPolicy.taps(0.25f))
        assertEquals(4, FilterPolicy.taps(0.249f))
        assertEquals(4, FilterPolicy.taps(0.05f))
    }

    @Test
    fun `the filter policy never returns a tap count the shader would clamp`() {
        // The shader clamps to Shaders.MAX_TAPS; if this ever exceeded it the
        // sample count and the divisor would still agree (the clamp sees to
        // that) but the policy would be quietly asking for something it does
        // not get. Also covers the values that cannot arise but reach uniforms.
        val scales = listOf(
            0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE, 1e-9f, 1e9f,
        ) + (1..200).map { it / 20f }
        for (s in scales) {
            val taps = FilterPolicy.taps(s)
            assertTrue(taps in 1..4, "taps($s) = $taps")
            assertTrue(taps == 1 || taps == 2 || taps == 4, "taps($s) = $taps is not a grid edge")
        }
    }
}
