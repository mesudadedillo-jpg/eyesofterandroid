package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.SaludDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /salud → {"status":"ok","db":false,"version":"abc1234","push":true,...}
 * La app la usa para saber si el servidor está encendido; Railway la usa como
 * healthcheck (railway.json). Los campos extra dicen qué está configurado, para
 * revisar el despliegue sin entrar al panel. "db" es false porque todavía no hay MySQL.
 */
fun Route.saludRoutes(servicios: Servicios) {
    get("/salud") {
        call.respond(
            SaludDto(
                status = "ok",
                db = false,
                version = servicios.config.version,
                push = servicios.canalesActivos.contains("push"),
                correo = servicios.canalesActivos.contains("correo"),
                escritorio = servicios.config.apiKeyEscritorio != null,
            )
        )
    }
}
