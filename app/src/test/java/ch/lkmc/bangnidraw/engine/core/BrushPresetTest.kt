package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.15, against `04-tools.md` §2 and §5.
 *
 * The half of §3.15 that reads preset *files* under `assets/brushes/` lands
 * with `BrushPresetStore`; there are no files to read yet. What is here is
 * the model those files decode into: its ranges, its forward compatibility,
 * and the dynamics the shipped preset promises.
 */
class BrushPresetTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun preset(id: String = "test.brush") = BrushPreset(id = id, name = "Test")

    @Test
    fun `a preset round-trips through JSON`() {
        val original = BrushPresets.INK_PEN
        val decoded = json.decodeFromString<BrushPreset>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an unknown field is ignored and a missing one takes the default`() {
        // Forward compatibility (`04` §5.1): an old build must open a preset
        // written by a new one, and a hand-edited file may omit anything it
        // does not care about.
        val decoded = json.decodeFromString<BrushPreset>(
            """{"id":"user.mine","name":"Mine","size":20.0,"sparkleAmount":0.9}""",
        )
        assertEquals("user.mine", decoded.id)
        assertEquals(20f, decoded.size)
        assertEquals(preset().opacity, decoded.opacity, "opacity took its default")
        assertEquals(TipShape.Round, decoded.tip, "tip took its default")
        assertEquals(BufferMode.Max, decoded.bufferMode, "bufferMode took its default")
    }

    @Test
    fun `a preset whose values are out of range is refused at construction`() {
        // The sizes bound the dab radius, which bounds the dirty rect, which
        // sizes the quad the shader draws. A NaN here becomes a dab that
        // silently paints nothing, far from whoever wrote the number.
        assertFailsWith<IllegalArgumentException> { preset().copy(size = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { preset().copy(size = -1f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(sizeMin = 40f, sizeMax = 10f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(size = 1000f, sizeMax = 400f) }
        assertFailsWith<IllegalArgumentException> {
            preset().copy(sizeMax = BrushPreset.MAX_SIZE + 1f)
        }
        assertFailsWith<IllegalArgumentException> { preset().copy(opacity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(flow = -0.1f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(hardness = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { preset().copy(stabilizer = 2f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(dilution = -1f) }
        assertFailsWith<IllegalArgumentException> { preset(id = "  ") }
        assertFailsWith<IllegalArgumentException> { preset().copy(name = "") }
        assertFailsWith<IllegalArgumentException> { preset().copy(grain = "  ") }
    }

    @Test
    fun `spacing of zero is refused, because it would emit dabs forever`() {
        // The generator also floors the step at half a pixel, but that floor
        // is a resolution limit, not a licence for a preset to ask for zero.
        assertFailsWith<IllegalArgumentException> { preset().copy(spacing = 0f) }
        assertFailsWith<IllegalArgumentException> { preset().copy(spacing = -0.5f) }
        assertFailsWith<IllegalArgumentException> {
            preset().copy(spacing = BrushPreset.MAX_SPACING + 1f)
        }
    }

    @Test
    fun `a malformed preset from disk throws rather than decoding to nonsense`() {
        // A hand-edited file is the realistic source of these. The loader that
        // catches this and drops the preset lands with `BrushPresetStore`;
        // what matters here is that the model refuses rather than producing a
        // brush with a negative radius.
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<BrushPreset>("""{"id":"bad","name":"Bad","spacing":0.0}""")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<BrushPreset>("""{"id":"bad","name":"Bad","opacity":4.0}""")
        }
    }

    @Test
    fun `the tip shapes and their bounds`() {
        assertEquals(0.3f, TipShape.Flat(0.3f).aspect)
        assertFailsWith<IllegalArgumentException> { TipShape.Flat(0f) }
        assertFailsWith<IllegalArgumentException> { TipShape.Flat(1.5f) }
        assertFailsWith<IllegalArgumentException> { TipShape.Flat(Float.NaN) }
        // A flat tip round-trips as a tagged arm, not as a bare float, so a
        // future third shape cannot be mistaken for it.
        val decoded = json.decodeFromString<BrushPreset>(
            """{"id":"t","name":"T","tip":{"type":"flat","aspect":0.4}}""",
        )
        assertEquals(TipShape.Flat(0.4f), decoded.tip)
    }

    @Test
    fun `effects refuse values that would invert or divide by zero`() {
        assertFailsWith<IllegalArgumentException> { TiltEffect(sizeAtFlat = 0f) }
        assertFailsWith<IllegalArgumentException> { TiltEffect(sizeAtFlat = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TiltEffect(opacityAtFlat = -1f) }
        // fastPxPerMs is divided by to normalize speed into 0..1.
        assertFailsWith<IllegalArgumentException> { VelocityEffect(fastPxPerMs = 0f) }
        assertFailsWith<IllegalArgumentException> { VelocityEffect(fastPxPerMs = -2f) }
        assertFailsWith<IllegalArgumentException> { Jitter(size = 2f) }
        assertFailsWith<IllegalArgumentException> { Jitter(position = -0.1f) }
    }

    @Test
    fun `the slider setters clamp into the preset's own range`() {
        // The rail sliders write into the session copy; a slider that could
        // leave the range would let the UI build a preset the model refuses.
        val ink = BrushPresets.INK_PEN
        assertEquals(ink.sizeMax, ink.withSize(10_000f).size)
        assertEquals(ink.sizeMin, ink.withSize(-5f).size)
        assertEquals(ink.size, ink.withSize(Float.NaN).size, "a NaN slider must not move the size")
        assertEquals(1f, ink.withOpacity(3f).opacity)
        assertEquals(0f, ink.withOpacity(-1f).opacity)
        assertEquals(ink.opacity, ink.withOpacity(Float.NaN).opacity)
    }

    @Test
    fun `baseRadius is half the size`() {
        assertEquals(3f, BrushPresets.INK_PEN.baseRadius)
    }

    @Test
    fun `the ink pen matches its row of the section 5 table`() {
        // §5's table *is* the spec ("every preset matches its §6 description
        // on device"). Pinning it here means a mistyped value fails in CI
        // rather than on someone's tablet.
        val ink = BrushPresets.INK_PEN
        assertEquals("builtin.ink_pen", ink.id)
        assertEquals(6f, ink.size)
        assertEquals(1f, ink.sizeMin)
        assertEquals(60f, ink.sizeMax)
        assertEquals(1f, ink.opacity)
        assertEquals(1f, ink.flow)
        assertEquals(1f, ink.hardness)
        assertEquals(0.10f, ink.spacing)
        assertEquals(TipShape.Round, ink.tip)
        assertEquals(TipOrientation.Fixed, ink.orientation)
        assertEquals(0.7f, ink.stabilizer)
        assertEquals(Curve(0.15f, 0.3f, 0.6f, 1f), ink.pressureSize)
        assertEquals(Curve.One, ink.pressureOpacity)
        assertEquals(Curve.One, ink.pressureFlow)
        assertEquals(TiltEffect.None, ink.tilt)
        assertEquals(VelocityEffect(sizeAtFast = 0.85f, fastPxPerMs = 2f), ink.velocity)
        assertEquals(Jitter.None, ink.jitter)
        assertEquals(BufferMode.Max, ink.bufferMode)
        assertTrue(!ink.mixing)
        assertTrue(!ink.eraseMode)
    }

    @Test
    fun `the ink pen exposes the dynamics PLAN promises`() {
        // §5's prose, as assertions: pressure goes into size (never into
        // opacity), a hair line is always available, the stabilizer is strong
        // because a pen line has nowhere to hide a wobble, and `Max` buffering
        // with opacity 1 is what makes overlaps invisible.
        val ink = BrushPresets.INK_PEN
        assertTrue(
            ink.pressureSize.eval(1f) > ink.pressureSize.eval(0f),
            "pressure must widen the ink pen",
        )
        assertTrue(
            ink.pressureSize.eval(0f) >= 0.15f,
            "a hair line must always be available: the size curve starts at 15 %",
        )
        assertEquals(
            1f,
            ink.pressureOpacity.eval(0f),
            "the ink pen must not put pressure into opacity",
        )
        assertTrue(ink.stabilizer >= 0.5f, "a pen line needs a strong stabilizer")
        assertEquals(BufferMode.Max, ink.bufferMode, "ink does not build up within one stroke")
        assertTrue(
            ink.velocity.sizeAtFast < 1f,
            "a fast ink stroke must thin, which reads as confidence",
        )
    }

    @Test
    fun `an erase preset is a preset, not a kind`() {
        // `04` §1: an eraser shares the stabilizer, generator and stroke
        // buffer with every other brush and differs only at the merge. If it
        // ever became its own ToolKind, 90 % of the brush path would be
        // duplicated for one boolean.
        val eraser = BrushPresets.INK_PEN.copy(id = "builtin.hard_eraser", eraseMode = true)
        val kind = ToolKind.Brush(eraser)
        assertIs<ToolKind.Brush>(kind)
        assertTrue(kind.preset.eraseMode)
        val kinds = listOf(
            ToolKind.Brush(BrushPresets.INK_PEN),
            ToolKind.Smudge(),
            ToolKind.Blur(),
            ToolKind.Fill(),
            ToolKind.Eyedropper(),
        )
        assertEquals(5, kinds.size, "`04` §1 declares exactly five kinds")
    }

    @Test
    fun `every tool kind round-trips through JSON under its own tag`() {
        // The tag is what makes a saved session readable by a build that added
        // a sixth kind; a positional encoding would not be.
        val kinds = listOf<ToolKind>(
            ToolKind.Brush(BrushPresets.INK_PEN),
            ToolKind.Smudge(SmudgeParams(strength = 0.4f)),
            ToolKind.Blur(BlurParams(strength = 0.2f)),
            ToolKind.Fill(FillParams(tolerance = 0.3f, contiguous = false)),
            ToolKind.Eyedropper(EyedropperParams(source = SampleSource.CurrentLayer, radius = 1)),
        )
        for (kind in kinds) {
            assertEquals(kind, json.decodeFromString<ToolKind>(json.encodeToString(kind)), "$kind")
        }
    }

    @Test
    fun `the non-brush parameter sets validate their own ranges`() {
        assertFailsWith<IllegalArgumentException> { SmudgeParams(strength = 2f) }
        assertFailsWith<IllegalArgumentException> { SmudgeParams(pickupRate = -1f) }
        assertFailsWith<IllegalArgumentException> { SmudgeParams(spacing = 0f) }
        assertFailsWith<IllegalArgumentException> { SmudgeParams(size = 1000f) }
        assertFailsWith<IllegalArgumentException> { BlurParams(radiusFraction = 1.5f) }
        assertFailsWith<IllegalArgumentException> { FillParams(tolerance = 2f) }
        assertFailsWith<IllegalArgumentException> { FillParams(expand = -1) }
        assertFailsWith<IllegalArgumentException> { FillParams(expand = FillParams.MAX_EXPAND + 1) }
        assertFailsWith<IllegalArgumentException> { EyedropperParams(radius = -1) }
        assertFailsWith<IllegalArgumentException> {
            EyedropperParams(radius = EyedropperParams.MAX_RADIUS + 1)
        }
    }

    @Test
    fun `a resource-key name is stored verbatim, like a layer name`() {
        // `01-product.md` §8: `@string/…` resolves through resources at
        // display time and anything else is shown as typed, so a user who
        // renames a brush to "@string/app_name" sees that string.
        assertTrue(BrushPresets.INK_PEN.name.startsWith("@string/"))
        val renamed = BrushPresets.INK_PEN.copy(name = "@string/app_name")
        assertEquals("@string/app_name", renamed.name, "the model must not resolve anything")
    }

    @Test
    fun `every shipped preset is valid and uniquely identified`() {
        assertTrue(BrushPresets.ALL.isNotEmpty())
        assertEquals(
            BrushPresets.ALL.size,
            BrushPresets.ALL.distinctBy { it.id }.size,
            "a preset id was reissued",
        )
        for (p in BrushPresets.ALL) {
            assertTrue(p.id.startsWith("builtin."), "${p.id} is shipped but not a builtin id")
            assertTrue(p.size in p.sizeMin..p.sizeMax, "${p.id} opens outside its own range")
        }
        assertTrue(
            BrushPresets.DEFAULT in BrushPresets.ALL,
            "the default preset must be one this build ships",
        )
    }
}
