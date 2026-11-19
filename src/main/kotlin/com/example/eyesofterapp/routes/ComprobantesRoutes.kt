package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.data.ComprobanteRecord
import com.example.eyesofterapp.data.EstadoComprobante
import com.example.eyesofterapp.data.TipoComprobante
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.model.ComprobanteDto
import com.example.eyesofterapp.model.RevisionComprobanteDto
import com.example.eyesofterapp.notificaciones.ROL_ENFERMERIA
import com.example.eyesofterapp.plugins.Sesion
import com.example.eyesofterapp.plugins.sesion
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Máximo permitido (C-RFNN-05). */
const val MAX_COMPROBANTE_BYTES = 10 * 1024 * 1024

/**
 * Se reconoce el tipo por los primeros bytes del archivo ("número mágico"),
 * no por lo que diga el teléfono: así no se cuela un ejecutable renombrado.
 */
internal fun tipoPorContenido(b: ByteArray): String? = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
    b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    b.size >= 5 && String(b, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
    else -> null
}

private fun ComprobanteRecord.toDto() = ComprobanteDto(
    id = id, tamizajeId = tamizajeId, tutor = tutorUsername, tipo = tipo.name, contentType = contentType,
    tamanoBytes = tamanoBytes, subidoEn = subidoEn.iso(), estado = estado.name,
    revisadoEn = revisadoEn?.iso(), motivoRechazo = motivoRechazo,
)

private fun Sesion.puedeVer(c: ComprobanteRecord) = esPersonalEscolar || c.tutorUsername == username

/**
 *  POST /api/comprobantes                   Tutor sube foto o PDF (multipart)
 *  GET  /api/comprobantes?estado=PENDIENTE  Tutor: los suyos. Personal escolar: todos
 *  GET  /api/comprobantes/{id}/archivo      Descarga el archivo
 *  POST /api/comprobantes/{id}/revision     Enfermería/Dirección aprueba o rechaza
 */
