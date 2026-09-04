package ch.lkmc.bangnidraw.desktop

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one GL thread and the one ES context every open document renders
 * through.
 *
 * A context can be current on one thread at a time, so several documents
 * cannot each own a thread of their own without each owning a context of its
 * own — and creating contexts at runtime is exactly the part of startup that
 * is delicate on macOS (AGENTS.md's ANGLE notes) and is created once, on a
 * path CI covers. One context with several [CanvasRenderer]s costs nothing:
 * the renderers hold independent GL object sets.
 *
 * Clients are attached and detached by posting onto this thread, so a window
 * that opens or closes never races the loop that is drawing it.
 */
internal class DesktopGlHost(
    private val context: DesktopEsContext,
    private val onFatal: (String) -> Unit,
) {
    /** One document's share of the GL thread. */
    interface Client {
        /** Creates this document's GL resources. Throwing fails the host. */
        fun onGlReady()

        /** One turn of the loop; true when it did something. */
        fun pumpGl(): Boolean

        /** Releases this document's GL resources. Runs on the GL thread. */
        fun releaseGl()
    }

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()

    /** GL-thread only: mutated by attach/detach tasks, read by the loop. */
    private val clients = ArrayList<Client>()

    private val started = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val thread = Thread(::run, GL_THREAD_NAME).apply { isDaemon = true }

    /** Submits [block] to the GL thread; dropped after a fatal failure. */
    fun post(block: () -> Unit) {
        if (!failed.get()) tasks.add(block)
    }

    fun attach(client: Client) {
        post {
            clients += client
            client.onGlReady()
        }
        if (!started.getAndSet(true)) thread.start()
    }

    /**
     * Removes [client] and frees its GL resources, on the GL thread. The
     * caller does not block: a window closing must not wait for a frame.
     */
    fun detach(client: Client) = post {
        if (clients.remove(client)) client.releaseGl()
    }

    fun stopAndJoin() {
        if (!started.get()) return

        thread.interrupt()
        if (Thread.currentThread() === thread) return

        try {
            thread.join(GL_SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            context.abandonAfterOwnerTimeout()
            Thread.currentThread().interrupt()
            return
        }

        // Never destroy a context that may still be current in native code.
        if (thread.isAlive) context.abandonAfterOwnerTimeout()
    }

    private fun run() {
        try {
            context.activate()
            loop()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            failed.set(true)
            val detail = failure.message ?: failure::class.simpleName ?: "unknown failure"
            onFatal("Desktop rendering stopped: $detail")
        } finally {
            release()
        }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            var worked = false
            while (true) {
                val task = tasks.poll() ?: break
                task()
                worked = true
            }
            // Indexed, not an iterator: a task drained above can attach or
            // detach a document, and the list is this thread's own.
            for (index in clients.indices) {
                if (clients[index].pumpGl()) worked = true
            }
            if (!worked) Thread.sleep(IDLE_SLEEP_MS)
        }
    }

    private fun release() {
        try {
            // One document's teardown must not strand another's textures.
            for (client in clients) {
                runCatching { client.releaseGl() }
            }
            clients.clear()
        } finally {
            context.deactivate()
        }
    }

    private companion object {
        const val GL_THREAD_NAME = "BangniDraw-GL"
        const val GL_SHUTDOWN_TIMEOUT_MS = 5_000L
        const val IDLE_SLEEP_MS = 4L
    }
}
