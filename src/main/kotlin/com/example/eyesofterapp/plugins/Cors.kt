package com.example.eyesofterapp.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/**
 * CORS: el navegador bloquea por seguridad que una página web llame a un
 * servidor de otro dominio. Esto le dice al navegador "sí está permitido".
 * Lo necesita la versión web de la app (webApp); Android no usa CORS.
 */
fun Application.configureCors() {
    install(CORS) {
        anyHost() // Cualquier dominio puede llamar (igual que antes)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }
}
