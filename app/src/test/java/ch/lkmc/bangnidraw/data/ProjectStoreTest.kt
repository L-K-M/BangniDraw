package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §5's `ProjectStoreTest`, on a JVM temp dir. */
class ProjectStoreTest {

    private val root = createTempDirectory("bangni-projects").toFile()
    private val store = ProjectStore(root)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun document(
        id: String = "p-1",
        title: String = "Cat study",
        updatedAt: Long = 1_756_000_000_000L,
        stack: LayerStack = LayerStack(
            layers = listOf(
                Layer(
                    LayerProps(
                        id = LayerId("layer-a"),
                        name = "@string/layer_default 1",
                        visible = false,
                        opacity = 0.25f,
                        blendMode = BlendMode.MULTIPLY,
                        alphaLock = true,
                        locked = true,
                    ),
                ),
                Layer(LayerProps(id = LayerId("layer-b"), name = "inks")),
            ),
            activeIndex = 1,
            nextName = 4,
        ),
    ) = Document(
        id = id,
        title = title,
        width = 512,
        height = 768,
        dpi = 300,
        paperColor = 0x00FF8800,
        stack = stack,
        historyCursor = 3,
        galleryUri = "content://media/external/images/42",
        createdAt = 1_755_000_000_000L,
        updatedAt = updatedAt,
    )

    @Test
    fun `a full document round-trips through project json`() {
        val doc = document()
        store.checkpoint(doc)
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-1"))
        assertEquals(0, loaded.unreadableLayers)
        assertEquals(doc, loaded.document)
    }

    @Test
    fun `layer tile sets come from the directory listing, not the json`() {
        val doc = document()
        store.checkpoint(doc)
        val layerDir = store.layerDir("p-1", LayerId("layer-b"))
        TileStore(layerDir).write(TileKey(1, 2), ByteArray(TILE_BYTES) { 1 })
        // A key outside the 512×768 canvas (2×3 tiles) is a stray file, not a
        // tile of this painting.
        TileStore(layerDir).write(TileKey(30, 30), ByteArray(TILE_BYTES) { 1 })
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-1"))
        assertEquals(
            setOf(TileKey(1, 2)),
            loaded.document.stack.layers[1].tiles,
        )
    }

