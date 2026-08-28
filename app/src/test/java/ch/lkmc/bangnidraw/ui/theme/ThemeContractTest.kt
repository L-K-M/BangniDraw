package ch.lkmc.bangnidraw.ui.theme

import java.io.File
import kotlin.test.Test
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
            "statusBarStyle =" in activity && "navigationBarStyle =" in activity,
            "both system bars must receive the explicit style",
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
            File(repositoryRoot(), NIGHT_PLATFORM_THEME_PATH).exists(),
            "a values-night launch theme would reintroduce system dark mode",
        )
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
        val appThemeFlow = section(prefs, APP_THEME_FLOW_START, APP_THEME_FLOW_END)

        assertTrue(
            NULL_LOADING_THEME.containsMatchIn(rootViewModel),
            "the root theme state must start unloaded instead of assuming a palette",
        )
        assertTrue(
            ".retryWhen {" in appThemeFlow && "emit(AppTheme.DEFAULT)" in appThemeFlow,
            "a preference failure must release the loading gate with the default theme",
        )
        assertTrue(
            "delay(PREFERENCE_READ_RETRY_DELAY_MS)" in appThemeFlow,
            "theme observation must retry without a busy loop",
        )
        assertFalse(
            ".catch {" in appThemeFlow,
            "a terminal fallback would ignore later preference updates",
        )
        assertTrue(
            "if (error is CancellationException) throw error" in appThemeFlow,
            "theme fallback must preserve structured cancellation",
        )
        assertTrue(
            THEME_LOADING_GATE.containsMatchIn(activity),
            "MainActivity must withhold app content until the persisted theme arrives",
        )
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue(startIndex >= 0, "section start is missing: $start")
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue(endIndex >= contentStart, "section end is missing: $end")

        return source.substring(contentStart, endIndex)
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
        const val NIGHT_PLATFORM_THEME_PATH = "app/src/main/res/values-night/themes.xml"
        const val APP_THEME_FLOW_START = "internal val appTheme: Flow<AppTheme>"
        const val APP_THEME_FLOW_END = "internal suspend fun setAppTheme"
        val APP_THEME_ARGUMENT = Regex(
            """BangniTheme\(\s*appTheme\s*=\s*appTheme""",
        )
        val NULL_LOADING_THEME = Regex(
            """val appTheme:\s*AppTheme\?\s*=\s*null""",
        )
        val THEME_LOADING_GATE = Regex(
            """val appTheme\s*=\s*state\.appTheme\s*\?:\s*return@setContent""",
        )
    }
}
