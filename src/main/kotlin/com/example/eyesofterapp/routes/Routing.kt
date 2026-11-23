package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.plugins.AUTH_APP
import com.example.eyesofterapp.plugins.LIMITE_ACCESO
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Índice de rutas: aquí se juntan todas. Para agregar una ruta nueva
 * se crea su archivo en routes/ y se llama desde aquí. Ver docs/API.md.
 */
fun Application.configureRouting(servicios: Servicios) {
    routing {
        get("/") { call.respond(mapOf("servicio" to "eyesofter-backend", "documentacion" to "docs/API.md")) }

        // --- Públicas ---
        saludRoutes(servicios)              // GET /salud
        opticasRoutes(servicios)            // GET /opticas/cercanas, GET /api/clinicas
        rateLimit(LIMITE_ACCESO) {
            authRoutes(servicios)           // POST /api/login
            recuperacionRoutes(servicios)   // POST /api/recuperar/solicitar, /api/recuperar/restablecer
            vinculacionRoutes(servicios)    // POST /api/escritorio/vinculaciones (el escritorio pide código)
            vinculacionFormularioRoutes(servicios) // POST /escritorio/vincular (navegador: login y aprobar)
            authenticate(AUTH_APP, optional = true) {
                reportesRoutes(servicios)   // POST /api/reportes (con o sin sesión)
            }
        }

        // --- Sistema de escritorio (equipo vinculado: Authorization: Bearer <token del equipo>) ---
        vinculacionEstadoRoutes(servicios)  // POST /api/escritorio/vinculaciones/estado, GET /escritorio/vincular
        escritorioRoutes(servicios)         // /api/escritorio/...

        // --- App con sesión (Authorization: Bearer <token>) ---
        authenticate(AUTH_APP) {
            consentimientoRoutes(servicios) // /api/consentimiento
            notificacionesRoutes(servicios) // /api/notificaciones...
            dispositivosRoutes(servicios)   // /api/dispositivos
            tamizajesRoutes(servicios)      // /api/tamizajes...
            comprobantesRoutes(servicios)   // /api/comprobantes...
        }
    }
}
