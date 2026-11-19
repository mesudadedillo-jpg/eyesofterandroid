package com.example.eyesofterapp.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * Convierte las excepciones en respuestas {"error": "..."}:
 *  - JSON mal formado o con campos faltantes → 400 (antes respondía 500).
 *  - Cualquier otra cosa inesperada → 500, y se registra en la bitácora (C-RS-03).
 */
fun Application.configureErrores() {
    val log = LoggerFactory.getLogger("errores")
    install(StatusPages) {
        exception<BadRequestException> { call, causa ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cuerpo de la petición inválido: ${causa.message}"))
        }
        exception<ContentTransformationException> { call, causa ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cuerpo de la petición inválido: ${causa.message}"))
        }
        exception<Throwable> { call, causa ->
            log.error("Error no controlado en ${call.request.local.uri}", causa)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error interno del servidor"))
        }
    }
}
