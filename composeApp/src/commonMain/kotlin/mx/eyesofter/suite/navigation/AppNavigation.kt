// Componente NavHost que orquesta la navegación de pantallas en Compose Multiplatform.
// Depende de mx.eyesofter.suite.navigation.Screen, mx.eyesofter.suite.ui.screens.resultado.ResultadoScreen y Koin.
package mx.eyesofter.suite.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import mx.eyesofter.suite.ui.screens.resultado.ResultadoScreen
import mx.eyesofter.suite.ui.screens.resultado.ResultadoViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Resultado.route
    ) {
        composable(Screen.Resultado.route) {
            val viewModel: ResultadoViewModel = koinViewModel()
            ResultadoScreen(viewModel = viewModel)
        }
    }
}
