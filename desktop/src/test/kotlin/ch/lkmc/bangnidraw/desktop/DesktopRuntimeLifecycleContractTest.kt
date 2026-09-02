package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertTrue(context.contains("private var activationThread"))
        assertTrue(context.contains("Thread.currentThread() === activationThread"))
        assertTrue(context.contains("check(!ownsGlfw)"))
        assertTrue(context.contains("terminateOwnedGlfw()"))
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
    fun `GL failures retain platform recovery guidance`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val context = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/GlfwEsContext.kt")
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(engine.contains("DesktopGlDiagnostics.rendererRequirements"))
        assertTrue(context.contains("DesktopGlDiagnostics.contextFailure"))
        assertTrue(main.contains("Windows: this desktop target supports macOS and Linux only."))
    }

    @Test
    fun `ANGLE exposure documents its first-window lifetime`() {
        val bootstrap = source(
            "desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopNativeBootstrap.kt",
        )

        assertTrue(
            bootstrap.replace(Regex("\\s+"), " ")
                .contains("Keep the returned environment open until the first GLFW window"),
        )
    }

    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }

        return candidate
    }
}
