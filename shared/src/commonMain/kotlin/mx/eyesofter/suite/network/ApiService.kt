// Servicio de red HTTP que realiza peticiones al servidor para autenticación y consulta de resultados de tamizaje.
// Depende de los modelos mx.eyesofter.suite.model.* y la configuración mx.eyesofter.suite.config.AppConfig.
package mx.eyesofter.suite.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import mx.eyesofter.suite.config.AppConfig
import mx.eyesofter.suite.model.LoginRequest
import mx.eyesofter.suite.model.LoginResponse
import mx.eyesofter.suite.model.ResultadoTamizaje

class ApiService(private val client: HttpClient) {
    private val baseUrl = AppConfig.baseUrl

    suspend fun login(request: LoginRequest): LoginResponse {
        return client.post("$baseUrl/api/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun obtenerResultado(alumnoId: Int): ResultadoTamizaje {
        return client.get("$baseUrl/api/alumnos/$alumnoId/resultado").body()
    }
}
