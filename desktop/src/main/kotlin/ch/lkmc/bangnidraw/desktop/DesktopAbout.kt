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
                "rule. Clicking the eraser while it is already selected swaps " +
                "between the hard and the soft eraser.",
            "The two sliders at the foot of the rail set brush size and " +
                "opacity — flow, on a watercolor preset. They follow the " +
                "selected brush, and each preset keeps its own tuning.",
            "Undo and Redo sit in the strip; the swatch beside them opens the " +
                "colour panel.",
            "Save PNG writes the painting to $picturesDirectory.",
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
