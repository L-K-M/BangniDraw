package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HueMilestoneTest {

    @Test
    fun `crossing a detent in either direction ticks`() {
        assertTrue(HueMilestone.crossed(59f, 61f))
        assertTrue(HueMilestone.crossed(61f, 59f))
    }

    @Test
    fun `zero detent crosses through wrap`() {
        assertTrue(HueMilestone.crossed(359f, 1f))
        assertTrue(HueMilestone.crossed(1f, 359f))
    }

    @Test
    fun `motion within a sector stays quiet`() {
        assertFalse(HueMilestone.crossed(12f, 48f))
        assertFalse(HueMilestone.crossed(60f, 61f))
    }
}
