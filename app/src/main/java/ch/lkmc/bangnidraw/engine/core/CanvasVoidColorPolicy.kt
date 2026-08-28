package ch.lkmc.bangnidraw.engine.core

/**
 * Theme-owned surround behind the transformed paper, independent of Compose and GL.
 * Fixed-light like every palette: the former dark-mode variant was removed on purpose.
 */
internal object CanvasVoidColorPolicy {

    fun argb(theme: AppTheme): Int = ThemeColorPolicy.colors(theme).canvasVoidArgb
}
