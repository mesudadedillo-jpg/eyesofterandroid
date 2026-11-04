package com.example.eyesofterapp.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/**
 * ContentNegotiation hace la traducción automática entre JSON y clases Kotlin:
 *  - call.receive<LoginRequestDto>() convierte el JSON que llega en un objeto.
 *  - call.respond(objeto) convierte el objeto en JSON para responder.
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}
