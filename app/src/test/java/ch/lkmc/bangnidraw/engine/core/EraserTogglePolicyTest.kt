package ch.lkmc.bangnidraw.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EraserTogglePolicyTest {

    private val hard = BrushPreset(
        id = BrushPresets.HARD_ERASER_ID,
        name = BrushPresets.HARD_ERASER_NAME,
        eraseMode = true,
    )
    private val soft = BrushPreset(
        id = BrushPresets.SOFT_ERASER_ID,
        name = "soft",
        eraseMode = true,
    )
    private val pencil = BrushPreset(
        id = BrushPresets.PENCIL_ID,
        name = "pencil",
    )

    @Test
    fun `two erasers swap both ways`() {
        val presets = listOf(pencil, hard, soft)

        assertEquals(soft.id, EraserTogglePolicy.next(hard.id, presets))
        assertEquals(hard.id, EraserTogglePolicy.next(soft.id, presets))
    }

    @Test
    fun `a single eraser has nothing to toggle to`() {
        assertNull(EraserTogglePolicy.next(hard.id, listOf(pencil, hard)))
    }

    @Test
    fun `no erasers has nothing to toggle to`() {
        assertNull(EraserTogglePolicy.next(hard.id, listOf(pencil)))
    }

    @Test
    fun `an unknown current id falls back to the first eraser`() {
        assertEquals(hard.id, EraserTogglePolicy.next("missing", listOf(pencil, hard, soft)))
    }

    @Test
    fun `preset order decides the fallback, not the id`() {
        assertEquals(hard.id, EraserTogglePolicy.next(soft.id, listOf(pencil, soft, hard)))
    }
}
