package com.example.eyesofterapp

import com.example.eyesofterapp.notificaciones.CanalCorreo
import com.example.eyesofterapp.notificaciones.CorreoBrevo
import com.example.eyesofterapp.notificaciones.CorreoResend
import com.example.eyesofterapp.notificaciones.EnviadorCorreo
import com.example.eyesofterapp.notificaciones.EnvioCodigosReal
import com.example.eyesofterapp.notificaciones.Destinatario
import com.example.eyesofterapp.repos.DispositivoRepository
import com.example.eyesofterapp.repos.NotificacionRecord
import com.example.eyesofterapp.repos.TipoNotificacion
import java.time.Instant
import java.util.UUID
import com.example.eyesofterapp.notificaciones.CanalPushFcm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prueba lo que el servidor le manda a Firebase y a Resend, sin red (MockEngine). */
class CanalesTest {

    init { BasePrueba.reiniciar() }
    private val idTutor = BasePrueba.idDe("tutor@eyesofter.mx")
    private val tutor = Destinatario(idTutor, "tutor@example.com", "Tutor Demo")
    private val idNotificacion = UUID.randomUUID()
    private val notificacion = NotificacionRecord(
        id = idNotificacion, destinatario = idTutor, tipo = TipoNotificacion.RESULTADO_TAMIZAJE,
        titulo = "Resultado del tamizaje visual", mensaje = "Ya está disponible",
        datos = mapOf("ruta" to "tamizaje/1", "tamizajeId" to "1"), creadaEn = Instant.EPOCH, leidaEn = null,
        recordatorios = 0, ultimoAvisoEn = Instant.EPOCH,
    )

