package ch.lkmc.bangnidraw.desktop

/**
 * The host of the engine's offscreen GLES context.
 *
 * [EglEsContext] is the primary implementation on every platform;
 * [GlfwEsContext] is the fallback that hosts the context in a hidden window.
 */
internal interface DesktopEsContext {
    /** Attaches the context and LWJGL capabilities to the render thread. */
    fun activate()

    /** Detaches thread-local GLES state before the owner is destroyed. */
    fun deactivate()

    /** Prevents unsafe native teardown when the GL owner did not stop. */
    fun abandonAfterOwnerTimeout()

    /** Releases the native context on the thread that created it. */
    fun destroy()
}
