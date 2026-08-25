package ch.lkmc.bangnidraw.engine.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.7, against the operation table of `docs/plan/05-layers.md` §3.1. */
class LayerStackTest {

    /** Deterministic ids, so a failing property case can be replayed. */
    private class Ids(private var n: Int = 1000) : IdSource {
        override fun newId(): LayerId = LayerId("id-${n++}")
    }

    private val cap = 16

    private fun stackOf(vararg names: String, active: Int = 0): LayerStack =
        LayerStack(
            layers = names.mapIndexed { i, n -> Layer(LayerProps(LayerId("id-$i"), n)) },
            activeIndex = active,
            nextName = names.size + 1,
        )

    private fun ok(result: StackResult): StackEdit {
        assertIs<StackResult.Ok>(result, "expected the operation to be allowed")
        return result.edit
    }

    private fun refusal(result: StackResult): Refusal {
        assertIs<StackResult.Refused>(result, "expected the operation to be refused")
        return result.reason
    }

    // ---------------------------------------------------------------- structure

    @Test
    fun `add inserts above the active layer and makes it active`() {
        val stack = stackOf("bottom", "middle", "top", active = 1)
        val edit = ok(stack.add(Ids(), cap))
        assertEquals(4, edit.stack.size)
        assertEquals(2, edit.stack.activeIndex, "the new layer sits directly above the old active one")
        assertEquals("id-0", edit.stack.layers[0].id.value)
        assertEquals("id-1", edit.stack.layers[1].id.value)
        assertEquals("id-2", edit.stack.layers[3].id.value, "the old top is pushed up, not replaced")
        assertEquals(LayerStack.defaultName(4), edit.stack.active.props.name)
        assertEquals(5, edit.stack.nextName, "the default-name counter only grows")
        assertNull(edit.pixels, "an empty layer has no pixel work")
        val entry = assertIs<HistoryEntry.LayerAdd>(edit.entry)
        assertEquals(2, entry.index)
        assertEquals("id-1", entry.activeBefore)
        assertEquals(edit.stack.active.id.value, entry.activeAfter)
    }

    @Test
    fun `add is refused at the layer cap, never thrown`() {
        val names = Array(4) { "layer $it" }
        val stack = stackOf(*names)
        assertEquals(Refusal.AT_CAP, refusal(stack.add(Ids(), maxLayers = 4)))
        assertEquals(Refusal.AT_CAP, refusal(stack.duplicate(0, Ids(), maxLayers = 4)))
    }

    @Test
    fun `a stack cannot exceed the MemoryBudget layer cap`() {
        val budget = MemoryBudget.compute(
            DeviceMemory(4L shl 30, false, 512, 256, 4096),
            CanvasSize(4096, 4096),
        )
        assertEquals(7, budget.maxLayers, "the pinned value of docs/plan/10-performance.md §4")
        var stack = stackOf("layer 1")
        val ids = Ids()
        while (stack.size < budget.maxLayers) {
            stack = ok(stack.add(ids, budget.maxLayers)).stack
        }
        assertEquals(budget.maxLayers, stack.size)
        assertEquals(Refusal.AT_CAP, refusal(stack.add(ids, budget.maxLayers)))
    }

    @Test
    fun `delete of the only layer is refused`() {
        assertEquals(Refusal.LAST_LAYER, refusal(stackOf("only").delete(0)))
    }

    @Test
    fun `delete of a locked layer is refused`() {
        val stack = stackOf("a", "b")
        val locked = ok(stack.setLocked(1, true)).stack
        assertEquals(Refusal.LOCKED, refusal(locked.delete(1)))
    }

    @Test
    fun `delete makes the layer below active, or the new bottom`() {
        val stack = stackOf("bottom", "middle", "top", active = 1)
        ok(stack.delete(1)).stack.let {
            assertEquals(0, it.activeIndex, "deleting the active layer selects the one below")
            assertEquals("id-0", it.active.id.value)
        }
        ok(stackOf("bottom", "middle", "top", active = 0).delete(0)).stack.let {
            assertEquals(0, it.activeIndex, "deleting the bottom selects the new bottom")
            assertEquals("id-1", it.active.id.value)
        }
        ok(stack.delete(0)).stack.let {
            assertEquals("id-1", it.active.id.value, "deleting another layer keeps the selection")
            assertEquals(0, it.activeIndex, "even though its index shifted")
        }
    }

