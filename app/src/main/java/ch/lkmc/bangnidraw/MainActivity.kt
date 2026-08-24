package ch.lkmc.bangnidraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.lkmc.bangnidraw.ui.navigation.BangniNavHost
import ch.lkmc.bangnidraw.ui.theme.BangniTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge: the canvas owns the whole screen (PLAN.md §1,
        // principle 1). The theme follows the system, so the default
        // auto() bar styles are right here — unlike Meltorama's always-dark
        // deck, there is no forced-dark special case.
        enableEdgeToEdge()
        setContent {
            BangniTheme {
                BangniNavHost()
            }
        }
    }
}
