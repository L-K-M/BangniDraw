package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollZoomTest {

    @Test
    fun `one notch up multiplies by the step`() {
        assertEquals(ScrollZoom.STEP_PER_NOTCH, ScrollZoom.factor(1f), 1e-6f)
    }

    @Test
    fun `one notch down divides by the step`() {
        assertEquals(1f / ScrollZoom.STEP_PER_NOTCH, ScrollZoom.factor(-1f), 1e-6f)
    }

    @Test
    fun `fractional trackpad ticks scale smoothly`() {
        val half = ScrollZoom.factor(0.5f)
        assertTrue(half > 1f && half < ScrollZoom.STEP_PER_NOTCH)
        assertEquals(ScrollZoom.factor(1f), half * half, 1e-5f)
    }

    @Test
    fun `zero and non-finite ticks zoom nothing`() {
        assertEquals(1f, ScrollZoom.factor(0f))
        assertEquals(1f, ScrollZoom.factor(Float.NaN))
        assertEquals(1f, ScrollZoom.factor(Float.POSITIVE_INFINITY))
        assertEquals(1f, ScrollZoom.factor(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `a coasting burst is bounded per event`() {
        val cap = ScrollZoom.factor(ScrollZoom.MAX_TICKS_PER_EVENT)
        assertEquals(cap, ScrollZoom.factor(100f))
        assertEquals(1f / cap, ScrollZoom.factor(-100f), 1e-6f)
    }

    @Test
    fun `pointer-class events pivot at the cursor`() {
        assertEquals(
            3f to 7f,
            ScrollZoom.pivot(
                pointerClass = true,
                eventX = 3f,
                eventY = 7f,
                viewWidth = 800f,
                viewHeight = 600f,
            ),
        )
    }

    @Test
    fun `touchpad events pivot at the viewport centre`() {
        assertEquals(
            400f to 300f,
            ScrollZoom.pivot(
                pointerClass = false,
                eventX = 3f,
                eventY = 7f,
                viewWidth = 800f,
                viewHeight = 600f,
            ),
        )
    }

    @Test
    fun `non-finite coordinates yield no pivot for either source class`() {
        for (pointerClass in listOf(true, false)) {
            assertNull(
                ScrollZoom.pivot(
                    pointerClass = pointerClass,
                    eventX = Float.NaN,
                    eventY = 7f,
                    viewWidth = 800f,
                    viewHeight = 600f,
                ),
            )
            assertNull(
                ScrollZoom.pivot(
                    pointerClass = pointerClass,
                    eventX = 3f,
                    eventY = Float.POSITIVE_INFINITY,
                    viewWidth = 800f,
                    viewHeight = 600f,
                ),
            )
        }
    }

    @Test
    fun `a touchpad event before layout yields no pivot`() {
        assertNull(
            ScrollZoom.pivot(
                pointerClass = false,
                eventX = 3f,
                eventY = 7f,
                viewWidth = null,
                viewHeight = null,
            ),
        )
    }
}
