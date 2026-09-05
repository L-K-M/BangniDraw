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
    /**
     * The canvas help, from the shared `strings.xml` like every other label.
     * It states the interactions this shell hides — the second click, the
     * right button, the keyboard — which is the rule AGENTS.md sets for every
     * help body in this product.
     */
    fun canvasBody(picturesDirectory: String = DesktopPlatform.picturesDir().toString()): String =
        DesktopStrings.get("desktop_help_body", picturesDirectory)
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
