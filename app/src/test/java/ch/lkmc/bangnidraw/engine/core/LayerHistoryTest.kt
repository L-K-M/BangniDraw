package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayerHistoryTest {

    private val a = LayerId("a")
    private val b = LayerId("b")
    private val key = TileKey(1, 2)

    @Test
    fun `undo and redo restore the active-layer hint`() {
        val before = stack(layer(a), layer(b), active = 0)
        val edit = assertIs<StackResult.Ok>(before.move(1, 0)).edit

        val undone = applied(edit.stack, edit.entry, HistoryDirection.UNDO)
        val redone = applied(undone.stack, edit.entry, HistoryDirection.REDO)

        assertEquals(a, undone.stack.active.id)
        assertEquals(b, redone.stack.active.id)
    }

    @Test
    fun `undo ignores a layer lock because history is not a new edit`() {
        val locked = stack(layer(a, locked = true))
        val entry = HistoryEntry.Stroke(
            activeBefore = a,
            activeAfter = a,
            layerId = a,
            tiles = listOf(key),
        )

        val result = LayerHistory.apply(locked, entry, HistoryDirection.UNDO)

        assertIs<LayerHistoryResult.Applied>(result)
    }

    @Test
    fun `redo duplicate copies the source's current tile set`() {
        val source = layer(a, tiles = setOf(key))
        val copy = LayerRecord(id = b.value, name = "copy")
        val entry = HistoryEntry.LayerDuplicate(
            activeBefore = a,
            activeAfter = b,
            sourceId = a,
            copy = copy,
            index = 1,
        )

        val redone = applied(stack(source), entry, HistoryDirection.REDO)

        assertEquals(setOf(key), redone.stack.active.tiles)
        assertEquals(listOf(PixelOp.Copy(a, b, setOf(key))), redone.pixelOps)
    }

    @Test
    fun `undo add releases the layer and redo does not enforce today's cap`() {
        val record = LayerRecord(id = b.value, name = "new")
        val entry = HistoryEntry.LayerAdd(
            activeBefore = a,
            activeAfter = b,
            layer = record,
            index = 1,
        )
        val after = stack(layer(a), Layer(record.toProps()), active = 1)

        val undone = applied(after, entry, HistoryDirection.UNDO)
        val redone = applied(undone.stack, entry, HistoryDirection.REDO)

        assertEquals(listOf(PixelOp.Delete(b)), undone.pixelOps)
        assertEquals(listOf(a, b), redone.stack.layers.map { it.id })
    }

    @Test
    fun `redo clear frees pixels while undo restores the tile keys`() {
        val entry = HistoryEntry.LayerClear(
            activeBefore = a,
            activeAfter = a,
            layerId = a,
            tiles = listOf(key),
        )
        val cleared = stack(layer(a))

        val undone = applied(cleared, entry, HistoryDirection.UNDO)
        val redone = applied(undone.stack, entry, HistoryDirection.REDO)

        assertEquals(setOf(key), undone.stack.active.tiles)
        assertEquals(emptySet(), redone.stack.active.tiles)
        assertEquals(listOf(PixelOp.Clear(a)), redone.pixelOps)
    }

    @Test
    fun `a missing subject marks the entry corrupt instead of editing another layer`() {
        val entry = HistoryEntry.LayerDelete(
            activeBefore = a,
            activeAfter = a,
            layer = LayerRecord(id = b.value, name = "missing"),
            index = 0,
            tiles = listOf(key),
        )

        val result = LayerHistory.apply(stack(layer(a)), entry, HistoryDirection.REDO)

        assertEquals(LayerHistoryResult.Corrupt, result)
    }

    private fun applied(
        stack: LayerStack,
        entry: HistoryEntry,
        direction: HistoryDirection,
    ): LayerHistoryEdit =
        assertIs<LayerHistoryResult.Applied>(LayerHistory.apply(stack, entry, direction)).edit

    private fun stack(vararg layers: Layer, active: Int = 0): LayerStack =
        LayerStack(layers.toList(), active, nextName = layers.size + 1)

    private fun layer(
        id: LayerId,
        locked: Boolean = false,
        tiles: Set<TileKey> = emptySet(),
    ): Layer = Layer(LayerProps(id, id.value, locked = locked), tiles)
}
