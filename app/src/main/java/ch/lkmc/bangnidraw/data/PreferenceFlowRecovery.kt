package ch.lkmc.bangnidraw.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** Retries transient preference reads without replacing already-rendered state. */
internal fun <T> Flow<T>.retryIoWithInitialFallback(
    fallback: T,
    onFirstIoFailure: (IOException) -> Unit,
    pauseBeforeRetry: suspend () -> Unit,
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

            pauseBeforeRetry()
            true
        }
        .collect(::emit)
}

private const val FIRST_RETRY_ATTEMPT = 0L
