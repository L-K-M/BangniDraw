package ch.lkmc.bangnidraw.engine.core

/**
 * When the next checkpoint is due (`docs/plan/06-document-and-persistence.md`
 * §6.2). Ported from Meltorama: the same function and the same constants —
 * reusing tested numbers beats retuning by feel.
 *
 * Two clocks govern `project.json` + `thumb.png` (tiles never wait for a
 * clock; they flush after every stroke):
 *
 * - **Quiet**: [QUIET_MS] after the last change — a pause is when a write
 *   costs nothing. The caller re-arms [delayMs] on every change.
 * - **Ceiling**: [ONE_CHECKPOINT_MS] since the document first differed from
 *   disk — someone painting steadily never pauses, so the quiet wait is
 *   capped by what is left of the ceiling.
 *
 * The leave and `ON_STOP` triggers bypass this entirely (they are "now").
 * Constants are shown in About rather than silently applied.
 */
object AutosavePolicy {

    /** A pause this long means the user is looking, not painting. */
    const val QUIET_MS = 10_000L

    /** No unwritten change ever waits longer than this. */
    const val ONE_CHECKPOINT_MS = 90_000L

    /**
     * How long to wait from *now* before checkpointing, given the document
     * has been dirty for [dirtyForMs]: one quiet window, but never past the
     * ceiling, and never negative — at or past the ceiling the write is due
     * immediately.
     */
    fun delayMs(dirtyForMs: Long): Long =
        minOf(QUIET_MS, maxOf(0L, ONE_CHECKPOINT_MS - dirtyForMs))
}
