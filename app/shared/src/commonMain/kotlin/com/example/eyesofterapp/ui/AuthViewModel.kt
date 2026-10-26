package com.example.eyesofterapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eyesofterapp.data.network.BASE_URL
import com.example.eyesofterapp.domain.model.InvalidCredentialsException
import com.example.eyesofterapp.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(private val loginUseCase: LoginUseCase) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            loginUseCase(username, password)
                .onSuccess { session ->
                    _state.update { it.copy(isLoading = false, session = session) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.toUserMessage()) }
                }
        }
    }

    fun logout() {
        _state.value = AuthUiState()
    }

    private fun Throwable.toUserMessage(): String = when {
        this is InvalidCredentialsException -> "Usuario o contraseña incorrectos"
        this is IllegalArgumentException -> message ?: "Datos inválidos"
        else -> {
            val msg = message ?: ""
            if (msg.contains("connect", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("refused", ignoreCase = true) ||
                msg.contains("No se pudo conectar", ignoreCase = true)
            ) {
                "No se pudo conectar con el servidor ($BASE_URL). Revisa tu conexión a internet."
            } else {
                msg.ifBlank { "No se pudo conectar con el servidor. Revisa tu conexión a internet." }
            }
        }
    }
}
