package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.Curve
import ch.lkmc.bangnidraw.engine.core.Jitter
import ch.lkmc.bangnidraw.engine.core.TiltEffect
import ch.lkmc.bangnidraw.engine.core.TipOrientation
import ch.lkmc.bangnidraw.engine.core.TipShape
import ch.lkmc.bangnidraw.engine.core.VelocityEffect
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `docs/plan/12-roadmap.md` step 5's pure preset-store contract. */
class BrushPresetStoreTest {

    private val root = createTempDirectory("bangni-brushes").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `every built-in asset parses and validates`() {
        val assets = File("src/main/assets/brushes")
        assertTrue(assets.isDirectory, "main brush assets must exist")
        val store = BrushPresetStore(root, DirectoryAssets(assets))

        val presets = store.load()

        assertEquals(
            setOf(
                "builtin.pencil",
                "builtin.ink_pen",
                "builtin.paintbrush",
                "builtin.airbrush",
                "builtin.marker",
                "builtin.hard_eraser",
                "builtin.soft_eraser",
            ),
            presets.mapTo(linkedSetOf()) { it.id },
        )
    }

    @Test
    fun `a preset round-trips every field`() {
        val store = BrushPresetStore(root, MapAssets())
        val expected = BrushPreset(
            id = "c1b565d8-1e72-4b38-88eb-f5c8bd6914be",
            name = "Charcoal",
            icon = "flat",
            size = 37f,
            sizeMin = 2f,
            sizeMax = 333f,
            opacity = 0.72f,
            flow = 0.34f,
            hardness = 0.61f,
            spacing = 0.17f,
            tip = TipShape.Flat(0.42f),
            orientation = TipOrientation.Stylus,
            pressureSize = Curve(0.2f, 0.3f, 0.7f, 1f),
            pressureOpacity = Curve(0.1f, 0.4f, 0.8f, 1f),
            pressureFlow = Curve(0.3f, 0.5f, 0.9f, 1f),
            tilt = TiltEffect(1.8f, 0.4f, elongate = true),
            velocity = VelocityEffect(0.8f, 0.7f, 2.5f),
            jitter = Jitter(0.12f, 0.23f),
            stabilizer = 0.44f,
            mixing = true,
            dilution = 0.19f,
            grain = "paper-fine",
            bufferMode = BufferMode.Accumulate,
        )

        store.save(expected)

        assertEquals(listOf(expected), store.load())
        assertTrue(File(root, "${expected.id}.json").readText().contains("\"v\": 1"))
    }

    @Test
    fun `unknown keys are ignored and missing keys use defaults`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "future.json" to
                    """{"v":1,"id":"future","name":"Future","future":{"n":7}}""",
            ),
        )

        val preset = store.load().single()

        assertEquals(12f, preset.size)
        assertEquals(TipShape.Round, preset.tip)
        assertEquals(BufferMode.Max, preset.bufferMode)
    }

    @Test
    fun `saving a built-in writes only an override and reset reveals the asset`() {
        val original = """{"v":1,"id":"builtin.pencil","name":"@string/preset_pencil","size":4}"""
        val assets = MapAssets("pencil.json" to original)
        val store = BrushPresetStore(root, assets)
        val builtIn = store.load().single()
        val edited = builtIn.copy(size = 8f)

        store.save(edited)

        assertEquals(original, assets.read("pencil.json"))
        assertEquals(8f, store.load().single().size)
        assertTrue(store.reset(builtIn.id))
        assertEquals(4f, store.load().single().size)
    }

    @Test
    fun `an invalid override is dropped without hiding its built-in`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "pencil.json" to
                    """{"v":1,"id":"builtin.pencil","name":"Pencil","size":4}""",
            ),
        )
        File(root, "builtin.pencil.json").writeText(
            """{"v":1,"id":"builtin.pencil","name":"Broken","hardness":2}""",
        )

        val loaded = store.load().single()

        assertEquals("Pencil", loaded.name)
        assertNotEquals("Broken", loaded.name)
    }

    @Test
    fun `newer formats and unsafe ids are dropped`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "new.json" to """{"v":2,"id":"new","name":"New"}""",
                "unsafe.json" to """{"v":1,"id":"../escape","name":"Unsafe"}""",
            ),
        )

        assertTrue(store.load().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            store.save(BrushPreset(id = "../escape", name = "Unsafe"))
        }
        assertFalse(File(root.parentFile, "escape.json").exists())
    }

    private class MapAssets(
        vararg entries: Pair<String, String>,
    ) : BrushPresetAssets {
        private val values = linkedMapOf(*entries)

        override fun names(): List<String> = values.keys.toList()

        override fun read(name: String): String = values.getValue(name)
    }

    private class DirectoryAssets(
        private val directory: File,
    ) : BrushPresetAssets {
        override fun names(): List<String> =
            directory.listFiles().orEmpty().filter { it.extension == "json" }.map { it.name }.sorted()

        override fun read(name: String): String = File(directory, name).readText()
    }
}
