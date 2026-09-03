package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopGlReportTest {

    @Test
    fun `a failed startup carries its own evidence into the window`() {
        val report = DesktopGlReport()
        report.note("host: Mac OS X 15.5 aarch64, JVM 17.0.20 (Temurin)")
        report.note(
            "ANGLE: /Applications/x.app/Contents/app/resources " +
                "(libEGL.dylib x86_64, libGLESv2.dylib x86_64, macOS 12.0+)" +
                " — built for another architecture, this JVM is aarch64",
        )
        report.fail("EGL", "eglInitialize failed: EGL_NOT_INITIALIZED")
        report.fail("GLFW", "no GLES 3.0 EGL context (EGL: Library not found [65543])")

        val message = DesktopGlDiagnostics.failure(report)

        assertNull(report.path)
        assertTrue(message.startsWith("OpenGL ES 3.0 is unavailable."))
        // Both hosts, so a report never leaves the reader guessing which ran.
        assertTrue(message.contains("EGL: eglInitialize failed: EGL_NOT_INITIALIZED"))
        assertTrue(message.contains("GLFW: no GLES 3.0 EGL context"))
        assertTrue(message.contains("built for another architecture"))
        assertTrue(message.contains("--gl-report"))
        assertTrue(message.contains("Linux: install Mesa libEGL and libGLESv2"))
    }

    @Test
    fun `a working host is named and leaves no failures behind`() {
        val report = DesktopGlReport()
        report.note("EGL display: ANGLE/Metal")
        report.succeed("EGL")

        assertEquals("EGL", report.path)
        assertEquals("", report.failures())
        assertTrue(report.text().contains("EGL display: ANGLE/Metal"))
        assertTrue(report.text().contains("EGL context created"))
    }

    @Test
    fun `details stay indented under the failure so the window reads as one block`() {
        val report = DesktopGlReport()
        report.note("host: Linux")
        report.fail("EGL", "no display: EGL_BAD_DISPLAY")

        val message = DesktopGlDiagnostics.failure(report)

        assertTrue(message.contains("Details:\n  host: Linux"))
        assertTrue(report.text().startsWith("host: Linux"))
    }
}
