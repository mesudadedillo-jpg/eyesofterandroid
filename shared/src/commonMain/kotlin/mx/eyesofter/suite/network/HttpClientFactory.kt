// Fábrica encargada de instanciar y configurar el cliente HTTP Ktor con soporte de serialización JSON.
// Depende del módulo Ktor Client y kotlinx.serialization.
package mx.eyesofter.suite.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    fun create(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
    }
}
