package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryDirection
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Durable intent for one undo or redo whose pixels and project checkpoint differ. */
internal class HistoryTransitionStore(private val dir: File) {

    data class Pending(
        val seq: Long,
        val direction: HistoryDirection,
        val fromCursor: Int,
        val toCursor: Int,
    )

    @Throws(IOException::class)
    fun begin(
        entry: HistoryEntry,
        direction: HistoryDirection,
        fromCursor: Int,
    ): Pending {
        require(entry.isStamped) { "a transition belongs to a written entry" }
        val toCursor = when (direction) {
            HistoryDirection.UNDO -> {
                require(fromCursor > 0) { "undo cursor must be positive" }
                fromCursor - 1
            }
            HistoryDirection.REDO -> {
                require(fromCursor >= 0) { "redo cursor must be non-negative" }
                fromCursor + 1
            }
        }
        val next = Pending(entry.seq, direction, fromCursor, toCursor)
        if (marker.isFile) {
            if (pending() == next) return next
            throw IOException("another history transition is pending")
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")

        val record = Record(
            seq = next.seq,
            direction = next.direction.name,
            fromCursor = next.fromCursor,
            toCursor = next.toCursor,
        )
        AtomicFiles.write(
            marker,
            JSON.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8),
        )
        return next
    }

    fun pending(): Pending? {
        val bytes = try {
            if (!marker.isFile) return null
            marker.readBytes()
        } catch (_: IOException) {
            return null
        }
        val record = try {
            JSON.decodeFromString(Record.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (record.version != FORMAT_VERSION) return null
        val direction = runCatching { HistoryDirection.valueOf(record.direction) }.getOrNull()
            ?: return null
        if (record.seq <= 0 || record.fromCursor < 0 || record.toCursor < 0) return null
        val expected = when (direction) {
            HistoryDirection.UNDO -> record.fromCursor - 1
            HistoryDirection.REDO -> record.fromCursor + 1
        }
        if (record.toCursor != expected) return null

        return Pending(record.seq, direction, record.fromCursor, record.toCursor)
    }

    fun hasPendingFile(): Boolean = marker.isFile

    /** Cancels an intent only while no pixel mutation has begun. */
    fun cancel(expected: Pending): Boolean {
        if (!marker.exists()) return true
        if (pending() != expected) return false
        return marker.delete() || !marker.exists()
    }

    /** Called only after project.json durably records this intent's target. */
    fun complete(expected: Pending): Boolean {
        if (!marker.exists()) return true
        if (pending() != expected) return false
        return marker.delete() || !marker.exists()
    }

    private val marker: File get() = File(dir, FILE_NAME)

    @Serializable
    private data class Record(
        val version: Int = FORMAT_VERSION,
        val seq: Long,
        val direction: String,
        val fromCursor: Int,
        val toCursor: Int,
    )

    private companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "transition.json"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
