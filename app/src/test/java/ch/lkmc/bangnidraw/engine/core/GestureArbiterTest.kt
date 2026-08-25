package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.2 / `07-input-and-stylus.md` §3.
 *
 * The arbiter takes a timeline of pointer events and no `MotionEvent`, so
 * every rule in §3's table is checkable here — which is the point of it being
 * pure. Each test names the number it pins, because the numbers are tuning
 * decisions with reasons, not constants that happen to work.
 */
class GestureArbiterTest {

    /** Records decisions in order, so a test asserts on a sequence, not a flag. */
    private class Recorder : GestureListener {
        val events = mutableListOf<String>()
        override fun onDraw(pointerId: Int, source: StrokeSource) { events += "draw($pointerId,$source)" }
        override fun onNavigate() { events += "navigate" }
        override fun onCancelStroke() { events += "cancel" }
        override fun onTapUndo() { events += "undo" }
        override fun onTapRedo() { events += "redo" }
        override fun onLongPressPick(x: Float, y: Float) { events += "pick($x,$y)" }
        override fun onIgnore(pointerId: Int) { events += "ignore($pointerId)" }
        override fun onStrokeEnd(pointerId: Int) { events += "end($pointerId)" }
        override fun onNavigateEnd() { events += "navEnd" }
    }

    private val density = 2f
    private fun arbiter(stylusOnly: Boolean = false) = GestureArbiter(density, stylusOnly)
    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `a second pen stroke does not roll back the first when a palm rests through the lift`() {
        // The palm keeps count > 0 across the pen's lift, so the gesture never
        // ends and resetGesture never runs. drawingId stayed pointing at the
        // pen that already lifted, and the next pen down took the "roll back
        // the live stroke" branch — discarding a committed stroke.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.STYLUS, 100f, 100f, ms(0), r)
        a.down(2, PointerTool.FINGER, 400f, 400f, ms(10), r)   // palm, ignored
        a.up(1, ms(100), r)                                  // the pen lifts normally
        r.events.clear()
        a.down(3, PointerTool.STYLUS, 120f, 120f, ms(200), r) // and comes back down
        assertTrue(
            !r.events.contains("cancel"),
            "a stroke that ended normally must not be rolled back: ${r.events}",
        )
    }

    @Test
    fun `a pointer beyond the tracking table can land and lift without crashing`() {
        // add() returns NO_POINTER once the table is full and indexOf() returns
        // -1 for an unknown id, so an untracked pointer's lift is an expected
        // input on a device reporting five contacts.
        val r = Recorder()
        val a = arbiter()
        for (i in 1..GestureArbiter.MAX_POINTERS) {
            a.down(i, PointerTool.FINGER, 100f * i, 100f, ms(i.toLong()), r)
        }
        a.down(99, PointerTool.FINGER, 500f, 500f, ms(50), r) // one too many
        a.move(99, 510f, 500f, ms(60), r)
        a.up(99, ms(70), r)                                   // must be a no-op
        assertTrue(r.events.contains("ignore(99)"), "the extra pointer is ignored: ${r.events}")
    }

    @Test
    fun `a palm still down does not turn a one-finger tap into an undo`() {
        // maxDown counted every tracked pointer, palms included. A palm added
        // while the pen was near stays down after the pen leaves, so the next
        // single-finger tap read as a two-finger tap and fired undo.
        val r = Recorder()
        val a = arbiter()
        a.stylusNear = true
        a.down(1, PointerTool.FINGER, 0f, 0f, ms(0), r)     // palm: ignored
        a.stylusNear = false                                // pen left, grace expired
        a.down(2, PointerTool.FINGER, 400f, 400f, ms(600), r)
        a.up(2, ms(650), r)                                 // a quick one-finger tap
        a.up(1, ms(700), r)                                 // the palm finally lifts
        assertTrue(
            !r.events.contains("undo"),
            "a palm must not count toward the tap's finger count: ${r.events}",
        )
    }

    // Comfortably past TAP_SLOP_DP (8 dp) at density 2 — 16 px.
    private val past = 40f

    @Test
    fun `a stylus draws immediately, with no pending window`() {
        // Pen latency is the product: a stylus never navigates, so there is
        // nothing to disambiguate and nothing to wait for.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.STYLUS, 10f, 10f, ms(0), r)
        assertEquals(listOf("draw(1,STYLUS)"), r.events)
    }

    @Test
    fun `the eraser end is a stroke with its own source`() {
        // The ViewModel swaps to the eraser preset off this, so the source has
        // to survive rather than collapsing to STYLUS.
        val r = Recorder()
        arbiter().down(1, PointerTool.ERASER, 10f, 10f, ms(0), r)
        assertEquals(listOf("draw(1,ERASER_END)"), r.events)
    }

