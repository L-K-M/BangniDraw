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
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        lastGallerySyncAt = 1_755_500_000_000L,
        galleryModifiedAt = 1_755_500_000L,
        galleryBytes = 123_456L,
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
    fun `relisting finds tiles for a layer recovered from history`() {
        val id = "recovered"
        val added = Layer(LayerProps(LayerId("added-after-checkpoint"), "Added"))
        val recovered = document(
            id = id,
            stack = LayerStack(listOf(added), activeIndex = 0, nextName = 2),
        )
        TileStore(store.layerDir(id, added.id))
            .write(TileKey(1, 1), ByteArray(TILE_BYTES) { 1 })

        val relisted = store.relistTiles(recovered)

        assertEquals(setOf(TileKey(1, 1)), relisted.stack.layers.single().tiles)
    }

    @Test
    fun `a version one fixture migrates on its defaults`() {
        val dir = store.projectDir("p-2").also { it.mkdirs() }
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/fixtures/projects/v1/project.json"),
        ).bufferedReader().use { it.readText() }
        val file = File(dir, "project.json")
        file.writeText(fixture)

        assertEquals(2, ProjectFile.FORMAT_VERSION)

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
        assertEquals(null, loaded.history.seqs)

        store.checkpoint(doc, loaded.history)
        assertTrue(file.readText().contains("\"formatVersion\":2"))
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
    fun `the recovered journal's names feed the nextName floor`() {
        // The replay half of the nextName obligation (roadmap 3b): a
        // crash-recovered journal can carry default names the checkpoint
        // never saw — even on layers a later entry deletes — and the scan
        // must see every record an entry embeds.
        val a = ch.lkmc.bangnidraw.engine.core.HistoryEntry.LayerAdd(
            activeBefore = LayerId("x"), activeAfter = LayerId("y"),
            layer = ch.lkmc.bangnidraw.engine.core.LayerRecord(
                id = "y", name = "@string/layer_default 9",
            ),
            index = 1,
        )
        val d = ch.lkmc.bangnidraw.engine.core.HistoryEntry.LayerDelete(
            activeBefore = LayerId("y"), activeAfter = LayerId("x"),
            layer = ch.lkmc.bangnidraw.engine.core.LayerRecord(
                id = "y", name = "@string/layer_default 12",
            ),
            index = 1, tiles = emptyList(),
        )
        assertEquals(12, highestDefaultNameIn(listOf(a, d)))
        assertEquals(0, highestDefaultNameIn(emptyList()))
        // User-typed names carry no number to honour.
        assertEquals(
            0,
            highestDefaultNameIn(
                listOf(a.copy(layer = a.layer.copy(name = "my layer 7"))),
            ),
        )
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
        val newerVersion = ProjectFile.FORMAT_VERSION + 1
        File(dir, "project.json").writeText(
            """{"formatVersion":$newerVersion,"id":"p-7","width":512,"height":512,
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

    // ------------------------------------------------------------------
    // The four load obligations carried in from PR #7's reviews
    // (docs/plan/12-roadmap.md step 3; REVIEW.md R-001, R-029, R-020).
    // ------------------------------------------------------------------

    @Test
    fun `a malformed layer id drops that layer, counted, and the open survives`() {
        // R-001's policy half. The id would name layers/<id>/, so no path may
        // ever be built from it; the layer has no degraded value (unlike a
        // tile) and is dropped whole — but one bad record must not throw the
        // whole painting away.
        val dir = store.projectDir("r001").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":1,"id":"r001","width":512,"height":512,
               "layers":[{"id":"../../evil","name":"escape"},
                         {"id":"good","name":"survivor"}],
               "activeLayerId":"../../evil"}""",
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("r001"))
        assertEquals(1, loaded.unreadableLayers)
        assertEquals(listOf("good"), loaded.document.stack.layers.map { it.id.value })
        // The active id named the dropped layer; selection degrades, the open
        // does not.
        assertEquals(0, loaded.document.stack.activeIndex)
    }

    @Test
    fun `a case-insensitive layer-id collision degrades on load instead of throwing`() {
        // R-029. LayerStack refuses the pair at construction — right for code
        // building a stack — but a document copied through a case-folding
        // filesystem arrives this way and must open, with the colliding layer
        // counted among the unreadable.
        val dir = store.projectDir("r029").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":1,"id":"r029","width":512,"height":512,
               "layers":[{"id":"Layer-A","name":"first"},
                         {"id":"layer-a","name":"claimant"},
                         {"id":"other","name":"untouched"}],
               "activeLayerId":"other"}""",
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("r029"))
        assertEquals(1, loaded.unreadableLayers)
        assertEquals(
            listOf("Layer-A", "other"),
            loaded.document.stack.layers.map { it.id.value },
            "the first claimant keeps the folded id",
        )
        assertEquals(1, loaded.document.stack.activeIndex)
    }

    @Test
    fun `a NaN opacity token decodes and degrades instead of failing the open`() {
        // R-020. kotlinx's default decoder throws on the token itself, before
        // LayerRecord's degrading toProps is ever reached — the loader's Json
        // must let it through so §4's one-bad-field rule can hold. A NaN
        // opacity degrades to fully visible (LayerProps.sanitizeOpacity):
        // a layer that vanished would read as lost work.
        val dir = store.projectDir("r020").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":1,"id":"r020","width":512,"height":512,
               "layers":[{"id":"a","name":"n","opacity":NaN},
                         {"id":"b","name":"m","opacity":-Infinity}],
               "activeLayerId":"a"}""",
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("r020"))
        assertEquals(0, loaded.unreadableLayers)
        assertEquals(1f, loaded.document.stack.layers[0].props.opacity)
        assertEquals(1f, loaded.document.stack.layers[1].props.opacity)
    }

    @Test
    fun `a document with no readable layer left is corrupt, not partially readable`() {
        val dir = store.projectDir("all-bad").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":1,"id":"all-bad","width":512,"height":512,
               "layers":[{"id":"..","name":"a"},{"id":"x/y","name":"b"}],
               "activeLayerId":".."}""",
        )
        val result = assertIs<ProjectStore.LoadResult.Failed>(store.load("all-bad"))
        assertEquals(ProjectStore.FailureReason.UNREADABLE, result.reason)
    }

    @Test
    fun `create refuses an id that already names a painting`() {
        store.create(document(id = "fresh"))
        assertIs<ProjectStore.LoadResult.Loaded>(store.load("fresh"))
        assertFailsWith<IllegalArgumentException> { store.create(document(id = "fresh")) }
    }

    @Test
    fun `rename moves title and updatedAt and nothing else`() {
        val doc = document(id = "r-1", updatedAt = 100)
        store.checkpoint(doc)
        assertTrue(store.rename("r-1", "Harbour", now = 5_000))
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("r-1"))
        assertEquals("Harbour", loaded.document.title)
        assertEquals(5_000, loaded.document.updatedAt)
        assertEquals(doc.copy(title = "Harbour", updatedAt = 5_000), loaded.document)
    }

    @Test
    fun `gallery sync outcomes persist without moving updatedAt`() {
        val doc = document(id = "g-1", updatedAt = 100)
        store.checkpoint(doc)
        assertTrue(
            store.updateGalleryFields(
                "g-1",
                galleryUri = "content://media/9",
                lastGallerySyncAt = 2_000,
                galleryModifiedAt = 2,
                galleryBytes = 42,
            ),
        )
        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("g-1"))
        assertEquals("content://media/9", loaded.document.galleryUri)
        assertEquals(2_000, loaded.document.lastGallerySyncAt)
        assertEquals(2, loaded.document.galleryModifiedAt)
        assertEquals(42, loaded.document.galleryBytes)
        // A sync is looking, not painting: "edited 5 min ago" must not move.
        assertEquals(100, loaded.document.updatedAt)
    }

    @Test
    fun `metadata writers migrate an older project format`() {
        val renameFile = legacyProject("legacy-rename")
        assertTrue(store.rename("legacy-rename", "new title", now = 200))
        assertCurrentFormat(renameFile)

        val galleryFile = legacyProject("legacy-gallery")
        assertTrue(
            store.updateGalleryFields(
                "legacy-gallery",
                galleryUri = "content://media/legacy",
                lastGallerySyncAt = 300,
                galleryModifiedAt = 3,
                galleryBytes = 30,
            ),
        )
        assertCurrentFormat(galleryFile)

        legacyProject("legacy-duplicate")
        val duplicateId = store.duplicate("legacy-duplicate", titleTransform = { it })
        kotlin.test.assertNotNull(duplicateId)
        assertCurrentFormat(File(store.projectDir(duplicateId), ProjectFile.FILE_NAME))
    }

    @Test
    fun `metadata writers refuse a newer project without rewriting it`() {
        val renameFile = futureProject("future-rename")
        val renameBytes = renameFile.readBytes()
        assertTrue(!store.rename("future-rename", "new title", now = 200))
        assertTrue(renameBytes.contentEquals(renameFile.readBytes()))

        val galleryFile = futureProject("future-gallery")
        val galleryBytes = galleryFile.readBytes()
        assertTrue(
            !store.updateGalleryFields(
                "future-gallery",
                galleryUri = "content://media/future",
                lastGallerySyncAt = 300,
                galleryModifiedAt = 3,
                galleryBytes = 30,
            ),
        )
        assertTrue(galleryBytes.contentEquals(galleryFile.readBytes()))

        val duplicateFile = futureProject("future-duplicate")
        val duplicateBytes = duplicateFile.readBytes()
        assertEquals(null, store.duplicate("future-duplicate", titleTransform = { it }))
        assertTrue(duplicateBytes.contentEquals(duplicateFile.readBytes()))
    }

    @Test
    fun `rename of an unreadable painting is refused, not a rewrite`() {
        val dir = store.projectDir("r-2").also { it.mkdirs() }
        val file = File(dir, "project.json")
        file.writeText("{ nope")
        assertTrue(!store.rename("r-2", "x"))
        assertEquals("{ nope", file.readText(), "never silently replaced")
    }

    @Test
    fun `checkpoint preserves exact history sequence membership`() {
        val history = HistoryRecord(
            cursor = 2,
            nextSeq = 8,
            oldestSeq = 2,
            entries = 3,
            bytes = 42,
            seqs = listOf(2, 6, 7),
        )

        store.checkpoint(document(id = "history-membership"), history)

        val loaded = assertIs<ProjectStore.LoadResult.Loaded>(store.load("history-membership"))
        assertEquals(history, loaded.history)
    }

    @Test
    fun `duplicate copies tiles but not history and gets a fresh id, remapped layer ids and no gallery URI`() {
        // 06 §8, `docs/plan/11-testing.md` §5's exact case.
        val doc = document(id = "src")
        store.checkpoint(doc, HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1, entries = 1))
        val srcTiles = TileStore(store.layerDir("src", LayerId("layer-b")))
        val pixels = ByteArray(TILE_BYTES) { 5 }
        srcTiles.write(TileKey(0, 1), pixels)
        HistoryStore(File(store.projectDir("src"), "history")).append(
            ch.lkmc.bangnidraw.engine.core.HistoryEntry.PaperColor(
                activeBefore = LayerId("layer-b"), activeAfter = LayerId("layer-b"),
                before = 0, after = 1,
            ),
            seq = 1, ts = 1, payloads = emptyList(),
        )

        val newId = store.duplicate("src", { it + " copy" }, now = 9_000)
        kotlin.test.assertNotNull(newId)
        kotlin.test.assertNotEquals("src", newId)

        val copy = assertIs<ProjectStore.LoadResult.Loaded>(store.load(newId)).document
        assertEquals("Cat study copy", copy.title)
        assertEquals(9_000, copy.createdAt)
        assertEquals(9_000, copy.updatedAt)
        assertEquals(null, copy.galleryUri)
        assertEquals(0, copy.lastGallerySyncAt)
        assertEquals(0, copy.historyCursor)
        assertEquals(HistoryRecord(), assertIs<ProjectStore.LoadResult.Loaded>(store.load(newId)).history)
        assertTrue(!File(store.projectDir(newId), "history").exists(), "history is not copied")

        // Fresh ids everywhere, and the tile travelled to the remapped dir.
        val srcIds = doc.stack.layers.map { it.id.value }.toSet()
        val copyIds = copy.stack.layers.map { it.id.value }.toSet()
        assertTrue(copyIds.intersect(srcIds).isEmpty(), "every layer id is remapped")
        assertEquals(copy.stack.layers[1].id, copy.stack.active.id, "active follows the remap")
        val movedTile = copy.stack.layers[1].tiles
        assertEquals(setOf(TileKey(0, 1)), movedTile)
        val read = TileStore(store.layerDir(newId, copy.stack.layers[1].id)).read(TileKey(0, 1))
        assertEquals(TileStore.Read.Pixels(pixels), read)
        // The source is untouched.
        assertIs<ProjectStore.LoadResult.Loaded>(store.load("src"))
        assertTrue(srcTiles.read(TileKey(0, 1)) is TileStore.Read.Pixels)
        assertTrue(
            root.listFiles().orEmpty().none {
                it.name.endsWith(ProjectStore.DUPLICATING_SUFFIX)
            },
        )
    }

    @Test
    fun `failed duplicate leaves no hidden project folder`() {
        store.checkpoint(document(id = "src"))
        TileStore(store.layerDir("src", LayerId("layer-b")))
            .write(TileKey(0, 0), ByteArray(TILE_BYTES) { 7 })
        val before = root.listFiles().orEmpty().map(File::getName).toSet()
        val failingStore = ProjectStore(
            root,
            DuplicateFileWriter { _, _ -> throw IOException("disk full") },
        )

        assertNull(failingStore.duplicate("src", { "$it copy" }))
        assertEquals(before, root.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test
    fun `abandoned duplicate stage is swept on restart`() {
        val stage = File(
            root,
            "00000000-0000-0000-0000-000000000001${ProjectStore.DUPLICATING_SUFFIX}",
        )
        assertTrue(stage.mkdirs())
        File(stage, "orphan").writeText("partial")

        ProjectStore(root).list()

        assertTrue(!stage.exists())
    }

    @Test
    fun `active duplicate stage survives listing from another store`() {
        store.checkpoint(document(id = "src"))
        TileStore(store.layerDir("src", LayerId("layer-b")))
            .write(TileKey(0, 0), ByteArray(TILE_BYTES) { 7 })
        var listed = false
        val listingStore = ProjectStore(
            root,
            DuplicateFileWriter { source, target ->
                if (!listed) {
                    listed = true
                    val stage = root.listFiles().orEmpty().single {
                        it.name.endsWith(ProjectStore.DUPLICATING_SUFFIX)
                    }
                    store.list()
                    assertTrue(stage.exists(), "another store must spare an active stage")
                }
                source.copyTo(target)
            },
        )

        val newId = listingStore.duplicate("src", { "$it copy" })

        kotlin.test.assertNotNull(newId)
        assertIs<ProjectStore.LoadResult.Loaded>(listingStore.load(newId))
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

    private fun legacyProject(id: String): File {
        store.checkpoint(document(id = id))
        val file = File(store.projectDir(id), ProjectFile.FILE_NAME)
        file.writeText(
            file.readText().replace(
                "\"formatVersion\":${ProjectFile.FORMAT_VERSION}",
                "\"formatVersion\":${ProjectFile.FORMAT_VERSION - 1}",
            ),
        )

        return file
    }

    private fun futureProject(id: String): File {
        store.checkpoint(document(id = id))
        val file = File(store.projectDir(id), ProjectFile.FILE_NAME)
        file.writeText(
            file.readText().replace(
                "\"formatVersion\":${ProjectFile.FORMAT_VERSION}",
                "\"formatVersion\":${ProjectFile.FORMAT_VERSION + 1}",
            ) + "\n",
        )

        return file
    }

    private fun assertCurrentFormat(file: File) {
        assertTrue(file.readText().contains("\"formatVersion\":${ProjectFile.FORMAT_VERSION}"))
    }
}
