package com.example.eyesofterapp

import com.example.eyesofterapp.data.DispositivoRepository
import com.example.eyesofterapp.data.NotificacionRecord
import com.example.eyesofterapp.data.NotificacionRepository
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.data.UserRecord
import com.example.eyesofterapp.notificaciones.CanalCorreoResend
import com.example.eyesofterapp.notificaciones.CanalPushFcm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prueba lo que el servidor le manda a Firebase y a Resend, sin red (MockEngine). */
class CanalesTest {

    private val tutor = UserRecord("tutor", "x", "Tutor Demo", "CLIENTE", email = "tutor@example.com")
    private val notificacion = NotificacionRecord(
        id = "n1", destinatario = "tutor", tipo = TipoNotificacion.RESULTADO_TAMIZAJE,
        titulo = "Resultado del tamizaje visual", mensaje = "Ya está disponible",
        datos = mapOf("ruta" to "tamizaje/t1", "tamizajeId" to "t1"), creadaEn = 0,
    )

    @Test
    fun fcmEnviaElMensajeCorrectoYOlvidaTokensVencidos() = runBlocking {
        val dispositivos = DispositivoRepository(null)
        dispositivos.registrar("token-bueno", "tutor", "ANDROID", 0)
        dispositivos.registrar("token-viejo", "tutor", "IOS", 0)

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
        assertEquals("tamizaje/t1", data["ruta"]!!.jsonPrimitive.content)
        assertEquals("n1", data["notificacionId"]!!.jsonPrimitive.content)
        assertEquals("RESULTADO_TAMIZAJE", data["tipo"]!!.jsonPrimitive.content)

        // El token que Firebase rechazó se borró; el bueno sigue.
        assertEquals(listOf("token-bueno"), dispositivos.deUsuario("tutor").map { it.token })
    }

    @Test
    fun fcmSinDispositivosNoEsExito() = runBlocking {
        val http = HttpClient(MockEngine { error("no debería llamar a Firebase") })
        val r = CanalPushFcm(DispositivoRepository(null), http, "p") { "t" }.enviar(notificacion, tutor, false)
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
        val canal = CanalCorreoResend(http, "re_123", "EYESOFTER <avisos@example.com>")
        assertTrue(canal.enviar(notificacion, tutor, false).exito)
        assertTrue(llamado)
        assertFalse(canal.enviar(notificacion, tutor.copy(email = null), false).exito)
    }

    @Test
    fun lasNotificacionesSobrevivenAUnReinicio() = runBlocking {
        val carpeta = Files.createTempDirectory("eyesofter").toFile()
        try {
            val archivo = carpeta.resolve("notificaciones.json")
            val antes = NotificacionRepository(archivo)
            val n = antes.crear("Tutor", TipoNotificacion.AVISO, "Hola", "Mensaje", emptyMap(), 1000)
            antes.marcarLeida(n.id, "tutor", 2000)

            val despues = NotificacionRepository(archivo) // como si el servidor reiniciara
            val leida = despues.deUsuario("tutor").single()
            assertEquals(2000L, leida.leidaEn)
            assertEquals("notificacion/${n.id}", leida.datos["ruta"])
        } finally {
            carpeta.deleteRecursively()
        }
    }
}
