package ch.lkmc.bangnidraw.ui.canvas

/** Guarantees that a failed final save cannot trap the user on the Canvas. */
internal object CanvasLeaveCoordinator {

    suspend fun run(
        checkpoint: suspend () -> Unit,
        onCheckpointFailure: (Exception) -> Unit,
        navigate: suspend () -> Unit,
    ) {
        try {
            checkpoint()
        } catch (failure: Exception) {
            onCheckpointFailure(failure)
        } finally {
            navigate()
        }
    }
}
