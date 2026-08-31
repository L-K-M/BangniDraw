package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ViewportResizePolicyTest {

    @Test
    fun `input rebases once and renderer preserves the published view`() {
        val oldFit = FitTransform(800f, 1280f, 1600f, 1200f)
        val newFit = FitTransform(1280f, 800f, 1600f, 1200f)
        val view = ViewTransform(scale = 2.4f, rotation = 0.35f, tx = -180f, ty = 90f)
        val start = ViewportResizeState(view, oldFit)

        val input = ViewportResizePolicy.resize(start, newFit, ViewportResizeOwner.INPUT)
        val renderer = ViewportResizePolicy.resize(
            ViewportResizeState(input.view, oldFit),
            newFit,
            ViewportResizeOwner.RENDERER,
        )

        assertEquals(view.rebase(oldFit, newFit), input.view)
        assertEquals(input.view, renderer.view)
        assertEquals(newFit, renderer.fit)
    }

    @Test
    fun `renderer resize before input does not steal the rebase`() {
        val oldFit = FitTransform(800f, 1280f, 1600f, 1200f)
        val newFit = FitTransform(1280f, 800f, 1600f, 1200f)
        val view = ViewTransform(scale = 1.7f, rotation = -0.2f, tx = 75f, ty = -40f)
        val start = ViewportResizeState(view, oldFit)

        val renderer = ViewportResizePolicy.resize(start, newFit, ViewportResizeOwner.RENDERER)
        val input = ViewportResizePolicy.resize(start, newFit, ViewportResizeOwner.INPUT)

        assertEquals(view, renderer.view)
        assertEquals(view.rebase(oldFit, newFit), input.view)
    }

    @Test
    fun `duplicate notifications do not rebase again`() {
        val oldFit = FitTransform(800f, 1280f, 1600f, 1200f)
        val newFit = FitTransform(1280f, 800f, 1600f, 1200f)
        val start = ViewportResizeState(ViewTransform(scale = 2f), oldFit)

        val resized = ViewportResizePolicy.resize(start, newFit, ViewportResizeOwner.INPUT)
        val duplicate = ViewportResizePolicy.resize(resized, newFit, ViewportResizeOwner.INPUT)

        assertSame(resized, duplicate)
    }
}
