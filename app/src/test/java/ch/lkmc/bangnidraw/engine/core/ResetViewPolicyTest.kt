package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetViewPolicyTest {

    @Test
    fun `fit tolerance ignores navigation noise`() {
        assertFalse(
            ResetViewPolicy.isDisplaced(
                ViewTransform(scale = 1.01f, rotation = 0.008f, tx = 7.9f),
                density = 2f,
            ),
        )
    }

    @Test
    fun `scale rotation and pan each reveal reset`() {
        assertTrue(ResetViewPolicy.isDisplaced(ViewTransform(scale = 1.02f), density = 2f))
        assertTrue(ResetViewPolicy.isDisplaced(ViewTransform(rotation = 0.009f), density = 2f))
        assertTrue(ResetViewPolicy.isDisplaced(ViewTransform(tx = 8.1f), density = 2f))
    }
}
