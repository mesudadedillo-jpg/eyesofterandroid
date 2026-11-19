package com.example.eyesofterapp

import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.model.AvisoEntradaDto
import com.example.eyesofterapp.model.AvisoEnviadoDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.NotificacionDto
import com.example.eyesofterapp.model.RegistroDispositivoDto
import com.example.eyesofterapp.model.SeguimientoDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Flujo completo de notificaciones: escritorio → tutor → lectura → recordatorios. */
class NotificacionesTest {

    private val push = CanalFalso("push")
    private val correo = CanalFalso("correo")
    private val reloj = RelojFalso()
    private val servicios = Servicios(configPrueba(), scope = null, canales = { listOf(push, correo) }, reloj = { reloj.ahora })

    private fun tamizaje(folio: String = "T-001", tutor: String = "tutor", semaforo: String = "AMARILLO") =
        TamizajeEntradaDto(
            folio = folio, alumnoNombre = "Ana López", grado = "3", grupo = "B",
            tutorUsername = tutor, fecha = "2026-11-18", semaforo = semaforo, observaciones = "Usa lentes desde 2025",
        )

    private suspend fun HttpClient.enviarTamizaje(t: TamizajeEntradaDto, clave: String? = API_KEY_PRUEBA): HttpResponse =
        post("/api/escritorio/tamizajes") {
            clave?.let { header("X-Api-Key", it) }
            contentType(ContentType.Application.Json)
            setBody(t)
        }

    private suspend fun HttpClient.bandeja(token: String) = get("/api/notificaciones") { bearerAuth(token) }.body<BandejaDto>()

    // ---------- Sesión ----------

