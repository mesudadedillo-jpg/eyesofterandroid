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

        val userRecord = servicios.usuarios.findByUsername(request.username)

        if (userRecord == null || request.password != userRecord.password) {
            call.application.environment.log.info("Login rechazado para: ${request.username}")
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas"))
            return@post
        }

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
