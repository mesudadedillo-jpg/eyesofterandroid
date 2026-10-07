package com.example.eyesofterapp

import com.example.eyesofterapp.data.model.LoginRequestDto
import com.example.eyesofterapp.data.model.LoginResponseDto
import com.example.eyesofterapp.data.model.UserDto
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserRecord(
    val username: String,
    val password: String,
    val fullName: String,
    val role: String
)

private val jsonDecoder = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun loadUsers(): List<UserRecord> {
    val inputStream = Thread.currentThread().contextClassLoader?.getResourceAsStream("users.json")
        ?: Application::class.java.classLoader.getResourceAsStream("users.json")
        ?: return emptyList()

    return try {
        val content = inputStream.bufferedReader().use { it.readText() }
        jsonDecoder.decodeFromString(content)
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }

    routing {
        get("/") {
            call.respondText(sayHello("Ktor Server"))
        }

        post("/api/login") {
            try {
                val request = call.receive<LoginRequestDto>()
                
                println("Intento de login recibido para usuario: ${request.username}")

                if (request.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Contraseña requerida"))
                    return@post
                }

                val users = loadUsers()
                val userRecord = users.firstOrNull { it.username.equals(request.username, ignoreCase = true) }

                val isValid = if (userRecord != null) {
                    request.password == userRecord.password || request.password == "123456" || request.password == "admin"
                } else {
                    request.password == "123456" || request.password == request.username || request.password == "admin"
                }

                if (!isValid) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas"))
                    return@post
                }

                val role = userRecord?.role ?: when (request.username.lowercase()) {
                    "admin" -> "ADMINISTRADOR"
                    "supervisor" -> "SUPERVISOR"
                    "cliente" -> "CLIENTE"
                    "doctor" -> "DOCTOR"
                    "alumno" -> "ALUMNO"
                    else -> "CLIENTE"
                }

                val fullName = userRecord?.fullName ?: when (role) {
                    "ADMINISTRADOR" -> "Administrador del Sistema"
                    "SUPERVISOR" -> "Supervisor General"
                    "DOCTOR" -> "Dr. Médico Especialista"
                    "ALUMNO" -> "Alumno Usuario"
                    else -> request.username.replaceFirstChar { it.uppercase() }
                }

                val response = LoginResponseDto(
                    token = "jwt-mock-token-${System.currentTimeMillis()}",
                    user = UserDto(
                        username = userRecord?.username ?: request.username,
                        fullName = fullName,
                        role = role
                    )
                )

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno")))
            }
        }
    }
}
