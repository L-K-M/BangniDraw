package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
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
        assertTrue("preview = null" in source)
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
        val source = source(CANVAS_VIEW_MODEL_PATH)
        val update = source
            .substringAfter("fun updateActiveToolSecondary(value: Float)")
            .substringBefore("internal fun adjustBrushSize")

        assertTrue("if (preset.watercolor == null)" in update)
        assertTrue("preset.withOpacity(value)" in update)
        assertTrue("preset.copy(" in update)
        assertTrue("flow = if (value.isNaN()) preset.flow" in update)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    @Test
    fun `water preserves the last specialty brush route`() {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val selection = viewModel
            .substringAfter("fun selectBrush(id: String)")
            .substringBefore("internal fun toggleEraserPreset")
        val water = viewModel
            .substringAfter("internal fun selectWater()")
            .substringBefore("fun selectBlur()")
        val rail = source(TOOL_RAIL_PATH)

        assertTrue(
            "if (preset.eraseMode) eraserBrushId = id else paintBrushId = id" in selection,
        )
        assertTrue("selectBrush(paintBrushId)" in selection)
        assertTrue("toolSwitcher.select(ToolKind.Water(waterParams))" in water)
        assertTrue("paintBrushId" !in water)
        assertTrue(
            "val currentPaint = paints.firstOrNull { it.id == paintBrushId }" in rail,
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
