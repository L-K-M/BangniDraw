package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.13 — Meltorama's five cases, constants read
 * from the object, never restated.
 */
class AutosavePolicyTest {

    @Test
    fun `a fresh change waits for a quiet moment`() {
        assertEquals(AutosavePolicy.QUIET_MS, AutosavePolicy.delayMs(0))
    }

    @Test
    fun `the quiet wait never pushes a write past the ceiling`() {
        val dirtyFor = AutosavePolicy.ONE_CHECKPOINT_MS - AutosavePolicy.QUIET_MS / 2
        val delay = AutosavePolicy.delayMs(dirtyFor)
        assertEquals(AutosavePolicy.QUIET_MS / 2, delay)
        assertEquals(AutosavePolicy.ONE_CHECKPOINT_MS, dirtyFor + delay)
    }

    @Test
    fun `at or past the ceiling the write is due now`() {
        assertEquals(0, AutosavePolicy.delayMs(AutosavePolicy.ONE_CHECKPOINT_MS))
        assertEquals(0, AutosavePolicy.delayMs(AutosavePolicy.ONE_CHECKPOINT_MS + 5_000))
    }

    @Test
    fun `the wait is never negative`() {
        for (dirtyFor in longArrayOf(0, 1, AutosavePolicy.ONE_CHECKPOINT_MS * 3, Long.MAX_VALUE / 2)) {
            assertTrue(AutosavePolicy.delayMs(dirtyFor) >= 0, "delayMs($dirtyFor)")
        }
    }

    @Test
    fun `the ceiling leaves room for at least one quiet wait`() {
        assertTrue(AutosavePolicy.ONE_CHECKPOINT_MS >= AutosavePolicy.QUIET_MS)
    }
}
