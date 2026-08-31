package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasResizeOwnershipContractTest {

    @Test
    fun `input owns resize rebasing and publishes it before state`() {
        val input = source(INPUT_PATH)
            .substringAfter("fun setViewport(")
            .substringBefore("private fun updateScreen()")
        val screenSource = source(SCREEN_PATH)
        val navigation = screenSource
            .substringAfter("override fun onViewChanged(view: ViewTransform)")
            .substringBefore("override fun onViewportResized(view: ViewTransform)")
        val resize = screenSource
            .substringAfter("override fun onViewportResized(view: ViewTransform)")
            .substringBefore("override fun onRotationSnapped()")

        assertTrue("ViewportResizeOwner.INPUT" in input)
        assertTrue("host.onViewportResized(view)" in input)
        assertFalse("session?.setView(view)" in navigation)
        assertTrue("session?.setView(view)" in resize)
        assertTrue(resize.indexOf("session?.setView(view)") < resize.indexOf("updateView(view)"))
    }

    @Test
    fun `renderer resize updates targets without a second rebase`() {
        val renderer = source(RENDERER_PATH)
            .substringAfter("fun onSurfaceChanged(width: Int, height: Int)")
            .substringBefore("// ------------------------------------------------------------- document")
        val initialAttach = renderer
            .substringAfter("if (previous == null)")
            .substringBefore("} else")

        assertTrue("fit = next" in initialAttach)
        assertTrue("ViewportResizeOwner.RENDERER" in renderer)
        assertTrue("fit = resized.fit" in renderer)
        assertFalse(".rebase(" in renderer)
    }

    @Test
    fun `renderer resize releases stale framebuffer attachments before target replacement`() {
        val resize = source(RENDERER_PATH)
            .substringAfter("fun onSurfaceChanged(width: Int, height: Int)")
            .substringBefore("// ------------------------------------------------------------- document")
        val changeDeclaration =
            "val viewportChanged = width != viewportWidth || height != viewportHeight"
        val changeBranch = "if (viewportChanged) {"
        val guardIndex = resize.indexOf("if (width <= 0 || height <= 0) return")
        val changeIndex = resize.indexOf(changeDeclaration)
        val branchIndex = resize.indexOf(changeBranch)
        val widthAssignment = resize.indexOf("viewportWidth = width")
        val heightAssignment = resize.indexOf("viewportHeight = height")
        val accum = resize.indexOf("accum.ensure(width, height, state)")
        val scratch = resize.indexOf("scratch.ensure(width, height, state)")

        assertTrue(changeIndex >= 0)
        assertTrue(guardIndex in 0 until changeIndex)
        assertTrue(branchIndex > changeIndex)
        assertTrue(widthAssignment > changeIndex)
        assertTrue(heightAssignment > changeIndex)
        assertTrue(accum > branchIndex)
        assertTrue(scratch > branchIndex)

        val changed = resize
            .substring(branchIndex)
            .substringAfter(changeBranch)
            .substringBefore("}")

        assertTrue("fbo.release()" in changed)
        assertTrue("readFbo.release()" in changed)

        val firstEnsure = minOf(accum, scratch)
        assertTrue(resize.indexOf("fbo.release()", branchIndex) in branchIndex until firstEnsure)
        assertTrue(resize.indexOf("readFbo.release()", branchIndex) in branchIndex until firstEnsure)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun repositoryRoot(): File {
        val userDirectory = System.getProperty(USER_DIRECTORY_PROPERTY)
            ?: fail("$USER_DIRECTORY_PROPERTY is unavailable")
        val workingDirectory = File(userDirectory).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val INPUT_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/input/CanvasTouchHandler.kt"
        const val SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val RENDERER_PATH =
            "engine-gl/src/jvmShared/kotlin/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
    }
}
