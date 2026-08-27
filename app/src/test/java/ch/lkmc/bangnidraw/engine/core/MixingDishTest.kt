package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

class MixingDishTest {

    @Test
    fun `dish asks the active mixer for nine inclusive steps`() {
        val mixer = object : ColorMixer {
            override val isPigment = true

            override fun mix(a: Int, b: Int, t: Float): Int = (t * 8f).toInt()
        }

        assertContentEquals(
            IntArray(9) { it },
            MixingDish.gradient(0, 1, mixer),
        )
    }
}
