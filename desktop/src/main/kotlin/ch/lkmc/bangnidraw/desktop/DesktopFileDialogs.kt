package ch.lkmc.bangnidraw.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The host operating system's own open and save dialogs.
 *
 * `java.awt.FileDialog`, not Swing's `JFileChooser`: on macOS it is the real
 * Cocoa panel — the one a Mac user expects, with their sidebar, their recent
 * places and their keyboard shortcuts — and on Windows it is the native
 * common dialog. `JFileChooser` is Swing's own drawing on every platform.
 *
 * These block the event thread while the dialog is up, which is what a modal
 * file dialog is; AWT keeps pumping events underneath.
 */
internal object DesktopFileDialogs {

    fun open(parent: Frame?): File? = show(
        parent = parent,
        title = "Open",
        mode = FileDialog.LOAD,
        suggestedName = null,
        extensions = DesktopDocumentIo.OPENABLE_EXTENSIONS,
    )

    /** A picture to trace over; the formats ImageIO reads on every host. */
    fun openImage(parent: Frame?): File? = show(
        parent = parent,
        title = "Open",
        mode = FileDialog.LOAD,
        suggestedName = null,
        extensions = DesktopReferenceIo.EXTENSIONS,
    )

    /**
     * A save target with an extension this app can actually write.
     *
     * A user who typed `sketch` means `sketch.<default>`; one who typed
     * `sketch.png` means PNG and must not get `sketch.png.bangni`. Any other
     * extension is a typo more often than a request, so [default] is appended
     * rather than trusted.
     */
    fun save(parent: Frame?, suggestedName: String, default: String): File? {
        val chosen = show(
            parent = parent,
            title = "Save As",
            mode = FileDialog.SAVE,
            suggestedName = suggestedName,
            extensions = WRITABLE_EXTENSIONS,
        ) ?: return null

        if (WRITABLE_EXTENSIONS.any { chosen.extension.equals(it, ignoreCase = true) }) return chosen

        return File(chosen.parentFile, chosen.name + "." + default)
    }

    private fun show(
        parent: Frame?,
        title: String,
        mode: Int,
        suggestedName: String?,
        extensions: List<String>,
    ): File? {
        val dialog = FileDialog(parent, title, mode)
        if (suggestedName != null) dialog.file = suggestedName
        // A filter is advisory on some platforms and ignored on others, so
        // the reader still validates whatever comes back.
        dialog.setFilenameFilter { _, name ->
            extensions.any { name.endsWith(".$it", ignoreCase = true) }
        }
        dialog.isVisible = true

        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(directory, file)
    }

    /** The two formats Save As offers: the document, and flat interchange. */
    private val WRITABLE_EXTENSIONS =
        listOf(ch.lkmc.bangnidraw.data.shared.BangniCodec.EXTENSION, DesktopImageIo.EXTENSION)
}
