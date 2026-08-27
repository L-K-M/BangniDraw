package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportResizePolicyTest {

    @Test
    fun `duplicate resize notifications rebase once`() {
        val oldFit = FitTransform(400f, 700f, 1200f, 800f)
        val newFit = FitTransform(700f, 400f, 1200f, 800f)
        val view = ViewTransform(scale = 2.4f, rotation = 0.35f, tx = -180f, ty = 90f)
        val start = ViewportResizeState(view = view, fit = oldFit)

        val resized = ViewportResizePolicy.resize(start, newFit)
        val duplicate = ViewportResizePolicy.resize(resized, newFit)

        assertEquals(view.rebase(oldFit, newFit), resized.view)
        assertEquals(resized, duplicate)
    }
}
