package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.model.UserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * POST /api/login
 * Mismo comportamiento que tenía Application.kt en el proyecto original:
 *  - 400 si la contraseña viene vacía.
 *  - 401 si el usuario no existe o la contraseña no coincide.
 *  - 429 si la cuenta está bloqueada por intentos fallidos seguidos.
 *  - 200 con un token JWT firmado y los datos del usuario si todo está bien.
 *    La app debe mandar ese token en "Authorization: Bearer <token>" a las rutas con sesión.
 *  - 400 si el JSON que llega está mal formado (lo resuelve plugins/Errores.kt).
 */
fun Route.authRoutes(servicios: Servicios) {
    post("/api/login") {
        val request = call.receive<LoginRequestDto>()

        if (request.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Contraseña requerida"))
            return@post
        }

        // C-RFNN-01: tras varios intentos fallidos seguidos la cuenta se bloquea unos minutos.
        servicios.bloqueoLogin.minutosRestantes(request.username)?.let { minutos ->
            call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf("error" to "Cuenta bloqueada por intentos fallidos. Intenta de nuevo en $minutos min.")
            )
            return@post
        }

        val userRecord = servicios.usuarios.findByUsername(request.username)

        if (userRecord == null || request.password != userRecord.password) {
            val bloqueada = servicios.bloqueoLogin.registrarFallo(request.username)
            auditoria.info("Login rechazado para: ${request.username}${if (bloqueada) " (cuenta bloqueada)" else ""}")
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas"))
            return@post
        }
        servicios.bloqueoLogin.registrarExito(request.username)

        val response = LoginResponseDto(
            token = servicios.tokens.crear(userRecord.username.lowercase(), userRecord.role),
            user = UserDto(
                username = userRecord.username,
                fullName = userRecord.fullName,
                role = userRecord.role
            )
        )

        call.respond(HttpStatusCode.OK, response)
    }
}
