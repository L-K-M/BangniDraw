package ch.lkmc.bangnidraw.engine.core

/** Hidden active layers surface only while the user is painting into them. */
object LayerVisibilityPolicy {
    fun shouldDraw(visible: Boolean, opacity: Float, strokePreview: Boolean): Boolean =
        opacity > 0f && (visible || strokePreview)
}
