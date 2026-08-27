package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OpacityMilestoneTest {
    @Test
    fun `reports each endpoint and midpoint crossed`() {
        assertEquals(listOf(0.5f), OpacityMilestone.crossed(0.4f, 0.6f))
        assertEquals(listOf(0.5f, 0f), OpacityMilestone.crossed(0.8f, 0f))
        assertEquals(listOf(1f), OpacityMilestone.crossed(0.8f, 1f))
        assertEquals(emptyList(), OpacityMilestone.crossed(0.2f, 0.3f))
    }
}
