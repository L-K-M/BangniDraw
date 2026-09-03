package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopRuntimeLifecycleContractTest {

    @Test
    fun `deadline scheduler serializes mutations and ignores stale timer events`() {
        val scheduler = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopSchedulers.kt")

        assertTrue(scheduler.contains("private val timerLock"))
        assertTrue(scheduler.contains("synchronized(timerLock)"))
        assertTrue(scheduler.contains("timers[callback] !== timer"))
    }

    @Test
    fun `GL context teardown checks visibility thread ownership and sole GLFW ownership`() {
        val context = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")

        assertTrue(context.contains("@Volatile\n    private var active"))
        assertTrue(context.contains("@Volatile\n    private var activationThread"))
        assertTrue(context.contains("Thread.currentThread() === activationThread"))
        assertTrue(context.contains("check(!ownsGlfw)"))
        assertTrue(context.contains("terminateOwnedGlfw()"))

        val destroyBody = context
            .substringAfter("fun destroy()")
            .substringBefore("companion object")
        val ownershipCheck = destroyBody.indexOf("check(ownsGlfw)")
        val firstNativeCall = destroyBody.indexOf("GLFW.")

        assertTrue(ownershipCheck >= 0, "destroy must check GLFW ownership")
        assertTrue(firstNativeCall >= 0, "destroy must call GLFW after validation")
        assertTrue(
            ownershipCheck < firstNativeCall,
            "destroy must reject stale contexts before calling GLFW",
        )
    }

    @Test
    fun `shutdown drains exports and reports queued cancellation`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")

        assertTrue(engine.contains("exportExecutor.shutdown()"))
        assertTrue(engine.contains("exportExecutor.awaitTermination("))
        assertTrue(engine.contains("DesktopExportTask"))
        assertTrue(engine.contains("task.cancel()"))
    }

    @Test
    fun `GL shutdown is bounded without destroying a live context`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val context = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")

        assertTrue(
            engine.contains("glThread.join(GL_SHUTDOWN_TIMEOUT_MS)"),
            "native GL shutdown must not block application exit forever",
        )
        assertTrue(engine.contains("context.abandonAfterOwnerTimeout()"))
        assertTrue(context.contains("if (abandonedAfterOwnerTimeout)"))
    }

    @Test
    fun `cancelled export task completes once`() {
        val results = mutableListOf<DesktopSaveResult>()
        val task = DesktopExportTask(
            export = { DesktopSaveResult.Saved("unused") },
            onComplete = results::add,
        )

        task.cancel()
        task.run()
        task.cancel()

        assertEquals(1, results.size)
        assertIs<DesktopSaveResult.Failed>(results.single())
    }

    @Test
    fun `fatal export completes once before rethrowing`() {
        val failure = AssertionError("fatal export")
        val results = mutableListOf<DesktopSaveResult>()
        val task = DesktopExportTask(
            export = { throw failure },
            onComplete = results::add,
        )

        val thrown = assertFailsWith<AssertionError> { task.run() }
        task.cancel()

        assertSame(failure, thrown)
        assertEquals(1, results.size)
        assertEquals(DesktopSaveResult.Failed("fatal export"), results.single())
    }

    @Test
    fun `interrupted export restores status before completion`() {
        Thread.interrupted()
        val completionInterrupts = mutableListOf<Boolean>()
        val task = DesktopExportTask(
            export = { throw InterruptedException("interrupted export") },
            onComplete = { completionInterrupts += Thread.currentThread().isInterrupted },
        )

        try {
            task.run()

            assertEquals(listOf(true), completionInterrupts)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `GL failures retain platform recovery guidance`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val context = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(engine.contains("DesktopGlDiagnostics.rendererRequirements"))
        assertTrue(context.contains("DesktopGlDiagnostics.contextFailure"))
        assertTrue(main.contains("Windows: this desktop target supports macOS and Linux only."))
    }

    @Test
    fun `both GL hosts record why ANGLE is loaded by absolute path`() {
        val glfw = prose("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val egl = prose("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/EglEsContext.kt")

        // Anchored on the technical tokens, not on prose: a reflow of these
        // comments must not fail the build, but losing the rule must.
        assertTrue(glfw.contains("dlopen"))
        assertTrue(glfw.contains("allowAtPaths"))
        assertTrue(glfw.contains("AMFI"))
        // Whitespace is collapsed above, so a phrase survives a reflow while
        // saying more than two words that could appear in any error string.
        assertTrue(egl.contains("EGL is loaded by absolute path"))
    }

    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    /**
     * A source file with its comment scaffolding flattened, so an anchor can be
     * a phrase: collapsing whitespace alone leaves the " * " a wrapped KDoc line
     * carries, which no quotable sentence contains.
     */
    private fun prose(path: String): String = source(path)
        .replace(Regex("\\n\\s*\\*\\s?"), " ")
        .replace(Regex("\\s+"), " ")

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }

        return candidate
    }
}