    @Test
    fun `delete journals the layer's tiles so undo can put them back`() {
        val tiles = setOf(TileKey(0, 0), TileKey(1, 0))
        val stack = LayerStack(
            layers = listOf(
                Layer(LayerProps(LayerId("a"), "a")),
                Layer(LayerProps(LayerId("b"), "b"), tiles),
            ),
            activeIndex = 1,
            nextName = 3,
        )
        val edit = ok(stack.delete(1))
        val entry = assertIs<HistoryEntry.LayerDelete>(edit.entry)
        assertEquals(tiles, entry.tiles.toSet())
        assertEquals(1, entry.index)
        assertEquals(PixelOp.Delete(LayerId("b")), edit.pixels)
    }

    @Test
    fun `moving a layer to its own index is a no-op and journals nothing`() {
        assertEquals(Refusal.NOOP, refusal(stackOf("a", "b", "c").move(1, 1)))
    }

    @Test
    fun `reorder is a permutation`() {
        val random = Random(42)
        // Chained, not repeated from one arrangement: a state-dependent
        // ordering bug only shows once the stack has been shuffled.
        var stack = stackOf("a", "b", "c", "d", "e")
        repeat(500) {
            val from = random.nextInt(stack.size)
            val to = random.nextInt(stack.size)
            if (from == to) return@repeat
            val before = stack
            val moved = ok(before.move(from, to)).stack
            assertEquals(
                before.layers.map { it.id.value }.sorted(),
                moved.layers.map { it.id.value }.sorted(),
                "move($from, $to) lost or duplicated a layer",
            )
            assertEquals(
                before.layers[from].id,
                moved.layers[to].id,
                "move($from, $to) did not land the layer at $to",
            )
            assertEquals(to, moved.activeIndex, "the moved layer follows the drag")
            stack = moved
        }
    }

    @Test
    fun `duplicate copies properties and tiles and places the copy above`() {
        val tiles = setOf(TileKey(2, 3))
        val source = Layer(
            LayerProps(LayerId("src"), "sketch", opacity = 0.4f, blendMode = BlendMode.MULTIPLY, locked = true),
            tiles,
        )
        val stack = LayerStack(listOf(source, Layer(LayerProps(LayerId("top"), "top"))), 0, 3)
        val edit = ok(stack.duplicate(0, Ids(), cap))
        val copy = edit.stack.layers[1]
        assertEquals(LayerStack.duplicateName("sketch"), copy.props.name)
        assertEquals(0.4f, copy.props.opacity)
        assertEquals(BlendMode.MULTIPLY, copy.props.blendMode)
        assertTrue(!copy.props.locked, "a copy is never born locked")
        assertEquals(tiles, copy.tiles)
        assertEquals(1, edit.stack.activeIndex)
        assertEquals(PixelOp.Copy(LayerId("src"), copy.id, tiles), edit.pixels)
        assertEquals("top", edit.stack.layers[2].props.name, "the layer above is pushed up")
        val entry = assertIs<HistoryEntry.LayerDuplicate>(edit.entry)
        assertEquals(1, entry.index, "undo removes the copy by this index")
        assertEquals("src", entry.sourceId, "redo re-copies from the source")
    }

