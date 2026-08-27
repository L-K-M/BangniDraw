package ch.lkmc.bangnidraw.data

import android.util.Log
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
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

    /** What [load] proved usable, plus an allocation point above preserved files. */
    data class Loaded(
        val entries: List<HistoryEntry>,
        val cursor: Int,
        val nextSeq: Long = 1L,
    )

    fun entryFile(seq: Long): File = File(dir, fileName(seq, ENTRY_SUFFIX))
    fun redoFile(seq: Long): File = File(dir, fileName(seq, REDO_SUFFIX))
    fun afterFile(seq: Long): File = File(dir, fileName(seq, AFTER_SUFFIX))

    /**
     * Writes `<seq>.entry` atomically and returns [entry] stamped with [seq],
     * [ts] and the file's real size — the journal's byte accounting is the
     * disk's truth, never a guess (§5.1).
     */
    @Throws(IOException::class)
    fun append(entry: HistoryEntry, seq: Long, ts: Long, payloads: List<Payload>): HistoryEntry {
        if (seq !in 1L..MAX_ALLOCATABLE_SEQUENCE) {
            throw IOException("history sequence exhausted")
        }
        val target = entryFile(seq)
        if (target.exists() || redoFile(seq).exists() || afterFile(seq).exists()) {
            throw IOException("history sequence $seq already exists")
        }

        val bytes = encode(entry, seq, ts, payloads, PayloadSide.BEFORE)
        ensureDir()
        AtomicFiles.write(target, bytes)
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
        val bytes = encode(entry, entry.seq, entry.timestamp, payloads, PayloadSide.REDO)
        ensureDir()
        AtomicFiles.write(redoFile(entry.seq), bytes)
        return bytes.size.toLong()
    }

    /**
     * Persists the post-edit pixels before their ordinary tile files change.
     * The file doubles as a commit marker for pixel operations with no tiles.
     */
    @Throws(IOException::class)
    fun writeRecoveryAfter(seq: Long, payloads: List<Payload>) {
        val bytes = encodeRecoveryAfter(seq, payloads)
        ensureDir()
        AtomicFiles.write(afterFile(seq), bytes)
    }

    /**
     * Reads a recovery after-image. Null means the step cannot be rolled
     * forward after a crash.
     */
    fun readRecoveryAfter(seq: Long): List<Payload>? {
        val file = afterFile(seq)
        val bytes = try {
            if (!file.isFile) return null
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val parsed = parseRecoveryAfter(bytes) ?: return null
        val (header, bodyOffset) = parsed
        if (header.seq != seq) return null

        val out = ArrayList<Payload>(header.payloads.size)
        val seen = HashSet<Pair<LayerId, TileKey>>()
        for (ref in header.payloads) {
            if (ref.tx !in TILE_COORDINATE_RANGE || ref.ty !in TILE_COORDINATE_RANGE) return null
            if (!payloadWithinBody(ref.off, ref.len.toLong(), bodyOffset, bytes.size.toLong())) {
                return null
            }
            val layer = try {
                LayerId(ref.layer)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val key = TileKey(ref.tx, ref.ty)
            if (!seen.add(layer to key)) return null

            val start = (bodyOffset + ref.off).toInt()
            val encoded = bytes.copyOfRange(start, start + ref.len)
            out += Payload(layer, key, encoded)
        }
        return out
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
            if (!payloadWithinBody(ref.off, ref.len.toLong(), bodyOffset, bytes.size.toLong())) {
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

    /** Deletes one step and both sidecars — truncation and pruning (§5.6). */
    fun delete(seqs: List<Long>) {
        for (seq in seqs) {
            entryFile(seq).delete()
            redoFile(seq).delete()
            afterFile(seq).delete()
        }
    }

    /** Deletes only the sidecar — its step was redone past, or re-captured. */
    fun deleteRedo(seq: Long) {
        redoFile(seq).delete()
    }

    /** A successful checkpoint makes older recovery after-images redundant. */
    fun deleteRecoveryBefore(nextSeq: Long) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val seq = parseAfterName(file.name) ?: continue
            if (seq < nextSeq) file.delete()
        }
    }

    /**
     * §5.6's load: headers only, lazily — payload bytes wait for an undo.
     *
     * - seqs below `oldestSeq` are orphans of a pruning the checkpoint never
     *   saw: deleted.
     * - new checkpoints list exact sequence membership, preserving deliberate
     *   gaps left by divergent edits. Older records retain the contiguous
     *   `[oldestSeq, nextSeq)` rule. The first unreadable entry truncates the
     *   list there (undo history is a prefix or it is lies).
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
        val listed = dir.listFiles() ?: return Loaded(
            entries = emptyList(),
            cursor = 0,
            nextSeq = maxOf(1L, record.nextSeq),
        )
        val present = HashMap<Long, File>()
        val allocated = HashSet<Long>()
        for (file in listed) {
            val entrySeq = parseEntryName(file.name)
            if (entrySeq != null) {
                present[entrySeq] = file
                allocated += entrySeq
                continue
            }

            val sidecarSeq = parseRedoName(file.name) ?: parseAfterName(file.name) ?: continue
            allocated += sidecarSeq
        }
        val nextAfterAllocated = nextSequenceAfter(allocated)
        if (!recordHasValidBounds(record)) {
            Log.w(TAG, "history: invalid checkpoint bounds; preserving every entry")
            return Loaded(emptyList(), 0, nextAfterAllocated)
        }

        val membershipShapeValid = exactMembershipHasValidShape(record)
        if (membershipShapeValid) {
            // Only trusted checkpoint metadata may classify files as orphans.
            for ((seq, _) in present) {
                if (seq < record.oldestSeq) {
                    Log.w(TAG, "history: deleting pre-oldest orphan $seq")
                    delete(listOf(seq))
                }
            }
        }

        val entries = ArrayList<HistoryEntry>()
        val exactSeqs = record.seqs
        val checkpointReadable = if (exactSeqs == null) {
            loadLegacyCheckpoint(record, present.keys, entries)
        } else {
            loadExactCheckpoint(record, exactSeqs, entries)
        }
        val checkpointComplete = membershipShapeValid && checkpointReadable
        if (checkpointComplete && exactSeqs != null) {
            deleteStaleCheckpointEntries(record, exactSeqs, present.keys)
        }
        // Entries the checkpoint recorded but the walk could not prove: their
        // redo files may exist; sweep nothing here — a corrupt entry stays on
        // disk as a support question, exactly like a corrupt tile.

        var cursor = record.cursor.coerceIn(0, entries.size)

        // The post-checkpoint run. Only meaningful when the whole
        // checkpointed range was readable — a truncated prefix means the
        // journal is already suspect, and appending later strokes onto a
        // shortened undo branch would replay them against the wrong state.
        val recoveredNext = if (checkpointComplete) {
            var next = record.nextSeq
            var replacedRedo = false
            while (next <= MAX_ALLOCATABLE_SEQUENCE) {
                val entry = readEntry(next) ?: break
                if (recoveryNeedsAfter(entry) && readRecoveryAfter(next) == null) {
                    Log.w(TAG, "history: incomplete recovery at seq $next")
                    break
                }

                if (!replacedRedo) {
                    replaceRedoBranch(entries, cursor)
                    replacedRedo = true
                }

                entries.add(entry)
                cursor = entries.size
                next += 1
            }
            // Anything past the first gap was orphaned by a truncation whose
            // checkpoint never landed: §5.6 says delete. Long.MAX_VALUE is
            // the exhaustion sentinel, never an allocatable orphan.
            val stale = present.keys
                .filter { it >= next && it <= MAX_ALLOCATABLE_SEQUENCE }
                .sorted()
            if (stale.isNotEmpty()) {
                Log.w(TAG, "history: deleting ${stale.size} entries after the gap at $next")
                delete(stale)
            }
            maxOf(next, nextAfterAllocated)
        } else {
            maxOf(record.nextSeq, nextAfterAllocated)
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

        return Loaded(entries, cursor, recoveredNext)
    }

    private fun recordHasValidBounds(record: HistoryRecord): Boolean =
        record.nextSeq > 0L &&
            record.oldestSeq in 1L..record.nextSeq &&
            record.cursor in 0..record.entries &&
            record.entries >= 0 &&
            record.entries.toLong() <= record.nextSeq - record.oldestSeq &&
            record.bytes >= 0L

    /** Invalid exact membership can never authorize a destructive sweep. */
    private fun exactMembershipHasValidShape(record: HistoryRecord): Boolean {
        val seqs = record.seqs ?: return true
        if (seqs.size != record.entries) return false

        var previous = 0L
        for (seq in seqs) {
            if (seq < record.oldestSeq || seq >= record.nextSeq || seq <= previous) return false
            previous = seq
        }
        return true
    }

    private fun nextSequenceAfter(seqs: Set<Long>): Long {
        val highest = seqs.maxOrNull() ?: 0L
        if (highest == Long.MAX_VALUE) return Long.MAX_VALUE

        return maxOf(1L, highest + 1L)
    }

    /** Older project files implied a contiguous checkpointed sequence range. */
    private fun loadLegacyCheckpoint(
        record: HistoryRecord,
        present: Set<Long>,
        entries: MutableList<HistoryEntry>,
    ): Boolean {
        val contiguous = ArrayList<HistoryEntry>()
        var seq = record.oldestSeq
        while (seq < record.nextSeq) {
            val entry = readEntry(seq) ?: break
            contiguous += entry
            seq += 1
        }

        val range = record.nextSeq - record.oldestSeq
        if (contiguous.size.toLong() == range && record.entries.toLong() == range) {
            entries += contiguous
            return true
        }

        // v1 wrote no membership. Its saved count can still prove a gapped
        // branch when every file in the checkpoint range is readable.
        if (record.entries <= 0) return false
        val inferredSeqs = present
            .filter { it >= record.oldestSeq && it < record.nextSeq }
            .sorted()
        if (inferredSeqs.size != record.entries) return false

        val inferred = ArrayList<HistoryEntry>(inferredSeqs.size)
        for (inferredSeq in inferredSeqs) {
            val entry = readEntry(inferredSeq) ?: return false
            inferred += entry
        }
        if (record.bytes > 0L && inferred.sumOf(HistoryEntry::bytes) != record.bytes) return false

        entries += inferred
        return true
    }

    /** Exact membership preserves a new branch without reusing old seqs. */
    private fun loadExactCheckpoint(
        record: HistoryRecord,
        seqs: List<Long>,
        entries: MutableList<HistoryEntry>,
    ): Boolean {
        val countMatches = seqs.size == record.entries
        if (!countMatches) {
            Log.w(TAG, "history: checkpoint lists ${seqs.size} of ${record.entries} entries")
            return false
        }

        val exact = ArrayList<HistoryEntry>(seqs.size)
        var previous = 0L
        for (seq in seqs) {
            if (seq < record.oldestSeq || seq >= record.nextSeq || seq <= previous) {
                Log.w(TAG, "history: invalid checkpointed sequence $seq after $previous")
                return false
            }
            val entry = readEntry(seq) ?: return false
            exact += entry
            previous = seq
        }

        entries += exact
        return true
    }

    /** A recovered commit proves that the checkpoint's redo tail was replaced. */
    private fun replaceRedoBranch(entries: MutableList<HistoryEntry>, cursor: Int) {
        if (cursor >= entries.size) return

        val stale = entries.subList(cursor, entries.size).map(HistoryEntry::seq)
        delete(stale)
        entries.subList(cursor, entries.size).clear()
    }

    /** A landed exact checkpoint makes files from its old redo branch stale. */
    private fun deleteStaleCheckpointEntries(
        record: HistoryRecord,
        seqs: List<Long>,
        present: Set<Long>,
    ) {
        val live = seqs.toHashSet()
        val stale = present.filter { seq ->
            seq >= record.oldestSeq && seq < record.nextSeq && seq !in live
        }
        if (stale.isEmpty()) return

        Log.w(TAG, "history: deleting ${stale.size} entries outside checkpoint membership")
        delete(stale)
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
            if (!payloadWithinBody(ref.off, ref.len.toLong(), bodyOffset, bytes.size.toLong())) {
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

    private fun parseRecoveryAfter(bytes: ByteArray): Pair<RecoveryHeader, Long>? {
        val newline = bytes.indexOf('\n'.code.toByte())
        if (newline <= 0) return null
        val header = try {
            HistoryCodec.json.decodeFromString(
                RecoveryHeader.serializer(),
                String(bytes, 0, newline, Charsets.UTF_8),
            )
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (header.v != RECOVERY_FORMAT_VERSION) return null
        return header to (newline + 1).toLong()
    }

    private fun encode(
        entry: HistoryEntry,
        seq: Long,
        ts: Long,
        payloads: List<Payload>,
        side: PayloadSide,
    ): ByteArray {
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
        val expected = when (side) {
            PayloadSide.BEFORE -> HistoryCodec.payloadKeys(entry)
            PayloadSide.REDO -> HistoryCodec.redoPayloadKeys(entry)
        }
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

    private fun encodeRecoveryAfter(seq: Long, payloads: List<Payload>): ByteArray {
        val refs = payloadRefs(payloads)
        val header = RecoveryHeader(seq = seq, payloads = refs)
        val headerLine = HistoryCodec.json.encodeToString(RecoveryHeader.serializer(), header)
        require('\n' !in headerLine) { "the header must be one line" }

        val payloadBytes = payloads.sumOf { it.encoded.size.toLong() }
        require(payloadBytes <= Int.MAX_VALUE - headerLine.length - 1) {
            "recovery after-image is too large"
        }
        val out = ByteArrayOutputStream(headerLine.length + 1 + payloadBytes.toInt())
        out.write(headerLine.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        for (payload in payloads) out.write(payload.encoded)
        return out.toByteArray()
    }

    private fun payloadRefs(payloads: List<Payload>): List<HistoryCodec.PayloadRef> {
        val refs = ArrayList<HistoryCodec.PayloadRef>(payloads.size)
        val seen = HashSet<Pair<LayerId, TileKey>>()
        var off = 0L
        for (payload in payloads) {
            require(seen.add(payload.layer to payload.key)) { "recovery keys must be unique" }
            refs += HistoryCodec.PayloadRef(
                layer = payload.layer.value,
                tx = payload.key.tx,
                ty = payload.key.ty,
                off = off,
                len = payload.encoded.size,
            )
            off += payload.encoded.size
        }
        return refs
    }

    private fun recoveryNeedsAfter(entry: HistoryEntry): Boolean = when (entry) {
        is HistoryEntry.LayerAdd,
        is HistoryEntry.LayerReorder,
        is HistoryEntry.LayerProps,
        is HistoryEntry.PaperColor,
        -> false
        else -> true
    }

    @Serializable
    private data class RecoveryHeader(
        val v: Int = RECOVERY_FORMAT_VERSION,
        val seq: Long,
        val payloads: List<HistoryCodec.PayloadRef> = emptyList(),
    )

    private enum class PayloadSide { BEFORE, REDO }

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
    }

    internal companion object {
        const val TAG = "HistoryStore"
        const val ENTRY_SUFFIX = ".entry"
        const val REDO_SUFFIX = ".redo"
        const val AFTER_SUFFIX = ".after"

        private const val RECOVERY_FORMAT_VERSION = 1
        /** The record must always have one representable next sequence. */
        private const val MAX_ALLOCATABLE_SEQUENCE = Long.MAX_VALUE - 1L
        private val TILE_COORDINATE_RANGE = 0..0xFFFF

        /** `00000042.entry` — a plain directory sort is a sequence sort (§2). */
        fun fileName(seq: Long, suffix: String): String =
            seq.toString().padStart(8, '0') + suffix

        /** The seq of an `.entry` file name, or null for anything else. */
        fun parseEntryName(name: String): Long? = parseSequenceName(name, ENTRY_SUFFIX)

        private fun parseRedoName(name: String): Long? = parseSequenceName(name, REDO_SUFFIX)

        private fun parseAfterName(name: String): Long? = parseSequenceName(name, AFTER_SUFFIX)

        private fun parseSequenceName(name: String, suffix: String): Long? {
            if (!name.endsWith(suffix)) return null
            val stem = name.removeSuffix(suffix)
            if (stem.length < 8 || stem.any { it !in '0'..'9' }) return null
            return stem.toLongOrNull()
        }
    }
}
