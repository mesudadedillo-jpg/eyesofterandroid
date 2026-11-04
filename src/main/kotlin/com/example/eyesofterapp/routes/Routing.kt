package com.example.eyesofterapp.routes

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Índice de rutas: aquí se juntan todas. Para agregar una ruta nueva
 * (por ejemplo las de ópticas en la Fase 2) se crea su archivo en routes/
 * y se llama desde aquí.
 */
fun Application.configureRouting() {
    routing {
        // GET / → antes llamaba a sayHello("Ktor Server") del módulo :core.
        // Como :core ya no existe aquí, se escribe el mismo texto directamente.
        get("/") {
            call.respondText("Hello, Ktor Server!")
        }

        saludRoutes()   // GET /salud
        authRoutes()    // POST /api/login
        opticasRoutes() // GET /opticas/cercanas
    }
}
