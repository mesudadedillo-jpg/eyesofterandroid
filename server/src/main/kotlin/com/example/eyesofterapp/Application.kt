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
import java.io.File

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


private fun findUsersFile(): File? {
    val candidates = listOfNotNull(
        System.getenv("USERS_FILE"),
        "src/main/resources/users.json",
        "server/src/main/resources/users.json"
    )
    return candidates.map { File(it) }.firstOrNull { it.isFile }
}

private fun loadUsers(): List<UserRecord> {
    val file = findUsersFile()
    return try {
        val content = if (file != null) {
            println("Leyendo usuarios desde: ${file.absolutePath}")
            file.readText(Charsets.UTF_8)
        } else {
            println("users.json no encontrado en disco; usando la copia empaquetada")
            Application::class.java.classLoader.getResourceAsStream("users.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return emptyList()
        }
        jsonDecoder.decodeFromString<List<UserRecord>>(content)
    } catch (e: Exception) {
        println("ERROR al leer users.json: ${e.message}")
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


                if (userRecord == null || request.password != userRecord.password) {
                    println("Login rechazado para: ${request.username}")
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas"))
                    return@post
                }

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
}