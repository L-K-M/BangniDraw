package ch.lkmc.bangnidraw.ui.theme

import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.ThemeColorPolicy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ThemeContractTest {

    @Test
    fun `app theme ignores the system appearance`() {
        val theme = source(THEME_PATH)
        val activity = source(MAIN_ACTIVITY_PATH)
        val platformTheme = source(PLATFORM_THEME_PATH)

        assertFalse(
            "isSystemInDarkTheme" in theme,
            "BangniTheme must not follow the system dark-mode setting",
        )
        assertTrue(
            APP_THEME_ARGUMENT.containsMatchIn(activity),
            "MainActivity must feed the selected app theme into BangniTheme",
        )
        assertTrue(
            "SystemBarStyle.light(" in activity,
            "edge-to-edge bars must use an explicit light appearance",
        )
        assertTrue(
            "statusBarStyle =" in activity,
            "the status bar must receive the explicit style",
        )
        assertTrue(
            "navigationBarStyle =" in activity,
            "the navigation bar must receive the explicit style",
        )
        assertFalse(
            "SystemBarStyle.auto" in activity,
            "system bars must not infer their appearance from dark mode",
        )
        assertFalse(
            "DayNight" in platformTheme,
            "the launch theme must ignore system dark mode",
        )
        assertTrue(
            "<item name=\"android:forceDarkAllowed\">false</item>" in platformTheme,
            "the platform must not auto-darken the fixed-light palette",
        )
        assertFalse(
            File(repositoryRoot(), NIGHT_RESOURCES_PATH).exists(),
            "night resources would reintroduce system-driven appearance",
        )
    }

    @Test
    fun `scheme construction cannot inherit Material baseline roles`() {
        val colors = source(COLOR_SCHEME_PATH)

        assertTrue(
            "return ColorScheme(" in colors,
            "the complete constructor must make every role explicit",
        )
        assertFalse(
            "lightColorScheme(" in colors,
            "a defaulting factory can leak Material baseline colors",
        )
    }

    @Test
    fun `launch background matches the default palette`() {
        val resources = source(COLOR_RESOURCES_PATH)
        val actual = colorResource(resources, LAUNCH_BACKGROUND_RESOURCE)
        val expected = ThemeColorPolicy.colors(AppTheme.DEFAULT).backgroundArgb

        assertEquals(expected, actual)
    }

    @Test
    fun `theme choice crosses settings storage and root boundaries`() {
        val prefs = source(PREFS_PATH)
        val rootViewModel = source(APP_THEME_VIEW_MODEL_PATH)
        val studioViewModel = source(STUDIO_VIEW_MODEL_PATH)
        val studioScreen = source(STUDIO_SCREEN_PATH)

        assertTrue(
            "AppTheme.fromStored(it[KEY_APP_THEME])" in prefs,
            "Prefs must decode stored theme names safely",
        )
        assertTrue(
            "it[KEY_APP_THEME] = theme.name" in prefs,
            "Prefs must persist the selected theme name",
        )
        assertTrue(
            "prefs.appTheme" in rootViewModel,
            "the activity-scoped owner must observe the persisted theme",
        )
        assertFalse(
            "prefs.appTheme.collect" in studioViewModel,
            "the activity-scoped owner must be the only theme observer",
        )
        assertTrue(
            "appTheme = LocalAppTheme.current" in studioScreen,
            "Settings must show the root theme without a second preference collector",
        )
        assertTrue(
            "prefs.setAppTheme(value)" in studioViewModel,
            "Settings changes must write through Prefs",
        )
        assertTrue(
            "onAppTheme = viewModel::setAppTheme" in studioScreen,
            "the Settings callback must reach StudioViewModel",
        )
    }

    @Test
    fun `app content waits for the persisted theme`() {
        val activity = source(MAIN_ACTIVITY_PATH)
        val rootViewModel = source(APP_THEME_VIEW_MODEL_PATH)
        val prefs = source(PREFS_PATH)

        assertTrue(
            NULL_LOADING_THEME.containsMatchIn(rootViewModel),
            "the root theme state must start unloaded instead of assuming a palette",
        )
        assertTrue(
            RETRY_HELPER_CALL.containsMatchIn(prefs),
            "theme observation must use the tested read-recovery policy",
        )
        assertTrue(
            THEME_LOADING_GATE.containsMatchIn(activity),
            "MainActivity must withhold app content until the persisted theme arrives",
        )
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun colorResource(source: String, name: String): Int {
        val resource = Regex(
            """<color\s+name="${Regex.escape(name)}">\s*#([\dA-Fa-f]+)\s*</color>""",
        )
        val match = resource.find(source)
            ?: fail("color resource is missing: $name")
        val hex = match.groupValues[1]
        val argb = when (hex.length) {
            RGB_HEX_LENGTH -> "$OPAQUE_ALPHA$hex"
            ARGB_HEX_LENGTH -> hex
            else -> fail("unsupported color resource: #$hex")
        }

        return argb.toLong(HEX_RADIX).toInt()
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val THEME_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/theme/Theme.kt"
        const val MAIN_ACTIVITY_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/MainActivity.kt"
        const val PREFS_PATH = "app/src/main/java/ch/lkmc/bangnidraw/data/Prefs.kt"
        const val APP_THEME_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/theme/AppThemeViewModel.kt"
        const val STUDIO_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioViewModel.kt"
        const val STUDIO_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
        const val PLATFORM_THEME_PATH = "app/src/main/res/values/themes.xml"
        const val COLOR_SCHEME_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/theme/Color.kt"
        const val COLOR_RESOURCES_PATH = "app/src/main/res/values/colors.xml"
        const val NIGHT_RESOURCES_PATH = "app/src/main/res/values-night"
        const val LAUNCH_BACKGROUND_RESOURCE = "launch_background"
        const val RGB_HEX_LENGTH = 6
        const val ARGB_HEX_LENGTH = 8
        const val HEX_RADIX = 16
        const val OPAQUE_ALPHA = "FF"
        val APP_THEME_ARGUMENT = Regex(
            """BangniTheme\(\s*appTheme\s*=\s*appTheme""",
        )
        val NULL_LOADING_THEME = Regex(
            """val appTheme:\s*AppTheme\?\s*=\s*null""",
        )
        val RETRY_HELPER_CALL = Regex(
            """\bretryIoWithInitialFallback\s*\(""",
        )
        val THEME_LOADING_GATE = Regex(
            """val appTheme\s*=\s*state\.appTheme\s*\?:\s*return@setContent""",
        )
    }
}
