package com.excp.podroid.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.ui.screens.home.HomeScreen
import com.excp.podroid.ui.screens.settings.SettingsScreen
import com.excp.podroid.ui.screens.setup.SetupScreen
import com.excp.podroid.ui.screens.terminal.TerminalScreen
import javax.inject.Inject

object Routes {
    const val SETUP    = "setup"
    const val HOME     = "home"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

@Composable
fun PodroidNavGraph(
    settingsRepository: SettingsRepository,
    navController: NavHostController = rememberNavController(),
) {
    val isSetupDone by settingsRepository.isSetupDone.collectAsState(initial = null)

    // Wait until we know whether setup is done before deciding the start destination
    val startDestination = when (isSetupDone) {
        true  -> Routes.HOME
        false -> Routes.SETUP
        null  -> return // still loading — render nothing to avoid flicker
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToTerminal = {
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.TERMINAL) {
            TerminalScreen(
                onNavigateBack = {
                    if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}
