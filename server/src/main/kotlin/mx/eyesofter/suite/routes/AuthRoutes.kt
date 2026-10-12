// Definición de las rutas Ktor para la autenticación de usuarios contra users.json.
// Adaptado a la estructura: username, password, fullName, role.
package mx.eyesofter.suite.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mx.eyesofter.suite.model.LoginRequest
import mx.eyesofter.suite.model.LoginResponse
import mx.eyesofter.suite.model.Usuario

private val jsonSerializer = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun Route.authRoutes() {
    route("/api") {
        post("/login") {
            val request = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LoginResponse(exito = false, mensaje = "Formato de solicitud inválido")
                )
                return@post
            }

            val usuarios = cargarUsuarios()
            val usuarioEncontrado = usuarios.find {
                it.username.equals(request.username, ignoreCase = true) &&
                        it.passwordEfectivo == request.credencial
            }

            if (usuarioEncontrado != null) {
                call.respond(
                    HttpStatusCode.OK,
                    LoginResponse(
                        exito = true,
                        mensaje = "Inicio de sesión exitoso",
                        username = usuarioEncontrado.username,
                        fullName = usuarioEncontrado.nombreEfectivo,
                        role = usuarioEncontrado.rolEfectivo
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    LoginResponse(exito = false, mensaje = "Credenciales incorrectas")
                )
            }
        }
    }
}

private fun cargarUsuarios(): List<Usuario> {
    return try {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("users.json")
            ?: return emptyList()
        val content = stream.bufferedReader().use { it.readText() }
        jsonSerializer.decodeFromString<List<Usuario>>(content)
    } catch (e: Exception) {
        emptyList()
    }
}
