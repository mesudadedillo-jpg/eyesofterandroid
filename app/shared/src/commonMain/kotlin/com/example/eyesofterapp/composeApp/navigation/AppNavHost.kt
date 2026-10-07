package com.example.eyesofterapp.composeApp.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.eyesofterapp.composeApp.screens.AdminScreen
import com.example.eyesofterapp.composeApp.screens.AlumnoScreen
import com.example.eyesofterapp.composeApp.screens.ClienteScreen
import com.example.eyesofterapp.composeApp.screens.DoctorScreen
import com.example.eyesofterapp.composeApp.screens.LoginScreen
import com.example.eyesofterapp.composeApp.screens.RoleScaffold
import com.example.eyesofterapp.composeApp.screens.SupervisorScreen
import com.example.eyesofterapp.ui.AuthUiState

/**
 * Navegacion principal. Toda depende de UNA linea:
 *   sin sesion -> Login ; con sesion -> la pantalla que le toca a su rol.
 */
@Composable
fun AppNavHost(
    state: AuthUiState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    // "?." evita error si algo es null; "?:" significa "si es null, usa lo de la derecha"
    val screen: Screen = state.session?.user?.toScreen() ?: Screen.Login

    // Crossfade hace un fundido suave cuando cambia de pantalla
    Crossfade(targetState = screen, label = "navegacion") { current ->
        when (current) {
            Screen.Login -> LoginScreen(state, onLogin)
            is Screen.Admin -> AdminScreen(current.user, onLogout)
            is Screen.Supervisor -> SupervisorScreen(current.user, onLogout)
            is Screen.Cliente -> ClienteScreen(current.user, onLogout)
            is Screen.Doctor -> DoctorScreen(current.user, onLogout)
            is Screen.Alumno -> AlumnoScreen(current.user, onLogout)
            is Screen.Desconocido -> RoleScaffold("Rol no reconocido", current.user, onLogout) {
                Text("Tu rol no tiene una vista asignada. Contacta al administrador.")
            }
        }
    }
}