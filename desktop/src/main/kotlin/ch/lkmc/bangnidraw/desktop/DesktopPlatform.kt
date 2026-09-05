package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.DeviceMemory
import java.lang.management.ManagementFactory

/** The desktop platform facts the shared engine asks for. */
internal object DesktopPlatform {

    /**
     * System RAM via `com.sun.management.OperatingSystemMXBean` — the honest
     * analog of `ActivityManager.MemoryInfo.totalMem`, because the tile pool
     * is native/GPU memory and `Runtime.maxMemory()` is only the JVM heap
     * ceiling (DESKTOP.md "Data layer — Memory sizing"). Never the heap.
     */
    fun deviceMemory(): DeviceMemory {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val totalBytes = (os as com.sun.management.OperatingSystemMXBean).totalMemorySize

        return DeviceMemory(
            totalMemBytes = totalBytes,
            isLowRamDevice = totalBytes < LOW_RAM_BYTES,
            largeMemoryClassMb = (totalBytes / (1024L * 1024L)).toInt(),
            glMaxArrayLayers = 0,
            glMaxTextureSize = 0,
        )
    }

    /**
     * Whether this is macOS, which puts menu accelerators on Command rather
     * than Control — and puts the menu bar itself at the top of the screen.
     */
    val isMacOs: Boolean
        get() = (System.getProperty("os.name") ?: "").startsWith("Mac")

    /** The OS config directory; prefs and the undo journal live under it. */
    fun configDir(): java.io.File {
        val osName = System.getProperty("os.name") ?: "unknown"
        val base = when {
            osName.startsWith("Mac") ->
                java.io.File(System.getProperty("user.home"), "Library/Application Support")
            osName.startsWith("Windows") ->
                java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
            else ->
                java.io.File(System.getenv("XDG_CONFIG_HOME") ?: System.getProperty("user.home") + "/.config")
        }
        return java.io.File(base, "BangniDraw").apply { mkdirs() }
    }

    /** Where Save drops PNGs; created if missing (DESKTOP.md's export mapping). */
    fun picturesDir(): java.io.File =
        java.io.File(System.getProperty("user.home"), "Pictures/BangniDraw").apply { mkdirs() }

    private const val LOW_RAM_BYTES = 4L * 1024 * 1024 * 1024
}
