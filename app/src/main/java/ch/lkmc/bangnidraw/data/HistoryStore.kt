package ch.lkmc.bangnidraw.data

import android.util.Log
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException

/**
 * `history/<seq>.entry` and `<seq>.redo` on disk
 * (`docs/plan/06-document-and-persistence.md` §5.3–§5.6): one undo step per
 * file — a JSON header line, then each before-tile as an entire tile file in
 * [TileCodec]'s format. Takes a plain [File] so the JVM suite runs it on a
 * temp dir (`11-testing.md` §5). IO thread only.
 *
 * A payload byte array of length 0 records "this tile was empty before":
 * restoring it deletes the tile, without which undoing a stroke that touched
 * a virgin tile could not know to clear it (§5.3).
 */
internal class HistoryStore(private val dir: File) {

    /** One before- (or after-) tile: already in [TileCodec] format, or empty. */
    class Payload(val layer: LayerId, val key: TileKey, val encoded: ByteArray)

    /** What [load] proved usable: a prefix, stamped with real on-disk bytes. */
    data class Loaded(val entries: List<HistoryEntry>, val cursor: Int)

    fun entryFile(seq: Long): File = File(dir, fileName(seq, ENTRY_SUFFIX))
    fun redoFile(seq: Long): File = File(dir, fileName(seq, REDO_SUFFIX))

    /**
     * Writes `<seq>.entry` atomically and returns [entry] stamped with [seq],
     * [ts] and the file's real size — the journal's byte accounting is the
     * disk's truth, never a guess (§5.1).
     */
    @Throws(IOException::class)
    fun append(entry: HistoryEntry, seq: Long, ts: Long, payloads: List<Payload>): HistoryEntry {
        val bytes = encode(entry, seq, ts, payloads)
        ensureDir()
        AtomicFiles.write(entryFile(seq), bytes)
        return entry.stamp(seq = seq, timestamp = ts, bytes = bytes.size.toLong())
    }

    /**
     * Writes the redo sidecar for an already-written entry (§5.4: on the
     * *first* undo, before any pixel is restored). Returns its size, for
     * `HistoryJournal.noteRedoBytes`.
     */
    @Throws(IOException::class)
    fun writeRedo(entry: HistoryEntry, payloads: List<Payload>): Long {
        require(entry.isStamped) { "a redo sidecar belongs to a written entry" }
        val bytes = encode(entry, entry.seq, entry.timestamp, payloads)
        ensureDir()
        AtomicFiles.write(redoFile(entry.seq), bytes)
        return bytes.size.toLong()
    }

    /** True when `<seq>.redo` exists — the first-undo capture already ran. */
    fun hasRedo(seq: Long): Boolean = redoFile(seq).isFile

