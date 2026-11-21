package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.RevisionComprobanteDto
import com.example.eyesofterapp.plugins.sesion
import com.example.eyesofterapp.repos.EstadosCaso
import com.example.eyesofterapp.repos.TiposComprobante
import com.example.eyesofterapp.repos.aBase
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
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import java.util.UUID

/** Máximo permitido (C-RFNN-05). */
const val MAX_COMPROBANTE_BYTES = 10 * 1024 * 1024

/**
 * Se reconoce el tipo por los primeros bytes del archivo ("número mágico"),
 * no por lo que diga el teléfono: así no se cuela un ejecutable renombrado.
 * Mismo criterio que Helpers/TipoArchivo.cs del portal.
 */
internal fun tipoPorContenido(b: ByteArray): String? = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
    b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    b.size >= 5 && String(b, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
    else -> null
}

/**
 *  POST /api/comprobantes                   Tutor sube foto o PDF (multipart: tamizajeId, tipo, archivo)
 *  GET  /api/comprobantes?estado=PENDIENTE  Tutor: los de sus hijos. Personal escolar: todos
 *  GET  /api/comprobantes/{id}/archivo      Descarga el archivo (se descifra al vuelo)
 *  POST /api/comprobantes/{id}/revision     Enfermería/Dirección aprueba o rechaza
 */
fun Route.comprobantesRoutes(servicios: Servicios) {
    post("/api/comprobantes") {
        val sesion = call.sesion()
        if (!sesion.esTutor) {
            call.error(HttpStatusCode.Forbidden, "Solo los tutores pueden subir comprobantes")
            return@post
        }
        if (!call.exigirConsentimiento(servicios, sesion)) return@post
        var tamizajeId: String? = null
        var tipoTexto: String? = null
        var bytes: ByteArray? = null
        var demasiadoGrande = false

        // Ktor revisa el tamaño de la petición completa (archivo + campos + separadores):
        // se le deja un margen y el límite real del archivo se revisa abajo.
        try {
            call.receiveMultipart(formFieldLimit = MAX_COMPROBANTE_BYTES + 256L * 1024).forEachPart { parte ->
                when (parte) {
                    is PartData.FormItem -> when (parte.name) {
                        "tamizajeId" -> tamizajeId = parte.value.trim()
                        "tipo" -> tipoTexto = parte.value.trim()
                    }
                    is PartData.FileItem -> if (parte.name == "archivo") {
                        val leido = parte.provider().readBuffer(MAX_COMPROBANTE_BYTES + 1L).readByteArray()
                        if (leido.size > MAX_COMPROBANTE_BYTES) demasiadoGrande = true else bytes = leido
                    }
                    else -> {}
                }
                parte.release()
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
        val tipo = aBase(tipoTexto, TiposComprobante.TODOS)
        if (tipo == null) {
            call.error(HttpStatusCode.BadRequest, "tipo debe ser RECETA o COMPROBANTE_LENTES")
            return@post
        }
        val detalle = tamizajeId?.toIntOrNull()?.let { servicios.expedientes.detalle(it) }
        if (detalle == null || detalle.tamizaje.tutorId != sesion.tutorId) {
            if (detalle != null) call.denegar(servicios, sesion, "Tamizaje", detalle.tamizaje.id, "Tamizaje no encontrado")
            else call.error(HttpStatusCode.NotFound, "Tamizaje no encontrado")
            return@post
        }
        val caso = detalle.caso
        if (caso == null || caso.estado == EstadosCaso.CERRADO) {
            call.error(HttpStatusCode.Conflict, if (caso == null) "Este resultado es verde: no requiere receta ni lentes." else "El caso ya está cerrado.")
            return@post
        }
        val c = servicios.comprobantes.guardar(caso.id, detalle.tamizaje.alumnoNombre, sesion.id, sesion.email, tipo, contentType, archivo, servicios.reloj())
        servicios.despachador.despertar()
        call.respond(HttpStatusCode.Created, c.toDto())
    }

    get("/api/comprobantes") {
        val sesion = call.sesion()
        val estadoTexto = call.request.queryParameters["estado"]
        val estado = estadoTexto?.let { aBase(it, listOf("Pendiente", "Aprobado", "Rechazado")) }
        if (estadoTexto != null && estado == null) {
            call.error(HttpStatusCode.BadRequest, "estado debe ser PENDIENTE, APROBADO o RECHAZADO")
            return@get
        }
        val lista = when {
            sesion.esPersonalEscolar -> servicios.comprobantes.listar(null, estado, sesion.alcance)
            sesion.esTutor -> if (call.exigirConsentimiento(servicios, sesion)) servicios.comprobantes.listar(sesion.tutorId, estado) else return@get
            else -> emptyList()
        }
        call.respond(lista.map { it.toDto() })
    }

    get("/api/comprobantes/{id}/archivo") {
        val sesion = call.sesion()
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        val c = id?.let { servicios.comprobantes.buscar(it) }
        if (c == null || !((sesion.esPersonalEscolar && sesion.permite(c.plantelId)) || (sesion.esTutor && c.tutorId == sesion.tutorId))) {
            if (c != null) call.denegar(servicios, sesion, "Comprobante", c.id, "Comprobante no encontrado")
            else call.error(HttpStatusCode.NotFound, "Comprobante no encontrado")
            return@get
        }
        val bytes = servicios.comprobantes.archivo(c.id)
        if (bytes == null) {
            call.error(HttpStatusCode.NotFound, "El archivo ya no está disponible")
            return@get
        }
        if (sesion.esPersonalEscolar) servicios.auditoria.registrar(sesion.email, "VER_COMPROBANTE", "Comprobante", c.id, ip = call.ip())
        call.respondBytes(bytes, ContentType.parse(c.contentType))
    }

    post("/api/comprobantes/{id}/revision") {
        val sesion = call.sesion()
        if (!sesion.esPersonalEscolar) {
            servicios.auditoria.registrar(sesion.email, "ACCESO_DENEGADO", "Comprobante", call.parameters["id"], "Revisar con rol ${sesion.rol}", call.ip(), exito = false)
            call.error(HttpStatusCode.Forbidden, "Solo Dirección o Enfermería pueden revisar comprobantes")
            return@post
        }
        val entrada = call.receive<RevisionComprobanteDto>()
        val motivo = entrada.motivo?.trim()
        if (!entrada.aprobado && (motivo.isNullOrBlank() || motivo.length > 300)) {
            call.error(HttpStatusCode.BadRequest, "Indica el motivo del rechazo (hasta 300 caracteres) para que el tutor sepa qué corregir")
            return@post
        }
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        // Solo comprobantes de alumnos de sus escuelas (C-RE-01).
        val existente = id?.let { servicios.comprobantes.buscar(it) }
        if (existente != null && !sesion.permite(existente.plantelId)) {
            call.denegar(servicios, sesion, "Comprobante", existente.id, "Comprobante no encontrado")
            return@post
        }
        val revisado = id?.let { servicios.comprobantes.revisar(it, entrada.aprobado, sesion.email, motivo, servicios.reloj()) }
        if (revisado == null) {
            if (id == null || servicios.comprobantes.buscar(id) == null) call.error(HttpStatusCode.NotFound, "Comprobante no encontrado")
            else call.error(HttpStatusCode.Conflict, "Este comprobante ya fue revisado")
            return@post
        }
        servicios.despachador.despertar()
        call.respond(revisado.toDto())
    }
}
