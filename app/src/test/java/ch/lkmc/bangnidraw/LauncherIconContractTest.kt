package ch.lkmc.bangnidraw

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

class LauncherIconContractTest {

    @Test
    fun `adaptive launcher foreground uses the generated source artwork`() {
        val root = repositoryRoot()
        val source = File(root, SOURCE_PATH)
        val generator = File(root, GENERATOR_PATH).readText()

        assertTrue(source.isFile, "launcher source artwork is missing")
        assertTrue(
            SOURCE_PATH_COMPONENTS in generator,
            "the icon generator must read $SOURCE_PATH",
        )
        assertTrue(
            GENERATED_FILE_NAME in generator,
            "the icon generator must write $GENERATED_FILE_NAME",
        )

        // Every adaptive entry point must show the generated painting, not an empty layer.
        for (icon in ADAPTIVE_ICONS) {
            val xml = File(root, "$ADAPTIVE_ICON_DIRECTORY/$icon")
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(xml)
            val foregrounds = document.getElementsByTagName(FOREGROUND_TAG)

            assertEquals(1, foregrounds.length, "$icon must declare one foreground")
            val foreground = foregrounds.item(0) as Element
            assertEquals(
                GENERATED_ARTWORK_REFERENCE,
                foreground.getAttributeNS(ANDROID_NAMESPACE, DRAWABLE_ATTRIBUTE),
                "$icon foreground must reference artwork generated from $SOURCE_PATH",
            )
        }
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty(USER_DIRECTORY_PROPERTY)).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val SOURCE_PATH = "media-sources/icon.png"
        const val SOURCE_PATH_COMPONENTS = "\"media-sources\", \"icon.png\""
        const val GENERATOR_PATH = "scripts/generate_icons.py"
        const val GENERATED_FILE_NAME = "ic_launcher_bg.png"
        const val GENERATED_ARTWORK_REFERENCE = "@mipmap/ic_launcher_bg"
        const val ADAPTIVE_ICON_DIRECTORY = "app/src/main/res/mipmap-anydpi-v26"
        const val FOREGROUND_TAG = "foreground"
        const val DRAWABLE_ATTRIBUTE = "drawable"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val ADAPTIVE_ICONS = listOf("ic_launcher.xml", "ic_launcher_round.xml")
    }
}
