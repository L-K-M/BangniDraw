package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolSwitcherTest {

    private val pencil = ToolKind.Brush(BrushPresets.DEFAULT.copy(id = "test.pencil"))
    private val markerPreset = BrushPresets.DEFAULT.copy(id = "test.marker")
    private val marker = ToolKind.Brush(markerPreset)
    private val ink = ToolKind.Brush(BrushPresets.INK_PEN)
    private val eraser = ToolKind.Brush(
        BrushPresets.DEFAULT.copy(id = "test.eraser", eraseMode = true),
    )
    private val eyedropper = ToolKind.Eyedropper()

    @Test
    fun `select changes the base tool`() {
        val switcher = ToolSwitcher(pencil)

        switcher.select(ink)

        assertEquals(ToolSelection(ink), switcher.selection.value)
        assertEquals(ink, switcher.current.value)
    }

    @Test
    fun `select during a temporary tool changes what is restored`() {
        val switcher = ToolSwitcher(pencil)
        switcher.pushTemporary(eraser, TemporaryReason.PenButton)

        switcher.select(ink)
        assertEquals(ToolSelection(eraser, TemporaryReason.PenButton), switcher.selection.value)

        switcher.popTemporary(TemporaryReason.PenButton)
        assertEquals(ToolSelection(ink), switcher.selection.value)
    }

    @Test
    fun `shared paint assignment updates the base beneath a temporary tool`() {
        val switcher = ToolSwitcher(pencil)
        switcher.pushTemporary(eraser, TemporaryReason.PenButton)

        switcher.replaceBasePaintPreset(markerPreset)

        assertEquals(ToolSelection(eraser, TemporaryReason.PenButton), switcher.selection.value)

        switcher.popTemporary(TemporaryReason.PenButton)
        assertEquals(ToolSelection(marker), switcher.selection.value)
    }

    @Test
    fun `nested overrides restore in stack order`() {
        val switcher = ToolSwitcher(pencil)
        switcher.pushTemporary(eyedropper, TemporaryReason.PenButton)
        switcher.pushTemporary(eraser, TemporaryReason.EraserEnd)

        switcher.popTemporary(TemporaryReason.EraserEnd)
        assertEquals(ToolSelection(eyedropper, TemporaryReason.PenButton), switcher.selection.value)

        switcher.popTemporary(TemporaryReason.PenButton)
        assertEquals(ToolSelection(pencil), switcher.selection.value)
    }

    @Test
    fun `an out-of-order pop cannot uncover the wrong tool`() {
        val switcher = ToolSwitcher(pencil)
        switcher.pushTemporary(eyedropper, TemporaryReason.PenButton)
        switcher.pushTemporary(eraser, TemporaryReason.EraserEnd)

        switcher.popTemporary(TemporaryReason.PenButton)

        assertEquals(ToolSelection(eraser, TemporaryReason.EraserEnd), switcher.selection.value)
    }

    @Test
    fun `repeating one reason updates instead of stacking`() {
        val switcher = ToolSwitcher(pencil)
        switcher.pushTemporary(eraser, TemporaryReason.PenButton)
        switcher.pushTemporary(eyedropper, TemporaryReason.PenButton)

        switcher.popTemporary(TemporaryReason.PenButton)

        assertEquals(ToolSelection(pencil), switcher.selection.value)
    }
}