    @Test
    fun `merge down keeps the lower layer's identity and drops the upper`() {
        val lowerTiles = setOf(TileKey(0, 0), TileKey(1, 1))
        val upperTiles = setOf(TileKey(1, 1), TileKey(2, 2))
        val stack = LayerStack(
            listOf(
                Layer(LayerProps(LayerId("lo"), "paint", opacity = 0.5f, blendMode = BlendMode.SCREEN), lowerTiles),
                Layer(LayerProps(LayerId("hi"), "lines", opacity = 0.7f, blendMode = BlendMode.MULTIPLY), upperTiles),
            ),
            activeIndex = 1,
            nextName = 3,
        )
        val edit = ok(stack.mergeDown(1))
        assertEquals(1, edit.stack.size)
        val merged = edit.stack.layers[0]
        assertEquals(LayerId("lo"), merged.id, "the merged layer keeps the lower layer's id")
        assertEquals("paint", merged.props.name)
        assertEquals(1f, merged.props.opacity, "the result is reset to Normal at 100 %")
        assertEquals(BlendMode.NORMAL, merged.props.blendMode)
        assertEquals(lowerTiles + upperTiles, merged.tiles)
        assertEquals(0, edit.stack.activeIndex)

        val entry = assertIs<HistoryEntry.LayerMerge>(edit.entry)
        assertEquals(BlendMode.SCREEN.name, entry.lower.blend, "undo needs the lower layer's mode from before")
        assertEquals(0.5f, entry.lower.opacity)
        assertEquals(upperTiles, entry.upperTiles.toSet())
        assertEquals(
            lowerTiles,
            entry.lowerTiles.toSet(),
            "the bottom layer is at 50 %, so every one of its tiles is rewritten and must be undoable",
        )
    }

    @Test
    fun `merge down rewrites every bottom tile whose look depended on the bottom's opacity`() {
        // The merged layer is Normal at 100 %, so a bottom layer at 50 % has
        // to have that opacity baked into ALL of its tiles — including the
        // ones the top never covers, which would otherwise jump from half to
        // fully opaque. This is what makes 05-layers.md §4.1's "a normal
        // bottom at *any* opacity merges exactly" true.
        val bottomOnly = TileKey(0, 0)
        val shared = TileKey(1, 1)
        val topOnly = TileKey(2, 2)
        fun stack(bottomOpacity: Float) = LayerStack(
            listOf(
                Layer(LayerProps(LayerId("lo"), "lo", opacity = bottomOpacity), setOf(bottomOnly, shared)),
                Layer(LayerProps(LayerId("hi"), "hi"), setOf(shared, topOnly)),
            ),
            activeIndex = 1,
            nextName = 3,
        )
        val faded = assertIs<PixelOp.Merge>(ok(stack(0.5f).mergeDown(1)).pixels)
        assertEquals(
            setOf(bottomOnly, shared, topOnly),
            faded.keys,
            "a bottom-only tile at 50 % must be re-composited, not left as it was",
        )
        assertEquals(0.5f, faded.bottomProps.opacity, "the op carries the pre-merge props the pixels need")

        val opaque = assertIs<PixelOp.Merge>(ok(stack(1f).mergeDown(1)).pixels)
        assertEquals(
            setOf(shared, topOnly),
            opaque.keys,
            "the upper layer's own tiles must be written into the merged layer; " +
                "at 100 % only the bottom-only tile is left alone",
        )
    }

    @Test
    fun `a bottom layer's blend mode alone never forces a rewrite of its untouched tiles`() {
        // Over transparent every blend mode reduces to source-over (pinned by
        // CompositeTest), and a bottom-only tile is composited over
        // transparent — so a non-NORMAL bottom at 100 % leaves those tiles
        // pixel-identical and the merge need not touch them.
        val bottomOnly = TileKey(0, 0)
        val shared = TileKey(1, 1)
        val topOnly = TileKey(2, 2)
        for (mode in BlendMode.entries) {
            val stack = LayerStack(
                listOf(
                    Layer(LayerProps(LayerId("lo"), "lo", blendMode = mode), setOf(bottomOnly, shared)),
                    Layer(LayerProps(LayerId("hi"), "hi"), setOf(shared, topOnly)),
                ),
                activeIndex = 1,
                nextName = 3,
            )
            val pixels = assertIs<PixelOp.Merge>(ok(stack.mergeDown(1)).pixels)
            assertEquals(
                setOf(shared, topOnly),
                pixels.keys,
                "$mode at 100 % should rewrite the upper layer's tiles and no more",
            )
            assertEquals(
                mode,
                pixels.bottomProps.blendMode,
                "the op must carry the pre-merge mode, not the reset one",
            )
        }
    }