    @Test
    fun rutasConSesionExigenToken() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth("jwt-mock-token-123") }.status)
        // Firmado con otro secreto (p. ej. de otro servidor) → rechazado.
        val ajeno = Servicios(configPrueba().copy(jwtSecret = "otro"), scope = null).tokens.crear("tutor", "CLIENTE")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(ajeno) }.status)
        // El token del login sí sirve.
        val bandeja = client.bandeja(client.tokenDe("tutor"))
        assertEquals(0, bandeja.noLeidas)
    }

    // ---------- Recepción de tamizajes ----------

    @Test
    fun escritorioExigeApiKey() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.enviarTamizaje(tamizaje(), clave = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.enviarTamizaje(tamizaje(), clave = "mala").status)
        assertTrue(push.envios.isEmpty())
    }

    @Test
    fun escritorioSinClaveConfiguradaResponde503() = testApplication {
        val client = servidor(Servicios(configPrueba().copy(apiKeyEscritorio = null), scope = null))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.enviarTamizaje(tamizaje()).status)
    }

    @Test
    fun tamizajeNuevoNotificaAlTutorPorPush() = testApplication {
        val client = servidor(servicios)
        val r = client.enviarTamizaje(tamizaje())
        assertEquals(HttpStatusCode.Created, r.status)
        val recibido = r.body<TamizajeRecibidoDto>()
        assertFalse(recibido.duplicado)

        // Se envió por push y, como funcionó, no se usó el correo.
        assertEquals(listOf("tutor"), push.envios.map { it.usuario })
        assertTrue(correo.envios.isEmpty())
        // El texto de la push no revela el resultado.
        assertFalse(push.envios.single().notificacion.mensaje.contains("AMARILLO", ignoreCase = true))
        assertFalse(push.envios.single().notificacion.mensaje.contains("observación", ignoreCase = true))

        val bandeja = client.bandeja(client.tokenDe("tutor"))
        assertEquals(1, bandeja.noLeidas)
        val n = bandeja.notificaciones.single()
        assertEquals("RESULTADO_TAMIZAJE", n.tipo)
        assertEquals(recibido.notificacionId, n.id)
        assertEquals("tamizaje/${recibido.tamizajeId}", n.datos["ruta"])
        assertNull(n.leidaEn)

        // Otro tutor no la ve.
        assertEquals(0, client.bandeja(client.tokenDe("tutor2")).notificaciones.size)
    }

    @Test
    fun folioRepetidoNoDuplicaNiReenvia() = testApplication {
        val client = servidor(servicios)
        val primero = client.enviarTamizaje(tamizaje()).body<TamizajeRecibidoDto>()
        val segundo = client.enviarTamizaje(tamizaje())
        assertEquals(HttpStatusCode.OK, segundo.status)
        val dto = segundo.body<TamizajeRecibidoDto>()
        assertTrue(dto.duplicado)
        assertEquals(primero.tamizajeId, dto.tamizajeId)
        assertEquals(1, push.envios.size)
    }

    @Test
    fun tamizajeInvalidoDevuelve400() = testApplication {
        val client = servidor(servicios)
        suspend fun espera400(t: TamizajeEntradaDto, texto: String) {
            val r = client.enviarTamizaje(t)
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains(texto), r.bodyAsText())
        }
        espera400(tamizaje(semaforo = "AZUL"), "semaforo")
        espera400(tamizaje(tutor = "noexiste"), "no existe")
        espera400(tamizaje(tutor = "doctor"), "no es tutor")
        espera400(tamizaje().copy(fecha = "18/11/2026"), "AAAA-MM-DD")
        espera400(tamizaje().copy(folio = " "), "folio")
        assertTrue(push.envios.isEmpty())
    }

    // ---------- Detalle del tamizaje (a donde lleva la notificación) ----------

    @Test
    fun soloElTutorYElPersonalVenElTamizaje() = testApplication {
        val client = servidor(servicios)
        val id = client.enviarTamizaje(tamizaje(semaforo = "rojo")).body<TamizajeRecibidoDto>().tamizajeId

        val delTutor = client.get("/api/tamizajes/$id") { bearerAuth(client.tokenDe("tutor")) }
        assertEquals(HttpStatusCode.OK, delTutor.status)
        val dto = delTutor.body<TamizajeDto>()
        assertEquals("ROJO", dto.semaforo)
        assertEquals("Atención urgente requerida", dto.semaforoTexto)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/$id") { bearerAuth(client.tokenDe("tutor2")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/tamizajes/$id") { bearerAuth(client.tokenDe("enfermeria")) }.status)

        assertEquals(1, client.get("/api/tamizajes") { bearerAuth(client.tokenDe("tutor")) }.body<List<TamizajeDto>>().size)
        assertEquals(0, client.get("/api/tamizajes") { bearerAuth(client.tokenDe("tutor2")) }.body<List<TamizajeDto>>().size)
    }

    // ---------- Confirmación de lectura ----------

    @Test
    fun marcarLeidaGuardaLaPrimeraFechaYSoloParaElDueno() = testApplication {
        val client = servidor(servicios)
        val id = client.enviarTamizaje(tamizaje()).body<TamizajeRecibidoDto>().notificacionId!!
        val tutor = client.tokenDe("tutor")

        assertEquals(HttpStatusCode.NotFound, client.post("/api/notificaciones/$id/leida") { bearerAuth(client.tokenDe("tutor2")) }.status)

        val primera = client.post("/api/notificaciones/$id/leida") { bearerAuth(tutor) }.body<NotificacionDto>()
        assertNotNull(primera.leidaEn)
        reloj.avanzarDias(1)
        val segunda = client.post("/api/notificaciones/$id/leida") { bearerAuth(tutor) }.body<NotificacionDto>()
        assertEquals(primera.leidaEn, segunda.leidaEn)

        assertEquals(0, client.bandeja(tutor).noLeidas)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/notificaciones/no-existe/leida") { bearerAuth(tutor) }.status)
    }

    @Test
    fun marcarTodasLeidas() = testApplication {
        val client = servidor(servicios)
        client.enviarTamizaje(tamizaje("A"))
        client.enviarTamizaje(tamizaje("B"))
        val tutor = client.tokenDe("tutor")
        assertEquals(2, client.bandeja(tutor).noLeidas)
        val r = client.post("/api/notificaciones/leidas") { bearerAuth(tutor) }
        assertTrue(r.bodyAsText().contains("2"))
        assertEquals(0, client.bandeja(tutor).noLeidas)
        assertEquals(2, client.bandeja(tutor).notificaciones.size)
        val soloNoLeidas = client.get("/api/notificaciones?soloNoLeidas=true") { bearerAuth(tutor) }.body<BandejaDto>()
        assertTrue(soloNoLeidas.notificaciones.isEmpty())
    }

    // ---------- Canales de respaldo ----------

    @Test
    fun siLaPushFallaSeUsaElCorreoYQuedaEnElSeguimiento() = testApplication {
        push.exito = false
        val client = servidor(servicios)
        client.enviarTamizaje(tamizaje())
        assertEquals(1, push.envios.size)
        assertEquals(1, correo.envios.size)

        val filas = client.get("/api/notificaciones/seguimiento") { bearerAuth(client.tokenDe("enfermeria")) }.body<List<SeguimientoDto>>()
        assertEquals(listOf("correo"), filas.single().entregadaPor)
        assertEquals("Ana López", filas.single().alumno)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/notificaciones/seguimiento") { bearerAuth(client.tokenDe("tutor")) }.status)
    }

    @Test
    fun sinCanalesLaNotificacionQuedaEnLaBandeja() = testApplication {
        val client = servidor(Servicios(configPrueba(), scope = null, canales = { emptyList() }))
        client.enviarTamizaje(tamizaje())
        assertEquals(1, client.bandeja(client.tokenDe("tutor")).noLeidas)
    }

    // ---------- Recordatorios (C-RFNN-04) ----------

    @Test
    fun avisoSinLeerTresDiasSeReenviaYAlertaAEnfermeriaUnaVez() = testApplication {
        val client = servidor(servicios)
        client.enviarTamizaje(tamizaje())
        push.envios.clear()

        reloj.avanzarDias(2)
        assertEquals(0, runBlocking { servicios.recordatorios.revisar() })

        reloj.avanzarDias(1)
        assertEquals(1, runBlocking { servicios.recordatorios.revisar() })
        // Al tutor le llega como recordatorio, y Enfermería recibe una alerta.
        val alTutor = push.envios.filter { it.usuario == "tutor" }
        assertTrue(alTutor.single().esRecordatorio)
        val alertas = client.bandeja(client.tokenDe("enfermeria")).notificaciones
        assertEquals(TipoNotificacion.ALERTA_ENFERMERIA.name, alertas.single().tipo)
        assertTrue(alertas.single().mensaje.contains("Ana López"))

        // Revisar de nuevo enseguida no reenvía.
        assertEquals(0, runBlocking { servicios.recordatorios.revisar() })

        // Otros 3 días: segundo recordatorio, sin nueva alerta a Enfermería.
        reloj.avanzarDias(3)
        assertEquals(1, runBlocking { servicios.recordatorios.revisar() })
        assertEquals(1, client.bandeja(client.tokenDe("enfermeria")).notificaciones.size)

        // Seguimiento refleja los recordatorios.
        val fila = client.get("/api/notificaciones/seguimiento") { bearerAuth(client.tokenDe("admin")) }.body<List<SeguimientoDto>>().single()
        assertEquals(2, fila.recordatoriosEnviados)

        // Una vez leída, ya no se recuerda.
        val tutor = client.tokenDe("tutor")
        val id = client.bandeja(tutor).notificaciones.single().id
        client.post("/api/notificaciones/$id/leida") { bearerAuth(tutor) }
        reloj.avanzarDias(10)
        assertEquals(0, runBlocking { servicios.recordatorios.revisar() })
    }

    @Test
    fun recordatoriosRespetanElMaximo() = testApplication {
        val client = servidor(servicios)
        client.enviarTamizaje(tamizaje())
        repeat(5) {
            reloj.avanzarDias(3)
            runBlocking { servicios.recordatorios.revisar() }
        }
        val recordatorios = push.envios.count { it.usuario == "tutor" && it.esRecordatorio }
        assertEquals(servicios.config.recordatorioMax, recordatorios)
    }

    // ---------- Dispositivos ----------

    @Test
    fun registrarYQuitarDispositivo() = testApplication {
        val client = servidor(servicios)
        val tutor = client.tokenDe("tutor")
        suspend fun registrar(plataforma: String) = client.post("/api/dispositivos") {
            bearerAuth(tutor)
            contentType(ContentType.Application.Json)
            setBody(RegistroDispositivoDto("fcm-token-abc", plataforma))
        }
        assertEquals(HttpStatusCode.BadRequest, registrar("WINDOWS").status)
        assertEquals(HttpStatusCode.NoContent, registrar("android").status)
        assertEquals("ANDROID", runBlocking { servicios.dispositivos.deUsuario("tutor") }.single().plataforma)

        // Si otro usuario inicia sesión en el mismo teléfono, el token pasa a él.
        client.post("/api/dispositivos") {
            bearerAuth(client.tokenDe("tutor2"))
            contentType(ContentType.Application.Json)
            setBody(RegistroDispositivoDto("fcm-token-abc", "ANDROID"))
        }
        assertTrue(runBlocking { servicios.dispositivos.deUsuario("tutor") }.isEmpty())

        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/dispositivos") { bearerAuth(tutor) }.status)
        client.delete("/api/dispositivos?token=fcm-token-abc") { bearerAuth(client.tokenDe("tutor2")) }
        assertTrue(runBlocking { servicios.dispositivos.deUsuario("tutor2") }.isEmpty())
    }

    // ---------- Avisos manuales ----------

    @Test
    fun soloElPersonalEnviaAvisosYPuedeUsarRol() = testApplication {
        val client = servidor(servicios)
        val aviso = AvisoEntradaDto("Junta de padres", "El viernes a las 10:00", rol = "CLIENTE", usernames = listOf("tutor", "fantasma"))

        val prohibido = client.post("/api/notificaciones") {
            bearerAuth(client.tokenDe("tutor"))
            contentType(ContentType.Application.Json)
            setBody(aviso)
        }
        assertEquals(HttpStatusCode.Forbidden, prohibido.status)

        val r = client.post("/api/notificaciones") {
            bearerAuth(client.tokenDe("enfermeria"))
            contentType(ContentType.Application.Json)
            setBody(aviso)
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val dto = r.body<AvisoEnviadoDto>()
        assertEquals(2, dto.enviadas) // tutor y tutor2, sin duplicar a "tutor"
        assertEquals(listOf("fantasma"), dto.noEncontrados)
        assertEquals("AVISO", client.bandeja(client.tokenDe("tutor2")).notificaciones.single().tipo)

        val vacio = client.post("/api/notificaciones") {
            bearerAuth(client.tokenDe("admin"))
            contentType(ContentType.Application.Json)
            setBody(AvisoEntradaDto("x", "y"))
        }
        assertEquals(HttpStatusCode.BadRequest, vacio.status)
    }
}
