package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.model.SaludDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GET /salud → {"status":"ok","db":true,"version":"abc1234","push":true,...}
 * La app la usa para saber si el servidor está encendido; Railway la usa como healthcheck
 * (railway.json). Responde 200 aunque la base no conteste ("db": false, "status": "degradado"),
 * para que Railway no reinicie el servicio en bucle si la base tarda en arrancar.
 */
fun Route.saludRoutes(servicios: Servicios) {
    get("/salud") {
        val pendientes = withTimeoutOrNull(2_000) {
            runCatching {
                servicios.db.leer { c -> c.uno("select count(*) from notificaciones where pendiente_envio") { it.getInt(1) } ?: 0 }
            }.getOrNull()
        }
        call.respond(
            HttpStatusCode.OK,
            SaludDto(
                status = if (pendientes != null) "ok" else "degradado",
                db = pendientes != null,
                version = servicios.config.version,
                push = servicios.canalesActivos.contains("push"),
                correo = servicios.canalesActivos.contains("correo"),
                escritorio = true, // Vinculación de equipos siempre disponible (ya no depende de una clave).
                pendientesEnvio = pendientes ?: 0,
            )
        )
    }
}
