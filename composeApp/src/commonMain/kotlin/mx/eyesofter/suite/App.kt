// Raíz composable compartida de la aplicación móvil Compose Multiplatform que inicializa Koin y la interfaz gráfica.
// Depende de mx.eyesofter.suite.di.appModule, mx.eyesofter.suite.navigation.AppNavigation y mx.eyesofter.suite.ui.theme.EyesofterTheme.
package mx.eyesofter.suite

import androidx.compose.runtime.Composable
import mx.eyesofter.suite.di.appModule
import mx.eyesofter.suite.navigation.AppNavigation
import mx.eyesofter.suite.ui.theme.EyesofterTheme
import org.koin.compose.KoinApplication
import org.koin.dsl.module

@Composable
fun App(androidContext: Any? = null) {
    KoinApplication(application = {
        if (androidContext != null) {
            val contextModule = module {
                single { androidContext }
            }
            modules(contextModule, appModule)
        } else {
            modules(appModule)
        }
    }) {
        EyesofterTheme {
            AppNavigation()
        }
    }
}
