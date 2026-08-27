package ch.lkmc.bangnidraw.ui.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioProjectWorkPolicyTest {

    @Test
    fun `open blocks later sweeps until Studio resumes`() {
        val policy = StudioProjectWorkPolicy()

        assertTrue(policy.beginSweep())
        assertTrue(policy.beginOpen())

        policy.finishSweep()

        assertFalse(policy.beginSweep())
        assertFalse(policy.beginOpen())
        assertFalse(policy.beginMutation())

        policy.resumeStudio()

        assertTrue(policy.beginSweep())
    }

    @Test
    fun `pending mutations block the stale sweep`() {
        val policy = StudioProjectWorkPolicy()

        assertTrue(policy.beginMutation())
        assertTrue(policy.beginMutation())
        assertFalse(policy.beginSweep())

        policy.finishMutation()

        assertFalse(policy.beginSweep())

        policy.finishMutation()

        assertTrue(policy.beginSweep())
    }
}
