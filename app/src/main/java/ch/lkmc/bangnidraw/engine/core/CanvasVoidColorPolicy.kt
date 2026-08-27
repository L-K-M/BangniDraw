package ch.lkmc.bangnidraw.engine.core

/** Theme-owned surround behind the transformed paper, independent of Compose and GL. */
internal object CanvasVoidColorPolicy {

    fun argb(tone: ThemeTone): Int = when (tone) {
        ThemeTone.LIGHT -> LIGHT_VOID
        ThemeTone.DARK -> DARK_VOID
    }

    private val LIGHT_VOID = 0xFFB8B2AA.toInt()
    private val DARK_VOID = 0xFF0F0E0D.toInt()
}
