package ch.lkmc.bangnidraw.desktop

import java.io.File

/**
 * Reads the load facts out of a Mach-O file's header: which architectures it
 * carries, and the oldest macOS it will load on.
 *
 * A bundled ANGLE for the wrong architecture is invisible to every error
 * message the platform produces — `dlopen` simply reports "not found" — and it
 * is a real possibility here, because the packaging step picks the ANGLE
 * download from the *Gradle* JVM's architecture while the shipped app runs on
 * whichever JVM jpackage bundled. So startup reports what it actually found on
 * disk rather than assuming the two agree.
 */
internal object MachOLibrary {
    /** The architectures [file] carries, spelled as the JVM's `os.arch`. */
    fun of(file: File): List<String> = try {
        read(file)
    } catch (failure: Throwable) {
        emptyList()
    }

    /** A one-word description for the startup report. */
    fun describe(file: File): String {
        val architectures = of(file)

        return if (architectures.isEmpty()) "unknown" else architectures.joinToString("+")
    }

    /** True when [file] can run on [osArch]; null when the header is unreadable. */
    fun runsOn(file: File, osArch: String): Boolean? {
        val architectures = of(file)
        if (architectures.isEmpty()) return null

        return architectures.any { it == normalize(osArch) }
    }

    /**
     * The oldest macOS [file] loads on (LC_BUILD_VERSION / LC_VERSION_MIN_MACOSX),
     * or null when the header does not say. dyld refuses an older host outright,
     * which otherwise looks exactly like a missing library.
     */
    fun minimumMacOs(file: File, osArch: String = System.getProperty("os.arch", "")): String? = try {
        readMinimumMacOs(file, osArch)
    } catch (failure: Throwable) {
        null
    }

    private fun readMinimumMacOs(file: File, osArch: String): String? {
        val header = ByteArray(LOAD_COMMAND_BYTES)
        val length = file.inputStream().use { it.readNBytes(header, 0, header.size) }
        if (length < THIN_HEADER_BYTES) return null

        return when (beInt(header, 0)) {
            FAT_MAGIC, FAT_MAGIC_64 -> {
                // Every slice carries its own floor; report the one this JVM
                // would load, or the first if none matches.
                val slices = fatSlices(header, length)
                val slice = slices.firstOrNull { architecture(it.cpuType) == normalize(osArch) }
                    ?: slices.firstOrNull()
                    ?: return null

                minimumMacOsAt(file, slice.offset)
            }
            else -> minimumMacOsAt(file, 0L)
        }
    }

    /** Reads a thin Mach-O header at [start] and returns its minimum macOS. */
    private fun minimumMacOsAt(file: File, start: Long): String? {
        val header = ByteArray(LOAD_COMMAND_BYTES)
        val length = file.inputStream().use { stream ->
            stream.skipNBytes(start)
            stream.readNBytes(header, 0, header.size)
        }
        if (length < THIN_HEADER_BYTES) return null

        // A 32-bit header is 28 bytes, not 32: walking one from the wrong
        // offset decodes a misaligned command rather than declining.
        val headerBytes = when (beInt(header, 0)) {
            THIN_MAGIC_LE -> MACH_HEADER_64_BYTES
            THIN_MAGIC_LE_32 -> MACH_HEADER_BYTES
            else -> return null
        }

        val commands = leInt(header, 16)
        var offset = headerBytes
        for (index in 0 until commands) {
            if (offset + 8 > length) return null
            val command = leInt(header, offset)
            val size = leInt(header, offset + 4)
            if (size <= 0) return null
            if (command == LC_BUILD_VERSION && offset + 16 <= length) {
                return version(leInt(header, offset + 12))
            }
            if (command == LC_VERSION_MIN_MACOSX && offset + 12 <= length) {
                return version(leInt(header, offset + 8))
            }
            offset += size
        }

        return null
    }

