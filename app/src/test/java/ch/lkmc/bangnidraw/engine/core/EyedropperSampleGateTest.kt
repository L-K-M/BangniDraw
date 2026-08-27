package ch.lkmc.bangnidraw.engine.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EyedropperSampleGate`'s contract: the first sample always reads, a read
 * inside the interval is dropped, and one outside it reads again — one
 * pipeline-syncing `glReadPixels` per interval at most.
 */
class EyedropperSampleGateTest {

    @Test
    fun `first sample reads`() {
        val gate = EyedropperSampleGate()

        assertTrue(gate.shouldRead(nowMs = 0L))
    }

    @Test
    fun `sample inside the interval is dropped`() {
        val gate = EyedropperSampleGate()

        gate.shouldRead(nowMs = 1_000L)

        assertFalse(gate.shouldRead(nowMs = 1_000L + INTERVAL - 1))
        assertFalse(gate.shouldRead(nowMs = 1_000L))
        assertFalse(gate.shouldRead(nowMs = 1_000L - 1))
    }

    @Test
    fun `sample after the interval reads`() {
        val gate = EyedropperSampleGate()

        gate.shouldRead(nowMs = 1_000L)

        assertTrue(gate.shouldRead(nowMs = 1_000L + INTERVAL))
        assertFalse(gate.shouldRead(nowMs = 1_000L + INTERVAL + INTERVAL / 2))
        assertTrue(gate.shouldRead(nowMs = 1_000L + INTERVAL + INTERVAL))
    }

    @Test
    fun `reset lets the next sample read immediately`() {
        val gate = EyedropperSampleGate()

        gate.shouldRead(nowMs = 10_000L)
        gate.reset()

        assertTrue(gate.shouldRead(nowMs = 10_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative interval is refused`() {
        EyedropperSampleGate(intervalMs = -1L)
    }

    private companion object {
        const val INTERVAL = 16L
    }
}
