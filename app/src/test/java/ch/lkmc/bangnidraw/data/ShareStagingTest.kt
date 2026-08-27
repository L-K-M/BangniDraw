package ch.lkmc.bangnidraw.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShareStagingTest {
    private val root = createTempDirectory("bangni-share").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the same friendly name keeps independent share contents`() {
        val ids = ArrayDeque(listOf("first", "second"))
        val staging = ShareStaging(root) { ids.removeFirst() }

        val first = staging.stage("Sketch.png", byteArrayOf(1, 2, 3))
        val second = staging.stage("Sketch.png", byteArrayOf(4, 5, 6))

        assertNotEquals(first, second)
        assertContentEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertContentEquals(byteArrayOf(4, 5, 6), second.readBytes())
    }

    @Test
    fun `rotation removes whole old shares`() {
        var next = 0
        val staging = ShareStaging(root) { "share-${next++}" }
        val files = List(ShareStaging.RETAINED_PREVIOUS_SHARES + 2) { index ->
            staging.stage("Sketch.png", byteArrayOf(index.toByte())).also {
                requireNotNull(it.parentFile).setLastModified(index.toLong() + 1L)
            }
        }

        assertTrue(requireNotNull(files.first().parentFile).notExists())
        for (file in files.takeLast(ShareStaging.RETAINED_PREVIOUS_SHARES + 1)) {
            assertTrue(file.isFile, "$file was rotated too early")
        }
    }

    @Test
    fun `a share name must be one path segment`() {
        val staging = ShareStaging(root) { "share" }

        assertFailsWith<IllegalArgumentException> {
            staging.stage("../project.json", byteArrayOf(1))
        }
    }

    private fun File.notExists(): Boolean = !exists()
}
