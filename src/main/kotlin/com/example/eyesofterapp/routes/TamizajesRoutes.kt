package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.CitaCreadaDto
import com.example.eyesofterapp.model.CitaEntradaDto
import com.example.eyesofterapp.model.DestinoDto
import com.example.eyesofterapp.model.DestinoEntradaDto
import com.example.eyesofterapp.plugins.Sesion
import com.example.eyesofterapp.plugins.sesion
import com.example.eyesofterapp.repos.ClinicaRepository
import com.example.eyesofterapp.repos.EstadosCaso
import com.example.eyesofterapp.repos.EstadosPase
import com.example.eyesofterapp.repos.ExpedienteRepository
import com.example.eyesofterapp.repos.ResultadoDestino
import com.example.eyesofterapp.repos.TamizajeDetalle
import com.example.eyesofterapp.repos.ZONA_MX
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

private val FECHA_MX = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 *  GET  /api/tamizajes                 Tutor: los de sus hijos (con caso, pase y línea de tiempo,
 *                                      para guardarlos y verlos sin conexión, M-RFNN-01/03).
 *                                      Alumno: los suyos. Dirección/Enfermería: los 200 más recientes.
 *  GET  /api/tamizajes/{id}            Detalle, a donde lleva la notificación (M-RC-02).
 *  POST /api/tamizajes/{id}/destino    El tutor elige la clínica: pase con QR o resumen (M-RFN-09..11).
 *  GET  /api/tamizajes/{id}/pase.png   El QR del pase vigente, para mostrarlo o guardarlo.
 *  POST /api/tamizajes/{id}/citas      Solicitar cita en una clínica del directorio (M-RFN-08).
 */
