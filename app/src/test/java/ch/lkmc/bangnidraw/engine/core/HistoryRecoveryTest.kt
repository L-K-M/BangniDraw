package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryRecoveryTest {

    private val original = Layer(LayerProps(LayerId("original"), "Original"))
    private val document = Document(
        id = "painting",
        title = "Painting",
        width = 512,
        height = 512,
        paperColor = 0xFFFFFFFF.toInt(),
        stack = LayerStack(listOf(original), activeIndex = 0, nextName = 2),
    )

    @Test
    fun `replay restores post-checkpoint structure and paper`() {
        val add = (document.stack.add(IdSource { LayerId("added") }, 8) as StackResult.Ok).edit
        val paper = HistoryEntry.PaperColor(
            activeBefore = add.stack.active.id,
            activeAfter = add.stack.active.id,
            before = document.paperColor,
            after = 0xFF112233.toInt(),
        )

        val recovered = HistoryRecovery.replay(document, listOf(add.entry, paper))

        assertEquals(2, recovered.appliedCount)
        assertEquals(listOf(original.id, LayerId("added")), recovered.document.stack.layers.map { it.id })
        assertEquals(LayerId("added"), recovered.document.stack.active.id)
        assertEquals(0xFF112233.toInt(), recovered.document.paperColor)
    }

    @Test
    fun `replay stops before the first transition that does not fit`() {
        val missingStroke = HistoryEntry.Stroke(
            activeBefore = original.id,
            activeAfter = original.id,
            layerId = LayerId("missing"),
            tiles = listOf(TileKey(0, 0)),
        )
        val paper = HistoryEntry.PaperColor(
            activeBefore = original.id,
            activeAfter = original.id,
            before = document.paperColor,
            after = 0xFF112233.toInt(),
        )

        val recovered = HistoryRecovery.replay(document, listOf(missingStroke, paper))

        assertEquals(0, recovered.appliedCount)
        assertEquals(document, recovered.document)
    }
}