    private fun version(packed: Int): String {
        val major = packed ushr 16
        val minor = (packed ushr 8) and 0xff
        val patch = packed and 0xff

        return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
    }

    private fun read(file: File): List<String> {
        val header = ByteArray(HEADER_BYTES)
        val length = file.inputStream().use { it.readNBytes(header, 0, header.size) }
        if (length < THIN_HEADER_BYTES) return emptyList()

        return when (val magic = beInt(header, 0)) {
            THIN_MAGIC_LE, THIN_MAGIC_LE_32 -> listOfNotNull(architecture(leInt(header, 4)))
            FAT_MAGIC, FAT_MAGIC_64 -> fatArchitectures(header, length)
            else -> if (magic == THIN_MAGIC_BE) listOfNotNull(architecture(beInt(header, 4))) else emptyList()
        }
    }

    private fun fatArchitectures(header: ByteArray, length: Int): List<String> =
        fatSlices(header, length).mapNotNull { architecture(it.cpuType) }.distinct()

    /** One entry per architecture in a fat header, in file order. */
    private fun fatSlices(header: ByteArray, length: Int): List<FatSlice> {
        val fat64 = beInt(header, 0) == FAT_MAGIC_64
        // fat_arch is 20 bytes; fat_arch_64 widens offset and size to 8 each.
        val stride = if (fat64) FAT_ARCH_64_BYTES else FAT_ARCH_BYTES
        val count = beInt(header, 4)
        if (count <= 0) return emptyList()

        val slices = mutableListOf<FatSlice>()
        for (index in 0 until count) {
            val offset = FAT_HEADER_BYTES + index * stride
            if (offset + stride > length) break
            val sliceOffset =
                if (fat64) beLong(header, offset + 8) else beInt(header, offset + 8).toLong()
            slices += FatSlice(beInt(header, offset), sliceOffset)
        }

        return slices
    }

    private class FatSlice(val cpuType: Int, val offset: Long)

    private fun architecture(cpuType: Int): String? = when (cpuType) {
        CPU_TYPE_ARM64 -> "aarch64"
        CPU_TYPE_X86_64 -> "x86_64"
        else -> null
    }

    private fun normalize(osArch: String): String = when (osArch) {
        "amd64", "x86-64" -> "x86_64"
        "arm64" -> "aarch64"
        else -> osArch
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or
            (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun beLong(bytes: ByteArray, offset: Int): Long =
        (beInt(bytes, offset).toLong() and 0xffffffffL shl 32) or
            (beInt(bytes, offset + 4).toLong() and 0xffffffffL)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset + 3].toInt() and 0xff shl 24) or
            (bytes[offset + 2].toInt() and 0xff shl 16) or
            (bytes[offset + 1].toInt() and 0xff shl 8) or
            (bytes[offset].toInt() and 0xff)

    // Magic values as they appear on disk, read big-endian.
    private const val THIN_MAGIC_LE = 0xcffaedfe.toInt()
    private const val THIN_MAGIC_LE_32 = 0xcefaedfe.toInt()
    private const val THIN_MAGIC_BE = 0xfeedfacf.toInt()
    private const val FAT_MAGIC = 0xcafebabe.toInt()
    private const val FAT_MAGIC_64 = 0xcafebabf.toInt()
    private const val CPU_TYPE_ARM64 = 0x0100000c
    private const val CPU_TYPE_X86_64 = 0x01000007
    private const val THIN_HEADER_BYTES = 8
    private const val FAT_HEADER_BYTES = 8
    private const val FAT_ARCH_BYTES = 20
    private const val FAT_ARCH_64_BYTES = 32
    private const val HEADER_BYTES = 256
    private const val LOAD_COMMAND_BYTES = 4096
    private const val MACH_HEADER_BYTES = 28
    private const val MACH_HEADER_64_BYTES = 32
    private const val LC_VERSION_MIN_MACOSX = 0x24
    private const val LC_BUILD_VERSION = 0x32
}
