// Servicio de red HTTP que realiza peticiones al servidor para obtener los resultados de tamizaje de un alumno.
// Depende del modelo mx.eyesofter.suite.model.ResultadoTamizaje y la configuración mx.eyesofter.suite.config.AppConfig.
package mx.eyesofter.suite.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import mx.eyesofter.suite.config.AppConfig
import mx.eyesofter.suite.model.ResultadoTamizaje

class ApiService(private val client: HttpClient) {
    private val baseUrl = AppConfig.baseUrl

    suspend fun obtenerResultado(alumnoId: Int): ResultadoTamizaje {
        return client.get("$baseUrl/api/alumnos/$alumnoId/resultado").body()
    }
}
