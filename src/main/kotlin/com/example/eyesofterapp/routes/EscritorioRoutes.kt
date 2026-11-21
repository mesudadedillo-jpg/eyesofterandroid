package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.AlumnoEscritorioDto
import com.example.eyesofterapp.model.EquipoEscritorioDto
import com.example.eyesofterapp.plugins.SesionEscritorio
import com.example.eyesofterapp.plugins.validarEscritorio
import com.example.eyesofterapp.model.IncidenciaEscritorioDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import com.example.eyesofterapp.repos.NotificacionRepository
import com.example.eyesofterapp.repos.ResultadoIngesta
import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.repos.Semaforos
import com.example.eyesofterapp.repos.TamizajeEntrante
import com.example.eyesofterapp.repos.TipoNotificacion
import com.example.eyesofterapp.repos.UsuarioRepository
import com.example.eyesofterapp.repos.aBase
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.LocalDate

private val AGUDEZA = Regex("^20/\\d{1,3}$")

/**
 * API del sistema de escritorio EYESOFTER (C-RFN-02, C-RI-01).
 *
 * Ya NO hay una clave compartida: cualquiera que instalara el escritorio con esa clave podía
 * buscar a todos los alumnos. Ahora cada equipo se VINCULA (ver [vinculacionRoutes]) con la
 * cuenta del portal de alguien de Dirección o Enfermería, y manda
 * `Authorization: Bearer <token del equipo>`.
 *
 *  POST /api/escritorio/tamizajes    Un resultado. Idempotente por folio: el escritorio puede
 *                                    reintentar sin miedo a duplicar (C-RFNN-03).
 *  POST /api/escritorio/incidencias  El escritorio avisa que lleva tiempo sin poder sincronizar:
 *                                    se alerta a Enfermería (C-RFNN-03).
 *  GET  /api/escritorio/alumnos?q=   Buscar (mín. 3 letras) para validar la matrícula. Solo alumnos de
 *                                    las escuelas de la cuenta que vinculó el equipo (C-RE-01).
 *  GET  /api/escritorio/equipo       ¿Sigue vinculado? ¿Quién lo autorizó?
 */