    @Test
    fun `a finger is pending for PENDING_MS, then draws`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        assertEquals(emptyList(), r.events, "nothing may be decided inside the window")
        a.tick(ms(GestureArbiter.PENDING_MS - 1), r)
        assertEquals(emptyList(), r.events, "one ms early is still pending")
        a.tick(ms(GestureArbiter.PENDING_MS), r)
        assertEquals(listOf("draw(1,FINGER)"), r.events)
    }

    @Test
    fun `a finger past the slop draws early — a deliberate line is not a chord`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.move(1, 10f + past, 10f, ms(20), r)
        assertEquals(listOf("draw(1,FINGER)"), r.events, "moving past the slop resolves before 120 ms")
    }

    @Test
    fun `a second finger inside the window cancels the pending stroke and navigates`() {
        // The two-finger chord is the universal navigation gesture, and a stray
        // mark from the first 100 ms would be worse than a lost 100 ms of line.
        // Order matters: the cancel must precede the navigate.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(40), r)
        assertEquals(listOf("cancel", "navigate"), r.events)
    }

    @Test
    fun `a second finger after the window is ignored, not a mode change`() {
        // The user is drawing with one finger and rested another; switching to
        // navigation mid-stroke would be a surprise.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.tick(ms(GestureArbiter.PENDING_MS), r)
        r.events.clear()
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(300), r)
        assertEquals(listOf("ignore(2)"), r.events)
    }

    @Test
    fun `any finger during a stylus stroke is ignored`() {
        // A palm, a knuckle, the other hand resting: a stylus stroke is never
        // interrupted by touch.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.STYLUS, 10f, 10f, ms(0), r)
        r.events.clear()
        a.down(2, PointerTool.FINGER, 300f, 300f, ms(50), r)
        a.down(3, PointerTool.FINGER, 400f, 400f, ms(60), r)
        assertEquals(listOf("ignore(2)", "ignore(3)"), r.events)
        // And lifting them does not end the stroke.
        a.up(2, ms(70), r)
        assertTrue("end(1)" !in r.events, "a palm lifting must not end the pen's stroke")
    }

    @Test
    fun `a finger while the stylus merely hovers is ignored — the palm rule`() {
        // §5's stylus-near rule, including the hover grace: the caller sets
        // this flag from StylusState and the arbiter obeys it.
        val r = Recorder()
        val a = arbiter()
        a.stylusNear = true
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        assertEquals(listOf("ignore(1)"), r.events)
        a.tick(ms(500), r)
        assertEquals(listOf("ignore(1)"), r.events, "an ignored pointer must not become a stroke or a pick")
    }

    @Test
    fun `a stylus landing mid-finger-stroke cancels it — the pen is the intent`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.tick(ms(GestureArbiter.PENDING_MS), r)
        r.events.clear()
        a.down(9, PointerTool.STYLUS, 50f, 50f, ms(300), r)
        assertEquals(listOf("cancel", "draw(9,STYLUS)"), r.events)
    }

    @Test
    fun `a stylus landing mid-navigation ends it without a step`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(30), r)
        r.events.clear()
        a.down(9, PointerTool.STYLUS, 50f, 50f, ms(300), r)
        assertEquals(listOf("navEnd", "draw(9,STYLUS)"), r.events)
    }

    @Test
    fun `two fingers up inside TAP_MS undo, three redo`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(10), r)
        r.events.clear()
        a.up(1, ms(80), r)
        a.up(2, ms(90), r)
        assertEquals(listOf("navEnd", "undo"), r.events)

        val r3 = Recorder()
        val a3 = arbiter()
        a3.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r3)
        a3.down(2, PointerTool.FINGER, 200f, 200f, ms(10), r3)
        a3.down(3, PointerTool.FINGER, 300f, 300f, ms(20), r3)
        r3.events.clear()
        a3.up(1, ms(80), r3)
        a3.up(2, ms(85), r3)
        a3.up(3, ms(90), r3)
        assertEquals(listOf("navEnd", "redo"), r3.events)
    }

    @Test
    fun `a tap that moved is a navigation, not an undo`() {
        // "A 2-finger tap that turns into movement is Navigate from the moment
        // slop is exceeded, so undo never fires by accident after a pan."
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(10), r)
        a.move(1, 10f + past, 10f, ms(40), r)
        r.events.clear()
        a.up(1, ms(80), r)
        a.up(2, ms(90), r)
        assertTrue("undo" !in r.events, "a pan must never fire undo: ${r.events}")
    }

    @Test
    fun `a tap held past TAP_MS is not a tap`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(10), r)
        r.events.clear()
        a.up(1, ms(GestureArbiter.TAP_MS + 50), r)
        a.up(2, ms(GestureArbiter.TAP_MS + 60), r)
        assertTrue("undo" !in r.events, "held too long to be a tap: ${r.events}")
    }

    @Test
    fun `the tap count is the maximum simultaneously down, not the count at lift`() {
        // Counting the maximum tolerates fingers landing — and lifting — a few
        // ms apart. Counting at lift would make every three-finger tap an undo.
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(5), r)
        a.down(3, PointerTool.FINGER, 300f, 300f, ms(10), r)
        r.events.clear()
        a.up(3, ms(60), r)
        a.up(2, ms(70), r)
        a.up(1, ms(80), r)
        assertTrue("redo" in r.events && "undo" !in r.events, "expected redo only: ${r.events}")
    }

    @Test
    fun `a long press picks colour, once, and only while still`() {
        val r = Recorder()
        val a = arbiter(stylusOnly = true)
        a.down(1, PointerTool.FINGER, 33f, 44f, ms(0), r)
        a.tick(ms(GestureArbiter.LONG_PRESS_MS - 1), r)
        assertTrue(r.events.none { it.startsWith("pick") }, "one ms early")
        a.tick(ms(GestureArbiter.LONG_PRESS_MS), r)
        assertEquals(listOf("pick(33.0,44.0)"), r.events)
        // Not twice for one hold.
        a.tick(ms(GestureArbiter.LONG_PRESS_MS + 200), r)
        assertEquals(1, r.events.count { it.startsWith("pick") })
    }

    @Test
    fun `stylus-only mode pans with one finger instead of drawing`() {
        // The most-requested S Pen feature: rest your hand, move the paper with
        // a finger. It must never produce a stroke.
        val r = Recorder()
        val a = arbiter(stylusOnly = true)
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.move(1, 10f + past, 10f, ms(20), r)
        assertEquals(listOf("navigate"), r.events)
        assertTrue(r.events.none { it.startsWith("draw") })
    }

    @Test
    fun `cancel rolls back a live stroke and leaves no trace`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.tick(ms(GestureArbiter.PENDING_MS), r)
        r.events.clear()
        a.cancel(r)
        assertEquals(listOf("cancel"), r.events)
        // And the machine is clean: the next finger starts a fresh gesture.
        a.down(2, PointerTool.FINGER, 10f, 10f, ms(400), r)
        a.tick(ms(400 + GestureArbiter.PENDING_MS), r)
        assertEquals(listOf("cancel", "draw(2,FINGER)"), r.events)
    }

    @Test
    fun `cancel with nothing live emits nothing`() {
        // ACTION_CANCEL arrives for gestures that never became anything, and a
        // spurious rollback would discard a stroke the user is still drawing
        // with the other hand.
        val r = Recorder()
        arbiter().cancel(r)
        assertEquals(emptyList(), r.events)
    }

    @Test
    fun `navigation continues pan-only with one finger left, and ends when it lifts`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.FINGER, 10f, 10f, ms(0), r)
        a.down(2, PointerTool.FINGER, 200f, 200f, ms(20), r)
        r.events.clear()
        a.up(1, ms(300), r)
        assertEquals(emptyList(), r.events, "one finger left still navigates, pan-only")
        a.up(2, ms(400), r)
        assertEquals(listOf("navEnd"), r.events)
    }

    @Test
    fun `a fourth pointer is ignored rather than tracked`() {
        // §7's table ends at three fingers and says "Nothing else". Four-finger
        // gestures have been a palm-triggered accident in every app with them.
        val r = Recorder()
        val a = arbiter()
        for (i in 1..GestureArbiter.MAX_POINTERS + 1) {
            a.down(i, PointerTool.FINGER, 10f * i, 10f, ms(i.toLong()), r)
        }
        assertTrue("ignore(${GestureArbiter.MAX_POINTERS + 1})" in r.events, "${r.events}")
    }

    @Test
    fun `a stroke ends normally on lift`() {
        val r = Recorder()
        val a = arbiter()
        a.down(1, PointerTool.STYLUS, 10f, 10f, ms(0), r)
        r.events.clear()
        a.up(1, ms(300), r)
        assertEquals(listOf("end(1)"), r.events)
    }

    @Test
    fun `the density conversion is refused for a nonsense value`() {
        // slopPx is TAP_SLOP_DP * density; a zero or negative density makes
        // every movement instantly past the slop, so a finger could never be
        // pending and the chord window would not exist.
        kotlin.test.assertFailsWith<IllegalArgumentException> { GestureArbiter(0f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GestureArbiter(-2f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GestureArbiter(Float.NaN) }
    }

    @Test
    fun `the slop is measured in dp, so density changes the pixel threshold`() {
        // The same 20 px move is past the slop at density 1 (16 px) and inside
        // it at density 4 (32 px). Without the conversion the gesture would
        // behave differently on every device.
        val near = 20f
        val low = Recorder()
        GestureArbiter(1f).apply {
            down(1, PointerTool.FINGER, 0f, 0f, ms(0), low)
            move(1, near, 0f, ms(10), low)
        }
        assertEquals(listOf("draw(1,FINGER)"), low.events)

        val high = Recorder()
        GestureArbiter(4f).apply {
            down(1, PointerTool.FINGER, 0f, 0f, ms(0), high)
            move(1, near, 0f, ms(10), high)
        }
        assertEquals(emptyList(), high.events, "20 px is inside an 8 dp slop at density 4")
    }
}
