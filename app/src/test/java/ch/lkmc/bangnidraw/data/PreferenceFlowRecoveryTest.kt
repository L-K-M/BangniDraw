package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.AppTheme
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.fail
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class PreferenceFlowRecoveryTest {

    @Test
    fun `initial IO failures emit one fallback and keep observing`() = runBlocking {
        val firstFailure = IOException("first")
        var collection = 0
        var pauses = 0
        val logged = mutableListOf<IOException>()
        val values = flow {
            when (collection++) {
                0 -> throw firstFailure
                1 -> throw IOException("second")
                else -> emit(AppTheme.CORAL)
            }
        }.retryIoWithInitialFallback(
            fallback = AppTheme.DEFAULT,
            onFirstIoFailure = logged::add,
            onRetriesExhausted = { fail("two transient failures must not exhaust retries") },
            pauseBeforeRetry = { pauses++ },
        ).toList()

        assertEquals(listOf(AppTheme.DEFAULT, AppTheme.CORAL), values)
        assertEquals(2, pauses)
        assertEquals(1, logged.size)
        assertSame(firstFailure, logged.single())
    }

    @Test
    fun `IO failure after a value never flashes the fallback`() = runBlocking {
        var collection = 0
        val values = flow {
            if (collection++ == 0) {
                emit(AppTheme.CORAL)
                throw IOException("after value")
            }

            emit(AppTheme.TEAL)
        }.retryIoWithInitialFallback(
            fallback = AppTheme.DEFAULT,
            onFirstIoFailure = {},
            onRetriesExhausted = {},
            pauseBeforeRetry = {},
        ).toList()

        assertEquals(listOf(AppTheme.CORAL, AppTheme.TEAL), values)
    }

    @Test
    fun `persistent IO failure keeps the fallback after bounded retries`() = runBlocking {
        var collections = 0
        var pauses = 0
        val attempts = mutableListOf<Long>()
        val logged = mutableListOf<IOException>()
        val exhausted = mutableListOf<IOException>()
        val values = flow<AppTheme> {
            collections++
            throw IOException("storage gone")
        }.retryIoWithInitialFallback(
            fallback = AppTheme.DEFAULT,
            onFirstIoFailure = logged::add,
            onRetriesExhausted = exhausted::add,
            pauseBeforeRetry = { attempt ->
                pauses++
                attempts += attempt
            },
        ).toList()

        assertEquals(listOf(AppTheme.DEFAULT), values)
        assertEquals(1 + MAX_RETRY_ATTEMPTS.toInt(), collections)
        assertEquals(MAX_RETRY_ATTEMPTS.toInt(), pauses)
        assertEquals((0L until MAX_RETRY_ATTEMPTS).toList(), attempts)
        assertEquals(1, logged.size)
        assertEquals(1, exhausted.size)
    }

    @Test
    fun `persistent IO failure after a value keeps that value`() = runBlocking {
        var collection = 0
        val values = flow {
            if (collection++ == 0) {
                emit(AppTheme.CORAL)
                throw IOException("first")
            }

            throw IOException("storage gone")
        }.retryIoWithInitialFallback(
            fallback = AppTheme.DEFAULT,
            onFirstIoFailure = {},
            onRetriesExhausted = {},
            pauseBeforeRetry = {},
        ).toList()

        assertEquals(listOf(AppTheme.CORAL), values)
    }

    @Test
    fun `non IO failure propagates unchanged`() = runBlocking {
        val failure = IllegalStateException("broken decoder")
        val thrown = assertFailsWith<IllegalStateException> {
            flow<AppTheme> { throw failure }
                .retryIoWithInitialFallback(
                    fallback = AppTheme.DEFAULT,
                    onFirstIoFailure = { fail("non-IO failure was logged") },
                    onRetriesExhausted = { fail("non-IO failure was exhausted") },
                    pauseBeforeRetry = { fail("non-IO failure was retried") },
                )
                .toList()
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `cancellation propagates unchanged`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            flow<AppTheme> { throw cancellation }
                .retryIoWithInitialFallback(
                    fallback = AppTheme.DEFAULT,
                    onFirstIoFailure = { fail("cancellation was logged") },
                    onRetriesExhausted = { fail("cancellation was exhausted") },
                    pauseBeforeRetry = { fail("cancellation was retried") },
                )
                .toList()
        }

        assertSame(cancellation, thrown)
    }
}
