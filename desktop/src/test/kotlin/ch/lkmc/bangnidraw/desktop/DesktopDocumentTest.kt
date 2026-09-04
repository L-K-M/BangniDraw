package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.IdSource
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.StackResult
import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopDocumentTest {

    @Test
    fun `a layer that leaves the stack has its textures released`() {
        val before = stackOf("a", "b")
        val after = stackOf("a")

        val ops = DesktopUndoOps.ops(before, after, emptyMap())

        assertEquals(listOf(PixelOp.Delete(LayerId("b"))), ops)
    }

    @Test
    fun `a layer that rejoins the stack has its tiles uploaded`() {
        val before = stackOf("a")
        val after = stackOf("a", "b")
        val tiles = mapOf(LayerId("b") to mapOf(TileKey(0, 0) to ByteArray(4)))

        val ops = DesktopUndoOps.ops(before, after, tiles)

        assertEquals(1, ops.size)
        val restore = ops.single() as PixelOp.Restore
        assertEquals(LayerId("b"), restore.layer)
        assertEquals(setOf(TileKey(0, 0)), restore.tiles.keys)
    }

    @Test
    fun `pixels for a layer the step removes are never uploaded`() {
        val before = stackOf("a", "b")
        val after = stackOf("a")
        // The step keeps b's pixels for the *other* direction; applying this
        // direction must not try to restore them onto a layer that is gone.
        val tiles = mapOf(LayerId("b") to mapOf(TileKey(0, 0) to ByteArray(4)))

        val ops = DesktopUndoOps.ops(before, after, tiles)

        assertEquals(listOf(PixelOp.Delete(LayerId("b"))), ops)
    }

    @Test
    fun `an empty tile map produces no work`() {
        val stack = stackOf("a")

        assertTrue(
            DesktopUndoOps.ops(stack, stack, mapOf(LayerId("a") to emptyMap())).isEmpty(),
        )
    }

    @Test
    fun `a flatten snapshots every layer, hidden ones included`() {
        val hidden = Layer(LayerProps(LayerId("b"), "b", visible = false))
        val before = LayerStack(listOf(layer("a"), hidden), activeIndex = 0, nextName = 3)
        val result = LayerId("c")
        val after = LayerStack(listOf(layer("c")), activeIndex = 0, nextName = 3)

        val touched = DesktopStackEdits.touchedLayers(
            before,
            after,
            PixelOp.Flatten(listOf(before.layers[0].props), result),
        )

        assertEquals(setOf(LayerId("a"), LayerId("b"), result), touched)
    }

    @Test
    fun `a merge snapshots both partners`() {
        val before = stackOf("a", "b")
        val after = stackOf("a")

        val touched = DesktopStackEdits.touchedLayers(
            before,
            after,
            PixelOp.Merge(
                top = LayerId("b"),
                topProps = before.layers[1].props,
                bottom = LayerId("a"),
                bottomProps = before.layers[0].props,
                keys = emptySet(),
            ),
        )

        assertEquals(setOf(LayerId("b"), LayerId("a")), touched)
    }

    @Test
    fun `a props edit touches nothing`() {
        val before = stackOf("a")
        val after = before.setOpacity(0, 0.5f).let { (it as StackResult.Ok).edit.stack }

        assertTrue(DesktopStackEdits.touchedLayers(before, after, null).isEmpty())
    }

    @Test
    fun `the snapshot key set is the union of both sides`() {
        val before = LayerStack(
            listOf(Layer(LayerProps(LayerId("a"), "a"), setOf(TileKey(0, 0)))),
            activeIndex = 0,
            nextName = 2,
        )
        val after = LayerStack(
            listOf(Layer(LayerProps(LayerId("a"), "a"), setOf(TileKey(1, 1)))),
            activeIndex = 0,
            nextName = 2,
        )

        assertEquals(
            setOf(TileKey(0, 0), TileKey(1, 1)),
            DesktopStackEdits.keysFor(before, after, LayerId("a")),
        )
    }

    @Test
    fun `every structural entry names its own cache row`() {
        val stack = stackOf("a", "b")
        val ids = IdSource { LayerId("c") }

        assertEquals(SandwichPolicy.Op.Add, invalidationOf(stack) { it.add(ids, MAX_LAYERS) })
        assertEquals(
            SandwichPolicy.Op.Delete(1),
            invalidationOf(stack) { it.delete(1) },
        )
        assertEquals(
            SandwichPolicy.Op.MergeDown(1),
            invalidationOf(stack) { it.mergeDown(1) },
        )
        assertEquals(
            SandwichPolicy.Op.Move(0, 1),
            invalidationOf(stack) { it.move(0, 1) },
        )
        assertEquals(SandwichPolicy.Op.Flatten, invalidationOf(stack) { it.flatten(ids) })
        assertEquals(
            SandwichPolicy.Op.Duplicate(1),
            invalidationOf(stack) { it.duplicate(1, ids, MAX_LAYERS) },
        )
    }

    @Test
    fun `a compositing property stales a cache half, an inert one does not`() {
        val stack = stackOf("a", "b")

        assertEquals(
            SandwichPolicy.Op.SetCompositingProperty(1),
            invalidationOf(stack) { it.setOpacity(1, 0.5f) },
        )
        assertEquals(
            SandwichPolicy.Op.SetCompositingProperty(1),
            invalidationOf(stack) { it.setVisible(1, false) },
        )
        assertEquals(
            SandwichPolicy.Op.SetCompositingProperty(0),
            invalidationOf(stack) { it.setBlendMode(0, BlendMode.MULTIPLY) },
        )
        assertEquals(
            SandwichPolicy.Op.SetInertProperty,
            invalidationOf(stack) { it.setAlphaLock(1, true) },
        )
        assertEquals(
            SandwichPolicy.Op.SetInertProperty,
            invalidationOf(stack) { it.rename(1, "renamed") },
        )
    }

    @Test
    fun `a commit widens the layer's tile set and leaves the rest alone`() {
        val stack = stackOf("a", "b")

        val next = DesktopStrokeTiles.withCommitted(
            stack,
            LayerId("b"),
            listOf(TileKey(0, 0), TileKey(1, 0)),
        )

        assertEquals(setOf(TileKey(0, 0), TileKey(1, 0)), next.layers[1].tiles)
        assertTrue(next.layers[0].tiles.isEmpty())
        assertEquals(stack.activeIndex, next.activeIndex)
    }

    @Test
    fun `a commit that adds no key returns the same stack`() {
        val stack = DesktopStrokeTiles.withCommitted(stackOf("a"), LayerId("a"), listOf(TileKey(0, 0)))

        assertSame(stack, DesktopStrokeTiles.withCommitted(stack, LayerId("a"), listOf(TileKey(0, 0))))
        assertSame(stack, DesktopStrokeTiles.withCommitted(stack, LayerId("a"), emptyList()))
        // A layer the model no longer holds must not resurrect one.
        assertSame(stack, DesktopStrokeTiles.withCommitted(stack, LayerId("gone"), listOf(TileKey(1, 1))))
    }

    @Test
    fun `the undo step's bytes count both directions`() {
        val stack = stackOf("a")
        val step = DesktopUndoStep(
            stackBefore = stack,
            stackAfter = stack,
            pixelsBefore = mapOf(LayerId("a") to mapOf(TileKey(0, 0) to ByteArray(16))),
            pixelsAfter = mapOf(LayerId("a") to mapOf(TileKey(0, 0) to ByteArray(32), TileKey(1, 0) to null)),
        )

        assertEquals(48L, step.bytes)
        assertSame(step.pixelsBefore, step.pixelsFor(HistoryDirection.Undo))
        assertSame(step.pixelsAfter, step.pixelsFor(HistoryDirection.Redo))
        assertSame(stack, step.stackFor(HistoryDirection.Undo))
    }

    @Test
    fun `only the paper side that moved is applied`() {
        val stack = stackOf("a")
        val step = DesktopUndoStep(
            stackBefore = stack,
            stackAfter = stack,
            pixelsBefore = emptyMap(),
            pixelsAfter = emptyMap(),
            paperBefore = 0xFFFFFFFF.toInt(),
            paperAfter = 0xFF000000.toInt(),
        )

        assertEquals(0xFFFFFFFF.toInt(), step.paperFor(HistoryDirection.Undo))
        assertEquals(0xFF000000.toInt(), step.paperFor(HistoryDirection.Redo))
        // A step that did not touch the paper reports nothing on either side.
        val plain = DesktopUndoStep(stack, stack, emptyMap(), emptyMap())
        assertEquals(null, plain.paperFor(HistoryDirection.Undo))
        assertEquals(null, plain.paperFor(HistoryDirection.Redo))
    }

    @Test
    fun `generated layer names resolve, user text does not`() {
        assertEquals("Layer 7", DesktopLayerNames.resolve(LayerStack.defaultName(7)))
        assertEquals("Flattened", DesktopLayerNames.resolve(LayerStack.FLATTENED_NAME))
        assertEquals(
            "Layer 2 copy copy",
            DesktopLayerNames.resolve(
                LayerStack.duplicateName(LayerStack.duplicateName(LayerStack.defaultName(2))),
            ),
        )
        // A name the user typed is shown back unchanged, token or not.
        assertEquals("@string/app_name", DesktopLayerNames.resolve("@string/app_name"))
        assertEquals("Sky", DesktopLayerNames.resolve("Sky"))
    }

    @Test
    fun `every refusal has something to say`() {
        for (reason in Refusal.entries) {
            assertTrue(DesktopLayerNames.refusal(reason, layerCap = 12).isNotBlank())
        }
        assertTrue(DesktopLayerNames.refusal(Refusal.AT_CAP, layerCap = 12).contains("12"))
    }

    private fun invalidationOf(
        stack: LayerStack,
        edit: (LayerStack) -> StackResult,
    ): SandwichPolicy.Op {
        val result = edit(stack) as StackResult.Ok
        return DesktopStackEdits.invalidation(result.edit.entry, stack)
    }

    private fun layer(id: String): Layer = Layer(LayerProps(LayerId(id), id))

    private fun stackOf(vararg ids: String): LayerStack =
        LayerStack(ids.map(::layer), activeIndex = 0, nextName = ids.size + 1)

    private companion object {
        const val MAX_LAYERS = 8
    }
}
