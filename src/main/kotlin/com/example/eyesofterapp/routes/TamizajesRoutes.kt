package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.data.Semaforo
import com.example.eyesofterapp.data.TamizajeRecord
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import com.example.eyesofterapp.plugins.sesion
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.time.LocalDate

/**
 * POST /api/escritorio/tamizajes
 *
 * El sistema de escritorio EYESOFTER manda aquí cada resultado (C-RFN-02),
 * autenticándose con el encabezado X-Api-Key = ESCRITORIO_API_KEY.
 * El servidor lo guarda y avisa al tutor por push (M-RFN-02) en segundos (M-RC-01).
 */
fun Route.escritorioRoutes(servicios: Servicios) {
    post("/api/escritorio/tamizajes") {
        val claveEsperada = servicios.config.apiKeyEscritorio
        if (claveEsperada == null) {
            call.error(HttpStatusCode.ServiceUnavailable, "La recepción de tamizajes no está configurada (ESCRITORIO_API_KEY)")
            return@post
        }
        val clave = call.request.headers["X-Api-Key"].orEmpty()
        // Comparación en tiempo constante para no filtrar la clave por tiempos de respuesta.
        if (!MessageDigest.isEqual(clave.toByteArray(), claveEsperada.toByteArray())) {
            auditoria.warn("Intento de enviar tamizaje con X-Api-Key inválida desde ${call.request.local.remoteAddress}")
            call.error(HttpStatusCode.Unauthorized, "X-Api-Key inválida")
            return@post
        }

        val e = call.receive<TamizajeEntradaDto>()
        val semaforo = Semaforo.entries.firstOrNull { it.name.equals(e.semaforo.trim(), ignoreCase = true) }
        val tutor = servicios.usuarios.findByUsername(e.tutorUsername.trim())
        val problema = when {
            e.folio.isBlank() -> "folio es obligatorio"
            e.alumnoNombre.isBlank() -> "alumnoNombre es obligatorio"
            semaforo == null -> "semaforo debe ser VERDE, AMARILLO o ROJO"
            runCatching { LocalDate.parse(e.fecha) }.isFailure -> "fecha debe tener el formato AAAA-MM-DD"
            tutor == null -> "El tutor ${e.tutorUsername} no existe"
            tutor.role != "CLIENTE" -> "El usuario ${e.tutorUsername} no es tutor (rol CLIENTE)"
            else -> null
        }
        if (problema != null) {
            call.error(HttpStatusCode.BadRequest, problema)
            return@post
        }

        val (tamizaje, esNuevo) = servicios.tamizajes.guardarSiEsNuevo { id ->
            TamizajeRecord(
                id = id,
                folio = e.folio.trim(),
                alumnoNombre = e.alumnoNombre.trim(),
                grado = e.grado.trim(),
                grupo = e.grupo.trim(),
                tutorUsername = tutor!!.username.lowercase(),
                fecha = e.fecha,
                semaforo = semaforo!!,
                observaciones = e.observaciones,
                recibidoEn = servicios.reloj(),
            )
        }

        if (!esNuevo) {
            call.respond(HttpStatusCode.OK, TamizajeRecibidoDto(tamizaje.id, notificacionId = null, duplicado = true))
            return@post
        }

        val notificacion = servicios.notificador.notificar(
            destinatario = tamizaje.tutorUsername,
            tipo = TipoNotificacion.RESULTADO_TAMIZAJE,
            titulo = "Resultado del tamizaje visual",
            // Sin el color del semáforo: la push pasa por servidores de Google/Apple.
            mensaje = "Ya está disponible el resultado del tamizaje visual de ${tamizaje.alumnoNombre}. Tócala para verlo.",
            datos = mapOf(
                "tamizajeId" to tamizaje.id,
                "alumno" to tamizaje.alumnoNombre,
                "ruta" to "tamizaje/${tamizaje.id}",
            ),
        )
        call.respond(HttpStatusCode.Created, TamizajeRecibidoDto(tamizaje.id, notificacion.id, duplicado = false))
    }
}

/**
 *  GET /api/tamizajes        Tutor: los de sus hijos. Dirección/Enfermería: todos.
 *  GET /api/tamizajes/{id}   El detalle, a donde lleva la notificación (M-RC-02).
 */
fun Route.tamizajesRoutes(servicios: Servicios) {
    get("/api/tamizajes") {
        val sesion = call.sesion()
        val lista = when {
            sesion.esPersonalEscolar -> servicios.tamizajes.todos()
            sesion.rol == "CLIENTE" -> servicios.tamizajes.deTutor(sesion.username)
            else -> emptyList()
        }
        call.respond(lista.map { it.toDto() })
    }

    get("/api/tamizajes/{id}") {
        val sesion = call.sesion()
        val id = call.parameters["id"]!!
        val t = servicios.tamizajes.buscar(id)
        if (t == null) {
            call.error(HttpStatusCode.NotFound, "Tamizaje no encontrado")
            return@get
        }
        if (!sesion.esPersonalEscolar && t.tutorUsername != sesion.username) {
            // C-RFNN-06: denegar y registrar el intento.
            auditoria.warn("${sesion.username} (${sesion.rol}) intentó ver el tamizaje ajeno $id")
            call.error(HttpStatusCode.NotFound, "Tamizaje no encontrado")
            return@get
        }
        call.respond(t.toDto())
    }
}
