package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RmwDabPlanTest {

    private val grid = TileGrid(512, 384)

    @Test
    fun `smudge clips its dab footprint to the canvas`() {
        val plan = RmwDabPlan.forDab(grid, x = 3f, y = 2f, radius = 8f, blurRadius = 0)

        assertEquals(IntRect(0, 0, 12, 11), plan.output)
        assertEquals(plan.output, plan.source)
    }

    @Test
    fun `blur expands its source around the clipped output`() {
        val plan = RmwDabPlan.forDab(grid, x = 250f, y = 190f, radius = 10f, blurRadius = 4)

        assertEquals(IntRect(239, 179, 261, 201), plan.output)
        assertEquals(IntRect(235, 175, 265, 205), plan.source)
    }

    @Test
    fun `blur source remains inside the canvas`() {
        val plan = RmwDabPlan.forDab(grid, x = 510f, y = 382f, radius = 8f, blurRadius = 12)

        assertEquals(IntRect(501, 373, 512, 384), plan.output)
        assertEquals(IntRect(489, 361, 512, 384), plan.source)
    }
}