    @Test
    fun `a file from an older version loads on its defaults`() {
        val dir = store.projectDir("p-2").also { it.mkdirs() }
        // A minimal file, as an older writer without the newer fields would
        // leave it — no nextLayerName, no gallery fields, no view.
        File(dir, "project.json").writeText(
            """{"formatVersion":1,"id":"p-2","createdAt":1,"updatedAt":2,
               "width":512,"height":512,"paperColor":-1,
               "layers":[{"id":"a","name":"@string/layer_default 7"}],
               "activeLayerId":"a"}""",
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-2"))
        val doc = loaded.document
        assertEquals("", doc.title)
        assertEquals(Document.DEFAULT_DPI, doc.dpi)
        assertEquals(1f, doc.stack.layers[0].props.opacity)
        assertEquals(BlendMode.NORMAL, doc.stack.layers[0].props.blendMode)
        // The counter floor: no persisted field, so one past the highest
        // default name — "Layer 7" exists, so the next is 8, and reopening
        // can never reissue a name already on a layer (AGENTS.md).
        assertEquals(8, doc.stack.nextName)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val doc = document(id = "p-3")
        store.checkpoint(doc)
        val file = File(store.projectDir("p-3"), "project.json")
        file.writeText(
            file.readText().removeSuffix("}") + ""","futureFeature":{"nested":[1,2,3]}}""",
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-3"))
        assertEquals(doc, loaded.document)
    }

    @Test
    fun `the persisted nextName survives a reopen even past the name scan`() {
        // nextName 4 while the highest default name is 1: the persisted value
        // must win, or add → undo → reopen would reissue "Layer 2" and "Layer
        // 3" (the persistence half of the AGENTS.md obligation; replay
        // re-derivation is 3b's).
        store.checkpoint(document(id = "p-4"))
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-4"))
        assertEquals(4, loaded.document.stack.nextName)
    }

    @Test
    fun `project ids are the only thing that names a folder`() {
        val doc = document(id = "p-5")
        store.checkpoint(doc)
        // Hand-edit the embedded id: the folder must win (06 §3), because the
        // folder name is what every path was derived from.
        val file = File(store.projectDir("p-5"), "project.json")
        file.writeText(file.readText().replace("\"id\":\"p-5\"", "\"id\":\"other\""))
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-5"))
        assertEquals("p-5", loaded.document.id)
    }

    @Test
    fun `an id that is not a safe path segment is refused before any path is built`() {
        val result = assertIs<ProjectStore.LoadResult.Failed>(store.load("../escape"))
        assertEquals(ProjectStore.FailureReason.BAD_ID, result.reason)
    }

    @Test
    fun `a missing folder is NOT_FOUND, not an error`() {
        val result = assertIs<ProjectStore.LoadResult.Failed>(store.load("nope"))
        assertEquals(ProjectStore.FailureReason.NOT_FOUND, result.reason)
    }

    @Test
    fun `a project json that fails to parse is reported, never silently replaced`() {
        val dir = store.projectDir("p-6").also { it.mkdirs() }
        val file = File(dir, "project.json")
        file.writeText("{ this is not json")
        val result = assertIs<ProjectStore.LoadResult.Failed>(store.load("p-6"))
        assertEquals(ProjectStore.FailureReason.UNREADABLE, result.reason)
        // Untouched: we do not overwrite a user's painting with an empty one.
        assertEquals("{ this is not json", file.readText())
    }

    @Test
    fun `a file from a newer format version is refused, not rewritten`() {
        val dir = store.projectDir("p-7").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":2,"id":"p-7","width":512,"height":512,
               "layers":[{"id":"a","name":"n"}],"activeLayerId":"a"}""",
        )
        val result = assertIs<ProjectStore.LoadResult.Failed>(store.load("p-7"))
        assertEquals(ProjectStore.FailureReason.NEWER_VERSION, result.reason)
    }

    @Test
    fun `a leftover tmp file is ignored and cleaned up`() {
        val doc = document(id = "p-8")
        store.checkpoint(doc)
        val dir = store.projectDir("p-8")
        File(dir, "project.json.tmp").writeText("torn half-write")
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("p-8"))
        assertEquals(doc, loaded.document)
        assertTrue(!File(dir, "project.json.tmp").exists())
    }

    @Test
    fun `list orders by last-edited, newest first`() {
        store.checkpoint(document(id = "old", title = "old", updatedAt = 100))
        store.checkpoint(document(id = "new", title = "new", updatedAt = 300))
        store.checkpoint(document(id = "mid", title = "mid", updatedAt = 200))
        // Not paintings: a folder without project.json, an unparseable one,
        // and a half-deleted leftover — skipped (the first two) or swept.
        File(root, "empty-folder").mkdirs()
        store.projectDir("broken").also { it.mkdirs() }
            .let { File(it, "project.json") }.writeText("nope")
        File(root, "gone.deleting").also { it.mkdirs() }
            .let { File(it, "project.json") }.writeText("{}")

        val listed = store.list()
        assertEquals(listOf("new", "mid", "old"), listed.map { it.id })
        assertEquals(2, listed[0].layerCount)
        assertEquals(512, listed[0].width)
        assertEquals(768, listed[0].height)
        assertTrue(!File(root, "gone.deleting").exists(), "deleting leftovers are swept")
    }

    @Test
    fun `delete removes the folder and nothing else`() {
        store.checkpoint(document(id = "keep"))
        store.checkpoint(document(id = "kill"))
        TileStore(store.layerDir("kill", LayerId("layer-a")))
            .write(TileKey(0, 0), ByteArray(TILE_BYTES) { 9 })
        store.delete("kill")
        assertTrue(!store.projectDir("kill").exists())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".deleting") })
        assertIs<ProjectStore.LoadResult.Loaded>(store.load("keep"))
    }
}
