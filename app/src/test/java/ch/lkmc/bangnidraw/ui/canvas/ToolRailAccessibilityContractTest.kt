package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ToolRailAccessibilityContractTest {

    @Test
    fun `eraser toggle is actionable and keeps a full touch target`() {
        val source = File(repositoryRoot(), TOOL_RAIL_PATH).readText()

        assertTrue("EraserTogglePolicy.next(eraser.id, presets)" in source)
        assertTrue(".minimumInteractiveComponentSize()" in source)
        assertTrue("LocalContentColor provides buttonColors.icon" in source)
        assertTrue(".clip(shape)" in source)
        assertTrue(".fillMaxSize()" in source)
        assertFalse("hapticsEnabled: Boolean" in source)
    }

    @Test
    fun `more menu exposes one selected tool choice`() {
        val source = File(repositoryRoot(), TOOL_RAIL_PATH).readText()

        assertTrue("val moreActive = blurActive || eyedropperActive" in source)
        assertTrue("modifier = Modifier.semantics { selectableGroup() }" in source)
        assertTrue("role = Role.RadioButton" in source)
        assertTrue("selected = item.active" in source)
        assertTrue("description = { stringResource(R.string.tool_blur) }" in source)
        assertTrue("description = { stringResource(R.string.tool_eyedropper) }" in source)
        assertFalse("onMoreSelected" in source)
    }

    @Test
    fun `compact modes keep six direct slots`() {
        val source = File(repositoryRoot(), TOOL_RAIL_PATH).readText()
        val grouped = source
            .substringAfter("private fun groupedSlots(")
            .substringBefore("private fun brushSlot(")
        val secondary = source
            .substringAfter("private fun groupedSecondarySlots(")
            .substringBefore("private fun fullSecondarySlots(")

        assertEquals(2, SLOT_PATTERN.findAll(grouped).count())
        assertEquals(4, SLOT_PATTERN.findAll(secondary).count())
        assertTrue("icon = Icons.Filled.WaterDrop" in secondary)
        assertTrue("icon = Icons.Filled.MoreHoriz" in secondary)
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
        val SLOT_PATTERN = Regex("(?:brushSlot|ToolSlot)\\(")
        const val TOOL_RAIL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
    }
}
