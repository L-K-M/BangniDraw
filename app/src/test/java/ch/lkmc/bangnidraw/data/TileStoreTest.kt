package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §5's `TileStoreTest`, on a JVM temp dir. */
class TileStoreTest {

    private val root = createTempDirectory("bangni-tiles").toFile()
    private val store = TileStore(root)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun randomTile(seed: Int = 3): ByteArray = Random(seed).nextBytes(TILE_BYTES)

    @Test
    fun `a tile round-trips deflated`() {
        val pixels = randomTile()
        store.write(TileKey(3, 5), pixels)
        assertEquals(TileStore.Read.Pixels(pixels), store.read(TileKey(3, 5)))
    }

    @Test
    fun `writes are tmp plus rename and leave no tmp file behind`() {
        store.write(TileKey(0, 0), randomTile())
        val names = root.list()!!.toList()
        assertEquals(listOf("0_0.tile"), names)
    }

    @Test
    fun `a sparse layer lists only the tiles that exist`() {
        store.write(TileKey(1, 2), randomTile(1))
        store.write(TileKey(30, 31), randomTile(2))
        // Foreign files are not tiles: an in-flight tmp, and a name that does
        // not parse (a stray leading zero would alias "7" — see parseName).
        File(root, "9_9.tile.tmp").writeBytes(ByteArray(4))
        File(root, "07_1.tile").writeBytes(ByteArray(4))
        File(root, "notes.txt").writeBytes(ByteArray(4))
        assertEquals(
            setOf(TileKey(1, 2), TileKey(30, 31)),
            store.list().toSet(),
        )
    }

    @Test
    fun `writing an all-transparent tile deletes the file instead`() {
        val key = TileKey(4, 4)
        store.write(key, randomTile())
        assertTrue(File(root, "4_4.tile").isFile)
        store.write(key, ByteArray(TILE_BYTES))
        assertTrue(!File(root, "4_4.tile").exists(), "erasing to nothing must reclaim the file")
        assertEquals(TileStore.Read.Empty, store.read(key))
    }

    @Test
    fun `writing an all-transparent tile reports a failed deletion`() {
        val key = TileKey(5, 5)
        val target = File(root, TileStore.fileName(key))
        assertTrue(target.mkdir())
        File(target, "blocker").writeBytes(ByteArray(1))

        assertFailsWith<IOException> {
            store.write(key, ByteArray(TILE_BYTES))
        }
        assertTrue(target.exists(), "a failed deletion must remain retryable")
    }

    @Test
    fun `a missing tile file reads as Empty`() {
        assertEquals(TileStore.Read.Empty, store.read(TileKey(9, 9)))
    }

    @Test
    fun `a corrupt tile file loads as Corrupt, not an exception`() {
        val key = TileKey(2, 2)
        File(root, TileStore.fileName(key)).writeBytes("not a tile".toByteArray())
        assertIs<TileStore.Read.Corrupt>(store.read(key))
        // §4: the bad file is left on disk for a future reader, never
        // rewritten or deleted by the load path.
        assertTrue(File(root, "2_2.tile").isFile)
    }

    @Test
    fun `tile file names parse strictly`() {
        assertEquals(TileKey(0, 0), TileStore.parseName("0_0.tile"))
        assertEquals(TileKey(12, 31), TileStore.parseName("12_31.tile"))
        assertNull(TileStore.parseName("1_2.tile.tmp"))
        assertNull(TileStore.parseName("-1_2.tile"))
        assertNull(TileStore.parseName("01_2.tile"))
        assertNull(TileStore.parseName("1_.tile"))
        assertNull(TileStore.parseName("_1.tile"))
        assertNull(TileStore.parseName("1_2_3.tile"))
        assertNull(TileStore.parseName("65536_0.tile"), "wraps TileKey's 16-bit packing")
        assertEquals(TileKey(65535, 0), TileStore.parseName("65535_0.tile"))
    }
}
