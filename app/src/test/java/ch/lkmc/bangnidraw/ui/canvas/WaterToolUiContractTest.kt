package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.WatercolorBehavior
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WaterToolUiContractTest {

    @Test
    fun `water settings expose the three plain controls`() {
        val source = source(SETTINGS_PATH)
        val water = source
            .substringAfter("internal fun WaterSettingsSheet(")
            .substringBefore("internal fun BlurSettingsSheet(")

        assertTrue("ToolSizeSlider(active.size, active.sizeMin, active.sizeMax)" in water)
        assertTrue("label = stringResource(R.string.water_amount)" in water)
        assertTrue("label = stringResource(R.string.water_spread)" in water)
    }

    @Test
    fun `watercolor brush exposes persisted behavior controls`() {
        val source = source(BRUSH_SETTINGS_PATH)
        val watercolor = source
            .substringAfter("val watercolor = active.watercolor")
            .substringBefore("// RGB ignores these stored values")

        assertTrue("R.string.water_amount" in watercolor)
        assertTrue("R.string.water_spread" in watercolor)
        assertTrue("R.string.water_granulation" in watercolor)
        assertTrue("R.string.water_edge_darkening" in watercolor)
        assertTrue("active.copy(watercolor = watercolor.copy" in watercolor)
        assertTrue("onValueChangeFinished = onPresetPersisted" in watercolor)
        assertTrue(
            Regex("if \\(active\\.watercolor == null\\)").findAll(source).count() == 4,
            "watercolor must hide unsupported opacity, mixing, and buffer controls",
        )
    }

    @Test
    fun `watercolor hides inert grain and inaccurate preview`() {
        val source = source(BRUSH_SETTINGS_PATH)
        val paint = source
            .substringAfter("SettingsGroup(stringResource(R.string.brush_group_paint))")
            .substringBefore("// RGB ignores these stored values")

        assertTrue(
            """if (watercolor == null) {
                ToggleRow(
                    label = stringResource(R.string.brush_grain)""" in paint,
        )
        assertTrue(
            "if (watercolor != null) return@LaunchedEffect" in source,
            "watercolor presets must not render the CPU preview (it cannot show wet behaviour)",
        )
        assertTrue("R.string.watercolor_preview_hint" in source)
    }

    @Test
    fun `watercolor flow uses durable quick tuning`() {
        val prefs = source(PREFS_PATH)
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)

        assertTrue("flow = snapshot[flowKey(id)]" in prefs)
        assertTrue("stored.setOrRemove(flowKey(id), tuning.flow)" in prefs)
        assertTrue("it.remove(flowKey(id))" in prefs)
        assertTrue("preset.applyTuning(tunings[preset.id])" in viewModel)
        assertTrue("prefs.setBrushTuning(preset.id, preset.persistedTuning())" in viewModel)
    }

    @Test
    fun `watercolor brush hides stroke opacity controls`() {
        val source = source(BRUSH_SETTINGS_PATH)

        assertTrue(
            """if (active.watercolor == null) {
                SettingSlider(
                    label = stringResource(R.string.brush_opacity)""" in source,
        )
        assertTrue(
            """if (active.watercolor == null) {
                CurveEditor(
                    title = stringResource(R.string.brush_pressure_opacity)""" in source,
        )
    }

    @Test
    fun `quick controls label wet semantics plainly`() {
        val rail = source(TOOL_RAIL_PATH)
        val ledge = source(SLIDER_LEDGE_PATH)

        assertTrue("ToolSliderSecondary.FLOW -> R.string.brush_flow" in rail)
        assertTrue("ToolSliderSecondary.WATER -> R.string.water_amount" in rail)
        assertTrue("ToolSliderSecondary.FLOW -> R.string.brush_flow" in ledge)
        assertTrue("ToolSliderSecondary.WATER -> R.string.water_amount" in ledge)
    }

    @Test
    fun `watercolor quick secondary updates flow`() {
        // `section`, not raw substringAfter/Before: those return the whole
        // receiver when a delimiter is missing, so a renamed anchor would
        // widen the window to the entire file and let the assertion below
        // pass on a call that had moved somewhere else entirely.
        val update = section(
            source(CANVAS_VIEW_MODEL_PATH),
            "fun updateActiveToolSecondary(value: Float)",
            "internal fun adjustBrushSize",
        )

        // The rule lives in engine-core so the desktop rail's secondary
        // slider cannot drift from this one; the ViewModel routes through it
        // rather than keeping a second copy of the watercolor branch.
        assertTrue("ToolSliderPreset.withSecondary(preset, value)" in update)

        val watercolor = BrushPresets.INK_PEN.copy(
            opacity = 1f,
            flow = 0.35f,
            mixing = true,
            watercolor = WatercolorBehavior(),
            bufferMode = BufferMode.Accumulate,
        )
        val tuned = ToolSliderPreset.withSecondary(watercolor, 0.25f)

        assertEquals(0.25f, tuned.flow)
        assertEquals(watercolor.opacity, tuned.opacity)
        // The guard the deleted ViewModel branch carried: an unset secondary
        // must not write NaN into flow and poison every watercolor stroke.
        assertEquals(watercolor.flow, ToolSliderPreset.withSecondary(watercolor, Float.NaN).flow)
        assertEquals(0.6f, ToolSliderPreset.withSecondary(watercolor.copy(watercolor = null), 0.6f).opacity)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()
    private fun section(source: String, start: String, end: String): String {
        assertTrue(start in source, "missing source anchor: $start")

        val afterStart = source.substringAfter(start)
        assertTrue(end in afterStart, "missing source anchor: $end")
        return afterStart.substringBefore(end)
    }

    @Test
    fun `water preserves the active paint slot`() {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val selection = section(
            viewModel,
            "fun selectBrushPreset(id: String)",
            "internal fun toggleEraserPreset",
        )
        val water = section(
            viewModel,
            "internal fun selectWater()",
            "fun selectBlur()",
        )
        val rail = source(TOOL_RAIL_PATH)
        val prefs = source(PREFS_PATH)

        assertTrue("val updated = prefs.assignPaintSlot(" in selection)
        assertTrue("activeIndex = paintSlots.activeIndex" in selection)
        assertTrue("presetId = id" in selection)
        assertTrue("paintSlots = updated" in selection)
        assertTrue("firstOrNull { it.id == updated.activePresetId }" in selection)
        assertTrue("?: return@collect" in selection)
        assertTrue("paintSlots = prefs.loadPaintSlots(paintPresetIds)" in viewModel)
        assertTrue("synchronized(paintSlotLock)" in prefs)
        assertTrue("selectPaintSlot(paintSlots.activeIndex)" in selection)
        assertTrue("toolSwitcher.select(ToolKind.Water(waterParams))" in water)
        assertTrue("paintSlots" !in water)
        assertTrue("it.assignmentIndex == paintSlots.activeIndex" in rail)
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
        const val PREFS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/data/Prefs.kt"
        const val CANVAS_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt"
        const val BRUSH_SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"
        const val SETTINGS_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/RmwSettingsSheet.kt"
        const val TOOL_RAIL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
        const val SLIDER_LEDGE_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/SliderLedge.kt"
    }
}
