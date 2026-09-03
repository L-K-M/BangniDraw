package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.desktop.MachOFixtures.CPU_TYPE_ARM64
import ch.lkmc.bangnidraw.desktop.MachOFixtures.CPU_TYPE_X86_64
import ch.lkmc.bangnidraw.desktop.MachOFixtures.version
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachOLibraryTest {

    @Test
    fun `a thin arm64 library reports its architecture and minimum macOS`() {
        val library = MachOFixtures.thin(CPU_TYPE_ARM64, version(12, 0, 0))

        assertEquals(listOf("aarch64"), MachOLibrary.of(library))
        assertEquals("aarch64", MachOLibrary.describe(library))
        assertEquals("12.0", MachOLibrary.minimumMacOs(library))
    }

    @Test
    fun `an architecture the JVM cannot run is refused, not merely described`() {
        val intel = MachOFixtures.thin(CPU_TYPE_X86_64, version(12, 0, 0))

        assertEquals(false, MachOLibrary.runsOn(intel, "aarch64"))
        assertEquals(true, MachOLibrary.runsOn(intel, "x86_64"))
        // Java on macOS says aarch64; other tools say arm64. Both must match.
        assertEquals(true, MachOLibrary.runsOn(MachOFixtures.thin(CPU_TYPE_ARM64), "arm64"))
        assertEquals(true, MachOLibrary.runsOn(MachOFixtures.thin(CPU_TYPE_X86_64), "amd64"))
    }

    @Test
    fun `a universal library runs on either architecture`() {
        val universal = MachOFixtures.fat(listOf(CPU_TYPE_ARM64, CPU_TYPE_X86_64))

        assertEquals(listOf("aarch64", "x86_64"), MachOLibrary.of(universal))
        assertEquals("aarch64+x86_64", MachOLibrary.describe(universal))
        assertEquals(true, MachOLibrary.runsOn(universal, "x86_64"))
    }

    @Test
    fun `a 64-bit fat header reads every slice, not just the first`() {
        // fat_arch_64 entries are 32 bytes; a 20-byte stride reads the second
        // slice's cputype out of the first slice's offset field.
        val universal = MachOFixtures.fat64(listOf(CPU_TYPE_ARM64, CPU_TYPE_X86_64))

        assertEquals(listOf("aarch64", "x86_64"), MachOLibrary.of(universal))
        assertEquals(true, MachOLibrary.runsOn(universal, "x86_64"))
    }

    @Test
    fun `a fat library reports the minimum macOS of the slice this JVM loads`() {
        val universal = MachOFixtures.fat64WithSlices(
            listOf(CPU_TYPE_ARM64 to version(12, 0, 0), CPU_TYPE_X86_64 to version(11, 3, 0)),
        )

        assertEquals("12.0", MachOLibrary.minimumMacOs(universal, "aarch64"))
        assertEquals("11.3", MachOLibrary.minimumMacOs(universal, "x86_64"))
        // No slice for this host: the first is still better than silence.
        assertEquals("12.0", MachOLibrary.minimumMacOs(universal, "riscv64"))
    }

    @Test
    fun `a 32-bit header is walked from its own 28-byte size`() {
        val library = MachOFixtures.thin32(CPU_TYPE_X86_64, version(10, 13, 0))

        assertEquals("10.13", MachOLibrary.minimumMacOs(library, "x86_64"))
    }

    @Test
    fun `a patch-level minimum keeps its third component`() {
        assertEquals(
            "11.0.1",
            MachOLibrary.minimumMacOs(MachOFixtures.thin(CPU_TYPE_ARM64, version(11, 0, 1))),
        )
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
        assertNull(MachOLibrary.minimumMacOs(MachOFixtures.thin(CPU_TYPE_ARM64)))
    }
}
