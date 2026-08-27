package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class LayerPanelMenuContractTest {

    @Test
    fun `empty reorder section stays out of the layer menu`() {
        val source = File(repositoryRoot(), LAYER_PANEL_PATH).readText()
        val menuStart = source.indexOf(LAYER_MENU_START)
        require(menuStart >= 0) { "$LAYER_MENU_START not found in $LAYER_PANEL_PATH" }

        val menuEnd = source.indexOf(ACTION_ITEM_START, menuStart)
        require(menuEnd > menuStart) { "$ACTION_ITEM_START not found after LayerMenu" }

        val menu = source.substring(menuStart, menuEnd)

        assertTrue(
            "if (reorderActions.isNotEmpty())" in menu,
            "layer menu does not guard the empty reorder section",
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val LAYER_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val LAYER_MENU_START = "private fun LayerMenu("
        const val ACTION_ITEM_START = "ActionItem(R.string.layer_rename"
    }
}
