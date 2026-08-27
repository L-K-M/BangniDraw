package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the layer row's drag handle as a touch-only affordance (k3.md §1.3):
 * an `IconButton(onClick = {})` is a focusable, activatable node that does
 * nothing on activation — a TalkBack trap. The accessible reorder path is
 * the row's custom actions and the per-row menu, so the handle carries no
 * semantics of its own; the drag gesture itself must survive.
 */
class LayerPanelDragHandleContractTest {

    @Test
    fun `no no-op click survives in the layer panel`() {
        assertFalse(
            source().contains(NO_OP_CLICK),
            "a no-op onClick is still present",
        )
    }

    @Test
    fun `drag handle exposes no semantics but keeps the drag`() {
        val handle = handleSection().replace(WHITESPACE, " ")

        assertTrue(
            "clearAndSetSemantics" in handle,
            "clearAndSetSemantics is missing; the handle may publish semantics",
        )
        assertTrue(
            "detectDragGestures(" in handle,
            "the drag gesture is gone",
        )
        assertTrue(
            "pointerInput(layer.id, documentBusy)" in handle &&
                "if (documentBusy) return@pointerInput" in handle,
            "the drag remains active while document actions are busy",
        )
    }

    private fun handleSection(): String {
        val text = source()
        val start = text.indexOf(DRAG_HANDLE_START)
        if (start < 0) fail("missing source marker: $DRAG_HANDLE_START")
        val end = text.indexOf(DRAG_HANDLE_END, start)
        if (end <= start) fail("missing source marker: $DRAG_HANDLE_END")
        return text.substring(start, end)
    }

    private fun source(): String = File(repositoryRoot(), LAYER_PANEL_PATH).readText()

    private fun repositoryRoot(): File {
        val userDirectory = checkNotNull(System.getProperty("user.dir")) {
            "user.dir is unavailable"
        }
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            val candidate = directory
            if (
                candidate.resolve("settings.gradle").isFile ||
                candidate.resolve("settings.gradle.kts").isFile
            ) {
                return candidate
            }

            directory = candidate.parentFile
        }

        fail("repository root not found above $userDirectory")
    }

    private companion object {
        const val LAYER_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val DRAG_HANDLE_START = "Touch-only affordance"
        const val DRAG_HANDLE_END = "LayerThumbnail(thumbnail)"
        val NO_OP_CLICK = Regex("""onClick\s*=\s*\{\s*\}""")
        val WHITESPACE = Regex("\\s+")
    }
}
