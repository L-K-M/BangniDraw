package ch.lkmc.bangnidraw.desktop

import java.awt.Desktop

internal enum class MixboxAttribution {
    Included,
    Excluded,
}

internal object DesktopAbout {
    fun body(attribution: MixboxAttribution): String {
        val base = "A layered raster drawing app for macOS, Linux, and Android."
        if (attribution == MixboxAttribution.Excluded) return base

        return "$base\n\n" +
            "Mixbox © Secret Weapons, CC BY-NC 4.0 — non-commercial."
    }
}

/**
 * The canvas Help sheet's body. AGENTS.md makes in-app documentation a
 * button rather than a hidden manual, and the icon rail hides exactly the
 * kind of second-click behaviour that rule exists for; this is also where
 * the export directory now lives, since the rail replaced the sidebar that
 * used to print it.
 */
internal object DesktopHelp {
    fun canvasBody(picturesDirectory: String = DesktopPlatform.picturesDir().toString()): String =
        listOf(
            "Draw with the left mouse button. Holding the right button erases " +
                "instead, the way a stylus flipped to its eraser end does. " +
                "Scroll to zoom around the pointer.",
            "The rail picks a brush: one icon per preset, the eraser below the " +
                "rule. Clicking a slot that is already selected opens the " +
                "brush panel, which is also where the hard and soft erasers " +
                "are chosen.",
            "Below the rule are the other tools: smudge, water, blur, fill " +
                "and the eyedropper. Each keeps its own settings, and a " +
                "narrow window moves blur and the eyedropper into a menu.",
            "The two sliders at the foot of the rail set size and the tool's " +
                "second value — opacity, flow, strength or water load. They " +
                "follow the selected tool, and each keeps its own tuning. " +
                "Fill and the eyedropper have neither, so the sliders go away.",
            "Undo and Redo sit in the strip; the swatch beside them opens the " +
                "colour panel, and the layers button beside that opens the " +
                "layer panel. Both panels are windows of their own — move " +
                "them anywhere, and close them when you are done.",
            "The layer panel adds, duplicates, deletes, merges and flattens " +
                "layers, and sets each one's opacity, blend mode, alpha lock " +
                "and lock. Its bottom row picks the paper colour.",
            "This is a document-based app: File opens and saves PNGs through " +
                "the system's own dialogs, several paintings can be open at " +
                "once, and closing one with unsaved work asks first. A PNG " +
                "holds one layer, so saving flattens what the layer panel " +
                "shows — the layers themselves are not stored in the file.",
            "The overflow's Save PNG writes the whole visible stack to " +
                "$picturesDirectory when the painting has no file yet.",
        ).joinToString("\n\n")
}

/** Routes macOS's native About item into the Compose dialog. */
internal object DesktopAboutHandler {
    fun install(onAbout: () -> Unit): AutoCloseable {
        val desktop = desktopOrNull() ?: return AutoCloseable {}
        if (!desktop.isSupported(Desktop.Action.APP_ABOUT)) return AutoCloseable {}

        desktop.setAboutHandler { onAbout() }
        return AutoCloseable {
            runCatching { desktop.setAboutHandler(null) }
        }
    }

    private fun desktopOrNull(): Desktop? = try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
