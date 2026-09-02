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
