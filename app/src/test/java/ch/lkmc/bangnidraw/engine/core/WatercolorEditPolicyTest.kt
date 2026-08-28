package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WatercolorEditPolicyTest {

    private val first = LayerId("first")
    private val second = LayerId("second")
    private val props = LayerProps(first, "First")

    @Test
    fun `metadata and duplicate keep wet state`() {
        assertEquals(
            WatercolorInvalidation.Keep,
            WatercolorEditPolicy.forEdit(emptyList(), SandwichPolicy.Op.SetInertProperty),
        )
        assertEquals(
            WatercolorInvalidation.Keep,
            WatercolorEditPolicy.forEdit(
                listOf(PixelOp.Copy(first, second, emptySet())),
                SandwichPolicy.Op.Duplicate(0),
            ),
        )
    }

    @Test
    fun `local destructive edits dry only affected layers`() {
        assertEquals(
            WatercolorInvalidation.Layers(setOf(first)),
            WatercolorEditPolicy.forEdit(
                listOf(PixelOp.Clear(first)),
                SandwichPolicy.Op.Clear(0),
            ),
        )
        assertEquals(
            WatercolorInvalidation.Layers(setOf(first, second)),
            WatercolorEditPolicy.forEdit(
                listOf(PixelOp.Merge(first, props, second, props.copy(id = second), emptySet())),
                SandwichPolicy.Op.MergeDown(0),
            ),
        )
    }

    @Test
    fun `history and flatten dry every layer`() {
        assertEquals(
            WatercolorInvalidation.All,
            WatercolorEditPolicy.forEdit(emptyList(), SandwichPolicy.Op.UndoRedo),
        )
        assertEquals(
            WatercolorInvalidation.All,
            WatercolorEditPolicy.forEdit(
                listOf(PixelOp.Flatten(listOf(props), second)),
                SandwichPolicy.Op.Flatten,
            ),
        )
    }
}
