package ch.lkmc.bangnidraw.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachOLibraryTest {

    @Test
    fun `a thin arm64 library reports its architecture and minimum macOS`() {
        val library = thin(CPU_TYPE_ARM64, minos = version(12, 0, 0))

        assertEquals(listOf("aarch64"), MachOLibrary.of(library))
        assertEquals("aarch64", MachOLibrary.describe(library))
        assertEquals("12.0", MachOLibrary.minimumMacOs(library))
    }

    @Test
    fun `an architecture the JVM cannot run is refused, not merely described`() {
        val intel = thin(CPU_TYPE_X86_64, minos = version(12, 0, 0))

        assertEquals(false, MachOLibrary.runsOn(intel, "aarch64"))
        assertEquals(true, MachOLibrary.runsOn(intel, "x86_64"))
        // Java on macOS says aarch64; other tools say arm64. Both must match.
        assertEquals(true, MachOLibrary.runsOn(thin(CPU_TYPE_ARM64), "arm64"))
        assertEquals(true, MachOLibrary.runsOn(thin(CPU_TYPE_X86_64), "amd64"))
    }

    @Test
    fun `a universal library runs on either architecture`() {
        val universal = fat(listOf(CPU_TYPE_ARM64, CPU_TYPE_X86_64))

        assertEquals(listOf("aarch64", "x86_64"), MachOLibrary.of(universal))
        assertEquals("aarch64+x86_64", MachOLibrary.describe(universal))
        assertEquals(true, MachOLibrary.runsOn(universal, "x86_64"))
    }

    @Test
    fun `a patch-level minimum keeps its third component`() {
        assertEquals("11.0.1", MachOLibrary.minimumMacOs(thin(CPU_TYPE_ARM64, version(11, 0, 1))))
    }

    @Test
    fun `an unreadable header is unknown rather than a guess`() {
        val text = Files.createTempFile("bangnidraw-macho", ".dylib").toFile()
        text.writeText("not a Mach-O file")

        assertTrue(MachOLibrary.of(text).isEmpty())
        assertEquals("unknown", MachOLibrary.describe(text))
        assertNull(MachOLibrary.runsOn(text, "aarch64"))
        assertNull(MachOLibrary.minimumMacOs(text))
        assertNull(MachOLibrary.minimumMacOs(File(text.parentFile, "absent.dylib")))
    }

    @Test
    fun `a header without a build version reports no minimum`() {
        assertNull(MachOLibrary.minimumMacOs(thin(CPU_TYPE_ARM64, minos = null)))
    }

    private fun version(major: Int, minor: Int, patch: Int): Int =
        (major shl 16) or (minor shl 8) or patch

    /** A 64-bit little-endian Mach-O header, optionally carrying LC_BUILD_VERSION. */
    private fun thin(cpuType: Int, minos: Int? = null): File {
        val commands = if (minos == null) 0 else 1
        val bytes = ByteBuffer.allocate(MACH_HEADER_64_BYTES + BUILD_VERSION_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(MH_MAGIC_64)
        bytes.putInt(cpuType)
        bytes.putInt(0) // cpusubtype
        bytes.putInt(MH_DYLIB)
        bytes.putInt(commands)
        bytes.putInt(if (minos == null) 0 else BUILD_VERSION_BYTES)
        bytes.putInt(0) // flags
        bytes.putInt(0) // reserved
        if (minos != null) {
            bytes.putInt(LC_BUILD_VERSION)
            bytes.putInt(BUILD_VERSION_BYTES)
            bytes.putInt(PLATFORM_MACOS)
            bytes.putInt(minos)
            bytes.putInt(minos) // sdk
            bytes.putInt(0) // ntools
        }

        return write(bytes.array().copyOf(bytes.position()))
    }

    /** A fat header: architectures are big-endian, unlike the slices they point at. */
    private fun fat(cpuTypes: List<Int>): File {
        val bytes = ByteBuffer.allocate(FAT_HEADER_BYTES + cpuTypes.size * FAT_ARCH_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        bytes.putInt(FAT_MAGIC)
        bytes.putInt(cpuTypes.size)
        cpuTypes.forEach { cpuType ->
            bytes.putInt(cpuType)
            bytes.putInt(0) // cpusubtype
            bytes.putInt(0) // offset
            bytes.putInt(0) // size
            bytes.putInt(0) // align
        }

        return write(bytes.array())
    }

    private fun write(bytes: ByteArray): File {
        val file = Files.createTempFile("bangnidraw-macho", ".dylib").toFile()
        file.writeBytes(bytes)

        return file
    }

    private companion object {
        const val MH_MAGIC_64 = 0xfeedfacf.toInt()
        const val FAT_MAGIC = 0xcafebabe.toInt()
        const val MH_DYLIB = 6
        const val CPU_TYPE_ARM64 = 0x0100000c
        const val CPU_TYPE_X86_64 = 0x01000007
        const val LC_BUILD_VERSION = 0x32
        const val PLATFORM_MACOS = 1
        const val MACH_HEADER_64_BYTES = 32
        const val BUILD_VERSION_BYTES = 24
        const val FAT_HEADER_BYTES = 8
        const val FAT_ARCH_BYTES = 20
    }
}
