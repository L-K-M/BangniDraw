package ch.lkmc.bangnidraw.engine.core

/**
 * One reversible step of the undo journal.
 *
 * `docs/plan/06-document-and-persistence.md` §5.2 is normative for the kinds,
 * their names and their fields; `docs/plan/05-layers.md` §5 says which
 * operation produces which kind and what each stores as "before". This file
 * is the declaration only — the journal (cursor, prune, redo capture) and the
 * on-disk encoding land with roadmap step 3, and the tile *payloads* never
 * live in these objects: an entry references tiles by [TileKey] and the bytes
 * sit beside the header in `history/<seq>.entry`.
 *
 * [seq], [timestamp] and [bytes] are assigned by the journal, not by the pure
 * operation that produced the entry; until then they are [UNSTAMPED] and
 * [stamp] fills them in.
 *
 * [activeBefore] and [activeAfter] are the active layer's id before and after
 * the edit, so undo lands the user where they were. Selection itself is never
 * an entry.
 *
 * Ids are [LayerId], not `String`. `06` §5.2 is normative for the on-disk
 * *encoding*, and that is unchanged — `LayerId` is a `@JvmInline` value class
 * over the same string. What the type buys is that a `history/<seq>.entry`
 * decoded from a hand-edited file cannot hand an unvalidated id to a path
 * join: the same trust boundary `LayerId`'s own guard exists for.
 *
 * **That holds for the [LayerId]-typed fields only.** Six kinds embed
 * [LayerRecord] — [LayerAdd.layer], [LayerDelete.layer], [LayerProps.before]
 * and `after`, [LayerMerge.upper] and `lower`, [LayerDuplicate.copy],
 * [Flatten.layers] and `result` — and a record's `id` is a plain `String`,
 * unvalidated until [LayerRecord.toProps] runs. Undoing a delete by
 * recreating `layers/<entry.layer.id>/` would walk straight past the guard.
 * Convert the record first, every time: `toProps()` to fail loudly, or
 * `toPropsOrNull()` to drop the layer, and only then is the id a [LayerId].
 */
sealed interface HistoryEntry {
    val seq: Long
    val timestamp: Long
    val bytes: Long
    val activeBefore: LayerId
    val activeAfter: LayerId

    /**
     * A copy of this entry with the journal's bookkeeping filled in. Single
     * shot: re-stamping would rewrite a `seq` the journal already issued, so
     * every override refuses it. An entry reloaded from disk is *constructed*
     * stamped, never stamped again.
     *
     * Also refuses `seq <= `[UNSTAMPED]. Without that, `stamp(seq = 0)` would
     * produce an entry that still reports [isStamped] `false` — so the
     * single-shot guard would pass a second time and the journal could reissue
     * the number. It makes [isStamped] sound here rather than resting on the
     * journal's `nextSeq = 1L` holding somewhere else.
     *
     * Not airtight, and cannot be: these are `data class`es, so the generated
     * `copy` sets all three fields with no guard at all. The journal must
     * re-validate on ingest (seq strictly increasing, never below 1); this
     * catches the honest mistake, not a determined one.
     */
    fun stamp(seq: Long, timestamp: Long, bytes: Long): HistoryEntry

    /**
     * True once the journal has stamped this entry. Sound only because journal
     * sequence numbers start at 1 (`docs/plan/06-document-and-persistence.md`
     * §3: `nextSeq = 1L`), so a stamped first entry can never look [UNSTAMPED].
     */
    val isStamped: Boolean get() = seq != UNSTAMPED

    /** A pixel edit of one layer: the stroke's tiles held their previous contents. */
    data class Stroke(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layerId: LayerId,
        val tiles: List<TileKey>,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): Stroke {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A bucket fill; a pixel edit of one layer, like [Stroke]. */
    data class Fill(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layerId: LayerId,
        val tiles: List<TileKey>,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): Fill {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A new, empty layer at [index]. Undo removes it; no tiles are stored. */
    data class LayerAdd(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layer: LayerRecord,
        val index: Int,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerAdd {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A deleted layer: the record, where it sat, and every tile it had. */
    data class LayerDelete(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layer: LayerRecord,
        val index: Int,
        val tiles: List<TileKey>,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerDelete {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A layer moved from [fromIndex] to [toIndex]. */
    data class LayerReorder(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layerId: LayerId,
        val fromIndex: Int,
        val toIndex: Int,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerReorder {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** Rename, opacity, visibility, blend mode, alpha lock, lock. */
    data class LayerProps(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layerId: LayerId,
        val before: LayerRecord,
        val after: LayerRecord,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerProps {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /**
     * Merge down. [lower] is the lower layer's record *before* the merge reset
     * its opacity, mode and flags (`docs/plan/05-layers.md` §4.1).
     *
     * [lowerTiles] are the lower layer's keys at the upper layer's keys — and
     * *all* of the lower layer's keys when its opacity was not 1, because the
     * merge then rewrites every one of them and undo has to be able to put
     * them back (AGENTS.md, "Deviations discovered while building").
     *
     * Undo must therefore restore [lowerTiles] on the lower layer **and delete
     * `upperTiles − lowerTiles` from it**, which reconstructs its tile set as
     * exactly what it was before the merge. Rebuilding that set from
     * [lowerTiles] alone would orphan every tile the upper layer did not
     * cover — silent data loss on the next save.
     */
    data class LayerMerge(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val upper: LayerRecord,
        val upperIndex: Int,
        val upperTiles: List<TileKey>,
        val lower: LayerRecord,
        val lowerTiles: List<TileKey>,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerMerge {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A duplicated layer: redo re-copies from [sourceId], so no tiles are stored. */
    data class LayerDuplicate(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val sourceId: LayerId,
        val copy: LayerRecord,
        val index: Int,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerDuplicate {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** A cleared layer: the props survive, every tile is stored as "before". */
    data class LayerClear(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layerId: LayerId,
        val tiles: List<TileKey>,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): LayerClear {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** Flatten: every layer's record in order, its keys, and the result's record. */
    data class Flatten(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val layers: List<LayerRecord>,
        val tilesPerLayer: Map<LayerId, List<TileKey>>,
        val result: LayerRecord,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): Flatten {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    /** The paper colour changed; pixel-free. */
    data class PaperColor(
        override val seq: Long = UNSTAMPED,
        override val timestamp: Long = UNSTAMPED,
        override val bytes: Long = UNSTAMPED,
        override val activeBefore: LayerId,
        override val activeAfter: LayerId,
        val before: Int,
        val after: Int,
    ) : HistoryEntry {
        override fun stamp(seq: Long, timestamp: Long, bytes: Long): PaperColor {
            validateStamp(this.seq, seq)
            return copy(seq = seq, timestamp = timestamp, bytes = bytes)
        }
    }

    companion object {
        /** The value of [seq]/[timestamp]/[bytes] before the journal stamps an entry. */
        const val UNSTAMPED = 0L

        /**
         * The single-shot, `seq >= 1` guard every [stamp] override runs. Hoisted
         * because the invariant otherwise lives in eleven copies, and step 3 is
         * going to want to strengthen it — validating `timestamp` too, most
         * likely — which is eleven chances to miss one.
         */
        fun validateStamp(current: Long, next: Long) {
            check(current == UNSTAMPED) { "entry is already stamped (seq=$current)" }
            require(next > UNSTAMPED) { "journal seq starts at 1, was $next" }
        }
    }
}
