package ch.lkmc.bangnidraw.engine.core

/** Whether a state value must cross the Compose-to-engine boundary. */
internal enum class EngineUpdate { APPLY, KEEP }

internal object EngineUpdatePolicy {
    fun <T : Any> decide(previous: T?, next: T): EngineUpdate {
        if (previous == next) return EngineUpdate.KEEP

        return EngineUpdate.APPLY
    }
}

/** Main-thread gate for duplicate view callbacks entering one engine session. */
internal class EngineViewUpdateGate {
    private var previous: ViewTransform? = null

    fun update(next: ViewTransform, onChanged: () -> Unit) {
        if (EngineUpdatePolicy.decide(previous, next) == EngineUpdate.KEEP) return
        previous = next

        onChanged()
    }
}
