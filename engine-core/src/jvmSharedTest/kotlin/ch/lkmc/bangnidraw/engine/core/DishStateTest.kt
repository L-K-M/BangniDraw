package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DishStateTest {

    @Test
    fun `default t is 0_5`() {
        assertEquals(0.5f, DishState.DEFAULT_T)
        assertEquals(0.5f, DishState(a = 0xFF000000.toInt(), b = 0xFFFFFFFF.toInt()).t)
    }

    @Test
    fun `t outside 0_1 and NaN are rejected`() {
        assertFailsWith<IllegalArgumentException> { DishState(a = 0, b = 0, t = -0.1f) }
        assertFailsWith<IllegalArgumentException> { DishState(a = 0, b = 0, t = 1.1f) }
        assertFailsWith<IllegalArgumentException> { DishState(a = 0, b = 0, t = Float.NaN) }
    }

    @Test
    fun `copy preserves wells and updates t`() {
        val dish = DishState(a = 0xFF112233.toInt(), b = 0xFF445566.toInt(), t = 0.2f)
        val moved = dish.copy(t = 0.8f)
        assertEquals(dish.a, moved.a)
        assertEquals(dish.b, moved.b)
        assertEquals(0.8f, moved.t)
    }

    @Test
    fun `t persists through copy of wells`() {
        val dish = DishState(a = 0xFF000000.toInt(), b = 0xFFFFFFFF.toInt(), t = 0.75f)
        val wellsChanged = dish.copy(a = 0xFF123456.toInt())
        assertEquals(0.75f, wellsChanged.t)
    }
}
