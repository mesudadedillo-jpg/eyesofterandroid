package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.CasoDto
import com.example.eyesofterapp.model.CitaDto
import com.example.eyesofterapp.model.ComprobanteDto
import com.example.eyesofterapp.model.EventoDto
import com.example.eyesofterapp.model.NotificacionDto
import com.example.eyesofterapp.model.PaseDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.plugins.Sesion
import com.example.eyesofterapp.repos.CasoFila
import com.example.eyesofterapp.repos.ComprobanteFila
import com.example.eyesofterapp.repos.EstadosPase
import com.example.eyesofterapp.repos.ExpedienteRepository
import com.example.eyesofterapp.repos.NotificacionRecord
import com.example.eyesofterapp.repos.PaseFila
import com.example.eyesofterapp.repos.Semaforos
import com.example.eyesofterapp.repos.TamizajeFila
import com.example.eyesofterapp.repos.aApp
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import java.net.URLEncoder
import java.time.Instant

fun Instant.iso(): String = toString()

fun NotificacionRecord.toDto() = NotificacionDto(
    id = id.toString(), tipo = tipo, titulo = titulo, mensaje = mensaje, datos = datos,
    creadaEn = creadaEn.iso(), leidaEn = leidaEn?.iso(),
)

fun TamizajeFila.toDto(caso: CasoFila? = null, urlPortal: String = "", ahora: Instant = Instant.now()) = TamizajeDto(
    id = id.toString(), folio = folio, alumnoId = alumnoId, alumnoNombre = alumnoNombre, grado = grado.toString(), grupo = grupo,
    fecha = fecha.toString(), semaforo = semaforo.uppercase(), semaforoTexto = Semaforos.titulo(semaforo),
    observaciones = observaciones.orEmpty(), campana = campana, caso = caso?.toDto(urlPortal, ahora),
)

fun CasoFila.toDto(urlPortal: String, ahora: Instant) = CasoDto(
    id = id, folio = folio, estado = aApp(estado),
    destinoDefinido = clinica != null || clinicaManual != null,
    clinicaId = clinica?.id, clinicaNombre = clinica?.nombre ?: clinicaManual,
    clinicaAsociada = clinica?.asociada == true && clinica.activa,
    pase = pase?.toDto(urlPortal, ahora), fechaAtencion = fechaAtencion?.toString(),
    comprobanteAprobado = comprobantesAprobados > 0,
    citas = citas.map { CitaDto(it.id, it.clinicaNombre, it.fechaPreferida.toString(), it.horario, it.comentario, aApp(it.estado), it.creadaEn.iso()) },
    eventos = eventos.map { EventoDto(it.fecha.toString(), aApp(it.tipo), it.descripcion) },
)

fun urlValidacion(urlPortal: String, codigo: String) =
    "${urlPortal.trimEnd('/')}/Pases/Validar?codigo=${URLEncoder.encode(codigo, Charsets.UTF_8)}"

fun PaseFila.estadoReal(ahora: Instant) = if (estado == EstadosPase.VIGENTE && expiraEn <= ahora) "EXPIRADO" else aApp(estado)

fun PaseFila.toDto(urlPortal: String, ahora: Instant) = PaseDto(
    codigo = codigo, codigoLegible = ExpedienteRepository.codigoLegible(codigo), estado = estadoReal(ahora),
    creadoEn = creadoEn.iso(), expiraEn = expiraEn.iso(), usadoEn = usadoEn?.iso(),
    clinicaId = clinica.id, clinicaNombre = clinica.nombre, clinicaDireccion = clinica.direccion, clinicaTelefono = clinica.telefono,
    urlValidacion = urlValidacion(urlPortal, codigo),
    convenio = convenio,
)

fun ComprobanteFila.toDto() = ComprobanteDto(
    id = id.toString(), tamizajeId = tamizajeId?.toString().orEmpty(), casoId = casoId, alumno = alumno, tutor = subidoPor,
    tipo = aApp(tipo), contentType = contentType, tamanoBytes = tamanoBytes, subidoEn = subidoEn.iso(),
    estado = aApp(estado), revisadoEn = revisadoEn?.iso(), motivoRechazo = motivoRechazo,
)

suspend fun ApplicationCall.error(codigo: HttpStatusCode, mensaje: String, clave: String? = null) =
    respond(codigo, if (clave == null) mapOf("error" to mensaje) else mapOf("error" to mensaje, "codigo" to clave))

fun ApplicationCall.ip(): String = request.origin.remoteAddress

/** ¿La sesión puede ver este tamizaje? Dirección/Enfermería: todos. Tutor: los de sus hijos. Alumno: los suyos. */
fun Sesion.puedeVer(t: TamizajeFila) = (esPersonalEscolar && permite(t.plantelId)) ||
    (esTutor && t.tutorId == tutorId) ||
    (alumnoId != null && t.alumnoId == alumnoId)

/**
 * C-RFNN-06: si alguien intenta ver algo que no le corresponde, se niega con 404 (no se revela
 * que existe) y se registra en la bitácora de auditoría que revisa Dirección en el portal.
 */
suspend fun ApplicationCall.denegar(servicios: Servicios, sesion: Sesion, entidad: String, id: Any, mensaje: String) {
    servicios.auditoria.registrar(sesion.email, "ACCESO_DENEGADO", entidad, id, "Rol ${sesion.rol}", ip(), exito = false)
    error(HttpStatusCode.NotFound, mensaje)
}

/**
 * C-RC-08: el tutor debe aceptar el aviso de privacidad vigente antes de ver o enviar datos
 * de sus hijos. Responde 403 con codigo CONSENTIMIENTO_PENDIENTE y devuelve false.
 */
suspend fun ApplicationCall.exigirConsentimiento(servicios: Servicios, sesion: Sesion): Boolean {
    if (!sesion.esTutor) return true
    if (servicios.consentimientos.aceptado(sesion.id, servicios.config.versionAviso) != null) return true
    error(HttpStatusCode.Forbidden, "Acepta el aviso de privacidad para continuar.", "CONSENTIMIENTO_PENDIENTE")
    return false
}
