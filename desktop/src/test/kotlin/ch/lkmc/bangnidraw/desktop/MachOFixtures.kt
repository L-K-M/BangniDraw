package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Mach-O headers built byte by byte, shared by every test that needs a library
 * of a given architecture.
 *
 * One copy on purpose: two hand-rolled headers drift, and a suite that fails
 * because its private fixture was the stale one teaches nothing about the code
 * under test.
 */
internal object MachOFixtures {
    const val CPU_TYPE_ARM64 = 0x0100000c
    const val CPU_TYPE_X86_64 = 0x01000007

    /** A 64-bit thin library, optionally carrying LC_BUILD_VERSION. */
    fun thin(cpuType: Int, minimumMacOs: Int? = null): File =
        write(thinBytes(cpuType, minimumMacOs, MH_MAGIC_64, MACH_HEADER_64_BYTES))

    /** A 32-bit thin library: 28 header bytes before the load commands, not 32. */
    fun thin32(cpuType: Int, minimumMacOs: Int): File =
        write(thinBytes(cpuType, minimumMacOs, MH_MAGIC_32, MACH_HEADER_32_BYTES))

    /** A classic fat header: 20-byte entries, no slices behind them. */
    fun fat(cpuTypes: List<Int>): File {
        val bytes = ByteBuffer.allocate(FAT_HEADER_BYTES + cpuTypes.size * FAT_ARCH_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        bytes.putInt(FAT_MAGIC)
        bytes.putInt(cpuTypes.size)
        cpuTypes.forEach { cpuType ->
            bytes.putInt(cpuType)
            repeat(4) { bytes.putInt(0) } // cpusubtype, offset, size, align
        }

        return write(bytes.array())
    }

    /** A 64-bit fat header: 32-byte entries, no slices behind them. */
    fun fat64(cpuTypes: List<Int>): File = write(fat64Bytes(cpuTypes.map { it to 0 }))

    /** A 64-bit fat library whose slices exist, each with its own macOS floor. */
    fun fat64WithSlices(slices: List<Pair<Int, Int>>): File {
        val offsets = slices.indices.map { SLICE_BASE + it * SLICE_STRIDE }
        val bytes = ByteArray(SLICE_BASE + slices.size * SLICE_STRIDE)
        fat64Bytes(slices.map { it.first }.zip(offsets)).copyInto(bytes)
        slices.forEachIndexed { index, (cpuType, minimum) ->
            thinBytes(cpuType, minimum, MH_MAGIC_64, MACH_HEADER_64_BYTES)
                .copyInto(bytes, offsets[index])
        }

        return write(bytes)
    }

    /** Packs a macOS version the way LC_BUILD_VERSION does. */
    fun version(major: Int, minor: Int, patch: Int): Int =
        (major shl 16) or (minor shl 8) or patch

    fun write(bytes: ByteArray, directory: File? = null, name: String? = null): File {
        val file = if (directory == null || name == null) {
            Files.createTempFile("bangnidraw-macho", ".dylib").toFile()
        } else {
            File(directory, name)
        }
        file.writeBytes(bytes)

        return file
    }

    fun thinBytes(cpuType: Int, minimumMacOs: Int? = null): ByteArray =
        thinBytes(cpuType, minimumMacOs, MH_MAGIC_64, MACH_HEADER_64_BYTES)

    private fun thinBytes(
        cpuType: Int,
        minimumMacOs: Int?,
        magic: Int,
        headerBytes: Int,
    ): ByteArray {
        val commands = if (minimumMacOs == null) 0 else 1
        val bytes = ByteBuffer.allocate(headerBytes + commands * BUILD_VERSION_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(magic)
        bytes.putInt(cpuType)
        bytes.putInt(0) // cpusubtype
        bytes.putInt(MH_DYLIB)
        bytes.putInt(commands)
        bytes.putInt(commands * BUILD_VERSION_BYTES)
        bytes.putInt(0) // flags
        if (headerBytes == MACH_HEADER_64_BYTES) bytes.putInt(0) // reserved
        if (minimumMacOs != null) {
            bytes.putInt(LC_BUILD_VERSION)
            bytes.putInt(BUILD_VERSION_BYTES)
            bytes.putInt(PLATFORM_MACOS)
            bytes.putInt(minimumMacOs)
            bytes.putInt(minimumMacOs) // sdk
            bytes.putInt(0) // ntools
        }

        return bytes.array()
    }

    private fun fat64Bytes(entries: List<Pair<Int, Int>>): ByteArray {
        val bytes = ByteBuffer.allocate(FAT_HEADER_BYTES + entries.size * FAT_ARCH_64_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        bytes.putInt(FAT_MAGIC_64)
        bytes.putInt(entries.size)
        entries.forEach { (cpuType, offset) ->
            bytes.putInt(cpuType)
            bytes.putInt(0) // cpusubtype
            bytes.putLong(offset.toLong())
            bytes.putLong(0L) // size
            bytes.putInt(0) // align
            bytes.putInt(0) // reserved
        }

        return bytes.array()
    }

    private const val MH_MAGIC_64 = 0xfeedfacf.toInt()
    private const val MH_MAGIC_32 = 0xfeedface.toInt()
    private const val FAT_MAGIC = 0xcafebabe.toInt()
    private const val FAT_MAGIC_64 = 0xcafebabf.toInt()
    private const val MH_DYLIB = 6
    private const val LC_BUILD_VERSION = 0x32
    private const val PLATFORM_MACOS = 1
    private const val MACH_HEADER_32_BYTES = 28
    private const val MACH_HEADER_64_BYTES = 32
    private const val BUILD_VERSION_BYTES = 24
    private const val FAT_HEADER_BYTES = 8
    private const val FAT_ARCH_BYTES = 20
    private const val FAT_ARCH_64_BYTES = 32
    private const val SLICE_BASE = 4096
    private const val SLICE_STRIDE = 4096
}
