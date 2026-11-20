package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.AlumnoEscritorioDto
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.time.LocalDate

private val AGUDEZA = Regex("^20/\\d{1,3}$")

/**
 * API del sistema de escritorio EYESOFTER (C-RFN-02, C-RI-01). Se autentica con el
 * encabezado X-Api-Key = ESCRITORIO_API_KEY.
 *
 *  POST /api/escritorio/tamizajes    Un resultado. Idempotente por folio: el escritorio puede
 *                                    reintentar sin miedo a duplicar (C-RFNN-03).
 *  POST /api/escritorio/incidencias  El escritorio avisa que lleva tiempo sin poder sincronizar:
 *                                    se alerta a Enfermería (C-RFNN-03).
 *  GET  /api/escritorio/alumnos?q=   Para validar la matrícula antes de capturar.
 */
fun Route.escritorioRoutes(servicios: Servicios) {
    post("/api/escritorio/tamizajes") {
        if (!call.claveEscritorioValida(servicios)) return@post
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
        )
        when (r) {
            is ResultadoIngesta.Duplicado -> call.respond(HttpStatusCode.OK, TamizajeRecibidoDto(r.tamizajeId.toString(), r.casoId, duplicado = true))
            is ResultadoIngesta.AlumnoDesconocido -> call.error(HttpStatusCode.UnprocessableEntity,
                "No hay ningún alumno con la matrícula ${r.matricula}. Regístralo en el portal y vuelve a enviar.", "ALUMNO_DESCONOCIDO")
            is ResultadoIngesta.Registrado -> {
                servicios.despachador.despertar()
                call.respond(HttpStatusCode.Created, TamizajeRecibidoDto(r.tamizajeId.toString(), r.casoId, r.casoFolio, r.tutoresAvisados, duplicado = false))
            }
        }
    }

    post("/api/escritorio/incidencias") {
        if (!call.claveEscritorioValida(servicios)) return@post
        val e = call.receive<IncidenciaEscritorioDto>()
        val mensaje = e.mensaje.trim().take(300)
        if (mensaje.isBlank()) {
            call.error(HttpStatusCode.BadRequest, "mensaje es obligatorio")
            return@post
        }
        val ahora = servicios.reloj()
        val texto = "El sistema de escritorio${e.equipo?.let { " ($it)" } ?: ""} no ha podido enviar ${e.pendientes} tamizaje(s)" +
            (e.desde?.let { " desde $it" } ?: "") + ": $mensaje"
        val avisados = servicios.db.transaccion { c ->
            val ids = UsuarioRepository.idsConRol(c, RolesIdentity.ENFERMERIA)
            ids.forEach { NotificacionRepository.crearEn(c, it, TipoNotificacion.ALERTA_ENFERMERIA, "Falla de sincronización", texto, mapOf("ruta" to "seguimiento"), ahora) }
            com.example.eyesofterapp.repos.Auditoria.registrarEn(c, "escritorio", "FALLA_SINCRONIZACION", detalle = texto, ip = call.ip(), origen = "escritorio", exito = false)
            ids.size
        }
        servicios.despachador.despertar()
        call.respond(HttpStatusCode.Created, mapOf("enfermeriaAvisada" to avisados))
    }

    get("/api/escritorio/alumnos") {
        if (!call.claveEscritorioValida(servicios)) return@get
        call.respond(servicios.expedientes.alumnos(call.request.queryParameters["q"]).map {
            AlumnoEscritorioDto(it.matricula, it.nombre, it.apellidos, it.nivel, it.grado, it.grupo, it.usaLentes)
        })
    }
}

/** Comparación en tiempo constante para no filtrar la clave por tiempos de respuesta. */
private suspend fun ApplicationCall.claveEscritorioValida(servicios: Servicios): Boolean {
    val esperada = servicios.config.apiKeyEscritorio
    if (esperada == null) {
        error(HttpStatusCode.ServiceUnavailable, "La API del escritorio no está configurada (ESCRITORIO_API_KEY)")
        return false
    }
    val clave = request.headers["X-Api-Key"].orEmpty()
    if (!MessageDigest.isEqual(clave.toByteArray(), esperada.toByteArray())) {
        servicios.auditoria.registrar("escritorio", "ACCESO_DENEGADO", detalle = "X-Api-Key inválida", ip = ip(), origen = "escritorio", exito = false)
        error(HttpStatusCode.Unauthorized, "X-Api-Key inválida")
        return false
    }
    return true
}
