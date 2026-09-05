package ch.lkmc.bangnidraw.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import java.io.File

/**
 * One open painting and the window showing it.
 *
 * The desktop shell is document-based: a painting is a file, several can be
 * open at once, and closing one with unsaved work asks first. Each document
 * owns its own engine and its own [DesktopShellState]; they share the one GL
 * thread and the one preferences store.
 */
@Stable
internal class DesktopDocument(
    val id: Long,
    val canvas: CanvasSize,
    val engine: DesktopEngine,
    val state: DesktopShellState,
    file: File?,
    /** Kept across saves so a document keeps one creation date in its file. */
    val createdAt: Long,
) {
    /** Where Save writes without asking; null until the first Save As. */
    var file by mutableStateOf(file)

    /** Set by any edit, cleared by a successful save. */
    var dirty by mutableStateOf(false)

    /** Raised to open the "close without saving?" prompt for this window. */
    var confirmingClose by mutableStateOf(false)

    val frame = mutableStateOf<DesktopEngine.Frame?>(null)

    /** The window title: the file's name, or "Untitled" before the first save. */
    val title: String
        get() {
            val name = file?.name ?: DesktopStrings.get("desktop_untitled")
            val mark = if (dirty) DIRTY_MARK else ""
            return "$name$mark — " + DesktopBrand.displayName
        }

    private companion object {
        /** The unsaved-changes mark every document window on these platforms wears. */
        const val DIRTY_MARK = " •"
    }
}

/**
 * Every open document, and the one place they are created and closed.
 *
 * Compose Desktop renders one `Window` per entry, so adding to [open] opens a
 * window and removing from it closes one. The application exits when the last
 * document goes.
 */
@Stable
internal class DesktopDocuments(
    private val memory: DeviceMemory,
    private val host: DesktopGlHost,
    private val catalogue: List<ch.lkmc.bangnidraw.engine.core.BrushPreset>,
    private val mixer: ColorMixer,
    private val prefs: DesktopPrefs,
) {
    val open = mutableStateListOf<DesktopDocument>()

    private var nextId = 1L

    /** A new empty painting at the default canvas size. */
    fun create(canvas: CanvasSize): DesktopDocument = add(canvas, file = null, initial = null)

    /**
     * The painting in [file], as a document of its own size. Returns the
     * failure message when the file cannot be read; nothing is opened then.
     */
    fun openFile(file: File): String? {
        // Reopening a file that is already open does not open it twice: two
        // documents on one path would overwrite each other on save. Canonical
        // rather than absolute, so a symlink or a `..` cannot slip past the
        // check and produce that pair anyway. (Raising the existing window
        // would be the friendlier answer and is not implemented yet; nothing
        // visible happens today.)
        val key = file.canonicalOrAbsolute()
        val existing = open.firstOrNull { it.file?.canonicalOrAbsolute() == key }
        if (existing != null) return null

        return when (val result = DesktopDocumentIo.read(file)) {
            is DesktopOpenResult.Failed -> result.message
            is DesktopOpenResult.Opened -> {
                val document = add(result.content.canvas, file = file, initial = result.content)
                // What the file carried that this build skipped. Shown once,
                // in the strip, rather than as a dialog nobody reads: the
                // painting did open.
                if (result.warnings.isNotEmpty()) {
                    document.state.savedMessage = result.warnings.first()
                }
                null
            }
        }
    }

    /**
     * The canonical path, or the absolute one when the file system refuses to
     * resolve it — a path that cannot be canonicalized still has to compare
     * as *something*, or an unreadable directory would let a file open twice.
     */
    private fun File.canonicalOrAbsolute(): File =
        try {
            canonicalFile
        } catch (_: java.io.IOException) {
            absoluteFile
        }

    fun close(document: DesktopDocument) {
        if (!open.remove(document)) return

        document.engine.stopAndJoin()
    }

    private fun add(
        canvas: CanvasSize,
        file: File?,
        initial: DesktopInitialContent?,
    ): DesktopDocument {
        // Two-step, because the engine's publish callbacks close over the
        // document they belong to and the document holds the engine.
        lateinit var document: DesktopDocument
        val engine = DesktopEngine(
            canvas = canvas,
            memory = memory,
            host = host,
            onFrame = { frame ->
                java.awt.EventQueue.invokeLater { document.frame.value = frame }
            },
            // Both arrive on the GL thread, from the same call sites as
            // `onEdited` above — publishing Compose state from there races the
            // recomposition reading it in the panels.
            onStack = { stack ->
                java.awt.EventQueue.invokeLater { document.state.publishStack(stack) }
            },
            onPaper = { argb ->
                java.awt.EventQueue.invokeLater { document.state.publishPaper(argb) }
            },
            onEdited = { java.awt.EventQueue.invokeLater { document.dirty = true } },
            initial = initial,
        )
        document = DesktopDocument(
            id = nextId++,
            canvas = canvas,
            engine = engine,
            state = DesktopShellState(engine, catalogue, mixer, prefs),
            file = file,
            createdAt = initial?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        open += document
        // The pixels are the engine's to place — it does that as it builds
        // its renderer, so nothing here races that. This only tells the
        // shell what the document holds, for the panel and the next save.
        initial?.reference?.let { reference ->
            document.state.restoreReference(reference, initial.referencePng ?: ByteArray(0))
        }
        engine.start()
        return document
    }

    /** Frees every document; the host's own thread is stopped separately. */
    fun closeAll() {
        for (document in open) document.engine.stopAndJoin()
        open.clear()
    }
}
