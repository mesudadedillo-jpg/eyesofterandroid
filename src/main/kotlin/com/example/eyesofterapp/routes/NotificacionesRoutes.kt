package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.model.AvisoEntradaDto
import com.example.eyesofterapp.model.AvisoEnviadoDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.SeguimientoDto
import com.example.eyesofterapp.plugins.sesion
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Bandeja de notificaciones. Todas requieren sesión; cada usuario solo ve y
 * marca las suyas (C-RC-06).
 *
 *  GET  /api/notificaciones                 Bandeja + número de no leídas (?soloNoLeidas=true)
 *  POST /api/notificaciones/{id}/leida      Confirmación de lectura (C-RFN-07)
 *  POST /api/notificaciones/leidas          Marcar todas como leídas
 *  POST /api/notificaciones                 Enviar un aviso (Dirección / Enfermería)
 *  GET  /api/notificaciones/seguimiento     Quién leyó su resultado (Dirección / Enfermería)
 */
fun Route.notificacionesRoutes(servicios: Servicios) {
    val repo = servicios.notificaciones

    route("/api/notificaciones") {

        get {
            val sesion = call.sesion()
            val soloNoLeidas = call.request.queryParameters["soloNoLeidas"].toBoolean()
            val todas = repo.deUsuario(sesion.username)
            val mostradas = if (soloNoLeidas) todas.filter { it.leidaEn == null } else todas
            call.respond(BandejaDto(noLeidas = todas.count { it.leidaEn == null }, notificaciones = mostradas.map { it.toDto() }))
        }

        post("/{id}/leida") {
            val sesion = call.sesion()
            val id = call.parameters["id"]!!
            val marcada = repo.marcarLeida(id, sesion.username, servicios.reloj())
            if (marcada == null) {
                // 404 tanto si no existe como si es de otro: no se revela que existe.
                if (repo.buscar(id) != null) auditoria.warn("${sesion.username} intentó marcar la notificación ajena $id")
                call.error(HttpStatusCode.NotFound, "Notificación no encontrada")
                return@post
            }
            call.respond(marcada.toDto())
        }

        post("/leidas") {
            val sesion = call.sesion()
            val cambiadas = repo.marcarTodasLeidas(sesion.username, servicios.reloj())
            call.respond(mapOf("marcadas" to cambiadas))
        }

        post {
            val sesion = call.sesion()
            if (!sesion.esPersonalEscolar) {
                auditoria.warn("${sesion.username} (${sesion.rol}) intentó enviar un aviso")
                call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden enviar avisos")
                return@post
            }
            val entrada = call.receive<AvisoEntradaDto>()
            if (entrada.titulo.isBlank() || entrada.mensaje.isBlank()) {
                call.error(HttpStatusCode.BadRequest, "titulo y mensaje son obligatorios")
                return@post
            }
            if (entrada.usernames.isEmpty() && entrada.rol.isNullOrBlank()) {
                call.error(HttpStatusCode.BadRequest, "Indica usernames, rol o ambos")
                return@post
            }
            val porNombre = entrada.usernames.associateWith { servicios.usuarios.findByUsername(it) }
            val porRol = entrada.rol?.takeIf { it.isNotBlank() }?.let { servicios.usuarios.findByRole(it) }.orEmpty()
            val destinatarios = (porNombre.values.filterNotNull() + porRol).map { it.username.lowercase() }.distinct()

            destinatarios.forEach {
                servicios.notificador.notificar(it, TipoNotificacion.AVISO, entrada.titulo.trim(), entrada.mensaje.trim())
            }
            call.respond(
                HttpStatusCode.Created,
                AvisoEnviadoDto(enviadas = destinatarios.size, noEncontrados = porNombre.filterValues { it == null }.keys.toList())
            )
        }

        get("/seguimiento") {
            val sesion = call.sesion()
            if (!sesion.esPersonalEscolar) {
                auditoria.warn("${sesion.username} (${sesion.rol}) intentó ver el seguimiento de notificaciones")
                call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden ver el seguimiento")
                return@get
            }
            val soloNoLeidas = call.request.queryParameters["soloNoLeidas"].toBoolean()
            val filas = repo.deTipo(TipoNotificacion.RESULTADO_TAMIZAJE)
                .filter { !soloNoLeidas || it.leidaEn == null }
                .map { n ->
                    SeguimientoDto(
                        notificacionId = n.id,
                        tutor = n.destinatario,
                        alumno = n.datos["alumno"].orEmpty(),
                        tamizajeId = n.datos["tamizajeId"].orEmpty(),
                        creadaEn = n.creadaEn.iso(),
                        leidaEn = n.leidaEn?.iso(),
                        recordatoriosEnviados = n.recordatorios,
                        entregadaPor = n.entregas.filter { it.exito }.map { it.canal }.distinct(),
                    )
                }
            call.respond(filas)
        }
    }
}
