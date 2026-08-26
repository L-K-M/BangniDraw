package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/03-canvas-engine.md` §7.5's batching rule: one draw per distinct
 * `(layerPage, strokePage, tailPage)` triple, with an absent tile joining
 * anything.
 *
 * The absence handling is the whole reason this is a class and not three `==`
 * in the GL loop, so most of what is below is about absence.
 */
class PreviewPlanTest {

    private val A = PreviewPlan.ABSENT

    private fun runEnd(from: Int, l: IntArray, s: IntArray, t: IntArray) =
        PreviewPlan.runEnd(from, l.size, l, s, t)

    @Test
    fun `tiles on the same three pages are one run`() {
        val n = 4
        val l = IntArray(n) { 0 }
        val s = IntArray(n) { 1 }
        val t = IntArray(n) { 2 }
        assertEquals(n, runEnd(0, l, s, t), "one triple must be one draw")
    }

    @Test
    fun `a change in any one of the three pages breaks the run`() {
        // Each of the three independently, because grouping on two of them and
        // forgetting the third would bind the wrong page for the forgotten one
        // and silently sample another tile's pixels.
        for (which in 0..2) {
            val l = intArrayOf(0, 0)
            val s = intArrayOf(0, 0)
            val t = intArrayOf(0, 0)
            listOf(l, s, t)[which][1] = 9
            assertEquals(
                1, runEnd(0, l, s, t),
                "a change in page $which must end the run",
            )
        }
    }

    @Test
    fun `an absent tile joins a run and does not pin its page`() {
        // The case §7.5 cares about: a stroke on blank canvas. Tile 0 has no
        // layer tile at all; tile 1 does, on page 3. If absence were treated as
        // a page number these would be two draws, and if it pinned the run the
        // second tile would be refused.
        val l = intArrayOf(A, 3, 3)
        val s = intArrayOf(0, 0, 0)
        val t = intArrayOf(A, A, A)
        assertEquals(3, runEnd(0, l, s, t), "absence must not split the run")

        val pages = IntArray(3)
        PreviewPlan.runPages(0, 3, l, s, t, pages)
        assertEquals(3, pages[0], "the run's layer page is the one real page in it")
        assertEquals(0, pages[1])
        assertEquals(A, pages[2], "a texture with no tile in the run has nothing to bind")
    }

    @Test
    fun `absence does not let two different real pages share a run`() {
        // The trap on the other side: absence joins, but it must not act as a
        // bridge. Pages 3 and 5 are still incompatible with a gap between them.
        val l = intArrayOf(3, A, 5)
        val s = intArrayOf(0, 0, 0)
        val t = intArrayOf(A, A, A)
        assertEquals(2, runEnd(0, l, s, t), "the run must end before the second real page")
        assertEquals(3, runEnd(2, l, s, t), "and the next run starts at it")
    }

    @Test
    fun `runEnd always advances, so a caller's loop terminates`() {
        // Every tile a different triple — the worst case, and the one where an
        // off-by-one would hang the render thread rather than draw wrongly.
        val n = 5
        val l = IntArray(n) { it }
        val s = IntArray(n) { it + 10 }
        val t = IntArray(n) { it + 20 }
        var i = 0
        var guard = 0
        while (i < n) {
            val end = runEnd(i, l, s, t)
            assertTrue(end > i, "runEnd must advance past $i, returned $end")
            i = end
            guard++
            assertTrue(guard <= n, "the walk must terminate")
        }
        assertEquals(n, guard, "every tile is its own draw when no two agree")
    }

    @Test
    fun `an all-absent run binds nothing`() {
        val l = intArrayOf(A, A)
        val s = intArrayOf(A, A)
        val t = intArrayOf(A, A)
        assertEquals(2, runEnd(0, l, s, t))
        val pages = IntArray(3)
        PreviewPlan.runPages(0, 2, l, s, t, pages)
        assertTrue(pages.all { it == A }, "nothing present means nothing to bind: ${pages.toList()}")
    }

    @Test
    fun `joins and adopt agree about which side wins`() {
        assertTrue(PreviewPlan.joins(A, 7), "an unpinned run admits any page")
        assertTrue(PreviewPlan.joins(7, A), "a pinned run admits an absent tile")
        assertTrue(PreviewPlan.joins(A, A))
        assertTrue(PreviewPlan.joins(7, 7))
        assertTrue(!PreviewPlan.joins(7, 8), "two real pages do not share a draw")

        assertEquals(7, PreviewPlan.adopt(A, 7), "the first real page pins the run")
        assertEquals(7, PreviewPlan.adopt(7, A), "an absent tile leaves it alone")
        assertEquals(7, PreviewPlan.adopt(7, 7))
        assertEquals(A, PreviewPlan.adopt(A, A), "nothing seen yet stays unpinned")
    }

    @Test
    fun `runEnd past the end is a no-op rather than a crash`() {
        val l = intArrayOf(0)
        assertEquals(1, PreviewPlan.runEnd(1, 1, l, l, l))
    }
}