fun Route.comprobantesRoutes(servicios: Servicios) {
    post("/api/comprobantes") {
        val sesion = call.sesion()
        if (sesion.rol != "CLIENTE") {
            call.error(HttpStatusCode.Forbidden, "Solo los tutores pueden subir comprobantes")
            return@post
        }
        var tamizajeId: String? = null
        var tipoTexto: String? = null
        var bytes: ByteArray? = null
        var demasiadoGrande = false

        // Ktor revisa el tamaño de la petición completa (archivo + campos + separadores):
        // se le deja un margen y el límite real del archivo se revisa abajo.
        // Si alguien manda algo enorme, Ktor corta la lectura con IOException → 413.
        try {
            call.receiveMultipart(formFieldLimit = MAX_COMPROBANTE_BYTES + 256L * 1024).forEachPart { parte ->
                when (parte) {
                    is PartData.FormItem -> when (parte.name) {
                        "tamizajeId" -> tamizajeId = parte.value.trim()
                        "tipo" -> tipoTexto = parte.value.trim()
                    }
                    is PartData.FileItem -> if (parte.name == "archivo") {
                        val leido = parte.provider().readRemaining(MAX_COMPROBANTE_BYTES + 1L).readByteArray()
                        if (leido.size > MAX_COMPROBANTE_BYTES) demasiadoGrande = true else bytes = leido
                    }
                    else -> {}
                }
                parte.dispose()
            }
        } catch (e: java.io.IOException) {
            if ("exceeds limit" in (e.message ?: "")) demasiadoGrande = true else throw e
        }

        if (demasiadoGrande) {
            call.error(HttpStatusCode.PayloadTooLarge, "El archivo pesa más de 10 MB. Toma la foto con menor resolución.")
            return@post
        }
        val archivo = bytes
        if (archivo == null || archivo.isEmpty()) {
            call.error(HttpStatusCode.BadRequest, "Falta el archivo (campo \"archivo\")")
            return@post
        }
        val contentType = tipoPorContenido(archivo)
        if (contentType == null) {
            call.error(HttpStatusCode.UnsupportedMediaType, "Solo se aceptan fotos (JPG, PNG, WEBP) o PDF")
            return@post
        }
        val tipo = TipoComprobante.entries.firstOrNull { it.name.equals(tipoTexto, ignoreCase = true) }
        if (tipo == null) {
            call.error(HttpStatusCode.BadRequest, "tipo debe ser RECETA o COMPROBANTE_LENTES")
            return@post
        }
        val tamizaje = tamizajeId?.let { servicios.tamizajes.buscar(it) }
        if (tamizaje == null || tamizaje.tutorUsername != sesion.username) {
            if (tamizaje != null) auditoria.warn("${sesion.username} intentó subir un comprobante al tamizaje ajeno ${tamizaje.id}")
            call.error(HttpStatusCode.NotFound, "Tamizaje no encontrado")
            return@post
        }

        val c = servicios.comprobantes.guardar(tamizaje.id, sesion.username, tipo, contentType, archivo, servicios.reloj())
        servicios.usuarios.findByRole(ROL_ENFERMERIA).forEach {
            servicios.notificador.notificar(
                destinatario = it.username,
                tipo = TipoNotificacion.COMPROBANTE_RECIBIDO,
                titulo = "Nuevo comprobante por revisar",
                mensaje = "El tutor de ${tamizaje.alumnoNombre} subió ${if (tipo == TipoComprobante.RECETA) "una receta" else "un comprobante de lentes"}.",
                datos = mapOf("comprobanteId" to c.id, "tamizajeId" to tamizaje.id, "alumno" to tamizaje.alumnoNombre, "ruta" to "comprobantes"),
            )
        }
        call.respond(HttpStatusCode.Created, c.toDto())
    }

    get("/api/comprobantes") {
        val sesion = call.sesion()
        val estado = call.request.queryParameters["estado"]?.let { e -> EstadoComprobante.entries.firstOrNull { it.name.equals(e, true) } }
        val lista = when {
            sesion.esPersonalEscolar -> servicios.comprobantes.listar(estado = estado)
            sesion.rol == "CLIENTE" -> servicios.comprobantes.listar(tutor = sesion.username, estado = estado)
            else -> emptyList()
        }
        call.respond(lista.map { it.toDto() })
    }

    get("/api/comprobantes/{id}/archivo") {
        val sesion = call.sesion()
        val c = servicios.comprobantes.buscar(call.parameters["id"]!!)
        if (c == null || !sesion.puedeVer(c)) {
            if (c != null) auditoria.warn("${sesion.username} intentó descargar el comprobante ajeno ${c.id}")
            call.error(HttpStatusCode.NotFound, "Comprobante no encontrado")
            return@get
        }
        val bytes = servicios.comprobantes.archivo(c.id)
        if (bytes == null) {
            call.error(HttpStatusCode.NotFound, "El archivo ya no está disponible")
            return@get
        }
        call.respondBytes(bytes, ContentType.parse(c.contentType))
    }

    post("/api/comprobantes/{id}/revision") {
        val sesion = call.sesion()
        if (!sesion.esPersonalEscolar) {
            auditoria.warn("${sesion.username} (${sesion.rol}) intentó revisar un comprobante")
            call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden revisar comprobantes")
            return@post
        }
        val entrada = call.receive<RevisionComprobanteDto>()
        if (!entrada.aprobado && entrada.motivo.isNullOrBlank()) {
            call.error(HttpStatusCode.BadRequest, "Indica el motivo del rechazo para que el tutor sepa qué corregir")
            return@post
        }
        val id = call.parameters["id"]!!
        val revisado = servicios.comprobantes.revisar(id, entrada.aprobado, sesion.username, entrada.motivo?.trim(), servicios.reloj())
        if (revisado == null) {
            val existe = servicios.comprobantes.buscar(id)
            if (existe == null) call.error(HttpStatusCode.NotFound, "Comprobante no encontrado")
            else call.error(HttpStatusCode.Conflict, "Este comprobante ya fue revisado")
            return@post
        }
        val alumno = servicios.tamizajes.buscar(revisado.tamizajeId)?.alumnoNombre ?: "tu hijo"
        servicios.notificador.notificar(
            destinatario = revisado.tutorUsername,
            tipo = TipoNotificacion.COMPROBANTE_REVISADO,
            titulo = if (entrada.aprobado) "Comprobante aceptado" else "Comprobante rechazado",
            mensaje = if (entrada.aprobado) "Enfermería aceptó el comprobante de $alumno. ¡Gracias!"
            else "Enfermería no pudo aceptar el comprobante de $alumno: ${revisado.motivoRechazo}. Sube uno nuevo.",
            datos = mapOf("comprobanteId" to revisado.id, "tamizajeId" to revisado.tamizajeId, "ruta" to "tamizaje/${revisado.tamizajeId}"),
        )
        call.respond(revisado.toDto())
    }
}
