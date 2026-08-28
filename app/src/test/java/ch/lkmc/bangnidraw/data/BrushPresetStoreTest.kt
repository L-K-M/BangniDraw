package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BrushModel
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.Curve
import ch.lkmc.bangnidraw.engine.core.GrainMode
import ch.lkmc.bangnidraw.engine.core.Jitter
import ch.lkmc.bangnidraw.engine.core.TiltEffect
import ch.lkmc.bangnidraw.engine.core.TipOrientation
import ch.lkmc.bangnidraw.engine.core.TipShape
import ch.lkmc.bangnidraw.engine.core.VelocityEffect
import ch.lkmc.bangnidraw.engine.core.WatercolorBehavior
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
                "builtin.spray_can",
                "builtin.marker",
                "builtin.charcoal",
                "builtin.soft_pastel",
                "builtin.technical_pen",
                "builtin.calligraphy",
                "builtin.dry_brush",
                "builtin.oil_paint",
                "builtin.pigment_wash",
                "builtin.hard_eraser",
                "builtin.soft_eraser",
            ),
            presets.mapTo(linkedSetOf()) { it.id },
        )
        assertEquals(
            GrainMode.Procedural,
            presets.single { it.id == "builtin.pencil" }.grainMode,
        )
        assertEquals(
            WatercolorBehavior(
                waterLoad = 0.72f,
                spread = 0.6f,
                granulation = 0.32f,
                edgeDarkening = 0.4f,
            ),
            presets.single { it.id == "builtin.paintbrush" }.watercolor,
        )
        assertEquals(
            BrushModel.ChineseInk,
            presets.single { it.id == BrushPresets.CALLIGRAPHY_ID }.model,
        )
        for (id in listOf("builtin.charcoal", "builtin.soft_pastel", "builtin.dry_brush")) {
            assertEquals(GrainMode.Procedural, presets.single { it.id == id }.grainMode, id)
        }
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
            opacity = 1f,
            flow = 0.34f,
            hardness = 0.61f,
            spacing = 0.17f,
            tip = TipShape.Flat(0.42f),
            orientation = TipOrientation.Stylus,
            pressureSize = Curve(0.2f, 0.3f, 0.7f, 1f),
            pressureOpacity = Curve.One,
            pressureFlow = Curve(0.3f, 0.5f, 0.9f, 1f),
            tilt = TiltEffect(1.8f, 0.4f, elongate = true),
            velocity = VelocityEffect(0.8f, 0.7f, 2.5f),
            jitter = Jitter(0.12f, 0.23f),
            stabilizer = 0.44f,
            mixing = true,
            dilution = 0.19f,
            grain = "paper-fine",
            model = BrushModel.ChineseInk,
            bufferMode = BufferMode.Accumulate,
            watercolor = WatercolorBehavior(
                waterLoad = 0.7f,
                spread = 0.6f,
                granulation = 0.3f,
                edgeDarkening = 0.4f,
            ),
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
        assertEquals(BrushModel.Standard, preset.model)
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
    fun `legacy paintbrush override adopts watercolor while keeping user tuning`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "paintbrush.json" to
                    """{"v":1,"id":"builtin.paintbrush","name":"Watercolor","icon":"watercolor","size":48,"sizeMax":400,"opacity":1,"flow":0.45,"mixing":true,"bufferMode":"Accumulate","watercolor":{"waterLoad":0.72,"spread":0.6,"granulation":0.32,"edgeDarkening":0.4}}""",
            ),
        )
        File(root, "builtin.paintbrush.json").writeText(
            """{"v":1,"id":"builtin.paintbrush","name":"Paintbrush","icon":"paintbrush","size":31,"opacity":0.37,"flow":0.26,"mixing":false,"bufferMode":"Max"}""",
        )

        val migrated = store.load().single()

        assertEquals(31f, migrated.size)
        assertEquals(0.26f, migrated.flow)
        assertEquals(1f, migrated.opacity)
        assertEquals(Curve.One, migrated.pressureOpacity)
        assertEquals("watercolor", migrated.icon)
        assertEquals(true, migrated.mixing)
        assertEquals(BufferMode.Accumulate, migrated.bufferMode)
        assertEquals(
            WatercolorBehavior(
                waterLoad = 0.72f,
                spread = 0.6f,
                granulation = 0.32f,
                edgeDarkening = 0.4f,
            ),
            migrated.watercolor,
        )
        assertTrue(File(root, "builtin.paintbrush.json").readText().contains("\"watercolor\""))
        assertEquals(migrated, store.load().single())
    }

    @Test
    fun `a legacy calligraphy override gains the Chinese ink model without losing edits`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "calligraphy.json" to
                    """{"v":1,"id":"builtin.calligraphy","name":"Chinese ink","model":"ChineseInk"}""",
            ),
        )
        File(root, "builtin.calligraphy.json").writeText(
            """{"v":1,"id":"builtin.calligraphy","name":"My brush","size":73,"hardness":0.41}""",
        )

        val loaded = store.load().single()

        assertEquals(BrushModel.ChineseInk, loaded.model)
        assertEquals("My brush", loaded.name)
        assertEquals(73f, loaded.size)
        assertEquals(0.41f, loaded.hardness)
    }

    @Test
    fun `an explicit calligraphy model is not migrated`() {
        val store = BrushPresetStore(
            root,
            MapAssets(
                "calligraphy.json" to
                    """{"v":1,"id":"builtin.calligraphy","name":"Chinese ink","model":"ChineseInk"}""",
            ),
        )
        File(root, "builtin.calligraphy.json").writeText(
            """{"v":1,"id":"builtin.calligraphy","name":"Classic","model":"Standard"}""",
        )

        assertEquals(BrushModel.Standard, store.load().single().model)
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
