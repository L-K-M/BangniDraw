package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanvasOpenPolicyTest {

    @Test
    fun `a missing project is rejected as an open failure`() {
        val decision = CanvasOpenPolicy.decide(
            ProjectStore.LoadResult.Failed(ProjectStore.FailureReason.NOT_FOUND),
        )

        val rejection = assertIs<CanvasOpenDecision.Reject>(decision)
        assertEquals(R.string.canvas_open_failed, rejection.message)
    }
}
