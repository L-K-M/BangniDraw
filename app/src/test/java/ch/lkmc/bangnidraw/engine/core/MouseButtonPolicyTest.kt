package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MouseButtonPolicyTest {

    @Test
    fun `desktop buttons have distinct gestures`() {
        assertEquals(MouseGesture.DRAW, MouseButtonPolicy.begin(MouseButton.PRIMARY))
        assertEquals(MouseGesture.PAN, MouseButtonPolicy.begin(MouseButton.MIDDLE))
        assertEquals(MouseGesture.IGNORE, MouseButtonPolicy.begin(MouseButton.SECONDARY))
        assertEquals(MouseGesture.NONE, MouseButtonPolicy.begin(MouseButton.NONE))
    }
}
