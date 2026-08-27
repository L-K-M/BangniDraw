package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DetachedReadbackWiringTest {

    private val source = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt",
    ).readText()

    @Test
    fun `stroke history drains its originating session`() {
        val callback = source.substringAfter("private fun onStrokeMerged(")
            .substringBefore("/** Called at pen-up")
        val attachment = source.substringAfter("fun attachSession(next: EngineSession?)")
            .substringBefore("fun leave(")

        assertTrue(callback.contains("engine: EngineSession"))
        assertTrue(callback.contains("awaitReadbacks(engine)"))
        assertTrue(attachment.contains("onStrokeMerged(next, spec"))
    }

    @Test
    fun `final checkpoint drains the detached session before snapshot`() {
        val cleared = source.substringAfter("override fun onCleared()")
            .substringBefore("private fun noteChange()")
        val captureEngine = cleared.indexOf("val engine = session")
        val queue = cleared.indexOf("queueDetachedSessionDrain")
        val drain = cleared.indexOf("awaitDetachedSessionDrain(detachBarrier)")
        val snapshot = cleared.indexOf("captureCheckpointSnapshot")

        assertTrue(captureEngine >= 0)
        assertTrue(captureEngine < queue)
        assertTrue(queue < drain)
        assertTrue(drain < snapshot)
    }

    @Test
    fun `final checkpoint also waits for a session detached by Compose`() {
        val cleared = source.substringAfter("override fun onCleared()")
            .substringBefore("private fun noteChange()")

        val existingBarrier = cleared.indexOf("?: detachedSessionDrain")
        val detachDrain = cleared.indexOf("awaitDetachedSessionDrain(detachBarrier)")
        val snapshot = cleared.indexOf("captureCheckpointSnapshot")

        assertTrue(existingBarrier >= 0)
        assertTrue(detachDrain >= 0)
        assertTrue(detachDrain < snapshot)
    }

    @Test
    fun `replacement waits for detached pixels to reach disk`() {
        val attachment = source.substringAfter("fun attachSession(next: EngineSession?)")
            .substringBefore("fun leave(")
        val drain = source.substringAfter("private fun queueDetachedSessionDrain(")
            .substringBefore("private suspend fun awaitDetachedSessionDrain(")
        val await = source.substringAfter("private suspend fun awaitDetachedSessionDrain(")
            .substringBefore("private suspend fun streamTiles(")

        val capture = attachment.indexOf("val departing = session")
        val beginSync = attachment.indexOf("actionGate.beginSessionSync()")
        val queue = attachment.indexOf("queueDetachedSessionDrain(departing)")
        val activeWork = attachment.indexOf("awaitActiveDocumentWork(workBarrier, historyBarrier)")
        val wait = attachment.indexOf("awaitDetachedSessionDrain(streamBarrier)")
        val currentDocument = attachment.indexOf("currentDocumentFor(next)")
        val relist = attachment.indexOf("store.relistTiles(currentDocument)")
        val publish = attachment.indexOf("publishRelistedDocument(next, diskDocument)")
        val stream = attachment.indexOf("streamTiles(next, diskDocument)")
        val finishSync = attachment.indexOf("finishSessionSync()")

        assertTrue(capture >= 0)
        assertTrue(capture < beginSync)
        assertTrue(capture < queue)
        assertTrue(queue < activeWork)
        assertTrue(activeWork < wait)
        assertTrue(queue < wait)
        assertTrue(wait < currentDocument)
        assertTrue(currentDocument < relist)
        assertTrue(relist < publish)
        assertTrue(publish < stream)
        assertTrue(relist < stream)
        assertTrue(stream < finishSync)
        assertTrue(drain.contains("awaitReleaseReadback(engine)"))
        assertTrue(await.contains("flusher.checkpointFlush()"))
    }

    @Test
    fun `replacement drain retries recoverable storage failure`() {
        val await = source.substringAfter("private suspend fun awaitDetachedSessionDrain(")
            .substringBefore("private suspend fun streamTiles(")

        assertTrue(await.contains("while (!flusher.checkpointFlush())"))
        assertTrue(await.contains("delay(SESSION_SYNC_RETRY_MS)"))
    }

    @Test
    fun `replacement snapshots active work before blocking the session`() {
        val attachment = source.substringAfter("fun attachSession(next: EngineSession?)")
            .substringBefore("fun leave(")

        val work = attachment.indexOf("val workBarrier = documentWorkBarrier")
        val history = attachment.indexOf("val historyBarrier = strokeHistoryBarrier")
        val beginSync = attachment.indexOf("actionGate.beginSessionSync()")

        assertTrue(work >= 0)
        assertTrue(work < history)
        assertTrue(history < beginSync)
    }
}
