package ch.lkmc.bangnidraw.engine.core

/** Theme-owned surround behind the transformed paper, independent of Compose and GL. */
internal object CanvasVoidColorPolicy {

    fun argb(theme: AppTheme): Int = ThemeColorPolicy.colors(theme).canvasVoidArgb
}
