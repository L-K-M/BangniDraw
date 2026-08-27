package ch.lkmc.bangnidraw.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StudioThumbnailKeyTest {
    @Test
    fun `same path is invalidated by a newer painting revision`() {
        val first = StudioThumbnailKey(path = "/paintings/id/thumb.png", revision = 10L)
        val rewritten = StudioThumbnailKey(path = "/paintings/id/thumb.png", revision = 11L)

        assertNotEquals(first, rewritten)
        assertEquals(rewritten, rewritten.copy())
    }
}
