package ch.lkmc.bangnidraw.ui.canvas

import java.util.concurrent.atomic.AtomicReference

/** The subsystem allowed to finish one asynchronous operation. */
internal enum class AsyncCompletionOwner { ENGINE, HISTORY }

/** Hands one completion from the engine to durable history without a race. */
internal class AsyncCompletionGate<T> {
    private data class Pending<T>(
        val owner: AsyncCompletionOwner,
        val callback: (T) -> Unit,
    )

    private val pending = AtomicReference<Pending<T>?>(null)

    fun begin(callback: (T) -> Unit): Boolean = pending.compareAndSet(
        null,
        Pending(AsyncCompletionOwner.ENGINE, callback),
    )

    fun handOffToHistory(): Boolean {
        while (true) {
            val current = pending.get() ?: return false
            if (current.owner != AsyncCompletionOwner.ENGINE) return false

            val handedOff = current.copy(owner = AsyncCompletionOwner.HISTORY)
            if (pending.compareAndSet(current, handedOff)) return true
        }
    }

    fun complete(owner: AsyncCompletionOwner, result: T): Boolean {
        while (true) {
            val current = pending.get() ?: return false
            if (current.owner != owner) return false
            if (!pending.compareAndSet(current, null)) continue

            current.callback(result)
            return true
        }
    }
}
