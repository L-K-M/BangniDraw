package ch.lkmc.bangnidraw.desktop

/** Preserves the renderer's top-first row contract across the UI handoff. */
internal object DesktopFramePixels {
    fun copyForCompose(pixels: ByteArray): ByteArray = pixels.copyOf()
}
