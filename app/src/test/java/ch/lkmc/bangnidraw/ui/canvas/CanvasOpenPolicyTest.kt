package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.HistoryRecord
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class CanvasOpenPolicyTest {

    @Test
    fun `a missing project is rejected as an open failure`() {
        val decision = CanvasOpenPolicy.decide(
            ProjectStore.LoadResult.Failed(ProjectStore.FailureReason.NOT_FOUND),
        )

        val rejection = assertIs<CanvasOpenDecision.Reject>(decision)
        assertEquals(R.string.canvas_open_failed, rejection.message)
    }

    @Test
    fun `invalid and unreadable projects use the open failure`() {
        val reasons = listOf(
            ProjectStore.FailureReason.BAD_ID,
            ProjectStore.FailureReason.UNREADABLE,
        )

        for (reason in reasons) {
            val decision = CanvasOpenPolicy.decide(ProjectStore.LoadResult.Failed(reason))

            val rejection = assertIs<CanvasOpenDecision.Reject>(decision)
            assertEquals(R.string.canvas_open_failed, rejection.message)
        }
    }

    @Test
    fun `a newer project identifies the version mismatch`() {
        val decision = CanvasOpenPolicy.decide(
            ProjectStore.LoadResult.Failed(ProjectStore.FailureReason.NEWER_VERSION),
        )

        val rejection = assertIs<CanvasOpenDecision.Reject>(decision)
        assertEquals(R.string.canvas_newer_version, rejection.message)
    }

    @Test
    fun `a loaded project passes through unchanged`() {
        val loaded = ProjectStore.LoadResult.Loaded(
            document = Document(
                id = "painting",
                width = 256,
                height = 256,
                paperColor = 0,
                stack = LayerStack.initial { LayerId("layer") },
            ),
            unreadableLayers = 0,
            history = HistoryRecord(),
        )

        val decision = assertIs<CanvasOpenDecision.Open>(CanvasOpenPolicy.decide(loaded))
        assertSame(loaded, decision.project)
    }
}
