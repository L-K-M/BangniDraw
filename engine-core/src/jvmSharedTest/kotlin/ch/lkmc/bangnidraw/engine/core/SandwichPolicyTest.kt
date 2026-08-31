package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.SandwichPolicy.Op
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy.Stale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/plan/05-layers.md` §8's table, row by row.
 *
 * The table is normative, so this is a transcription of it rather than a
 * derivation — which is the point. A half left valid when it should be stale
 * shows a *correct-looking* canvas built from the wrong layers, with no error
 * anywhere; only a screenshot comparison would catch it on a device, and CI
 * has no device.
 */
class SandwichPolicyTest {

    /** The active layer sits in the middle, so both sides exist. */
    private val a = 3

    private fun stale(op: Op) = SandwichPolicy.stale(op, activeIndex = a)

    @Test
    fun `tracing reference invalidates only below`() {
        assertEquals(SandwichPolicy.Stale.BELOW, stale(Op.TracingReference))
    }

    @Test
    fun `select stales both sides, because both memberships are relative to the active layer`() {
        assertEquals(Stale.BOTH, stale(Op.Select(0)))
        assertEquals(Stale.BOTH, stale(Op.Select(6)))
        // Re-selecting the layer that is already active changes nothing, and
        // the layer panel emits this on any tap of the current row.
        assertEquals(Stale.NEITHER, stale(Op.Select(a)))
    }

    @Test
    fun `a compositing property stales only the side holding that layer`() {
        assertEquals(Stale.BELOW, stale(Op.SetCompositingProperty(0)))
        assertEquals(Stale.BELOW, stale(Op.SetCompositingProperty(a - 1)))
        assertEquals(Stale.ABOVE, stale(Op.SetCompositingProperty(a + 1)))
        assertEquals(Stale.ABOVE, stale(Op.SetCompositingProperty(9)))
        // The active layer is composited live BETWEEN the halves, so its own
        // opacity and mode are uniforms of that pass — dragging the opacity
        // slider on the layer you are painting must not rebuild anything.
        assertEquals(Stale.NEITHER, stale(Op.SetCompositingProperty(a)))
    }

    @Test
    fun `properties no composite reads stale nothing`() {
        assertEquals(Stale.NEITHER, stale(Op.SetInertProperty))
    }

    @Test
    fun `moving the active layer stales both halves, because crossed layers swap sides`() {
        // The rule this replaced — "only the side it crossed into" — was
        // transcribed from `05-layers.md` §8 and was wrong in the plan itself.
        // It tracks the moved layer, which is in neither half; every layer it
        // CROSSES leaves one half and joins the other. With a = 3, moving the
        // active layer to 0 empties `below` and adds three layers to `above`.
        // Staling only `below` leaves those three in neither the reused half
        // nor the live pass — they vanish from the canvas.
        assertEquals(Stale.BOTH, stale(Op.Move(from = a, to = 0)))
        assertEquals(Stale.BOTH, stale(Op.Move(from = a, to = 7)))
        // Adjacent counts: one layer still crosses.
        assertEquals(Stale.BOTH, stale(Op.Move(from = a, to = a - 1)))
        assertEquals(Stale.BOTH, stale(Op.Move(from = a, to = a + 1)))
        // Only a move that moves nothing is free.
        assertEquals(Stale.NEITHER, stale(Op.Move(from = a, to = a)))
    }

    @Test
    fun `moving any other layer stales both, because the moved layer becomes active`() {
        // §3.1: the moved layer becomes active, so `a` changes — and any
        // change of `a` redefines both halves' membership.
        assertEquals(Stale.BOTH, stale(Op.Move(from = 0, to = 5)))
        assertEquals(Stale.BOTH, stale(Op.Move(from = 6, to = 1)))
    }

    @Test
    fun `add stales only below, which is why it is the cheap structural op`() {
        // The general principle would say both — `a` changes. `add` is
        // special-cased because the new layer is empty and sits directly on
        // the old active, so `above` is provably unchanged. It is also the
        // most frequent structural operation, which is why the exception earns
        // its keep.
        assertEquals(Stale.BELOW, stale(Op.Add))
    }

    @Test
    fun `duplicate follows add for the active layer and the general rule otherwise`() {
        assertEquals(Stale.BELOW, stale(Op.Duplicate(a)))
        assertEquals(Stale.BOTH, stale(Op.Duplicate(0)))
        assertEquals(Stale.BOTH, stale(Op.Duplicate(a + 2)))
    }

    @Test
    fun `delete stales both only when it removes the active layer`() {
        assertEquals(Stale.BOTH, stale(Op.Delete(a)))
        assertEquals(Stale.BELOW, stale(Op.Delete(1)))
        assertEquals(Stale.ABOVE, stale(Op.Delete(a + 1)))
    }

    @Test
    fun `mergeDown distinguishes all three cases`() {
        // The bottom absorbs the active layer; the merged layer is the new
        // active and `above` never changed.
        assertEquals(Stale.BELOW, stale(Op.MergeDown(a)))
        // Merging the layer above INTO the active one: the active layer's
        // pixels change (read live) and the above set shrinks by one.
        assertEquals(Stale.ABOVE, stale(Op.MergeDown(a + 1)))
        // Anywhere else the merged layer becomes active, so `a` changes.
        assertEquals(Stale.BOTH, stale(Op.MergeDown(1)))
        assertEquals(Stale.BOTH, stale(Op.MergeDown(a + 3)))
    }

    @Test
    fun `clear behaves like a pixel edit`() {
        assertEquals(Stale.NEITHER, stale(Op.Clear(a)))
        assertEquals(Stale.BELOW, stale(Op.Clear(0)))
        assertEquals(Stale.ABOVE, stale(Op.Clear(a + 1)))
    }

    @Test
    fun `flatten and undo stale both`() {
        assertEquals(Stale.BOTH, stale(Op.Flatten))
        // An undo entry can be anything, and undo is not a per-frame event, so
        // two stale flags buy correctness for the price of one rebuild of the
        // visible tiles.
        assertEquals(Stale.BOTH, stale(Op.UndoRedo))
    }

    @Test
    fun `the paper colour stales below because the paper is baked into it`() {
        // 03 §4: a non-normal bottom layer over a TRANSPARENT backdrop
        // degenerates to source-over, so a paper-less Below drawn over the
        // paper would render layer 0's Multiply as Normal and diverge from
        // both the direct composite and from flatten.
        assertEquals(Stale.BELOW, stale(Op.PaperColor))
    }

    @Test
    fun `a stroke commit stales nothing, which is the whole point of the sandwich`() {
        // The live path's cost must not grow with the layer count, and a
        // stroke is the most frequent event there is. The stroke buffer merges
        // into the active layer only, which the live pass reads directly.
        assertEquals(Stale.NEITHER, stale(Op.StrokeCommit))
        assertEquals(Stale.NEITHER, stale(Op.PixelEdit(a)))
        assertEquals(Stale.BELOW, stale(Op.PixelEdit(0)))
        assertEquals(Stale.ABOVE, stale(Op.PixelEdit(a + 1)))
    }

    @Test
    fun `every operation is covered, so a new one cannot default to stale-nothing`() {
        // The guard on this file rather than on the policy. `stale` is an
        // exhaustive `when`, so a new Op fails to compile there — but a new Op
        // added WITH a hasty `-> Stale.NEITHER` arm would compile and would be
        // tested by nothing. This fails until it is listed here.
        val covered = listOf(
            Op.Select(0), Op.SetCompositingProperty(0), Op.SetInertProperty,
            Op.Move(0, 1), Op.Add, Op.Duplicate(0), Op.Delete(0), Op.MergeDown(1),
            Op.Clear(0), Op.Flatten, Op.PaperColor, Op.TracingReference, Op.UndoRedo,
            Op.StrokeCommit,
            Op.PixelEdit(0),
        )
        val kinds = covered.map { it::class.simpleName }.toSet()
        // Derived from the sealed hierarchy, not hardcoded. A literal count is
        // exactly the vacuous guard this test exists to prevent: a fifteenth Op
        // with a hasty `-> Stale.NEITHER` arm would leave the literal correct
        // and the assertion green.
        //
        // `permittedSubclasses` rather than Kotlin's `sealedSubclasses`, which
        // needs kotlin-reflect at runtime — a dependency this module does not
        // carry and would not be worth adding for one assertion. A Kotlin
        // sealed interface compiles to a JVM sealed interface, so the JDK's own
        // list is the same list.
        assertEquals(
            Op::class.java.permittedSubclasses.size,
            kinds.size,
            "an Op kind is missing from `covered` (or listed twice): $kinds",
        )
        // Every one of them must be a decision this test actually made, i.e.
        // the policy must answer without throwing for all of them.
        for (op in covered) SandwichPolicy.stale(op, activeIndex = a)
    }

    @Test
    fun `above is cacheable only when every visible layer over the active one is normal`() {
        // Source-over is associative, so `above OVER (active BLEND below)` is
        // exact. Multiply and friends are not associative with respect to the
        // backdrop, so one non-normal layer above makes the cache WRONG rather
        // than merely stale — those layers get K individual passes instead.
        assertTrue(SandwichPolicy.aboveIsCacheable(emptyList()))
        assertTrue(SandwichPolicy.aboveIsCacheable(listOf(layer(BlendMode.NORMAL))))
        assertFalse(
            SandwichPolicy.aboveIsCacheable(
                listOf(layer(BlendMode.NORMAL), layer(BlendMode.MULTIPLY)),
            ),
        )
        // Every non-normal mode disqualifies it, not just Multiply.
        for (mode in BlendMode.entries) {
            val cacheable = SandwichPolicy.aboveIsCacheable(listOf(layer(mode)))
            assertEquals(mode == BlendMode.NORMAL, cacheable, "$mode")
        }
    }

    @Test
    fun `a hidden non-normal layer above does not disqualify the cache`() {
        // An invisible layer contributes nothing to the composite, so it
        // cannot break associativity. Hiding the one Multiply layer above you
        // restores the fast path — which is what a user would expect if they
        // thought about it, and the alternative is paying K passes for a layer
        // that draws nothing.
        assertTrue(
            SandwichPolicy.aboveIsCacheable(listOf(layer(BlendMode.MULTIPLY, visible = false))),
        )
        assertFalse(
            SandwichPolicy.aboveIsCacheable(
                listOf(layer(BlendMode.MULTIPLY, visible = false), layer(BlendMode.SCREEN)),
            ),
        )
    }

    @Test
    fun `below is cacheable for every blend mode once the backdrop pass exists`() {
        for (mode in BlendMode.entries) {
            assertTrue(
                SandwichPolicy.belowIsCacheable(listOf(layer(mode))),
                "$mode must be composited against the partial below cache",
            )
        }
    }

    private var nextId = 0

    private fun layer(mode: BlendMode, visible: Boolean = true): Layer = Layer(
        LayerProps(
            id = LayerId("layer-${nextId++}"),
            name = "L",
            visible = visible,
            blendMode = mode,
        ),
    )
}
