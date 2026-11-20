package com.example.eyesofterapp

import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.ComprobanteDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recetas y comprobantes (M-RFN-05, C-RFNN-05, C-RC-04). */
class ComprobantesTest {
    private val servicios = serviciosPrueba()
    private val hoy = LocalDate.now(ZoneId.of("America/Mexico_City"))

    /** Inicio de un JPG real (FF D8 FF) seguido de relleno. */
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2000) { 7 }

    private suspend fun HttpClient.recibir(folio: String = "ESC-1", matricula: String = "A001", semaforo: String = "ROJO") =
        post("/api/escritorio/tamizajes") {
            header("X-Api-Key", API_KEY_PRUEBA); contentType(ContentType.Application.Json)
            setBody(TamizajeEntradaDto(folio, matricula, hoy.toString(), semaforo))
        }.body<TamizajeRecibidoDto>().tamizajeId

    private suspend fun HttpClient.subir(token: String, tamizajeId: String, bytes: ByteArray, tipo: String = "RECETA"): HttpResponse =
        submitFormWithBinaryData("/api/comprobantes", formData {
            append("tamizajeId", tamizajeId)
            append("tipo", tipo)
            append("archivo", bytes, Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"receta.jpg\"")
            })
        }) { bearerAuth(token) }

    @Test
    fun subirGuardaCifradoYEnfermeriaLoRevisa() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")

        val r = client.subir(tutor, id, jpg)
        assertEquals(HttpStatusCode.Created, r.status)
        val c = r.body<ComprobanteDto>()
        assertEquals("PENDIENTE", c.estado)
        assertEquals("RECETA", c.tipo)
        assertEquals("image/jpeg", c.contentType)
        assertEquals("tutor@eyesofter.mx", c.tutor)

        // C-RC-04: en la base va cifrado (prefijo "E1"), no el JPG tal cual.
        val crudo = BasePrueba.consulta("select contenido from comprobantes") { it.getBytes(1) }!!
        assertEquals("E1", String(crudo, 0, 2))
        assertTrue(crudo.size > jpg.size)

        val enf = client.tokenDe("enfermeria@eyesofter.mx")
        assertEquals("COMPROBANTE_RECIBIDO", client.get("/api/notificaciones") { bearerAuth(enf) }.body<BandejaDto>().notificaciones.single().tipo)
        assertEquals(1, client.get("/api/comprobantes?estado=PENDIENTE") { bearerAuth(enf) }.body<List<ComprobanteDto>>().size)
        assertContentEquals(jpg, client.get("/api/comprobantes/${c.id}/archivo") { bearerAuth(enf) }.readRawBytes())

        // Rechazo sin motivo → 400; con motivo → aviso al tutor.
        suspend fun revisar(cuerpo: String, token: String = enf) =
            client.post("/api/comprobantes/${c.id}/revision") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(cuerpo) }
        assertEquals(HttpStatusCode.Forbidden, revisar("""{"aprobado":true}""", tutor).status)
        assertEquals(HttpStatusCode.BadRequest, revisar("""{"aprobado":false}""").status)
        val rechazado = revisar("""{"aprobado":false,"motivo":"La foto está borrosa"}""").body<ComprobanteDto>()
        assertEquals("RECHAZADO", rechazado.estado)
        assertEquals(HttpStatusCode.Conflict, revisar("""{"aprobado":true}""").status)
        val aviso = client.get("/api/notificaciones") { bearerAuth(tutor) }.body<BandejaDto>().notificaciones.first { it.tipo == "COMPROBANTE_REVISADO" }
        assertTrue("borrosa" in aviso.mensaje)
        assertEquals("tamizaje/$id", aviso.datos["ruta"])

        // Un segundo comprobante, aprobado → el caso queda con corrección comprobada.
        val otro = client.subir(tutor, id, jpg, "comprobante_lentes").body<ComprobanteDto>()
        client.post("/api/comprobantes/${otro.id}/revision") { bearerAuth(enf); contentType(ContentType.Application.Json); setBody("""{"aprobado":true}""") }
        assertTrue(client.get("/api/tamizajes/$id") { bearerAuth(tutor) }.body<TamizajeDto>().caso!!.comprobanteAprobado)
        assertEquals(2, client.get("/api/comprobantes") { bearerAuth(tutor) }.body<List<ComprobanteDto>>().size)
    }

    @Test
    fun rechazaArchivosQueNoSonImagenOPdfOMuyGrandes() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.subir(tutor, id, "MZ ejecutable".toByteArray()).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.subir(tutor, id, jpg + ByteArray(10 * 1024 * 1024)).status)
        assertEquals(HttpStatusCode.BadRequest, client.subir(tutor, id, jpg, "FOTO").status)
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(100)
        assertEquals(HttpStatusCode.Created, client.subir(tutor, id, pdf).status)
    }

    @Test
    fun soloElTutorDelAlumnoYSoloConCaso() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val verde = client.recibir("ESC-V", "A003", "VERDE")
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        assertEquals(HttpStatusCode.NotFound, client.subir(client.tokenDe("tutor2@eyesofter.mx"), id, jpg).status)
        assertEquals(HttpStatusCode.Forbidden, client.subir(client.tokenDe("enfermeria@eyesofter.mx"), id, jpg).status)
        assertEquals(HttpStatusCode.Conflict, client.subir(tutor, verde, jpg).status)

        val c = client.subir(tutor, id, jpg).body<ComprobanteDto>()
        val ajeno = client.tokenDe("tutor2@eyesofter.mx")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/comprobantes/${c.id}/archivo") { bearerAuth(ajeno) }.status)
        assertTrue(client.get("/api/comprobantes") { bearerAuth(ajeno) }.body<List<ComprobanteDto>>().isEmpty())
    }
}
