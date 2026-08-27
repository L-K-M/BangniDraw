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
        val queue = attachment.indexOf("queueDetachedSessionDrain(departing)")
        val wait = attachment.indexOf("awaitDetachedSessionDrain(streamBarrier)")
        val relist = attachment.indexOf("store.relistTiles(doc)")
        val stream = attachment.indexOf("streamTiles(next, diskDocument)")

        assertTrue(capture >= 0)
        assertTrue(capture < queue)
        assertTrue(queue < wait)
        assertTrue(wait < relist)
        assertTrue(relist < stream)
        assertTrue(wait < stream)
        assertTrue(drain.contains("awaitReleaseReadback(engine)"))
        assertTrue(await.contains("flusher.checkpointFlush()"))
    }
}
