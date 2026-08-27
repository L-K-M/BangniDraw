package ch.lkmc.bangnidraw.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.lkmc.bangnidraw.ui.canvas.CanvasScreen
import ch.lkmc.bangnidraw.ui.home.StudioScreen
import kotlinx.serialization.Serializable

// Type-safe routes. Two rooms (PLAN.md §5): the Studio is where paintings
// hang, the Canvas is where one is painted.
@Serializable
object StudioRoute

/** The Canvas opens exactly one painting, by the id of its project folder. */
@Serializable
data class CanvasRoute(val projectId: String)

@Composable
fun BangniNavHost() {
    val navController = rememberNavController()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    NavHost(navController = navController, startDestination = StudioRoute) {
        composable<StudioRoute> {
            StudioScreen(
                onOpenPainting = { id -> navController.navigate(CanvasRoute(projectId = id)) },
                openSettings = openSettings,
                onSettingsOpened = { openSettings = false },
            )
        }
        composable<CanvasRoute> {
            // navigateUp, not popBackStack: a second tap during the pop
            // transition would otherwise pop the start destination too and
            // leave an empty NavHost; navigateUp no-ops at the root.
            CanvasScreen(
                onBack = { navController.navigateUp() },
                onSettings = {
                    openSettings = true
                    navController.navigateUp()
                },
            )
        }
    }
}
