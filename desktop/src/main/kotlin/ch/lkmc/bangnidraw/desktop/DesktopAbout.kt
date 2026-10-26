package ch.lkmc.bangnidraw.desktop

import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI

internal enum class MixboxAttribution {
    Included,
    Excluded,
}

internal object DesktopAbout {
    /**
     * The About text: the app's own license scoped to its own code, Mixbox's
     * full CC BY-NC 4.0 attribution when it ships (the shared Android
     * strings), and where the bundled notices are, as a path a user can open
     * even when the desktop cannot open it for them.
     */
    fun body(attribution: MixboxAttribution, notices: File? = DesktopNotices.file()): String {
        val text = StringBuilder("A layered raster drawing app for macOS, Linux, and Android.")
        text.append("\n\n").append(DesktopStrings.get("about_license"))
        if (attribution == MixboxAttribution.Included) {
            text.append("\n\n").append(DesktopStrings.get("about_mixbox"))
        }
        if (notices != null) {
            text.append("\n\n").append(DesktopStrings.get("open_source_licenses"))
                .append(": ").append(notices.path)
        }
        return text.toString()
    }
}

/**
 * The bundled third-party notices: every component's license and notice
 * texts, which the packages ship among Compose's app resources
 * (lib/app/resources, Contents/app/resources).
 */
internal object DesktopNotices {
    const val FILE_NAME = "Third-Party-Notices.txt"
    const val MIXBOX_LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/"

    /** The notices file, or null where no app resources exist (unit tests). */
    fun file(resourcesDir: String? = System.getProperty(COMPOSE_RESOURCES_PROPERTY)): File? =
        resourcesDir?.let { File(it, FILE_NAME) }?.takeIf(File::isFile)

    /** Opens [file] in the default viewer; false when the desktop cannot. */
    fun open(file: File): Boolean = withDesktop(Desktop.Action.OPEN) { it.open(file) }

    /** Opens [uri] in the default browser; false when the desktop cannot. */
    fun browse(uri: String): Boolean = withDesktop(Desktop.Action.BROWSE) { it.browse(URI(uri)) }

    private fun withDesktop(action: Desktop.Action, run: (Desktop) -> Unit): Boolean {
        val desktop = desktopOrNull()?.takeIf { it.isSupported(action) } ?: return false
        return try {
            run(desktop)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"
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
}

private fun desktopOrNull(): Desktop? = try {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
} catch (_: UnsupportedOperationException) {
    null
} catch (_: SecurityException) {
    null
}
