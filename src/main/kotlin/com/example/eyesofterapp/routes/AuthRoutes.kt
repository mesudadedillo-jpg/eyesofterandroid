package com.example.eyesofterapp.routes

import com.example.eyesofterapp.data.UserRepository
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
 *  - 200 con un token SIMULADO y los datos del usuario si todo está bien.
 *  - 500 si algo falla (por ejemplo, el JSON que llega está mal formado).
 */
fun Route.authRoutes() {
    post("/api/login") {
        try {
            val request = call.receive<LoginRequestDto>()

            println("Intento de login recibido para usuario: ${request.username}")

            if (request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Contraseña requerida"))
                return@post
            }

            val userRecord = UserRepository.findByUsername(request.username)

            if (userRecord == null || request.password != userRecord.password) {
                println("Login rechazado para: ${request.username}")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas"))
                return@post
            }

            // Todavía no es un JWT real: solo un texto con la hora actual.
            val response = LoginResponseDto(
                token = "jwt-mock-token-${System.currentTimeMillis()}",
                user = UserDto(
                    username = userRecord.username,
                    fullName = userRecord.fullName,
                    role = userRecord.role
                )
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno")))
        }
    }
}
