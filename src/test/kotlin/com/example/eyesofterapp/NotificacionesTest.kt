package com.example.eyesofterapp

import com.example.eyesofterapp.model.AvisoEnviadoDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.NotificacionDto
import com.example.eyesofterapp.model.RegistroDispositivoDto
import com.example.eyesofterapp.model.SeguimientoDto
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bandeja compartida, entrega por push/correo y recordatorios (M-RFN-02, C-RFN-07/08, C-RFNN-04, M-RFNN-04). */
class NotificacionesTest {
    private val push = CanalFalso("push")
    private val correo = CanalFalso("correo")
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj, listOf(push, correo))
    private val hoy = LocalDate.now(ZoneId.of("America/Mexico_City"))

    private suspend fun HttpClient.recibir(folio: String = "ESC-1", matricula: String = "A001", semaforo: String = "AMARILLO") =
        post("/api/escritorio/tamizajes") {
            header("X-Api-Key", API_KEY_PRUEBA); contentType(ContentType.Application.Json)
            setBody(TamizajeEntradaDto(folio, matricula, hoy.toString(), semaforo))
        }.body<TamizajeRecibidoDto>()

    private suspend fun HttpClient.bandeja(token: String) = get("/api/notificaciones") { bearerAuth(token) }.body<BandejaDto>()

    @Test
    fun lecturaConservaLaPrimeraFechaYNoSeTocanLasAjenas() = testApplication {
        val client = servidor(servicios)
        client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        val n = client.bandeja(tutor).notificaciones.single()

        val primera = client.post("/api/notificaciones/${n.id}/leida") { bearerAuth(tutor) }.body<NotificacionDto>()
        assertNotNull(primera.leidaEn)
        reloj.avanzarDias(1)
        val segunda = client.post("/api/notificaciones/${n.id}/leida") { bearerAuth(tutor) }.body<NotificacionDto>()
        assertEquals(primera.leidaEn, segunda.leidaEn)
        assertEquals(0, client.bandeja(tutor).noLeidas)

        val otro = client.tokenDe("tutor2@eyesofter.mx")
        assertEquals(HttpStatusCode.NotFound, client.post("/api/notificaciones/${n.id}/leida") { bearerAuth(otro) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/notificaciones/${UUID.randomUUID()}/leida") { bearerAuth(otro) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/notificaciones/no-es-uuid/leida") { bearerAuth(otro) }.status)
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where accion = 'ACCESO_DENEGADO' and entidad = 'Notificacion'"))
    }

    @Test
    fun avisosDelPersonal() = testApplication {
        val client = servidor(servicios)
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        val enf = client.tokenDe("enfermeria@eyesofter.mx")
        val cuerpo = """{"titulo":"Junta","mensaje":"Junta de padres el viernes","usernames":["alumno@eyesofter.mx","nadie@x.mx"],"rol":"CLIENTE"}"""

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/notificaciones") { bearerAuth(tutor); contentType(ContentType.Application.Json); setBody(cuerpo) }.status)
        val r = client.post("/api/notificaciones") { bearerAuth(enf); contentType(ContentType.Application.Json); setBody(cuerpo) }
        assertEquals(HttpStatusCode.Created, r.status)
        val enviado = r.body<AvisoEnviadoDto>()
        assertEquals(3, enviado.enviadas) // 2 tutores activos + el alumno
        assertEquals(listOf("nadie@x.mx"), enviado.noEncontrados)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/notificaciones") {
            bearerAuth(enf); contentType(ContentType.Application.Json); setBody("""{"titulo":"x","mensaje":"y","rol":"MARCIANO"}""")
        }.status)
        assertEquals("Junta", client.bandeja(tutor).notificaciones.single().titulo)
    }

    @Test
    fun despachadorPruebaPushYLuegoCorreo() = testApplication {
        val client = servidor(servicios)
        client.recibir()
        runBlocking { servicios.despachador.despacharPendientes() }
        assertEquals(1, push.envios.size)
        assertEquals("tutor@eyesofter.mx", push.envios.single().usuario)
        assertTrue(correo.envios.isEmpty()) // la push llegó: no hace falta el correo
        assertEquals(0, BasePrueba.cuenta("select count(*) from notificaciones where pendiente_envio"))

        // M-RFNN-04: si la push falla (sin permiso o sin app), se manda por correo.
        push.exito = false
        client.recibir("ESC-2", "A002")
        runBlocking { servicios.despachador.despacharPendientes() }
        assertEquals("tutor2@eyesofter.mx", correo.envios.single().usuario)
        assertEquals(3, BasePrueba.cuenta("select count(*) from entregas_notificacion"))

        val seguimiento = client.get("/api/notificaciones/seguimiento") { bearerAuth(client.tokenDe("enfermeria@eyesofter.mx")) }.body<List<SeguimientoDto>>()
        assertEquals(setOf(listOf("push"), listOf("correo")), seguimiento.map { it.entregadaPor }.toSet())
        assertEquals("Luis Ruiz", seguimiento.first { it.tutor == "tutor2@eyesofter.mx" }.alumno)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/notificaciones/seguimiento") { bearerAuth(client.tokenDe("tutor@eyesofter.mx")) }.status)
    }

    @Test
    fun loQueDejaElPortalEnLaBandejaDeSalidaTambienSeEntrega() = testApplication {
        servidor(servicios)
        // Así inserta el portal (Notificador.cs): pendiente_envio = true.
        BasePrueba.ejecutar(
            """insert into notificaciones (id, usuario_id, tipo, titulo, mensaje, datos, creada_en, recordatorios, ultimo_aviso_en, pendiente_envio, envio_es_recordatorio)
               values (?, ?, 'AVISO', 'Desde el portal', 'Hola', '{"ruta":"notificacion/x"}'::jsonb, now(), 0, now(), true, false)""",
            UUID.randomUUID(), BasePrueba.idDe("tutor2@eyesofter.mx")
        )
        // Y una para una cuenta desactivada: no se entrega.
        BasePrueba.ejecutar(
            """insert into notificaciones (id, usuario_id, tipo, titulo, mensaje, datos, creada_en, recordatorios, ultimo_aviso_en, pendiente_envio, envio_es_recordatorio)
               values (?, ?, 'AVISO', 'No', 'No', '{}'::jsonb, now(), 0, now(), true, false)""",
            UUID.randomUUID(), BasePrueba.idDe("inactivo@eyesofter.mx")
        )
        assertEquals(2, runBlocking { servicios.despachador.despacharPendientes() })
        assertEquals("Desde el portal", push.envios.single().notificacion.titulo)
    }

    @Test
    fun recordatoriosAlTutorYAlertaAEnfermeriaUnaSolaVez() = testApplication {
        val client = servidor(servicios)
        client.recibir()
        runBlocking { servicios.despachador.despacharPendientes() }
        push.envios.clear()

        reloj.avanzarDias(2)
        assertEquals(0, runBlocking { servicios.recordatorios.revisar() }) // aún no pasan 3 días

        reloj.avanzarDias(2)
        runBlocking { servicios.recordatorios.revisar(); servicios.despachador.despacharPendientes() }
        val alTutor = push.envios.filter { it.usuario == "tutor@eyesofter.mx" }
        assertEquals(1, alTutor.size)
        assertTrue(alTutor.single().esRecordatorio)
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA'"))

        // Siguen sin leer: hasta 3 recordatorios, pero la alerta a Enfermería solo la primera vez.
        repeat(5) { reloj.avanzarDias(4); runBlocking { servicios.recordatorios.revisar() } }
        assertEquals(3, BasePrueba.cuenta("select recordatorios from notificaciones where tipo = 'RESULTADO_TAMIZAJE'"))
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA'"))
    }

    @Test
    fun leerLaNotificacionDetieneLosRecordatorios() = testApplication {
        val client = servidor(servicios)
        client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        client.post("/api/notificaciones/leidas") { bearerAuth(tutor) }
        reloj.avanzarDias(4)
        runBlocking { servicios.recordatorios.revisar() }
        assertEquals(0, BasePrueba.cuenta("select recordatorios from notificaciones where tipo = 'RESULTADO_TAMIZAJE'"))
    }

    @Test
    fun casoSinAtenderRecibeRecordatorioSemanal() = testApplication {
        val client = servidor(servicios)
        val r = client.recibir(semaforo = "ROJO")
        client.post("/api/notificaciones/leidas") { bearerAuth(client.tokenDe("tutor@eyesofter.mx")) }
        reloj.avanzarDias(8)
        runBlocking { servicios.recordatorios.revisar() }
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'RECORDATORIO_CASO'"))
        runBlocking { servicios.recordatorios.revisar() } // misma semana: no repite
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'RECORDATORIO_CASO'"))
        // Con un comprobante aprobado ya no se recuerda.
        BasePrueba.ejecutar(
            "insert into comprobantes (id, caso_id, subido_por_id, tipo, content_type, tamano_bytes, contenido, subido_en, estado) values (?, ?, 'x', 'Receta', 'image/png', 1, ?, now(), 'Aprobado')",
            UUID.randomUUID(), r.casoId, byteArrayOf(1)
        )
        reloj.avanzarDias(8)
        runBlocking { servicios.recordatorios.revisar() }
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'RECORDATORIO_CASO'"))
    }

    @Test
    fun dispositivos() = testApplication {
        val client = servidor(servicios)
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        fun registro(token: String, plataforma: String = "android") = RegistroDispositivoDto(token, plataforma)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/dispositivos") { bearerAuth(tutor); contentType(ContentType.Application.Json); setBody(registro("t1", "windows")) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/dispositivos") { bearerAuth(tutor); contentType(ContentType.Application.Json); setBody(registro("t1")) }.status)
        // El mismo teléfono inicia sesión con otra cuenta: el token cambia de dueño.
        val otro = client.tokenDe("tutor2@eyesofter.mx")
        client.post("/api/dispositivos") { bearerAuth(otro); contentType(ContentType.Application.Json); setBody(registro("t1", "IOS")) }
        assertEquals(BasePrueba.idDe("tutor2@eyesofter.mx"), BasePrueba.consulta("select usuario_id from dispositivos where token = 't1'") { it.getString(1) })
        // El tutor anterior no puede borrarlo; el dueño sí.
        client.delete("/api/dispositivos?token=t1") { bearerAuth(tutor) }
        assertEquals(1, BasePrueba.cuenta("select count(*) from dispositivos"))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/dispositivos?token=t1") { bearerAuth(otro) }.status)
        assertNull(BasePrueba.consulta("select token from dispositivos") { it.getString(1) })
    }
}
