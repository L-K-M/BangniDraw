package ch.lkmc.bangnidraw.input

import android.view.View
import kotlin.test.Test
import kotlin.test.assertTrue

class MouseNavigationWiringTest {

    @Test
    fun `canvas handler receives generic mouse motion`() {
        assertTrue(
            View.OnGenericMotionListener::class.java.isAssignableFrom(
                CanvasTouchHandler::class.java,
            ),
        )
    }
}
