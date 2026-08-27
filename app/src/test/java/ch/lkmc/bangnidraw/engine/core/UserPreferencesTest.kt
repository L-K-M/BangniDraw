package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UserPreferencesTest {

    @Test
    fun `unknown stored preferences fall back safely`() {
        assertEquals(Hand.RIGHT, Hand.fromStored("ambidextrous"))
        assertEquals(TouchDrawingMode.ENABLED, TouchDrawingMode.fromStored("sometimes"))
        assertEquals(HapticsMode.ENABLED, HapticsMode.fromStored("maybe"))
    }

    @Test
    fun `stored preference names round trip`() {
        for (hand in Hand.entries) assertEquals(hand, Hand.fromStored(hand.name))
        for (mode in TouchDrawingMode.entries) {
            assertEquals(mode, TouchDrawingMode.fromStored(mode.name))
        }
        for (mode in HapticsMode.entries) {
            assertEquals(mode, HapticsMode.fromStored(mode.name))
        }
    }
}
