package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import kotlin.test.Test
import kotlin.test.assertEquals

class GlFenceWaitPolicyTest {

    @Test
    fun `the first idle fence poll submits producer commands`() {
        assertEquals(
            GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,
            GlFenceWaitPolicy.flags(FenceWaitPhase.FIRST),
        )
        assertEquals(0, GlFenceWaitPolicy.flags(FenceWaitPhase.LATER))
    }
}
