package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayerVisibilityPolicyTest {
    @Test
    fun `a hidden active layer is visible only during its stroke preview`() {
        assertFalse(LayerVisibilityPolicy.shouldDraw(visible = false, opacity = 1f, strokePreview = false))
        assertTrue(LayerVisibilityPolicy.shouldDraw(visible = false, opacity = 1f, strokePreview = true))
        assertFalse(LayerVisibilityPolicy.shouldDraw(visible = false, opacity = 0f, strokePreview = true))
        assertTrue(LayerVisibilityPolicy.shouldDraw(visible = true, opacity = 1f, strokePreview = false))
    }
}
