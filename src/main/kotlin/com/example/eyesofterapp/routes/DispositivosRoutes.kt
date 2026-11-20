package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.RegistroDispositivoDto
import com.example.eyesofterapp.plugins.sesion
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post

private val PLATAFORMAS = setOf("ANDROID", "IOS")

/**
 *  POST   /api/dispositivos            La app registra su token de Firebase tras el login
 *                                      (y cada vez que Firebase le da uno nuevo).
 *  DELETE /api/dispositivos?token=...  Al cerrar sesión, para que ese teléfono deje de recibir avisos.
 */
fun Route.dispositivosRoutes(servicios: Servicios) {
    post("/api/dispositivos") {
        val sesion = call.sesion()
        val entrada = call.receive<RegistroDispositivoDto>()
        val plataforma = entrada.plataforma.trim().uppercase()
        if (entrada.token.isBlank() || entrada.token.length > 4096) {
            call.error(HttpStatusCode.BadRequest, "token inválido")
            return@post
        }
        if (plataforma !in PLATAFORMAS) {
            call.error(HttpStatusCode.BadRequest, "plataforma debe ser ANDROID o IOS")
            return@post
        }
        servicios.dispositivos.registrar(entrada.token.trim(), sesion.id, plataforma, servicios.reloj())
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/api/dispositivos") {
        val sesion = call.sesion()
        val token = call.request.queryParameters["token"]
        if (token.isNullOrBlank()) {
            call.error(HttpStatusCode.BadRequest, "Falta el parámetro token")
            return@delete
        }
        // 204 aunque no existiera: el resultado final es el mismo (ese token ya no recibe avisos).
        servicios.dispositivos.eliminar(token, sesion.id)
        call.respond(HttpStatusCode.NoContent)
    }
}
