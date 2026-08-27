package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Palette
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaletteStoreTest {

    private val root = createTempDirectory("bangni-palettes").toFile()
    private val store = PaletteStore(root)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a user palette round-trips and unknown keys are ignored`() {
        val palette = Palette("user-one", "Landscape", listOf(1, 2, 3))
        store.save(palette)
        File(root, "user-one.json").writeText(
            File(root, "user-one.json").readText().replaceFirst("{", "{\n  \"future\": 7,"),
        )

        assertEquals(listOf(palette), store.load())
    }

    @Test
    fun `temporary and invalid ownership files never become palettes`() {
        File(root, "stray.json.tmp").writeText("partial")
        File(root, "wrong.json").writeText(
            """{"id":"another","name":"Wrong","swatches":[],"builtIn":false}""",
        )

        assertTrue(store.load().isEmpty())
        assertFalse(File(root, "stray.json.tmp").exists())
    }

    @Test
    fun `built-ins cannot be written`() {
        assertFailsWith<IllegalArgumentException> {
            store.save(Palette("built-in", "Built in", emptyList(), builtIn = true))
        }
    }

    @Test
    fun `unsafe ids are dropped during load`() {
        File(root, "escape.json").writeText(
            """{"id":"../escape","name":"Escape","swatches":[],"builtIn":false}""",
        )

        assertTrue(store.load().isEmpty())
    }
}
