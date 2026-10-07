// Actividad Android principal que inicia el contexto de la aplicación y renderiza el composable App().
// Depende de mx.eyesofter.suite.App.
package mx.eyesofter.suite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App(androidContext = this.applicationContext)
        }
    }
}
