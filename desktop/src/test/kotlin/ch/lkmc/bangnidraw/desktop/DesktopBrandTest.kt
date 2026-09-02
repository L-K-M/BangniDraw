package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopBrandTest {

    @Test
    fun `desktop reads the canonical Android application name`() {
        assertEquals("帮你Draw", DesktopBrand.displayName)
    }

    @Test
    fun `desktop brand accepts reordered attributes and decodes XML text`() {
        val xml = """
            <resources>
                <string translatable="false" name = "app_name">
                    Draw &amp; Paint &lt;Fast&gt; &quot;Now&quot; &apos;Today&apos; &#39;Again&#39;
                </string>
            </resources>
        """.trimIndent()

        assertEquals(
            "Draw & Paint <Fast> \"Now\" 'Today' 'Again'",
            DesktopBrand.parseDisplayName(xml),
        )
    }

    @Test
    fun `desktop brand decodes one XML layer and rejects blank text`() {
        assertEquals(
            "&lt;",
            DesktopBrand.parseDisplayName(
                """<string name="app_name">&amp;lt;</string>""",
            ),
        )
        assertFailsWith<IllegalStateException> {
            DesktopBrand.parseDisplayName(
                """<string name="app_name">   </string>""",
            )
        }
    }

    @Test
    fun `export file stem removes path separators and reserved characters`() {
        assertEquals(
            "Bang_ni_Draw_",
            DesktopBrand.exportFileStem(" Bang/ni\\Draw:*? "),
        )
    }
}
