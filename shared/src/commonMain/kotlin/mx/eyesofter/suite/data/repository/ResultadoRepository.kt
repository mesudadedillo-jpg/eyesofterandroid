// Repositorio principal que coordina las llamadas a la red y el respaldo de la caché local en DataStore.
// Depende de mx.eyesofter.suite.network.ApiService y mx.eyesofter.suite.data.local.ResultadoLocalDataSource.
package mx.eyesofter.suite.data.repository

import kotlinx.coroutines.flow.Flow
import mx.eyesofter.suite.data.local.ResultadoLocalDataSource
import mx.eyesofter.suite.model.ResultadoTamizaje
import mx.eyesofter.suite.network.ApiService

class ResultadoRepository(
    private val apiService: ApiService,
    private val localDataSource: ResultadoLocalDataSource
) {
    val ultimoResultadoLocal: Flow<ResultadoTamizaje?> = localDataSource.ultimoResultado

    suspend fun obtenerResultado(alumnoId: Int): Result<ResultadoTamizaje> {
        return try {
            val resultadoRemoto = apiService.obtenerResultado(alumnoId)
            localDataSource.guardarResultado(resultadoRemoto)
            Result.success(resultadoRemoto)
        } catch (e: Exception) {
            val resultadoLocal = localDataSource.obtenerUltimoResultado()
            if (resultadoLocal != null) {
                Result.success(resultadoLocal)
            } else {
                Result.failure(e)
            }
        }
    }
}