fun Route.tamizajesRoutes(servicios: Servicios) {
    val ex = servicios.expedientes
    val portal = servicios.config.urlPortal

    get("/api/tamizajes") {
        val s = call.sesion()
        if (!call.exigirConsentimiento(servicios, s)) return@get
        val ahora = servicios.reloj()
        val lista = when {
            s.esPersonalEscolar -> ex.recientes().map { it.toDto() }
            s.esTutor -> ex.deTutor(s.tutorId!!).map { t -> t.toDto(t.casoId?.let { ex.detalle(t.id)?.caso }, portal, ahora) }
            s.alumnoId != null -> ex.deAlumno(s.alumnoId!!).map { it.toDto() }
            else -> emptyList()
        }
        call.respond(lista)
    }

    get("/api/tamizajes/{id}") {
        val (s, d) = call.detallePermitido(servicios) ?: return@get
        call.respond(d.tamizaje.toDto(d.caso, portal, servicios.reloj()))
        if (s.esPersonalEscolar) servicios.auditoria.registrar(s.email, "VER_TAMIZAJE", "Tamizaje", d.tamizaje.id, ip = call.ip())
    }

    post("/api/tamizajes/{id}/destino") {
        val (s, d) = call.detallePermitido(servicios, soloTutorOPersonal = true) ?: return@post
        val caso = d.caso
        if (caso == null) {
            call.error(HttpStatusCode.Conflict, "Este resultado es verde: no requiere visita al especialista.")
            return@post
        }
        if (caso.estado == EstadosCaso.CERRADO) {
            call.error(HttpStatusCode.Conflict, "El caso ya está cerrado.")
            return@post
        }
        val e = call.receive<DestinoEntradaDto>()
        var clinica = e.clinicaId?.let { servicios.clinicas.porId(it) }
        if (e.clinicaId != null && (clinica == null || !clinica.activa)) {
            call.error(HttpStatusCode.BadRequest, "La clínica elegida no está en el directorio.")
            return@post
        }
        val manual = e.nombreManual?.trim()?.take(160)
        if (clinica == null) {
            if (manual.isNullOrBlank() || manual.length < 3) {
                call.error(HttpStatusCode.BadRequest, "Elige una clínica del directorio o escribe su nombre.")
                return@post
            }
            // M-RFNN-06: se verifica en el directorio aunque la hayan escrito a mano.
            val n = ClinicaRepository.normalizar(manual)
            clinica = servicios.clinicas.activas().singleOrNull { ClinicaRepository.normalizar(it.nombre) == n }
        }
        val ahora = servicios.reloj()
        val resultado = ex.asignarDestino(caso.id, clinica?.id, manual, s.email, ahora)
        val mensaje = when (resultado) {
            is ResultadoDestino.ConPase ->
                "Listo. Muestra este pase con QR en ${resultado.pase.clinica.nombre}. Vence el ${resultado.pase.expiraEn.atZone(ZONA_MX).format(FECHA_MX)}."
            is ResultadoDestino.SoloResumen -> if (resultado.registrada)
                "${resultado.clinicaNombre} no está asociada al programa EYESOFTER, por eso no se genera pase con QR. Lleva el resumen del tamizaje a la consulta."
            else
                "\"${resultado.clinicaNombre}\" no está registrada en el directorio, así que se trata como no asociada: no hay pase con QR. Lleva el resumen del tamizaje a la consulta."
        }
        val nuevo = ex.detalle(d.tamizaje.id)!!
        call.respond(DestinoDto(resultado is ResultadoDestino.ConPase, mensaje, nuevo.tamizaje.toDto(nuevo.caso, portal, ahora)))
    }

    get("/api/tamizajes/{id}/pase.png") {
        val (_, d) = call.detallePermitido(servicios) ?: return@get
        val ahora = servicios.reloj()
        val pase = d.caso?.pase?.takeIf { it.estado == EstadosPase.VIGENTE && it.expiraEn.isAfter(ahora) }
        if (pase == null) {
            call.error(HttpStatusCode.NotFound, "No hay pase vigente para este tamizaje.")
            return@get
        }
        val tamano = call.request.queryParameters["tamano"]?.toIntOrNull()?.coerceIn(160, 1024) ?: 480
        call.respondBytes(qrPng(urlValidacion(portal, pase.codigo), tamano), ContentType.Image.PNG)
    }

    post("/api/tamizajes/{id}/citas") {
        val (s, d) = call.detallePermitido(servicios, soloTutorOPersonal = true) ?: return@post
        if (!s.esTutor) {
            call.error(HttpStatusCode.Forbidden, "Solo el tutor puede solicitar la cita.")
            return@post
        }
        val caso = d.caso
        if (caso == null || caso.estado == EstadosCaso.CERRADO) {
            call.error(HttpStatusCode.Conflict, if (caso == null) "Este resultado es verde: no requiere cita." else "El caso ya está cerrado.")
            return@post
        }
        val e = call.receive<CitaEntradaDto>()
        val clinica = servicios.clinicas.porId(e.clinicaId)?.takeIf { it.activa }
        val hoy = ExpedienteRepository.hoy(servicios.reloj())
        val fecha = runCatching { LocalDate.parse(e.fechaPreferida) }.getOrNull()
        val problema = when {
            clinica == null -> "La clínica no está en el directorio."
            fecha == null -> "fechaPreferida debe tener el formato AAAA-MM-DD."
            fecha.isBefore(hoy) -> "Elige una fecha a partir de hoy."
            fecha.isAfter(hoy.plusDays(180)) -> "Elige una fecha dentro de los próximos 6 meses."
            (e.comentario?.length ?: 0) > 300 -> "El comentario no puede pasar de 300 caracteres."
            else -> null
        }
        if (problema != null) {
            call.error(HttpStatusCode.BadRequest, problema)
            return@post
        }
        val id = ex.solicitarCita(caso.id, clinica!!, fecha!!, e.horario, e.comentario, s.id, s.email, d.tamizaje.alumnoNombre, servicios.reloj())
        servicios.despachador.despertar()
        call.respond(HttpStatusCode.Created,
            CitaCreadaDto(id, "Solicitud enviada. Llama a ${clinica.nombre} al ${clinica.telefono} si necesitas confirmarla antes."))
    }
}

/**
 * Carga el tamizaje {id} y revisa que la sesión pueda verlo (C-RC-06, C-RFNN-06) y, si es tutor,
 * que haya aceptado el aviso de privacidad (C-RC-08). Si algo falla, ya respondió y devuelve null.
 */
private suspend fun ApplicationCall.detallePermitido(servicios: Servicios, soloTutorOPersonal: Boolean = false): Pair<Sesion, TamizajeDetalle>? {
    val s = sesion()
    if (!exigirConsentimiento(servicios, s)) return null
    val id = parameters["id"]?.toIntOrNull()
    val d = id?.let { servicios.expedientes.detalle(it) }
    if (d == null) {
        error(HttpStatusCode.NotFound, "Tamizaje no encontrado")
        return null
    }
    val permitido = if (soloTutorOPersonal) s.esPersonalEscolar || (s.esTutor && d.tamizaje.tutorId == s.tutorId) else s.puedeVer(d.tamizaje)
    if (!permitido) {
        denegar(servicios, s, "Tamizaje", d.tamizaje.id, "Tamizaje no encontrado")
        return null
    }
    return s to d
}

/** QR con corrección de errores media (M): se lee bien aunque la pantalla esté rayada o con brillo. */
fun qrPng(texto: String, tamano: Int): ByteArray {
    val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tamano, tamano,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_BYTE_BINARY)
    for (x in 0 until m.width) for (y in 0 until m.height) img.setRGB(x, y, if (m[x, y]) 0x000000 else 0xFFFFFF)
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}
