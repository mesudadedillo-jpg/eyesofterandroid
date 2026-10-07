// Configuración del plugin ContentNegotiation con soporte para kotlinx.serialization en Ktor Server.
// Depende de io.ktor.server.plugins.contentnegotiation y kotlinx.serialization.json.Json.
package mx.eyesofter.suite.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
        })
    }
}
