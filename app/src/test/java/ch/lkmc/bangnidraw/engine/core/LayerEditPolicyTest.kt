package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.SandwichPolicy.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayerEditPolicyTest {

    private val bottom = layer("bottom")
    private val active = layer("active")
    private val top = layer("top")
    private val stack = LayerStack(listOf(bottom, active, top), activeIndex = 1, nextName = 4)

    @Test
    fun `each structural entry maps to its cache operation`() {
        val ids = IdSource { LayerId("fresh") }
        val edits = listOf(
            stack.add(ids, maxLayers = 8) to Op.Add,
            stack.delete(2) to Op.Delete(2),
            stack.move(0, 2) to Op.Move(0, 2),
            stack.duplicate(1, ids, maxLayers = 8) to Op.Duplicate(1),
            stack.mergeDown(2) to Op.MergeDown(2),
            stack.clear(1) to Op.Clear(1),
            stack.flatten(ids) to Op.Flatten,
        )
        for ((result, expected) in edits) {
            val edit = (result as StackResult.Ok).edit
            assertEquals(expected, LayerEditPolicy.invalidation(stack, edit.entry))
        }
    }

    @Test
    fun `property entries distinguish compositing from inert fields`() {
        val opacity = (stack.setOpacity(1, 0.5f) as StackResult.Ok).edit.entry
        val rename = (stack.rename(1, "Ink") as StackResult.Ok).edit.entry

        assertEquals(Op.SetCompositingProperty(1), LayerEditPolicy.invalidation(stack, opacity))
        assertEquals(Op.SetInertProperty, LayerEditPolicy.invalidation(stack, rename))
    }

    @Test
    fun `pixel and paper entries are covered`() {
        val stroke = HistoryEntry.Stroke(
            activeBefore = active.id,
            activeAfter = active.id,
            layerId = active.id,
            tiles = listOf(TileKey(0, 0)),
        )
        val paper = HistoryEntry.PaperColor(
            activeBefore = active.id,
            activeAfter = active.id,
            before = 0,
            after = 1,
        )

        assertEquals(Op.PixelEdit(1), LayerEditPolicy.invalidation(stack, stroke))
        assertEquals(Op.PaperColor, LayerEditPolicy.invalidation(stack, paper))
        assertNull(LayerEditPolicy.invalidation(stack, stroke.copy(layerId = LayerId("gone"))))
    }

    @Test
    fun `deleted layer ids retain stack order`() {
        val after = LayerStack(listOf(active), activeIndex = 0, nextName = stack.nextName)

        assertEquals(
            listOf(bottom.id, top.id),
            LayerEditPolicy.deletedLayers(stack, after),
        )
    }

    @Test
    fun `duplicate flushes every copied destination tile`() {
        val source = Layer(
            LayerProps(LayerId("source"), "source"),
            setOf(TileKey(1, 0), TileKey(2, 0)),
        )
        val one = LayerStack(listOf(source), activeIndex = 0, nextName = 2)
        val edit = (one.duplicate(0, IdSource { LayerId("copy") }, 8) as StackResult.Ok).edit

        assertEquals(
            setOf(LayerId("copy") to TileKey(1, 0), LayerId("copy") to TileKey(2, 0)),
            LayerEditPolicy.changedTiles(one, edit.pixels).toSet(),
        )
    }

    @Test
    fun `flatten flushes the result and every removed source tile`() {
        val a = Layer(LayerProps(LayerId("a"), "a"), setOf(TileKey(0, 0)))
        val b = Layer(LayerProps(LayerId("b"), "b"), setOf(TileKey(1, 0)))
        val two = LayerStack(listOf(a, b), activeIndex = 1, nextName = 3)
        val edit = (two.flatten(IdSource { LayerId("flat") }) as StackResult.Ok).edit

        assertEquals(
            setOf(
                a.id to TileKey(0, 0),
                b.id to TileKey(1, 0),
                LayerId("flat") to TileKey(0, 0),
                LayerId("flat") to TileKey(1, 0),
            ),
            LayerEditPolicy.changedTiles(two, edit.pixels).toSet(),
        )
    }

    private fun layer(id: String) = Layer(
        LayerProps(id = LayerId(id), name = id),
        tiles = setOf(TileKey(0, 0)),
    )
}
