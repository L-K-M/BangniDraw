package ch.lkmc.bangnidraw.data

import java.io.File
import java.io.IOException
import java.util.UUID

/** File staging beneath the Android share adapter, kept JVM-testable. */
internal class ShareStaging(
    private val root: File,
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    @Throws(IOException::class)
    fun stage(fileName: String, bytes: ByteArray): File {
        requireValidName(fileName)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("could not create $root")

        rotate()
        val share = File(root, idSource())
        if (!share.mkdir()) throw IOException("could not create $share")

        val file = File(share, fileName)
        try {
            AtomicFiles.write(file, bytes)
        } catch (e: IOException) {
            share.deleteRecursively()
            throw e
        }
        return file
    }

    private fun rotate() {
        val shares = root.listFiles()?.sortedByDescending(File::lastModified) ?: return
        for (stale in shares.drop(RETAINED_PREVIOUS_SHARES)) stale.deleteRecursively()
    }

    private fun requireValidName(fileName: String) {
        require(
            fileName.isNotBlank() &&
                fileName != "." &&
                fileName != ".." &&
                fileName.none { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            "share name must be one non-empty path segment"
        }
    }

    companion object {
        /** Completed shares retained while a receiving app may still read them. */
        const val RETAINED_PREVIOUS_SHARES = 3
    }
}
