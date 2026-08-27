package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioProjectWorkWiringTest {

    private val viewModel = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/home/StudioViewModel.kt",
    ).readText()
    private val screen = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt",
    ).readText()

    @Test
    fun `existing painting waits for the stale sweep`() {
        val open = viewModel.substringAfter("fun openPainting(")
            .substringBefore("fun share(")

        assertTrue(screen.contains("viewModel.openPainting(painting.id, onOpenPainting)"))
        assertTrue(open.contains("cancelAndJoinBackgroundJobs(jobs)"))
        assertTrue(
            open.indexOf("cancelAndJoinBackgroundJobs(jobs)") < open.indexOf("onReady(id)"),
        )
    }

    @Test
    fun `new painting still opens directly after creation`() {
        assertTrue(
            screen.contains(
                "viewModel.createPainting(size, paper) { id -> onOpenPainting(id) }",
            ),
        )
    }

    @Test
    fun `wide empty guidance precedes the new painting tile`() {
        val grid = screen.substringAfter("LazyVerticalGrid(")
            .substringBefore("items(state.paintings")
        val guidance = grid.indexOf("item(key = EMPTY_PAINTING_KEY")
        val newPainting = grid.indexOf("item(key = NEW_PAINTING_KEY")

        assertTrue(guidance >= 0)
        assertTrue(newPainting >= 0)
        assertTrue(guidance < newPainting)
    }

    @Test
    fun `gallery result is recorded before cancellation can finish`() {
        val sweep = viewModel.substringAfter("private fun syncStale(")
            .substringBefore("fun openPainting(")

        val cancellationCheck = sweep.lastIndexOf("ensureActive()")
        val nonCancellable = sweep.indexOf("withContext(NonCancellable)")
        val export = sweep.indexOf("exporter.sync(")
        val record = sweep.indexOf("store.updateGalleryFields(")

        assertTrue(cancellationCheck >= 0)
        assertTrue(cancellationCheck < nonCancellable)
        assertTrue(nonCancellable < export)
        assertTrue(export < record)
    }

    @Test
    fun `project mutations use the stale sweep barrier`() {
        val helper = viewModel.substringAfter("private fun launchProjectMutation(")
            .substringBefore("fun delete(")
        val barrier = viewModel.substringAfter("private suspend fun cancelAndJoinBackgroundJobs(")
            .substringBefore("fun share(")

        assertTrue(helper.contains("cancelAndJoinBackgroundJobs(backgroundJobs)"))
        assertTrue(helper.contains("projectWorkMutex.withLock"))
        assertTrue(barrier.contains("cancelAndJoin()"))

        for (verb in listOf("delete", "duplicate", "rename")) {
            val body = viewModel.substringAfter("fun $verb(")
                .substringBefore("\n    }")
            assertTrue(body.contains("launchProjectMutation"), verb)
        }

        val share = viewModel.substringAfter("fun share(")
            .substringBefore("fun createPainting(")
        assertFalse(share.contains("launchProjectMutation"))
    }

    @Test
    fun `delete uses gallery fields recorded by the finished sweep`() {
        val delete = viewModel.substringAfter("fun delete(")
            .substringBefore("fun duplicate(")

        val reload = delete.indexOf("store.load(id)")
        val galleryDelete = delete.indexOf("exporter.delete(currentGalleryUri)")

        assertTrue(reload >= 0)
        assertTrue(reload < galleryDelete)
    }
}