    @Test
    fun `an opacity outside 0 to 1 is refused at construction, never quietly repaired`() {
        for (bad in listOf(-1f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("opacity $bad must be refused") {
                LayerProps(LayerId("a"), "a", opacity = bad)
            }
        }
        // The setter clamps and the deserialization boundary clamps; only
        // direct construction refuses, because a corrupt file must degrade.
        assertEquals(1f, LayerProps(LayerId("a"), "a").withOpacity(4f).opacity)
        assertEquals(0f, LayerProps(LayerId("a"), "a").withOpacity(-4f).opacity)
        assertEquals(1f, LayerRecord(id = "a", name = "a", opacity = 9f).toProps().opacity)
    }

    @Test
    fun `a corrupt opacity degrades at the boundaries instead of failing the open`() {
        // coerceIn is not enough on its own: both of its comparisons are false
        // for NaN, so it returns NaN unchanged and construction would then
        // refuse it — one corrupt field failing a whole document open, which
        // 06-document-and-persistence.md §4 forbids. kotlinx.serialization does
        // parse a bare NaN token into a Float, so this is reachable from a
        // hand-edited project.json.
        assertEquals(
            1f,
            LayerRecord(id = "a", name = "a", opacity = Float.NaN).toProps().opacity,
            "a NaN opacity must degrade to fully visible, not throw",
        )
        assertEquals(1f, LayerRecord(id = "a", name = "a", opacity = Float.POSITIVE_INFINITY).toProps().opacity)
        assertEquals(
            1f,
            LayerRecord(id = "a", name = "a", opacity = Float.NEGATIVE_INFINITY).toProps().opacity,
            "every non-finite opacity degrades alike: -inf is corruption, not a slider underflow",
        )
        // A merely out-of-range finite value still clamps the ordinary way.
        assertEquals(0f, LayerRecord(id = "a", name = "a", opacity = -0.001f).toProps().opacity)

        // The clamping setter is total too — `copy` re-runs `init`, so a NaN
        // arriving from a slider binding or an animation would otherwise crash.
        val props = LayerProps(LayerId("a"), "a")
        assertEquals(1f, props.withOpacity(Float.NaN).opacity)
        assertEquals(1f, props.withOpacity(Float.POSITIVE_INFINITY).opacity)
        assertEquals(1f, props.withOpacity(Float.NEGATIVE_INFINITY).opacity)
        assertEquals(0f, props.withOpacity(-0.001f).opacity, "a finite underflow still means 0")
    }

    @Test
    fun `merge down is refused without a layer below, when locked, or when either partner is hidden`() {
        val stack = stackOf("a", "b", "c")
        assertEquals(Refusal.NO_LAYER_BELOW, refusal(stack.mergeDown(0)))
        assertEquals(Refusal.LOCKED, refusal(ok(stack.setLocked(0, true)).stack.mergeDown(1)))
        assertEquals(Refusal.LOCKED, refusal(ok(stack.setLocked(1, true)).stack.mergeDown(1)))
        assertEquals(Refusal.HIDDEN_PARTNER, refusal(ok(stack.setVisible(0, false)).stack.mergeDown(1)))
        assertEquals(Refusal.HIDDEN_PARTNER, refusal(ok(stack.setVisible(1, false)).stack.mergeDown(1)))
    }

    @Test
    fun `flatten leaves exactly one layer holding every visible layer's tiles`() {
        val stack = LayerStack(
            listOf(
                Layer(LayerProps(LayerId("a"), "a"), setOf(TileKey(0, 0))),
                Layer(LayerProps(LayerId("b"), "b", visible = false), setOf(TileKey(1, 1))),
                Layer(LayerProps(LayerId("c"), "c"), setOf(TileKey(2, 2))),
            ),
            activeIndex = 2,
            nextName = 4,
        )
        val edit = ok(stack.flatten(Ids()))
        assertEquals(1, edit.stack.size)
        assertEquals(setOf(TileKey(0, 0), TileKey(2, 2)), edit.stack.layers[0].tiles, "hidden layers are dropped")
        assertEquals(LayerStack.FLATTENED_NAME, edit.stack.layers[0].props.name)
        assertEquals(0, edit.stack.activeIndex)
        val pixels = assertIs<PixelOp.Flatten>(edit.pixels)
        assertEquals(listOf(LayerId("a"), LayerId("c")), pixels.order.map { it.id })
        val entry = assertIs<HistoryEntry.Flatten>(edit.entry)
        assertEquals(3, entry.layers.size, "undo must restore the hidden layer too")
        assertEquals(listOf(TileKey(1, 1)), entry.tilesPerLayer.getValue("b"))
    }

    @Test
    fun `flatten with every layer hidden is refused rather than wiping the document`() {
        val stack = LayerStack(
            listOf(
                Layer(LayerProps(LayerId("a"), "a", visible = false), setOf(TileKey(0, 0))),
                Layer(LayerProps(LayerId("b"), "b", visible = false), setOf(TileKey(1, 1))),
            ),
            activeIndex = 0,
            nextName = 3,
        )
        assertEquals(
            Refusal.NOOP,
            refusal(stack.flatten(Ids())),
            "flattening nothing visible would destroy both layers for a blank result",
        )
    }

    @Test
    fun `flatten of a single layer is a no-op and a locked layer refuses it`() {
        assertEquals(Refusal.NOOP, refusal(stackOf("only").flatten(Ids())))
        val stack = stackOf("a", "b")
        assertEquals(Refusal.LOCKED, refusal(ok(stack.setLocked(1, true)).stack.flatten(Ids())))
    }

    @Test
    fun `clear keeps the layer and its selection but frees its tiles`() {
        val stack = LayerStack(
            listOf(Layer(LayerProps(LayerId("a"), "a"), setOf(TileKey(0, 0), TileKey(1, 0)))),
            0,
            2,
        )
        val edit = ok(stack.clear(0))
        assertEquals(1, edit.stack.size)
        assertTrue(edit.stack.layers[0].tiles.isEmpty())
        assertEquals("a", edit.stack.layers[0].props.name)
        assertEquals(PixelOp.Clear(LayerId("a")), edit.pixels)
        assertEquals(Refusal.NOOP, refusal(edit.stack.clear(0)), "clearing an empty layer does nothing")
    }

    // --------------------------------------------------------------- properties

    @Test
    fun `alpha lock, visibility, opacity, blend, rename and lock edits each journal their inverse`() {
        val stack = stackOf("a", "b")
        val edits = listOf(
            stack.setAlphaLock(1, true),
            stack.setVisible(1, false),
            stack.setOpacity(1, 0.25f),
            stack.setBlendMode(1, BlendMode.OVERLAY),
            stack.rename(1, "ink"),
            stack.setLocked(1, true),
        )
        for (result in edits) {
            val edit = ok(result)
            val entry = assertIs<HistoryEntry.LayerProps>(edit.entry)
            assertEquals("id-1", entry.layerId)
            assertEquals(stack.layers[1].props.toRecord(), entry.before, "the entry must carry the props from before")
            assertEquals(edit.stack.layers[1].props.toRecord(), entry.after)
            assertNull(edit.pixels, "a property edit moves no pixels")
            assertEquals(stack.activeIndex, edit.stack.activeIndex, "a property edit never moves the selection")
        }
    }

    @Test
    fun `setting a property to its current value is a no-op`() {
        val stack = stackOf("a", "b")
        assertEquals(Refusal.NOOP, refusal(stack.setVisible(1, true)))
        assertEquals(Refusal.NOOP, refusal(stack.setOpacity(1, 1f)))
        assertEquals(Refusal.NOOP, refusal(stack.setBlendMode(1, BlendMode.NORMAL)))
        assertEquals(Refusal.NOOP, refusal(stack.rename(1, "b")))
        assertEquals(Refusal.NOOP, refusal(stack.setAlphaLock(1, false)))
        assertEquals(Refusal.NOOP, refusal(stack.setLocked(1, false)))
    }

    @Test
    fun `opacity is clamped into range rather than stored as typed`() {
        val stack = stackOf("a")
        assertEquals(0f, ok(stack.setOpacity(0, -3f)).stack.layers[0].props.opacity)
        assertEquals(Refusal.NOOP, refusal(stack.setOpacity(0, 4f)), "clamping to the current value changes nothing")
    }

    @Test
    fun `a layer id that is not a single path segment is refused`() {
        // The id becomes a directory name, and it arrives from project.json —
        // a file that can be hand-edited or carried between devices.
        for (bad in listOf("", ".", "..", "../../evil", "a/b", "a\\b", "C:x", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>("id \"$bad\" must be refused") { LayerId(bad) }
        }
        LayerId("0f9a3c2e-1b4d-4a7e-9c31-2f8b6d5a0e17")
        LayerId("id-0")

        // The throw is the guard; toPropsOrNull is how the loader degrades,
        // so one corrupt record is a skipped layer and not a failed open.
        assertFailsWith<IllegalArgumentException> {
            LayerRecord(id = "../../evil", name = "x").toProps()
        }
        assertEquals(null, LayerRecord(id = "../../evil", name = "x").toPropsOrNull())
        assertEquals("id-0", LayerRecord(id = "id-0", name = "x").toPropsOrNull()?.id?.value)
    }

    @Test
    fun `selection is not an edit`() {
        val stack = stackOf("a", "b", "c")
        assertEquals(2, stack.select(2).activeIndex)
        assertEquals(stack, stack.select(0), "selecting the current layer changes nothing")
        assertEquals(stack, stack.select(9), "an out-of-range selection is ignored")
    }

    @Test
    fun `a property edit on a locked layer is still allowed - lock protects pixels, not arrangement`() {
        val locked = ok(stackOf("a", "b").setLocked(1, true)).stack
        ok(locked.rename(1, "ink"))
        ok(locked.setOpacity(1, 0.5f))
        ok(locked.setVisible(1, false))
        ok(locked.move(1, 0))
    }

    // ----------------------------------------------------------------- property

    @Test
    fun `every op's inverse applied to its result restores the original stack`() {
        val random = Random(42)
        val ids = Ids(100)
        var stack = seededStack()
        repeat(2000) {
            val before = stack
            val result = randomOperation(before, random, ids)
            if (result is StackResult.Refused) return@repeat
            val edit = (result as StackResult.Ok).edit
            val restored = undo(edit.stack, edit.entry)
            assertEquals(
                before.layers,
                restored.layers,
                "undoing ${edit.entry::class.simpleName} did not restore the layers",
            )
            assertEquals(
                before.activeIndex,
                restored.activeIndex,
                "undoing ${edit.entry::class.simpleName} did not restore the selection",
            )
            assertTrue(
                edit.stack.nextName >= before.nextName,
                "the default-name counter must only grow",
            )
            assertTrue(
                restored.nextName >= before.nextName,
                "undo must not roll the default-name counter back, or a later add reissues a name",
            )
            stack = edit.stack
        }
    }

    @Test
    fun `every stack keeps its invariants under random operations`() {
        val random = Random(7)
        val ids = Ids(200)
        var stack = seededStack()
        val grid = TileGrid(1024, 1024)
        repeat(5000) {
            val result = randomOperation(stack, random, ids)
            if (result is StackResult.Ok) stack = result.edit.stack
            assertTrue(stack.layers.isNotEmpty(), "a document always has one layer")
            assertTrue(stack.activeIndex in stack.layers.indices, "activeIndex left the stack")
            assertEquals(
                stack.layers.size,
                stack.layers.distinctBy { it.id.value }.size,
                "a layer id was reissued",
            )
            assertTrue(stack.layers.size <= cap, "the layer cap was exceeded")
            assertTrue(
                stack.layers.all { l -> l.tiles.all { grid.contains(it) } },
                "a tile key escaped the canvas",
            )
        }
    }

    /** Three layers that actually own tiles, so the property tests exercise the tile sets too. */
    private fun seededStack(): LayerStack = LayerStack(
        layers = listOf(
            Layer(LayerProps(LayerId("id-0"), "a"), setOf(TileKey(0, 0), TileKey(1, 0))),
            Layer(LayerProps(LayerId("id-1"), "b"), setOf(TileKey(1, 0), TileKey(2, 1))),
            Layer(LayerProps(LayerId("id-2"), "c"), setOf(TileKey(3, 3))),
        ),
        activeIndex = 0,
        nextName = 4,
    )

    private fun randomOperation(stack: LayerStack, random: Random, ids: Ids): StackResult {
        val i = random.nextInt(stack.size)
        return when (random.nextInt(13)) {
            0 -> stack.add(ids, cap)
            1 -> stack.duplicate(i, ids, cap)
            2 -> stack.delete(i)
            3 -> stack.move(i, random.nextInt(stack.size))
            4 -> stack.mergeDown(i)
            5 -> stack.clear(i)
            6 -> stack.setOpacity(i, random.nextInt(0, 101) / 100f)
            7 -> stack.setVisible(i, random.nextBoolean())
            8 -> stack.setBlendMode(i, BlendMode.entries[random.nextInt(BlendMode.entries.size)])
            9 -> stack.flatten(ids)
            10 -> stack.rename(i, "name-${random.nextInt(1000)}")
            // Without these the exploration never reaches a stack that
            // *contains* a locked layer, so lock interactions with the
            // structural ops go untested mid-sequence.
            11 -> stack.setLocked(i, random.nextBoolean())
            else -> stack.setAlphaLock(i, random.nextBoolean())
        }
    }

    /**
     * The structural half of undo, per `docs/plan/06-document-and-persistence.md`
     * §5.2 — enough to prove the entries are invertible. The tile *bytes* and
     * the journal itself land with roadmap step 3; here a tile key set stands
     * in for the pixels.
     */
    // TODO(roadmap step 3): promote these inverse branches into shared
    // production code (a LayerStackInverter) that both the journal and this
    // test call, rather than writing a second inverse in step 3 and pointing
    // the test at it — two implementations is the drift this warns about.
    private fun undo(stack: LayerStack, entry: HistoryEntry): LayerStack {
        val layers = stack.layers.toMutableList()
        fun indexOf(id: String) = layers.indexOfFirst { it.id.value == id }
        when (entry) {
            is HistoryEntry.LayerAdd -> layers.removeAt(entry.index)
            is HistoryEntry.LayerDuplicate -> layers.removeAt(entry.index)
            is HistoryEntry.LayerDelete ->
                layers.add(entry.index, Layer(entry.layer.toProps(), entry.tiles.toSet()))
            is HistoryEntry.LayerReorder -> {
                // Exact inverse of move()'s add(toIndex, removeAt(fromIndex));
                // toIndex must stay a POST-removal insert index, or this
                // oracle silently diverges from production on downward moves.
                val moved = layers.removeAt(entry.toIndex)
                layers.add(entry.fromIndex, moved)
            }
            is HistoryEntry.LayerProps -> {
                val at = indexOf(entry.layerId)
                    .also { check(it >= 0) { "layer ${entry.layerId} is not in the rewound stack" } }
                layers[at] = layers[at].copy(props = entry.before.toProps())
            }
            is HistoryEntry.LayerClear -> {
                val at = indexOf(entry.layerId)
                    .also { check(it >= 0) { "layer ${entry.layerId} is not in the rewound stack" } }
                layers[at] = layers[at].copy(tiles = entry.tiles.toSet())
            }
            is HistoryEntry.LayerMerge -> {
                val at = indexOf(entry.lower.id)
                    .also { check(it >= 0) { "merged layer ${entry.lower.id} is not in the rewound stack" } }
                val upperTiles = entry.upperTiles.toSet()
                val lowerTiles = (layers[at].tiles - upperTiles) + entry.lowerTiles.toSet()
                layers[at] = Layer(entry.lower.toProps(), lowerTiles)
                layers.add(entry.upperIndex, Layer(entry.upper.toProps(), upperTiles))
            }
            is HistoryEntry.Flatten -> {
                layers.clear()
                entry.layers.forEach { record ->
                    layers += Layer(record.toProps(), entry.tilesPerLayer.getValue(record.id).toSet())
                }
            }
            else -> error("${entry::class.simpleName} is not a layer-stack entry")
        }
        return LayerStack(
            layers = layers,
            activeIndex = layers.indexOfFirst { it.id.value == entry.activeBefore }
                .also { check(it >= 0) { "activeBefore ${entry.activeBefore} is not in the rewound stack" } },
            nextName = stack.nextName,
        )
    }
}
