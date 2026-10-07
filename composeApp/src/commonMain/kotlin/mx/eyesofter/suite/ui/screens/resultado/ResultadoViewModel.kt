// ViewModel que gestiona el estado de la pantalla de resultados del tamizaje mediante StateFlow.
// Depende del repositorio mx.eyesofter.suite.data.repository.ResultadoRepository y del modelo mx.eyesofter.suite.model.ResultadoTamizaje.
package mx.eyesofter.suite.ui.screens.resultado

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mx.eyesofter.suite.data.repository.ResultadoRepository
import mx.eyesofter.suite.model.ResultadoTamizaje

sealed interface ResultadoUiState {
    data object Cargando : ResultadoUiState
    data class Exito(val resultado: ResultadoTamizaje) : ResultadoUiState
    data class Error(val mensaje: String) : ResultadoUiState
}

class ResultadoViewModel(
    private val repository: ResultadoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResultadoUiState>(ResultadoUiState.Cargando)
    val uiState: StateFlow<ResultadoUiState> = _uiState.asStateFlow()

    init {
        cargarResultado(1)
    }

    fun cargarResultado(alumnoId: Int) {
        viewModelScope.launch {
            _uiState.value = ResultadoUiState.Cargando
            repository.obtenerResultado(alumnoId)
                .onSuccess { resultado ->
                    _uiState.value = ResultadoUiState.Exito(resultado)
                }
                .onFailure { error ->
                    _uiState.value = ResultadoUiState.Error(
                        error.message ?: "No se pudo cargar el resultado del tamizaje."
                    )
                }
        }
    }
}
