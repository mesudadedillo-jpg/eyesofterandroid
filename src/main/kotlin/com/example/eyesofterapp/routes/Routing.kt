package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.plugins.AUTH_APP
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Índice de rutas: aquí se juntan todas. Para agregar una ruta nueva
 * se crea su archivo en routes/ y se llama desde aquí.
 */
fun Application.configureRouting(servicios: Servicios) {
    routing {
        get("/") {
            call.respondText("Hello, Ktor Server!")
        }

        // --- Públicas ---
        saludRoutes(servicios)        // GET /salud
        authRoutes(servicios)         // POST /api/login
        opticasRoutes()               // GET /opticas/cercanas

        // --- Sistema de escritorio (clave X-Api-Key) ---
        escritorioRoutes(servicios)   // POST /api/escritorio/tamizajes

        // --- App con sesión (Authorization: Bearer <token>) ---
        authenticate(AUTH_APP) {
            notificacionesRoutes(servicios) // /api/notificaciones...
            dispositivosRoutes(servicios)   // /api/dispositivos
            tamizajesRoutes(servicios)      // GET /api/tamizajes...
            comprobantesRoutes(servicios)   // /api/comprobantes...
        }
    }
}
