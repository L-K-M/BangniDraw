package ch.lkmc.bangnidraw.engine.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TemporaryReason {
    EraserEnd,
    PenButton,
    LongPress,
    Hover,
    Rail,
    Keyboard,
}

data class ToolSelection(
    val kind: ToolKind,
    val temporaryReason: TemporaryReason? = null,
)

/** Owns the base tool and the nested temporary overrides from `04` §9. */
class ToolSwitcher(initial: ToolKind) {

    private data class TemporaryEntry(
        val kind: ToolKind,
        val reason: TemporaryReason,
    )

    private var base = initial
    private val temporary = ArrayList<TemporaryEntry>()

    private val _selection = MutableStateFlow(ToolSelection(initial))
    val selection: StateFlow<ToolSelection> = _selection.asStateFlow()

    private val _current = MutableStateFlow(initial)
    val current: StateFlow<ToolKind> = _current.asStateFlow()

    /** Changes what is restored after temporary tools leave. */
    fun select(kind: ToolKind) {
        base = kind
        publish()
    }

    fun pushTemporary(kind: ToolKind, reason: TemporaryReason) {
        val existing = temporary.indexOfFirst { it.reason == reason }
        if (existing >= 0) {
            temporary[existing] = TemporaryEntry(kind, reason)
        } else {
            temporary += TemporaryEntry(kind, reason)
        }
        publish()
    }

    /** Pops only the matching top entry; out-of-order releases change nothing. */
    fun popTemporary(reason: TemporaryReason) {
        if (temporary.lastOrNull()?.reason != reason) return

        temporary.removeAt(temporary.lastIndex)
        publish()
    }

    fun clearTemporary() {
        if (temporary.isEmpty()) return

        temporary.clear()
        publish()
    }

    private fun publish() {
        val active = temporary.lastOrNull()
        val next = if (active == null) {
            ToolSelection(base)
        } else {
            ToolSelection(active.kind, active.reason)
        }
        _selection.value = next
        _current.value = next.kind
    }
}
