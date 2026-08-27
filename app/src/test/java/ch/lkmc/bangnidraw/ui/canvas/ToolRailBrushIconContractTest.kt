package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRailBrushIconContractTest {

    private val source = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt",
    ).readText()

    @Test
    fun `brush slot uses the persisted icon key`() {
        val brushSlot = source.substringAfter("private fun brushSlot(")
            .substringBefore("private fun secondarySlots(")
        val iconKeys = source.substringAfter("private enum class BrushIconKey")
            .substringBefore("private data class ToolSlot")

        assertTrue(brushSlot.contains("iconFor(preset.icon)"))
        assertFalse(brushSlot.contains("iconFor(preset.id)"))
        assertTrue(iconKeys.contains("Round(\"round\")"))
        assertTrue(iconKeys.contains("Flat(\"flat\")"))
    }
}
