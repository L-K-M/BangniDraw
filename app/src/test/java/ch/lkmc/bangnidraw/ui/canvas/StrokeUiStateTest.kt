package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrokeUiStateTest {

    @Test
    fun `brush color converts from opaque argb`() {
        val state = StrokeUiState()

        state.setColor(0xFF804020.toInt())

        assertEquals(0xFF804020.toInt(), state.colorArgb)
        assertEquals(128f / 255f, state.colorR)
        assertEquals(64f / 255f, state.colorG)
        assertEquals(32f / 255f, state.colorB)
    }

    @Test
    fun `a new pick generation invalidates queued samples`() {
        val state = StrokeUiState()
        val first = state.nextPickGeneration()

        val second = state.nextPickGeneration()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(second, state.pickGeneration)
    }

    @Test
    fun `a quick pick reads its final position before completing`() {
        val state = StrokeUiState()
        val params = EyedropperParams(radius = 1)
        state.beginPick(params)
        state.recordPickPosition(12f, 34f)
        var request: FinalPickRequest? = null
        var callback: ((Int?) -> Unit)? = null
        var completedColor: Int? = null

        val queued = state.requestFinalPick(
            sample = { next, onColor ->
                request = next
                callback = onColor
            },
            onComplete = { completedColor = it },
        )

        assertTrue(queued)
        assertEquals(12f, assertNotNull(request).x)
        assertEquals(34f, assertNotNull(request).y)
        assertEquals(params, assertNotNull(request).params)

        callback?.invoke(0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), completedColor)
        assertEquals(null, state.pickParams)
    }

    @Test
    fun `final pick invalidates a delayed preview callback`() {
        val state = StrokeUiState()
        state.beginPick(EyedropperParams())
        state.recordPickPosition(12f, 34f)
        val previewGeneration = state.pickGeneration
        var previews = 0

        assertTrue(state.requestFinalPick(sample = { _, _ -> }, onComplete = {}))
        state.deliverPickPreview(previewGeneration, 0xFF123456.toInt()) { previews++ }

        assertEquals(0, previews)
    }

    @Test
    fun `cancelling a pending final pick invalidates completion and releases once`() {
        val state = StrokeUiState()
        state.beginPick(EyedropperParams())
        state.recordPickPosition(12f, 34f)
        var callback: ((Int?) -> Unit)? = null
        var completions = 0
        var releases = 0
        assertTrue(
            state.requestFinalPick(
                sample = { _, onColor -> callback = onColor },
                onComplete = { completions++ },
            ),
        )

        if (state.cancelPick()) releases++
        callback?.invoke(0xFF123456.toInt())

        assertEquals(1, releases)
        assertEquals(0, completions)
        assertFalse(state.cancelPick(), "the gate owner is released once")
    }
}
