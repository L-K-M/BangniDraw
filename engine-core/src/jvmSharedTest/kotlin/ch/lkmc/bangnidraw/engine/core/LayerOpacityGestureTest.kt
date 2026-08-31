package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayerOpacityGestureTest {
    @Test
    fun `many previews finish as one props entry`() {
        val stack = LayerStack.initial(IdSource { LayerId("layer") })
        val gesture = LayerOpacityGesture.begin(stack, 0)!!

        assertEquals(0.8f, gesture.withValue(0.8f).preview(stack)!!.active.props.opacity)
        val result = assertIs<StackResult.Ok>(gesture.withValue(0.4f).finish(stack))
        val entry = assertIs<HistoryEntry.LayerProps>(result.edit.entry)

        assertEquals(1f, entry.before.opacity)
        assertEquals(0.4f, entry.after.opacity)
        assertEquals(0.4f, result.edit.stack.active.props.opacity)
    }

    @Test
    fun `ending at the starting value is not journaled`() {
        val stack = LayerStack.initial(IdSource { LayerId("layer") })
        val gesture = LayerOpacityGesture.begin(stack, 0)!!

        val result = assertIs<StackResult.Refused>(gesture.finish(stack))

        assertEquals(Refusal.NOOP, result.reason)
    }
}
