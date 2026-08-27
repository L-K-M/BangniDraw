package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushSizeScaleTest {

    @Test
    fun `slider endpoints map to preset bounds`() {
        assertEquals(2f, BrushSizeScale.size(0f, 2f, 200f))
        assertEquals(200f, BrushSizeScale.size(1f, 2f, 200f))
        assertEquals(0f, BrushSizeScale.fraction(2f, 2f, 200f))
        assertEquals(1f, BrushSizeScale.fraction(200f, 2f, 200f))
    }

    @Test
    fun `slider midpoint is the geometric mean`() {
        val size = BrushSizeScale.size(0.5f, 2f, 200f)

        assertEquals(20f, size, absoluteTolerance = 0.0001f)
        assertEquals(0.5f, BrushSizeScale.fraction(size, 2f, 200f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `fixed range remains fixed`() {
        assertEquals(12f, BrushSizeScale.size(0.8f, 12f, 12f))
        assertEquals(0f, BrushSizeScale.fraction(12f, 12f, 12f))
    }
}