fun Route.escritorioRoutes(servicios: Servicios) {
    post("/api/escritorio/tamizajes") {
        val equipo = call.equipoVinculado(servicios) ?: return@post
        val e = call.receive<TamizajeEntradaDto>()
        val semaforo = aBase(e.semaforo, Semaforos.TODOS)
        val fecha = runCatching { LocalDate.parse(e.fecha.trim()) }.getOrNull()
        val hoy = com.example.eyesofterapp.repos.ExpedienteRepository.hoy(servicios.reloj())
        val od = e.agudezaOjoDerecho?.trim()?.ifBlank { null }
        val oi = e.agudezaOjoIzquierdo?.trim()?.ifBlank { null }
        val problema = when {
            e.folio.isBlank() || e.folio.trim().length > 40 -> "folio es obligatorio (hasta 40 caracteres)"
            e.matricula.isBlank() -> "matricula es obligatoria"
            semaforo == null -> "semaforo debe ser VERDE, AMARILLO o ROJO"
            fecha == null -> "fecha debe tener el formato AAAA-MM-DD"
            fecha.isAfter(hoy.plusDays(1)) -> "La fecha del tamizaje no puede ser futura"
            od != null && !AGUDEZA.matches(od) -> "agudezaOjoDerecho debe tener el formato 20/NN"
            oi != null && !AGUDEZA.matches(oi) -> "agudezaOjoIzquierdo debe tener el formato 20/NN"
            (e.observaciones?.length ?: 0) > 500 -> "observaciones no puede pasar de 500 caracteres"
            else -> null
        }
        if (problema != null) {
            call.error(HttpStatusCode.BadRequest, problema)
            return@post
        }
        val r = servicios.expedientes.ingestar(
            TamizajeEntrante(e.folio.trim(), e.matricula.trim(), fecha!!, semaforo!!, od, oi, e.observaciones?.trim()?.ifBlank { null }),
            servicios.reloj(),
            enviadoPor = equipo.usuario.email,
            // El equipo hereda las escuelas de la cuenta que lo vinculó (C-RE-01).
            alcance = equipo.usuario.alcance,
        )
        when (r) {
            is ResultadoIngesta.Duplicado -> call.respond(HttpStatusCode.OK, TamizajeRecibidoDto(r.tamizajeId.toString(), r.casoId, duplicado = true))
            is ResultadoIngesta.AlumnoDesconocido -> call.error(HttpStatusCode.UnprocessableEntity,
                "No hay ningún alumno con la matrícula ${r.matricula} en las escuelas de este equipo. Regístralo en el portal y vuelve a enviar.", "ALUMNO_DESCONOCIDO")
            is ResultadoIngesta.MatriculaAmbigua -> call.error(HttpStatusCode.Conflict,
                "La matrícula ${r.matricula} existe en más de una de las escuelas de este equipo. Vincula el equipo con una cuenta de una sola escuela.", "MATRICULA_AMBIGUA")
            is ResultadoIngesta.Registrado -> {
                servicios.despachador.despertar()
                call.respond(HttpStatusCode.Created, TamizajeRecibidoDto(r.tamizajeId.toString(), r.casoId, r.casoFolio, r.tutoresAvisados, duplicado = false))
            }
        }
    }

    post("/api/escritorio/incidencias") {
        val equipo = call.equipoVinculado(servicios) ?: return@post
        val e = call.receive<IncidenciaEscritorioDto>()
        val mensaje = e.mensaje.trim().take(300)
        if (mensaje.isBlank()) {
            call.error(HttpStatusCode.BadRequest, "mensaje es obligatorio")
            return@post
        }
        val ahora = servicios.reloj()
        val nombreEquipo = equipo.equipo.ifBlank { e.equipo?.trim()?.take(60).orEmpty() }
        val texto = "El sistema de escritorio${nombreEquipo.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""} no ha podido enviar ${e.pendientes} tamizaje(s)" +
            (e.desde?.let { " desde $it" } ?: "") + ": $mensaje"
        val avisados = servicios.db.transaccion { c ->
            // Solo la Enfermería de las escuelas del equipo.
            val ids = UsuarioRepository.idsConRolEnPlanteles(c, RolesIdentity.ENFERMERIA, equipo.usuario.alcance)
            ids.forEach { NotificacionRepository.crearEn(c, it, TipoNotificacion.ALERTA_ENFERMERIA, "Falla de sincronización", texto, mapOf("ruta" to "seguimiento"), ahora) }
            com.example.eyesofterapp.repos.Auditoria.registrarEn(c, equipo.usuario.email, "FALLA_SINCRONIZACION", detalle = texto, ip = call.ip(), origen = "escritorio", exito = false)
            ids.size
        }
        servicios.despachador.despertar()
        call.respond(HttpStatusCode.Created, mapOf("enfermeriaAvisada" to avisados))
    }

    get("/api/escritorio/alumnos") {
        val equipo = call.equipoVinculado(servicios) ?: return@get
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.length < 3) {
            call.error(HttpStatusCode.BadRequest, "Escribe al menos 3 letras del nombre o de la matrícula para buscar.")
            return@get
        }
        val lista = servicios.expedientes.alumnos(q.take(80), equipo.usuario.alcance)
        servicios.auditoria.registrar(equipo.usuario.email, "BUSCAR_ALUMNOS", detalle = "Equipo ${equipo.equipo}: \"${q.take(80)}\" (${lista.size} resultado(s))",
            ip = call.ip(), origen = "escritorio")
        call.respond(lista.map { AlumnoEscritorioDto(it.matricula, it.nombre, it.apellidos, it.nivel, it.grado, it.grupo) })
    }

    get("/api/escritorio/equipo") {
        val equipo = call.equipoVinculado(servicios) ?: return@get
        call.respond(EquipoEscritorioDto(equipo.equipo, equipo.usuario.email, equipo.usuario.nombre, equipo.usuario.rolApp))
    }
}

/**
 * El equipo que llama, si está vinculado y la cuenta que lo autorizó sigue activa, sigue
 * siendo de Dirección/Enfermería y no cambió su contraseña. Si no, responde 401 y lo anota.
 */
private suspend fun ApplicationCall.equipoVinculado(servicios: Servicios): SesionEscritorio? {
    val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    val sesion = servicios.tokens.validarEscritorio(token, servicios.usuarios)
    if (sesion == null) {
        servicios.auditoria.registrar("escritorio", "ACCESO_DENEGADO",
            detalle = if (token.isNullOrBlank()) "Equipo sin vincular" else "Token de equipo inválido o revocado", ip = ip(), origen = "escritorio", exito = false)
        error(HttpStatusCode.Unauthorized, "Este equipo no está vinculado o su vinculación ya no es válida. Vincúlalo en Configuración → Conexión con EYESOFTER Suite.",
            "EQUIPO_NO_VINCULADO")
    }
    return sesion
}
