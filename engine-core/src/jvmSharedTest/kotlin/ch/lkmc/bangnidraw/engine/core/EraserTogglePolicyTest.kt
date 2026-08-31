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
    private val textured = BrushPreset(
        id = "textured-eraser",
        name = "textured",
        eraseMode = true,
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
    fun `a known non-eraser falls back to the first eraser`() {
        assertEquals(hard.id, EraserTogglePolicy.next(pencil.id, listOf(pencil, hard, soft)))
    }

    @Test
    fun `three erasers cycle in preset order`() {
        val presets = listOf(pencil, hard, soft, textured)

        assertEquals(soft.id, EraserTogglePolicy.next(hard.id, presets))
        assertEquals(textured.id, EraserTogglePolicy.next(soft.id, presets))
        assertEquals(hard.id, EraserTogglePolicy.next(textured.id, presets))
    }

    @Test
    fun `cycling follows preset order rather than preset ids`() {
        assertEquals(hard.id, EraserTogglePolicy.next(soft.id, listOf(pencil, soft, hard)))
    }
}
