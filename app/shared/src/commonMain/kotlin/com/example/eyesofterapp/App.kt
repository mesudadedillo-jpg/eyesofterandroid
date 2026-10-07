package com.example.eyesofterapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eyesofterapp.composeApp.navigation.AppNavHost
import com.example.eyesofterapp.composeApp.theme.EyeSofterTheme
import com.example.eyesofterapp.ui.AuthViewModel

/*
 * VERSION FINAL de App.kt (Integrante 3).
 * Se usa SOLO despues de hacer "git pull origin frontend" y tener los archivos del Integrante 2
 * (AuthViewModel.kt y AppContainer.kt).
 */
@Composable
fun App() {
    EyeSofterTheme {
        // Se crea una sola vez: arma HttpClient -> AuthApi -> Repository -> UseCase
        val container = remember { AppContainer() }

        // El ViewModel sobrevive a rotaciones de pantalla
        val authViewModel = viewModel<AuthViewModel> { AuthViewModel(container.loginUseCase) }

        // Convierte el StateFlow en un estado de Compose: cada cambio redibuja la pantalla
        val state by authViewModel.state.collectAsState()

        AppNavHost(
            state = state,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout
        )
    }
}