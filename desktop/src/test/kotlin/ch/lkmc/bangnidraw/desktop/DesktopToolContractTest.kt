package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.engine.core.RmwStrokePolicy
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.WaterParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopToolContractTest {

    private val paint = DesktopBrushes.loadAll().first { it.id == BrushPresets.INK_PEN_ID }

    // ------------------------------------------------------------- tuning

    @Test
    fun `the size slider writes where the shared policy reads it from`() {
        for (kind in tunableTools()) {
            val preset = assertNotNull(ToolSliderPreset.forKind(kind), "no slider preset for $kind")
            val target = (preset.sizeMin + preset.sizeMax) / 2f

            val tuned = DesktopToolTuning.withSize(kind, target)

            assertEquals(
                target,
                ToolSliderPreset.forKind(tuned)?.size,
                "size did not round-trip for $kind",
            )
        }
    }

    @Test
    fun `the second slider writes where the shared policy reads it from`() {
        for (kind in tunableTools()) {
            val tuned = DesktopToolTuning.withSecondary(kind, SECOND_VALUE)

            assertEquals(
                SECOND_VALUE,
                ToolSliderPreset.secondaryValue(tuned),
                "secondary did not round-trip for $kind",
            )
        }
    }

    @Test
    fun `an out-of-range slider value is clamped, never thrown`() {
        for (kind in tunableTools()) {
            val preset = assertNotNull(ToolSliderPreset.forKind(kind))
            val big = DesktopToolTuning.withSize(kind, HUGE_SIZE)
            val small = DesktopToolTuning.withSize(kind, -HUGE_SIZE)

            assertEquals(preset.sizeMax, ToolSliderPreset.forKind(big)?.size)
            assertEquals(preset.sizeMin, ToolSliderPreset.forKind(small)?.size)
            assertEquals(1f, ToolSliderPreset.secondaryValue(DesktopToolTuning.withSecondary(kind, 2f)))
            assertEquals(0f, ToolSliderPreset.secondaryValue(DesktopToolTuning.withSecondary(kind, -1f)))
        }
    }

    @Test
    fun `NaN keeps the current value rather than poisoning the tool`() {
        for (kind in tunableTools()) {
            assertEquals(kind, DesktopToolTuning.withSize(kind, Float.NaN))
            assertEquals(kind, DesktopToolTuning.withSecondary(kind, Float.NaN))
        }
    }

    @Test
    fun `fill and the eyedropper have no sliders and no tuning`() {
        for (kind in listOf(ToolKind.Fill(FillParams()), ToolKind.Eyedropper(EyedropperParams()))) {
            assertNull(ToolSliderPreset.forKind(kind))
            assertNull(ToolSliderPreset.secondaryValue(kind))
            assertSame(kind, DesktopToolTuning.withSize(kind, 10f))
            assertSame(kind, DesktopToolTuning.withSecondary(kind, 0.5f))
        }
    }

    @Test
    fun `the three RMW tools ask the shared policy for their spec`() {
        assertNotNull(RmwStrokePolicy.spec(ToolKind.Smudge(SmudgeParams()), RgbMixer))
        assertNotNull(RmwStrokePolicy.spec(ToolKind.Water(WaterParams()), RgbMixer))
        assertNotNull(RmwStrokePolicy.spec(ToolKind.Blur(BlurParams()), RgbMixer))
        // A plain brush is not read-modify-write; a watercolor one is.
        assertNull(RmwStrokePolicy.spec(ToolKind.Brush(paint), RgbMixer))
    }

    // ------------------------------------------------------------ dispatch

    @Test
    fun `neither fill nor the eyedropper opens a stroke`() {
        val begin = between(
            main(),
            "override fun onStrokeBegin(pointerId: Int, source: StrokeSource)",
            "override fun onStrokeSample(",
        )

        // Both return before the driver and the engine's beginStroke: a fill
        // is one commit from a point, and a pick writes no pixels at all.
        assertTrue(begin.contains("if (kind is ToolKind.Eyedropper)"))
        assertTrue(begin.contains("if (kind is ToolKind.Fill)"))
        val afterPick = begin.substringAfter("if (kind is ToolKind.Eyedropper)")
        assertTrue(afterPick.substringBefore("}").contains("return"))
    }

    @Test
    fun `the eyedropper reads through the shared gate, not per sample`() {
        val main = main()

        assertTrue(main.contains("EyedropperSampleGate()"))
        assertTrue(main.contains("pickGate.shouldRead("))
        assertTrue(main.contains("pickGate.reset()"))
    }

    @Test
    fun `the right button erases only where there is a brush to erase with`() {
        val main = main()

        // ERASER_END resolves the tool to the brush arm or refuses; without
        // that, right-dragging with smudge selected would smudge.
        assertTrue(main.contains("DesktopShell.eraserKind(tool.kind) ?: return"))
        assertTrue(main.contains("fun eraserKind(kind: ToolKind): ToolKind? = kind as? ToolKind.Brush"))
    }

    @Test
    fun `an RMW tool never paints the brush colour`() {
        val begin = between(
            main(),
            "override fun onStrokeBegin(pointerId: Int, source: StrokeSource)",
            "override fun onStrokeSample(",
        )

        assertTrue(begin.contains("if (kind is ToolKind.Brush)"))
        assertTrue(begin.contains("StrokeMode.PAINT"))
        assertTrue(begin.contains("RmwStrokePolicy.spec(kind, tool.mixer)"))
    }

    @Test
    fun `the fill runs once per gesture and is abandoned on cancel`() {
        val main = main()

        assertTrue(main.contains("fillPending = false"))
        assertTrue(main.contains("engine.startFill(x, y, params, tool.colorArgb)"))
        assertTrue(main.contains("engine.cancelFill()"))
    }

    @Test
    fun `the fill scan runs off the GL thread and re-reads the active layer`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")
        val fill = between(engine, "fun startFill(", "private fun finishFill(")

        // A 4096-square scan is seconds; the GL thread must keep presenting.
        assertTrue(fill.contains("fillExecutor.execute"))
        // Matched loosely: an exact literal stops biting the moment anyone
        // reformats the call, and an assertion that cannot fail is worse
        // than no assertion, because it reads as cover.
        assertFalse(
            // `[^)]*` stops at the first `)`, so any earlier argument
            // carrying parentheses — `progress = { p -> report(p) }` — hides
            // the call this is looking for.
            Regex("""scan\.run\((?:[^()]|\([^()]*\))*isCancelled\s*=\s*\{\s*false\s*\}""")
                .containsMatchIn(fill),
            "the scan must not run inline with a cancellation that never fires",
        )

        val finish = between(engine, "private fun finishFill(", "fun cancelFill()")
        // The layer panel is its own window; the selection can move mid-scan.
        assertTrue(finish.contains("val active = stack.active"))
        assertTrue(finish.contains("PixelCommitKind.Fill"))
        // And re-reading it means re-checking it: the layer the scan was
        // authorized against is not necessarily the one being committed to,
        // so a lock acquired during the scan must still refuse the pixels.
        assertTrue(
            finish.contains("StrokeLayerDecision.REFUSE_LOCKED"),
            "a fill can land on a layer locked while its scan ran",
        )
    }

    @Test
    fun `a fill is journaled by the same commit path a stroke is`() {
        val engine = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/DesktopEngine.kt")

        // Both pixel commits — the stroke merge and the fill upload — go
        // through the one helper, so there is a single place that can forget
        // the readback drain or the journal entry.
        assertEquals(2, Regex("""commitMerged\(renderer\)""").findAll(engine).count())
        assertEquals(1, Regex("""ReadbackDelivery\.Complete""").findAll(engine).count())
    }

    private fun tunableTools(): List<ToolKind> = listOf(
        ToolKind.Brush(paint),
        ToolKind.Smudge(SmudgeParams()),
        ToolKind.Water(WaterParams()),
        ToolKind.Blur(BlurParams()),
    )

    private fun main(): String =
        source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, startIndex = from + 1)
        check(from >= 0 && to > from) { "markers not found or misordered: $start .. $end" }
        return source.substring(from, to)
    }

    private fun source(path: String): String =
        java.io.File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): java.io.File {
        var candidate = java.io.File(".").canonicalFile
        while (!java.io.File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }

    private companion object {
        const val SECOND_VALUE = 0.375f
        const val HUGE_SIZE = 100_000f
    }
}