    @Test
    fun fcmEnviaElMensajeCorrectoYOlvidaTokensVencidos() = runBlocking {
        val dispositivos = DispositivoRepository(BasePrueba.db)
        dispositivos.registrar("token-bueno", idTutor, "ANDROID", Instant.now())
        dispositivos.registrar("token-viejo", idTutor, "IOS", Instant.now())

        val cuerpos = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            assertEquals("https://fcm.googleapis.com/v1/projects/mi-proyecto/messages:send", req.url.toString())
            assertEquals("Bearer acceso-123", req.headers[HttpHeaders.Authorization])
            val cuerpo = (req.body as TextContent).text
            cuerpos += cuerpo
            if ("token-viejo" in cuerpo) respond("""{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""", HttpStatusCode.NotFound)
            else respond("""{"name":"projects/mi-proyecto/messages/1"}""", HttpStatusCode.OK)
        })
        val canal = CanalPushFcm(dispositivos, http, "mi-proyecto") { "acceso-123" }

        val r = canal.enviar(notificacion, tutor, esRecordatorio = true)
        assertTrue(r.exito)
        assertEquals(2, cuerpos.size)

        val mensaje = Json.parseToJsonElement(cuerpos.first { "token-bueno" in it }).jsonObject["message"]!!.jsonObject
        assertEquals("Recordatorio: Resultado del tamizaje visual", mensaje["notification"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val data = mensaje["data"]!!.jsonObject
        assertEquals("tamizaje/1", data["ruta"]!!.jsonPrimitive.content)
        assertEquals(idNotificacion.toString(), data["notificacionId"]!!.jsonPrimitive.content)
        assertEquals("RESULTADO_TAMIZAJE", data["tipo"]!!.jsonPrimitive.content)

        // El token que Firebase rechazó se borró; el bueno sigue.
        assertEquals(listOf("token-bueno"), dispositivos.deUsuario(idTutor))
    }

    @Test
    fun fcmSinDispositivosNoEsExito() = runBlocking {
        val http = HttpClient(MockEngine { error("no debería llamar a Firebase") })
        val r = CanalPushFcm(DispositivoRepository(BasePrueba.db), http, "p") { "t" }.enviar(notificacion, tutor, false)
        assertFalse(r.exito)
    }

    @Test
    fun correoUsaResendYRequiereEmail() = runBlocking {
        var llamado = false
        val http = HttpClient(MockEngine { req ->
            llamado = true
            assertEquals("https://api.resend.com/emails", req.url.toString())
            assertEquals("Bearer re_123", req.headers[HttpHeaders.Authorization])
            val cuerpo = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            assertEquals("tutor@example.com", cuerpo["to"].toString().trim('[', ']', '"'))
            respond("""{"id":"abc"}""", HttpStatusCode.OK)
        })
        val canal = CanalCorreo(CorreoResend({ http }, "re_123", "EYESOFTER <avisos@example.com>"))
        assertTrue(canal.enviar(notificacion, tutor, false).exito)
        assertTrue(llamado)
        assertFalse(canal.enviar(notificacion, tutor.copy(email = null), false).exito)
    }

    @Test
    fun correoConBrevoUsaSuApiYSeparaElRemitente() = runBlocking {
        var llamado = false
        val http = HttpClient(MockEngine { req ->
            llamado = true
            assertEquals(CorreoBrevo.URL, req.url.toString())
            assertEquals("xkeysib-123", req.headers["api-key"])
            assertEquals(null, req.headers[HttpHeaders.Authorization])
            val cuerpo = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            val remitente = cuerpo["sender"]!!.jsonObject
            assertEquals("EYESOFTER", remitente["name"]!!.jsonPrimitive.content)
            assertEquals("eyesofter.avisos@gmail.com", remitente["email"]!!.jsonPrimitive.content)
            assertEquals("tutor@example.com", cuerpo["to"]!!.jsonArray.single().jsonObject["email"]!!.jsonPrimitive.content)
            assertTrue(cuerpo["subject"]!!.jsonPrimitive.content.startsWith("EYESOFTER: "))
            assertTrue(cuerpo["textContent"]!!.jsonPrimitive.content.contains("Hola,"))
            respond("""{"messageId":"<1@brevo>"}""", HttpStatusCode.Created)
        })
        val canal = CanalCorreo(CorreoBrevo({ http }, "xkeysib-123", "EYESOFTER <eyesofter.avisos@gmail.com>"))
        val r = canal.enviar(notificacion, tutor, false)
        assertTrue(r.exito)
        assertTrue(r.detalle!!.contains("Brevo"))
        assertTrue(llamado)
    }

    @Test
    fun siBrevoRechazaElCorreoSeReportaElError() = runBlocking {
        val http = HttpClient(MockEngine { respond("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized) })
        val r = CanalCorreo(CorreoBrevo({ http }, "mala", "a@gmail.com")).enviar(notificacion, tutor, false)
        assertFalse(r.exito)
        assertTrue(r.detalle!!.startsWith("Brevo 401"))
    }

    @Test
    fun eligeBrevoAntesQueResendYNadaSinRemitente() {
        val http = { HttpClient(MockEngine { respond("") }) }
        assertEquals("Brevo", EnviadorCorreo.crear(http, "xkeysib", "re_1", "a@gmail.com")?.proveedor)
        assertEquals("Resend", EnviadorCorreo.crear(http, null, "re_1", "a@gmail.com")?.proveedor)
        assertEquals("Brevo", EnviadorCorreo.crear(http, "xkeysib", null, "a@gmail.com")?.proveedor)
        assertEquals(null, EnviadorCorreo.crear(http, "xkeysib", "re_1", null))
        assertEquals(null, EnviadorCorreo.crear(http, " ", null, "a@gmail.com"))
    }

    @Test
    fun separaNombreYCorreoDelRemitente() {
        assertEquals("EYESOFTER" to "eyesofter.avisos@gmail.com", EnviadorCorreo.separarRemitente("EYESOFTER <eyesofter.avisos@gmail.com>"))
        assertEquals("Escuela METANOIA" to "a@b.mx", EnviadorCorreo.separarRemitente("\"Escuela METANOIA\" <a@b.mx>"))
        assertEquals("EYESOFTER" to "avisos@gmail.com", EnviadorCorreo.separarRemitente("  avisos@gmail.com "))
    }

    @Test
    fun elCodigoDeRecuperacionSaleConBrevo() = runBlocking {
        var texto = ""
        val http = HttpClient(MockEngine { req ->
            texto = (req.body as TextContent).text
            respond("{}", HttpStatusCode.Created)
        })
        val envio = EnvioCodigosReal({ http }, CorreoBrevo({ http }, "xkeysib", "a@gmail.com"), null, null, null)
        assertTrue(envio.correo("tutor@example.com", "Ana", "123456"))
        assertTrue(texto.contains("123456"))
        assertFalse(EnvioCodigosReal({ http }, null, null, null, null).correo("tutor@example.com", "Ana", "123456"))
    }
}
