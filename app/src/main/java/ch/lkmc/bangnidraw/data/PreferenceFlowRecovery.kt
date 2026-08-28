package ch.lkmc.bangnidraw.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/**
 * Retries transient preference reads without replacing already-rendered state.
 *
 * A corrupt file never reaches this helper: the DataStore's corruption handler
 * resets it first. Other I/O failures retry with caller-provided backoff, but
 * only [MAX_RETRY_ATTEMPTS] times; a permanently broken store then ends the
 * flow on its last value instead of looping or failing the collector.
 */
internal fun <T> Flow<T>.retryIoWithInitialFallback(
    fallback: T,
    onFirstIoFailure: (IOException) -> Unit,
    onRetriesExhausted: (IOException) -> Unit,
    pauseBeforeRetry: suspend (attempt: Long) -> Unit,
): Flow<T> = flow {
    var hasSourceValue = false

    this@retryIoWithInitialFallback
        .onEach { hasSourceValue = true }
        .retryWhen { error, attempt ->
            if (error is CancellationException) throw error
            if (error !is IOException) return@retryWhen false

            val isFirstFailure = attempt == FIRST_RETRY_ATTEMPT

            // Warn once; only an unloaded collector needs a fallback.
            if (isFirstFailure) onFirstIoFailure(error)
            if (isFirstFailure && !hasSourceValue) emit(fallback)
            if (attempt >= MAX_RETRY_ATTEMPTS) {
                onRetriesExhausted(error)
                return@retryWhen false
            }

            pauseBeforeRetry(attempt)
            true
        }
        // The bounded give-up above rethrows; end on the last value instead.
        .catch { error ->
            if (error is CancellationException || error !is IOException) throw error
        }
        .collect(::emit)
}

internal const val MAX_RETRY_ATTEMPTS = 5L

private const val FIRST_RETRY_ATTEMPT = 0L
