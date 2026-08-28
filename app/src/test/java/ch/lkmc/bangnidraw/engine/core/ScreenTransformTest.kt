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
                assertEquals(cx, screen.invertX(sx, sy), tol)
                assertEquals(cy, screen.invertY(sx, sy), tol)
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
        assertEquals(0f, degenerate.invertX(5f, 5f))
        assertEquals(0f, degenerate.invertY(5f, 5f))
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
        // viewport pillarboxes by 200 px a side (fit.scale = 800/1024, so the
        // fitted width is 800 and the offset is (1200-800)/2), so this catches it.
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
        // And the FIXTURE genuinely breaks a two-corner span — otherwise the
        // containment loop above is checking a box that a two-corner
        // implementation would also produce. The previous version of this
        // assertion compared `bounds.top` (floored and inflated) against the
        // raw corner y, and at this rotation the topmost corner IS corners[0],
        // so it passed on the 1 px of inflation alone and proved nothing.
        val twoCornerLeft = floor(minOf(corners[0].first, corners[2].first)).toInt() - 1
        val twoCornerTop = floor(minOf(corners[0].second, corners[2].second)).toInt() - 1
        val twoCornerRight = ceil(maxOf(corners[0].first, corners[2].first)).toInt() + 1
        val twoCornerBottom = ceil(maxOf(corners[0].second, corners[2].second)).toInt() + 1
        assertTrue(
            listOf(corners[1], corners[3]).any { (cx, cy) ->
                cx < twoCornerLeft || cx > twoCornerRight ||
                    cy < twoCornerTop || cy > twoCornerBottom
            },
            "a corner of the other diagonal must escape the two-corner span, " +
                "or this fixture proves nothing",
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
    fun `inflated front damage includes every neighboring tile it clears`() {
        val screen = ScreenTransform(a = 0.5f, b = 0f, tx = 0f, ty = 0f)
        val dirty = IntRect(256, 256, 512, 512)
        val window = screen.screenBoundsOf(dirty, 1024, 1024)
        val coverage = screen.canvasBoundsOf(window, 1024, 1024)

        assertEquals(IntRect(127, 127, 257, 257), window)
        assertEquals(IntRect(254, 254, 514, 514), coverage)

        val keys = TileGrid(1024, 1024).keysFor(coverage).toSet()
        assertEquals(
            setOf(
                TileKey(0, 0), TileKey(1, 0), TileKey(2, 0),
                TileKey(0, 1), TileKey(1, 1), TileKey(2, 1),
                TileKey(0, 2), TileKey(1, 2), TileKey(2, 2),
            ),
            keys,
        )
    }

    @Test
    fun `rotated front damage covers the whole scissor rather than its source rect`() {
        val halfRootTwo = kotlin.math.sqrt(0.5f)
        val screen = ScreenTransform(
            a = halfRootTwo,
            b = halfRootTwo,
            tx = 512f,
            ty = 0f,
        )
        val dirty = IntRect(256, 256, 768, 512)
        val window = screen.screenBoundsOf(dirty, 1200, 1200)
        val coverage = screen.canvasBoundsOf(window, 1024, 1024)

        // The screen-space AABB's corners map well outside the rotated source
        // rect. Clearing that AABB while redrawing only `dirty` leaves wedges.
        assertTrue(coverage.left < dirty.left)
        assertTrue(coverage.top < dirty.top)
        assertTrue(coverage.right > dirty.right)
        assertTrue(coverage.bottom > dirty.bottom)
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
    fun `raw screen bounds keep the exact transformed rect without inflation`() {
        val screen = ScreenTransform(a = 2f, b = 0f, tx = 10f, ty = 20f)

        assertEquals(IntRect(10, 20, 30, 60), screen.rawScreenBoundsOf(IntRect(0, 0, 10, 20)))
        assertEquals(IntRect.EMPTY, screen.rawScreenBoundsOf(IntRect.EMPTY))

        // Unclamped on purpose: the caller folds these into band arithmetic
        // around the canvas, and clamping here would swallow the bands whole.
        val off = screen.rawScreenBoundsOf(IntRect(-100, -100, -90, -90))
        assertEquals(IntRect(-190, -180, -170, -160), off)
    }

    @Test
    fun `raw canvas bounds invert the viewport without clamping to the canvas`() {
        val screen = ScreenTransform(a = 0.5f, b = 0f, tx = -100f, ty = -100f)

        assertEquals(IntRect(200, 200, 2200, 1800), screen.rawCanvasBoundsOf(1000, 800))
        assertEquals(IntRect.EMPTY, screen.rawCanvasBoundsOf(0, 800))
    }

    @Test
    fun `void bands disappear when the canvas covers the whole clip`() {
        assertEquals(
            emptyList(),
            voidBandsAround(IntRect(-10, -10, 30, 30), IntRect(0, 0, 20, 20)),
        )
        assertEquals(emptyList(), voidBandsAround(IntRect(0, 0, 20, 20), IntRect.EMPTY))
    }

    @Test
    fun `a canvas past one clip edge leaves that whole side as one band`() {
        // Canvas entirely left of the clip: only the right band remains, and
        // it is the full clip — the top/bottom bands sit strictly between the
        // canvas's x extent, which no longer intersects the clip.
        assertEquals(
            listOf(IntRect(0, 0, 20, 20)),
            voidBandsAround(IntRect(-30, -5, 0, 25), IntRect(0, 0, 20, 20)),
        )
    }

    @Test
    fun `a canvas inside the clip yields four disjoint bands covering the void`() {
        val clip = IntRect(0, 0, 100, 80)
        val canvas = IntRect(20, 10, 60, 50)
        val bands = voidBandsAround(canvas, clip)

        assertEquals(
            listOf(
                IntRect(0, 0, 20, 80),
                IntRect(60, 0, 100, 80),
                IntRect(20, 0, 60, 10),
                IntRect(20, 50, 60, 80),
            ),
            bands,
        )

        // Disjoint, and their union is exactly the clip minus the canvas.
        val bandArea = bands.sumOf { it.width.toLong() * it.height.toLong() }
        assertEquals(100L * 80 - 40L * 40, bandArea)
        for (i in bands.indices) {
            for (j in i + 1 until bands.size) {
                val a = bands[i]
                val b = bands[j]
                assertTrue(
                    minOf(a.right, b.right) <= maxOf(a.left, b.left) ||
                        minOf(a.bottom, b.bottom) <= maxOf(a.top, b.top),
                    "bands $a and $b overlap",
                )
            }
        }
        for (band in bands) {
            assertTrue(
                band.right <= canvas.left || band.left >= canvas.right ||
                    band.bottom <= canvas.top || band.top >= canvas.bottom,
                "band $band cuts into the canvas $canvas",
            )
        }
    }

    @Test
    fun `a partially overlapping canvas still bands the covered remainder`() {
        // Canvas's top-left quadrant covers the clip's bottom-right quadrant.
        val bands = voidBandsAround(IntRect(-50, -50, 50, 50), IntRect(0, 0, 100, 100))

        assertEquals(
            listOf(
                IntRect(50, 0, 100, 100),
                IntRect(0, 50, 50, 100),
            ),
            bands,
        )
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
