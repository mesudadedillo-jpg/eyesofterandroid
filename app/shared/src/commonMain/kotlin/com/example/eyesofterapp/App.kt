package com.example.eyesofterapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.eyesofterapp.composeApp.navigation.AppNavHost
import com.example.eyesofterapp.composeApp.theme.EyeSofterTheme
import com.example.eyesofterapp.domain.model.Role
import com.example.eyesofterapp.domain.model.Session
import com.example.eyesofterapp.domain.model.User
import com.example.eyesofterapp.ui.AuthUiState

/*
 * VERSION TEMPORAL de App.kt (Integrante 3).
 * Funciona SIN servidor: simula un login para poder ver cada pantalla por rol.
 * Cambia Role.DOCTOR por otro rol para probar cada vista.
 * Cuando el Integrante 2 suba AuthViewModel y AppContainer, se reemplaza por la version final.
 */
@Composable
fun App() {
    EyeSofterTheme {
        var state by remember { mutableStateOf(AuthUiState()) }

        AppNavHost(
            state = state,
            onLogin = { username, _ ->
                state = AuthUiState(
                    session = Session(
                        token = "token-falso",
                        user = User(username, "Usuario de prueba", Role.DOCTOR)
                    )
                )
            },
            onLogout = { state = AuthUiState() }
        )
    }
}
