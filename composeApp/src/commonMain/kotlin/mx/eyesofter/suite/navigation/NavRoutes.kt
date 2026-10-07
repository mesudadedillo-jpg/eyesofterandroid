// Definición de las rutas de navegación tipadas para Compose Multiplatform.
// Este archivo representa la estructura de rutas y no depende de otros archivos del proyecto.
package mx.eyesofter.suite.navigation

sealed class Screen(val route: String) {
    data object Resultado : Screen("resultado")
}
