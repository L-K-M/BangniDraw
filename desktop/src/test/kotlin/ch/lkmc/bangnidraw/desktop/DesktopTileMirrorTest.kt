package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTileMirrorTest {

    @Test
    fun `history restore updates pixels and removes absent tiles`() {
        val removed = TileKey(1)
        val replaced = TileKey(2)
        val replacement = byteArrayOf(7, 8, 9)
        val mirror = mutableMapOf(
            removed to byteArrayOf(1),
            replaced to byteArrayOf(2),
        )

        DesktopTileMirror.apply(
            mirror,
            mapOf(
                removed to null,
                replaced to replacement,
            ),
        )

        assertFalse(removed in mirror)
        assertContentEquals(replacement, mirror.getValue(replaced))

        replacement[0] = 99
        assertContentEquals(byteArrayOf(7, 8, 9), mirror.getValue(replaced))
    }

    @Test
    fun `RMW cancellation snapshots pre-stroke pixels including absent tiles`() {
        val present = TileKey(3)
        val absent = TileKey(4)
        val pixels = byteArrayOf(5, 6)

        val snapshot = DesktopTileMirror.snapshot(
            source = mapOf(present to pixels),
            keys = listOf(present, absent),
        )

        assertContentEquals(pixels, snapshot.getValue(present))
        assertTrue(absent in snapshot)
        assertNull(snapshot.getValue(absent))

        pixels[0] = 99
        assertContentEquals(byteArrayOf(5, 6), snapshot.getValue(present))
    }
}
