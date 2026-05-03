package app.murmurnote.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.murmurnote.android.ui.navigation.MainScreen
import app.murmurnote.android.ui.navigation.MainViewModel
import app.murmurnote.android.ui.screen.debug.DebugScreen
import app.murmurnote.android.ui.screen.detail.DetailScreen
import app.murmurnote.android.ui.screen.onboarding.OnboardingScreen
import app.murmurnote.android.ui.screen.search.SearchScreen

@Composable
fun MurmurnoteApp(viewModel: MainViewModel = hiltViewModel()) {
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // DataStore 第一次 emit 之前不挂 NavHost，避免老用户冷启动时闪一下 Onboarding。
    // 用主题背景占位，视觉上和后续的 MAIN/ONBOARDING 衔接顺滑。
    val resolved = onboardingCompleted ?: run {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (resolved) Routes.MAIN else Routes.ONBOARDING
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onComplete = {
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.MAIN) {
            MainScreen(
                onOpenDetail = { id -> navController.navigate(Routes.detail(id)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenDebug = { navController.navigate(Routes.DEBUG) }
            )
        }
        composable(Routes.DETAIL_PATTERN) { entry ->
            DetailScreen(
                recordingId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenDetail = { id ->
                    navController.navigate(Routes.detail(id)) { popUpTo(Routes.MAIN) }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DEBUG) {
            DebugScreen(onBack = { navController.popBackStack() })
        }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val DEBUG = "debug"
    const val DETAIL_PATTERN = "detail/{id}"
    fun detail(id: String) = "detail/$id"
}