    /**
     * Reads one file's payloads, validating every offset and length against
     * the file (§5.6: offsets past the file mean the header lies). Null when
     * the file is missing or unreadable — never an exception for content.
     */
    fun readPayloads(seq: Long, sidecar: Boolean): List<Payload>? {
        val file = if (sidecar) redoFile(seq) else entryFile(seq)
        val bytes = try {
            if (!file.isFile) return null
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val parsed = parse(bytes) ?: return null
        val (header, bodyOffset) = parsed
        val out = ArrayList<Payload>(header.payloads.size)
        for (ref in header.payloads) {
            if (!payloadWithinBody(ref.off.toLong(), ref.len.toLong(), bodyOffset, bytes.size.toLong())) {
                return null
            }
            val layer = try {
                LayerId(ref.layer)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val start = (bodyOffset + ref.off).toInt()
            out += Payload(
                layer = layer,
                key = TileKey(ref.tx, ref.ty),
                encoded = bytes.copyOfRange(start, start + ref.len),
            )
        }
        return out
    }

    /** Deletes `<seq>.entry` and `<seq>.redo` — truncation and pruning (§5.6). */
    fun delete(seqs: List<Long>) {
        for (seq in seqs) {
            entryFile(seq).delete()
            redoFile(seq).delete()
        }
    }

    /** Deletes only the sidecar — its step was redone past, or re-captured. */
    fun deleteRedo(seq: Long) {
        redoFile(seq).delete()
    }

    /**
     * §5.6's load: headers only, lazily — payload bytes wait for an undo.
     *
     * - seqs below `oldestSeq` are orphans of a pruning the checkpoint never
     *   saw: deleted.
     * - the checkpointed range `[oldestSeq, nextSeq)` is read in order; the
     *   first missing or unreadable entry truncates the list there (undo
     *   history is a prefix or it is lies).
     * - a contiguous run at `nextSeq` and beyond was pushed after the
     *   checkpoint (§5.6: truncation orphans always predate it): appended to
     *   the *undo* branch as applied — its tiles are on disk by §5.6's write
     *   order. The first gap ends the run and everything past it is deleted.
     * - redo-branch entries (index ≥ cursor) whose kind needs pixels to redo
     *   but whose `.redo` is missing lose the branch from that point; the
     *   undo branch is untouched (§5.4).
     *
     * None of this throws to the UI: the painting opens with as much undo as
     * could be proven.
     */
    fun load(record: HistoryRecord): Loaded {
        val listed = dir.listFiles() ?: return Loaded(emptyList(), 0)
        val present = HashMap<Long, File>()
        for (file in listed) {
            val seq = parseEntryName(file.name) ?: continue
            present[seq] = file
        }

        // Orphans of an unrecorded pruning.
        for ((seq, _) in present) {
            if (seq < record.oldestSeq) {
                Log.w(TAG, "history: deleting pre-oldest orphan $seq")
                delete(listOf(seq))
            }
        }

        val entries = ArrayList<HistoryEntry>()
        var seq = record.oldestSeq
        while (seq < record.nextSeq) {
            val entry = readEntry(seq) ?: break
            entries.add(entry)
            seq += 1
        }
        // Entries the checkpoint recorded but the walk could not prove: their
        // redo files may exist; sweep nothing here — a corrupt entry stays on
        // disk as a support question, exactly like a corrupt tile.

        var cursor = record.cursor.coerceIn(0, entries.size)

        // The post-checkpoint run. Only meaningful when the whole
        // checkpointed range was readable — a truncated prefix means the
        // journal is already suspect, and appending later strokes onto a
        // shortened undo branch would replay them against the wrong state.
        if (entries.size.toLong() == record.nextSeq - record.oldestSeq) {
            var next = record.nextSeq
            while (true) {
                val entry = readEntry(next) ?: break
                entries.add(entry)
                cursor = entries.size
                next += 1
            }
            // Anything past the first gap was orphaned by a truncation whose
            // checkpoint never landed: §5.6 says delete.
            val stale = present.keys.filter { it >= next }.sorted()
            if (stale.isNotEmpty()) {
                Log.w(TAG, "history: deleting ${stale.size} entries after the gap at $next")
                delete(stale)
            }
        }

        // The redo branch needs its sidecars.
        var end = entries.size
        for (index in cursor until entries.size) {
            val entry = entries[index]
            if (HistoryCodec.redoNeedsPixels(entry) && !hasRedo(entry.seq)) {
                Log.w(TAG, "history: redo branch truncated at seq ${entry.seq}: missing sidecar")
                end = index
                break
            }
        }
        while (entries.size > end) entries.removeAt(entries.size - 1)

        return Loaded(entries, cursor)
    }

    // ------------------------------------------------------------- internals

    private fun readEntry(seq: Long): HistoryEntry? {
        val file = entryFile(seq)
        val bytes = try {
            if (!file.isFile) return null
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val parsed = parse(bytes) ?: return null
        val (header, bodyOffset) = parsed
        if (header.seq != seq) {
            Log.w(TAG, "history: $seq.entry claims seq ${header.seq}")
            return null
        }
        // Offsets are validated at load, not first at undo: an entry whose
        // payloads exceed the file must truncate the journal *now* (§5.6),
        // not surprise the user the day they reach for undo. The same bound
        // as readPayloads — one predicate, two sites, no drift between what
        // load truncates on and what an undo reads with.
        for (ref in header.payloads) {
            if (!payloadWithinBody(ref.off.toLong(), ref.len.toLong(), bodyOffset, bytes.size.toLong())) {
                Log.w(TAG, "history: $seq.entry payload exceeds the file")
                return null
            }
        }
        val entry = HistoryCodec.entryOf(header) ?: run {
            Log.w(TAG, "history: $seq.entry header does not describe a known entry")
            return null
        }
        val redoBytes = redoFile(seq).takeIf { it.isFile }?.length() ?: 0L
        return entry.stamp(seq = seq, timestamp = header.ts, bytes = bytes.size + redoBytes)
    }

    /**
     * Whether a payload ref's `off`/`len` address bytes the file actually
     * holds, computed so no input can wrap the arithmetic: the subtraction
     * runs only once `bodyOffset ∈ [0, size]` is established, and every
     * remaining term is then bounded by the file's size — so a hand-edited
     * `off` near `Long.MAX_VALUE` (or a `len` the file could never hold)
     * fails the bound rather than wrapping `bodyOffset + off + len` past it,
     * the way the naive sum did. §5.6: offsets past the file mean the header
     * lies.
     *
     * The guard on `bodyOffset` itself holds by construction today — `parse`
     * derives it from a newline index inside the file — but the helper's
     * safety does not lean on that invariant living somewhere else; it
     * re-establishes it, in the sign checks before the subtraction.
     */
    private fun payloadWithinBody(off: Long, len: Long, bodyOffset: Long, size: Long): Boolean =
        bodyOffset in 0..size && off >= 0 && len >= 0 && off <= size - bodyOffset - len

    private fun parse(bytes: ByteArray): Pair<HistoryCodec.EntryHeader, Long>? {
        val newline = bytes.indexOf('\n'.code.toByte())
        if (newline <= 0) return null
        val header = try {
            HistoryCodec.json.decodeFromString(
                HistoryCodec.EntryHeader.serializer(),
                String(bytes, 0, newline, Charsets.UTF_8),
            )
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (header.v < 1 || header.v > HistoryCodec.FORMAT_VERSION) return null
        return header to (newline + 1).toLong()
    }

    private fun encode(entry: HistoryEntry, seq: Long, ts: Long, payloads: List<Payload>): ByteArray {
        val refs = ArrayList<HistoryCodec.PayloadRef>(payloads.size)
        var off = 0L
        for (payload in payloads) {
            refs += HistoryCodec.PayloadRef(
                layer = payload.layer.value,
                tx = payload.key.tx,
                ty = payload.key.ty,
                off = off,
                len = payload.encoded.size,
            )
            off += payload.encoded.size
        }
        val expected = HistoryCodec.payloadKeys(entry)
        require(expected == payloads.map { it.layer to it.key }) {
            "payloads must match the entry's tiles in order"
        }
        val header = HistoryCodec.headerOf(entry, seq, ts, refs)
        val headerLine = HistoryCodec.json
            .encodeToString(HistoryCodec.EntryHeader.serializer(), header)
        require('\n' !in headerLine) { "the header must be one line" }
        val out = ByteArrayOutputStream(headerLine.length + 1 + off.toInt())
        out.write(headerLine.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        for (payload in payloads) out.write(payload.encoded)
        return out.toByteArray()
    }

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
    }

    internal companion object {
        const val TAG = "HistoryStore"
        const val ENTRY_SUFFIX = ".entry"
        const val REDO_SUFFIX = ".redo"

        /** `00000042.entry` — a plain directory sort is a sequence sort (§2). */
        fun fileName(seq: Long, suffix: String): String =
            seq.toString().padStart(8, '0') + suffix

        /** The seq of an `.entry` file name, or null for anything else. */
        fun parseEntryName(name: String): Long? {
            if (!name.endsWith(ENTRY_SUFFIX)) return null
            val stem = name.removeSuffix(ENTRY_SUFFIX)
            if (stem.length < 8 || stem.any { it !in '0'..'9' }) return null
            return stem.toLongOrNull()
        }
    }
}
