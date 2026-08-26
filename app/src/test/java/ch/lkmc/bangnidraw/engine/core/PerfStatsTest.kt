package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/plan/10-performance.md` §5.3's stats block.
 *
 * Small, but the peak tracking has the shape that goes wrong quietly: a maximum
 * that is reset at the wrong moment, or updated with the wrong comparison,
 * still shows a plausible number — and the whole point of the overlay is that
 * the number is trusted.
 */
class PerfStatsTest {

    @Test
    fun `a frame records its timings and counts`() {
        val p = PerfStats()
        p.frame(stampMs = 0.4f, compositeMs = 1.2f, dabs = 37)
        assertEquals(0.4f, p.stampMs, 1e-6f)
        assertEquals(1.2f, p.compositeMs, 1e-6f)
        assertEquals(37, p.dabsPerFrame)
        assertEquals(1, p.frames)
    }

    @Test
    fun `the peak is the worst frame, not the last one`() {
        val p = PerfStats()
        p.frame(stampMs = 3f, compositeMs = 9f, dabs = 1)
        p.frame(stampMs = 0.1f, compositeMs = 0.2f, dabs = 1)
        assertEquals(0.1f, p.stampMs, 1e-6f, "last is the last")
        assertEquals(3f, p.stampMsMax, 1e-6f, "max survives a fast frame after a slow one")
        assertEquals(9f, p.compositeMsMax, 1e-6f)
        assertEquals(2, p.frames)
    }

    @Test
    fun `the two peaks are tracked independently`() {
        // One shared max would report the composite's 9 ms as a stamp overrun,
        // and §11 gives them different budgets — 1 ms and 2 ms — so a reader
        // would conclude the wrong pass blew the frame.
        val p = PerfStats()
        p.frame(stampMs = 0.5f, compositeMs = 9f, dabs = 1)
        assertEquals(0.5f, p.stampMsMax, 1e-6f)
        assertEquals(9f, p.compositeMsMax, 1e-6f)
    }

    @Test
    fun `resetPeaks clears the peaks and the frame count, and keeps the last frame`() {
        val p = PerfStats()
        p.frame(stampMs = 3f, compositeMs = 9f, dabs = 42)
        p.resetPeaks()
        assertEquals(0f, p.stampMsMax, 1e-6f, "a new stroke starts from no peak")
        assertEquals(0f, p.compositeMsMax, 1e-6f)
        assertEquals(0, p.frames)
        // The last-frame values describe a frame that really happened. Blanking
        // them would make the overlay flash to zero at every pen-down, which
        // reads as a stall rather than as a reset.
        assertEquals(3f, p.stampMs, 1e-6f)
        assertEquals(9f, p.compositeMs, 1e-6f)
        assertEquals(42, p.dabsPerFrame)
    }

    @Test
    fun `a peak rebuilds after a reset from the frames that follow it`() {
        val p = PerfStats()
        p.frame(stampMs = 8f, compositeMs = 8f, dabs = 1)
        p.resetPeaks()
        p.frame(stampMs = 2f, compositeMs = 1f, dabs = 1)
        assertEquals(2f, p.stampMsMax, 1e-6f, "the reset must not be sticky")
        assertEquals(1, p.frames)
    }

    @Test
    fun `commitMs is independent of the frame path`() {
        // Pen-up is not a front-buffered frame: it must not bump `frames` or
        // land in either budget's peak, or a slow merge would read as a slow
        // composite and send the next reader into the wrong pass.
        val p = PerfStats()
        p.frame(stampMs = 0.2f, compositeMs = 0.3f, dabs = 5)
        p.commitMs = 12f
        assertEquals(12f, p.commitMs, 1e-6f)
        assertEquals(0.3f, p.compositeMsMax, 1e-6f)
        assertEquals(1, p.frames)
    }
}
