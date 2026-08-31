package ch.lkmc.bangnidraw.engine.core

/**
 * Theme-owned surround behind the transformed paper, independent of Compose and GL.
 * Two tones, neutral within each: light themes keep the original gray,
 * dark themes get a dark gray; neither is palette-tinted.
 */
object CanvasVoidColorPolicy {

    fun argb(theme: AppTheme): Int = ThemeColorPolicy.colors(theme).canvasVoidArgb
}
