package ch.lkmc.bangnidraw.engine.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES

class LayerTileUpdatesTest {

    private val id = LayerId("layer")
    private val kept = TileKey(0, 0)
    private val erased = TileKey(1, 0)
    private val added = TileKey(2, 0)
    private val stack = LayerStack(
        layers = listOf(
            Layer(LayerProps(id, "Layer"), setOf(kept, erased)),
        ),
        activeIndex = 0,
        nextName = 2,
    )

    @Test
    fun `empty updates remove keys while painted updates add them`() {
        val updates = mapOf(
            (id to erased) to TilePresence.EMPTY,
            (id to added) to TilePresence.PAINTED,
        )

        val result = LayerTileUpdates.apply(stack, updates)

        assertEquals(setOf(kept, added), result.active.tiles)
    }

    @Test
    fun `updates for removed layers are ignored`() {
        val result = LayerTileUpdates.apply(
            stack,
            mapOf((LayerId("gone") to added) to TilePresence.PAINTED),
        )

        assertEquals(stack, result)
    }

    @Test
    fun `restore outcomes fold exactly like the checkpoint fold`() {
        // The undo/redo apply path feeds restore outcomes here immediately;
        // the later checkpoint re-applies the same values from the readback
        // sink. A null restore (EMPTY) must remove a stale key the model
        // still holds, a byte restore (PAINTED) must add a missing one, and
        // the second pass over the same map must change nothing further.
        val restoreOutcomes = mapOf(
            (id to erased) to TilePresence.EMPTY,
            (id to added) to TilePresence.PAINTED,
        )

        val applied = LayerTileUpdates.apply(stack, restoreOutcomes)
        val reapplied = LayerTileUpdates.apply(applied, restoreOutcomes)

        assertEquals(setOf(kept, added), applied.active.tiles)
        assertEquals(applied, reapplied, "the fold and a restore's re-derivation agree")
    }

    @Test
    fun `byte-buffer classification preserves its position`() {
        val empty = ByteBuffer.wrap(byteArrayOf(0, 0, 0)).apply { position(1) }
        val painted = ByteBuffer.wrap(byteArrayOf(0, 1, 0)).apply { position(1) }

        assertEquals(TilePresence.EMPTY, presenceOf(empty))
        assertEquals(TilePresence.PAINTED, presenceOf(painted))
        assertEquals(1, empty.position())
        assertEquals(1, painted.position())
    }

    @Test
    fun `both folds classify tile presence through one rule`() {
        // The readback fold and the restore fold must derive the same
        // presence from the same bytes; a divergence is the model-vs-pixels
        // lag this rule exists to kill.
        val painted = ByteArray(TILE_BYTES).also { it[0] = 1 }

        assertEquals(TilePresence.EMPTY, presenceOf(null))
        assertEquals(TilePresence.EMPTY, presenceOf(ByteArray(TILE_BYTES)))
        assertEquals(TilePresence.PAINTED, presenceOf(painted))
    }
}
