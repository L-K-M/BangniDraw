package ch.lkmc.bangnidraw.engine.gl.platform

/**
 * The engine's logging seam for the nine former `android.util.Log` call
 * sites. ViewModels and the renderer have no Context and no locale
 * (AGENTS.md), so nothing richer than tag + message travels through here.
 */
expect object GlLog {
    fun w(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable?)
}
