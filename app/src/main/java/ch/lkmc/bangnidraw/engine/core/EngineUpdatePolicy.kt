package ch.lkmc.bangnidraw.engine.core

/** Whether a state value must cross the Compose-to-engine boundary. */
internal enum class EngineUpdate { APPLY, KEEP }

internal object EngineUpdatePolicy {
    fun <T : Any> decide(previous: T?, next: T): EngineUpdate {
        if (previous == next) return EngineUpdate.KEEP

        return EngineUpdate.APPLY
    }
}
