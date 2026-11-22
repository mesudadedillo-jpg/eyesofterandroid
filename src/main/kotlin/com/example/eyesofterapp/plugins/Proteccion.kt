package com.example.eyesofterapp.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/** Límite por IP para el login y los reportes: frena ataques de fuerza bruta y spam. */
val LIMITE_ACCESO = RateLimitName("acceso")

/**
 *  - X-Forwarded-For / -Proto: Railway pone un proxy delante; sin esto todas las peticiones
 *    parecerían venir de la misma IP y la bitácora no serviría.
 *  - Límite de 30 peticiones por minuto por IP en el login (además del bloqueo de cuenta,
 *    C-RFNN-01). 30 y no menos porque una escuela comparte una IP.
 *  - Encabezados: las respuestas con datos personales no se guardan en cachés intermedias.
 */
fun Application.configureProteccion() {
    install(XForwardedHeaders)
    install(RateLimit) {
        register(LIMITE_ACCESO) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteAddress }
        }
    }
    install(createApplicationPlugin("CabecerasSeguridad") {
        onCall { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            // Railway sirve por HTTPS: el navegador nunca debe intentar HTTP con este servidor.
            call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    })
}
