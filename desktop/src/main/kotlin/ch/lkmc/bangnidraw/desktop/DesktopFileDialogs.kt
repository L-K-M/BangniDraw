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

    fun open(parent: Frame?): File? = show(parent, "Open", FileDialog.LOAD, suggestedName = null)

    fun save(parent: Frame?, suggestedName: String): File? {
        val chosen = show(parent, "Save As", FileDialog.SAVE, suggestedName) ?: return null

        // A user who typed "sketch" means sketch.png; one who typed the
        // extension already must not get "sketch.png.png".
        return if (chosen.extension.equals(DesktopImageIo.EXTENSION, ignoreCase = true)) {
            chosen
        } else {
            File(chosen.parentFile, chosen.name + "." + DesktopImageIo.EXTENSION)
        }
    }

    private fun show(parent: Frame?, title: String, mode: Int, suggestedName: String?): File? {
        val dialog = FileDialog(parent, title, mode)
        if (suggestedName != null) dialog.file = suggestedName
        // A filter is advisory on some platforms and ignored on others, so
        // `read` still validates whatever comes back.
        dialog.setFilenameFilter { _, name ->
            name.endsWith("." + DesktopImageIo.EXTENSION, ignoreCase = true)
        }
        dialog.isVisible = true

        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(directory, file)
    }
}
