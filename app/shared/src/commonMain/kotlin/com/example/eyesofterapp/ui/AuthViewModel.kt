package com.example.eyesofterapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private fun Throwable.toUserMessage(): String = when (this) {
        is InvalidCredentialsException -> "Usuario o contraseña incorrectos"
        is IllegalArgumentException -> message ?: "Datos invalidos"
        else -> "No se pudo conectar con el servidor. Revisa tu conexion."
    }
}