// Controlador de vista para iOS (UIViewController) que encapsula la raíz composable de la aplicación.
// Depende de mx.eyesofter.suite.App.
package mx.eyesofter.suite

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { App() }
