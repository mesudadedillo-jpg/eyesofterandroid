package com.example.eyesofterapp

import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.ComprobanteDto
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.RevisionComprobanteDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recetas y comprobantes (M-RFN-05, C-RFNN-05) y bloqueo de login (C-RFNN-01). */
class ComprobantesTest {

    private val reloj = RelojFalso()
    private val servicios = Servicios(configPrueba(), scope = null, canales = { emptyList() }, reloj = { reloj.ahora })

    /** Inicio de un JPG real (FF D8 FF) seguido de relleno. */
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2000) { 7 }

    private suspend fun HttpClient.nuevoTamizaje(tutor: String = "tutor", folio: String = "F1"): String =
        post("/api/escritorio/tamizajes") {
            header("X-Api-Key", API_KEY_PRUEBA)
            contentType(ContentType.Application.Json)
            setBody(TamizajeEntradaDto(folio, "Ana López", tutorUsername = tutor, fecha = "2026-11-18", semaforo = "ROJO"))
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
    fun tutorSubeYEnfermeriaApruebaYAvisaAlTutor() = testApplication {
        val client = servidor(servicios)
        val tamizaje = client.nuevoTamizaje()
        val tutor = client.tokenDe("tutor")
        val enfermeria = client.tokenDe("enfermeria")

        val subida = client.subir(tutor, tamizaje, jpg)
        assertEquals(HttpStatusCode.Created, subida.status)
        val c = subida.body<ComprobanteDto>()
        assertEquals("PENDIENTE", c.estado)
        assertEquals("image/jpeg", c.contentType)

        // Enfermería recibe aviso y lo ve en pendientes.
        val bandejaEnf = client.get("/api/notificaciones") { bearerAuth(enfermeria) }.body<BandejaDto>()
        assertEquals("COMPROBANTE_RECIBIDO", bandejaEnf.notificaciones.single().tipo)
        val pendientes = client.get("/api/comprobantes?estado=PENDIENTE") { bearerAuth(enfermeria) }.body<List<ComprobanteDto>>()
        assertEquals(listOf(c.id), pendientes.map { it.id })

        // Puede descargar el archivo tal cual.
        val archivo = client.get("/api/comprobantes/${c.id}/archivo") { bearerAuth(enfermeria) }
        assertEquals(ContentType.Image.JPEG, archivo.contentType())
        assertContentEquals(jpg, archivo.readRawBytes())

        // Rechazar sin motivo no se permite.
        val sinMotivo = client.post("/api/comprobantes/${c.id}/revision") {
            bearerAuth(enfermeria); contentType(ContentType.Application.Json); setBody(RevisionComprobanteDto(aprobado = false))
        }
        assertEquals(HttpStatusCode.BadRequest, sinMotivo.status)

        val aprobado = client.post("/api/comprobantes/${c.id}/revision") {
            bearerAuth(enfermeria); contentType(ContentType.Application.Json); setBody(RevisionComprobanteDto(aprobado = true))
        }.body<ComprobanteDto>()
        assertEquals("APROBADO", aprobado.estado)

        // Revisar dos veces → 409.
        val otraVez = client.post("/api/comprobantes/${c.id}/revision") {
            bearerAuth(enfermeria); contentType(ContentType.Application.Json); setBody(RevisionComprobanteDto(aprobado = true))
        }
        assertEquals(HttpStatusCode.Conflict, otraVez.status)

        val bandejaTutor = client.get("/api/notificaciones") { bearerAuth(tutor) }.body<BandejaDto>()
        assertTrue(bandejaTutor.notificaciones.any { it.tipo == "COMPROBANTE_REVISADO" && it.titulo == "Comprobante aceptado" })
    }

    @Test
    fun seRechazanArchivosInvalidosYAjenos() = testApplication {
        val client = servidor(servicios)
        val tamizaje = client.nuevoTamizaje()
        val tutor = client.tokenDe("tutor")

        // Un .exe renombrado a .jpg: el contenido no es imagen.
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.subir(tutor, tamizaje, "MZ\u0090\u0000hola".toByteArray()).status)
        // Más de 10 MB.
        val grande = jpg + ByteArray(10 * 1024 * 1024)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.subir(tutor, tamizaje, grande).status)
        // Tipo inválido.
        assertEquals(HttpStatusCode.BadRequest, client.subir(tutor, tamizaje, jpg, tipo = "FOTO").status)
        // Tamizaje de otro tutor.
        assertEquals(HttpStatusCode.NotFound, client.subir(client.tokenDe("tutor2"), tamizaje, jpg).status)
        // Un alumno no sube comprobantes.
        assertEquals(HttpStatusCode.Forbidden, client.subir(client.tokenDe("alumno"), tamizaje, jpg).status)

        // Y el tutor2 no ve ni descarga los del tutor.
        val c = client.subir(tutor, tamizaje, jpg).body<ComprobanteDto>()
        val otro = client.tokenDe("tutor2")
        assertTrue(client.get("/api/comprobantes") { bearerAuth(otro) }.body<List<ComprobanteDto>>().isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/comprobantes/${c.id}/archivo") { bearerAuth(otro) }.status)
        // Ni revisa.
        val revision = client.post("/api/comprobantes/${c.id}/revision") {
            bearerAuth(tutor); contentType(ContentType.Application.Json); setBody(RevisionComprobanteDto(aprobado = true))
        }
        assertEquals(HttpStatusCode.Forbidden, revision.status)
    }

    @Test
    fun tresIntentosFallidosBloqueanLaCuenta() = testApplication {
        val client = servidor(servicios)
        suspend fun intento(pass: String) = client.post("/api/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequestDto("tutor", pass))
        }
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, intento("mala").status) }
        // Un acierto reinicia el contador.
        assertEquals(HttpStatusCode.OK, intento("123456").status)
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, intento("mala").status) }
        // Bloqueada: ni con la contraseña correcta.
        val bloqueada = intento("123456")
        assertEquals(HttpStatusCode.TooManyRequests, bloqueada.status)
        assertTrue(bloqueada.bodyAsText().contains("15 min"), bloqueada.bodyAsText())
        // Otro usuario no se ve afectado.
        assertEquals(HttpStatusCode.OK, client.post("/api/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequestDto("tutor2", "123456"))
        }.status)
        // Pasado el tiempo, vuelve a entrar.
        reloj.ahora += 16 * 60_000
        assertEquals(HttpStatusCode.OK, intento("123456").status)
    }

    @Test
    fun corsPermiteDeleteParaLaVersionWeb() = testApplication {
        val client = servidor(servicios)
        val r = client.options("/api/dispositivos") {
            header(HttpHeaders.Origin, "https://ejemplo.com")
            header(HttpHeaders.AccessControlRequestMethod, "DELETE")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().contains("DELETE"))
    }
}
