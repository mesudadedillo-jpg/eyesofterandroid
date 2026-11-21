package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.AvisoEntradaDto
import com.example.eyesofterapp.model.AvisoEnviadoDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.SeguimientoDto
import com.example.eyesofterapp.plugins.sesion
import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.repos.TipoNotificacion
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Bandeja de notificaciones (la MISMA que ve el portal). Todas requieren sesión; cada usuario
 * solo ve y marca las suyas (C-RC-06).
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
            val todas = repo.deUsuario(sesion.id)
            val mostradas = if (soloNoLeidas) todas.filter { it.leidaEn == null } else todas
            call.respond(BandejaDto(noLeidas = repo.noLeidas(sesion.id), notificaciones = mostradas.map { it.toDto() }))
        }

        post("/{id}/leida") {
            val sesion = call.sesion()
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            val marcada = id?.let { repo.marcarLeida(it, sesion.id, servicios.reloj()) }
            if (marcada == null) {
                // 404 tanto si no existe como si es de otro: no se revela que existe.
                if (id != null && repo.buscar(id) != null) call.denegar(servicios, sesion, "Notificacion", id, "Notificación no encontrada")
                else call.error(HttpStatusCode.NotFound, "Notificación no encontrada")
                return@post
            }
            call.respond(marcada.toDto())
        }

        post("/leidas") {
            val sesion = call.sesion()
            call.respond(mapOf("marcadas" to repo.marcarTodasLeidas(sesion.id, servicios.reloj())))
        }

        post {
            val sesion = call.sesion()
            if (!sesion.esPersonalEscolar) {
                servicios.auditoria.registrar(sesion.email, "ACCESO_DENEGADO", "Aviso", detalle = "Enviar aviso con rol ${sesion.rol}", ip = call.ip(), exito = false)
                call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden enviar avisos")
                return@post
            }
            val entrada = call.receive<AvisoEntradaDto>()
            val titulo = entrada.titulo.trim()
            val mensaje = entrada.mensaje.trim()
            if (titulo.isBlank() || mensaje.isBlank() || titulo.length > 120 || mensaje.length > 600) {
                call.error(HttpStatusCode.BadRequest, "titulo (hasta 120) y mensaje (hasta 600) son obligatorios")
                return@post
            }
            if (entrada.usernames.isEmpty() && entrada.rol.isNullOrBlank()) {
                call.error(HttpStatusCode.BadRequest, "Indica usernames, rol o ambos")
                return@post
            }
            val rol = entrada.rol?.takeIf { it.isNotBlank() }?.let { r ->
                RolesIdentity.desdeApp(r) ?: run {
                    call.error(HttpStatusCode.BadRequest, "Rol desconocido: $r")
                    return@post
                }
            }
            // Solo cuentas visibles para sus escuelas (C-RE-01): un correo de otra escuela cuenta como "no encontrado".
            val alcance = sesion.alcance
            val porCorreo = entrada.usernames.distinct().associateWith {
                servicios.usuarios.credenciales(it)?.usuario?.takeIf { u -> u.activo && servicios.usuarios.esVisible(u.id, alcance) }
            }
            val porRol = rol?.let { servicios.usuarios.conRolVisible(it, alcance) }.orEmpty()
            val destinatarios = (porCorreo.values.filterNotNull() + porRol).map { it.id }.distinct()

            val ahora = servicios.reloj()
            destinatarios.forEach { repo.crear(it, TipoNotificacion.AVISO, titulo, mensaje, emptyMap(), ahora) }
            servicios.auditoria.registrar(sesion.email, "ENVIAR_AVISO", "Notificacion", detalle = "$titulo → ${destinatarios.size} cuentas", ip = call.ip())
            servicios.despachador.despertar()
            call.respond(
                HttpStatusCode.Created,
                AvisoEnviadoDto(enviadas = destinatarios.size, noEncontrados = porCorreo.filterValues { it == null }.keys.toList())
            )
        }

        get("/seguimiento") {
            val sesion = call.sesion()
            if (!sesion.esPersonalEscolar) {
                servicios.auditoria.registrar(sesion.email, "ACCESO_DENEGADO", "Seguimiento", detalle = "Rol ${sesion.rol}", ip = call.ip(), exito = false)
                call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden ver el seguimiento")
                return@get
            }
            val soloNoLeidas = call.request.queryParameters["soloNoLeidas"].toBoolean()
            call.respond(repo.seguimiento(soloNoLeidas, sesion.alcance).map { f ->
                val n = f.notificacion
                SeguimientoDto(
                    notificacionId = n.id.toString(), tutor = f.tutor, alumno = n.datos["alumno"].orEmpty(),
                    tamizajeId = n.datos["tamizajeId"].orEmpty(), creadaEn = n.creadaEn.iso(), leidaEn = n.leidaEn?.iso(),
                    recordatoriosEnviados = n.recordatorios, entregadaPor = f.entregadaPor,
                )
            })
        }
    }
}
