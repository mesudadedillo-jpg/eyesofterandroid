package com.example.eyesofterapp.routes

import com.example.eyesofterapp.model.SaludDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /salud → {"status":"ok","db":false}
 * La app la usa para saber si el servidor está encendido antes de hacer login.
 * "db" es false porque todavía no hay base de datos; cuando se conecte MySQL,
 * aquí se hará una consulta de prueba y se pondrá true si responde.
 */
fun Route.saludRoutes() {
    get("/salud") {
        call.respond(SaludDto(status = "ok", db = false))
    }
}
