package com.example.eyesofterapp

import com.example.eyesofterapp.model.AlumnoEscritorioDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** API del sistema de escritorio (C-RFN-02, C-RI-01, C-RFNN-03). */
class EscritorioTest {
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj)
    private val hoy = LocalDate.now(ZoneId.of("America/Mexico_City")).toString()

    private fun tamizaje(folio: String = "ESC-001", matricula: String = "A001", semaforo: String = "AMARILLO", fecha: String = hoy) =
        TamizajeEntradaDto(folio, matricula, fecha, semaforo, "20/40", "20/30", "Entrecierra los ojos al leer el pizarrón")

    private suspend fun HttpClient.enviar(t: TamizajeEntradaDto, clave: String? = API_KEY_PRUEBA): HttpResponse =
        post("/api/escritorio/tamizajes") {
            clave?.let { header("X-Api-Key", it) }
            contentType(ContentType.Application.Json)
            setBody(t)
        }

    @Test
    fun exigeApiKey() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(), clave = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(), clave = "mala").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/escritorio/alumnos").status)
        assertEquals(0, BasePrueba.cuenta("select count(*) from tamizajes"))
        assertEquals(3, BasePrueba.cuenta("select count(*) from auditoria where accion = 'ACCESO_DENEGADO' and origen = 'escritorio'"))
    }

    @Test
    fun sinApiKeyConfiguradaLaRutaEstaApagada() = testApplication {
        val client = servidor(Servicios(configPrueba().copy(apiKeyEscritorio = null), db = BasePrueba.db, canales = { emptyList() }))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.enviar(tamizaje()).status)
    }

    @Test
    fun validaLosDatos() = testApplication {
        val client = servidor(servicios)
        val malos = listOf(
            tamizaje(folio = " "), tamizaje(semaforo = "AZUL"), tamizaje(fecha = "18/11/2026"),
            tamizaje(fecha = LocalDate.parse(hoy).plusDays(10).toString()), tamizaje().copy(agudezaOjoDerecho = "malo"),
        )
        for (t in malos) assertEquals(HttpStatusCode.BadRequest, client.enviar(t).status, t.toString())
        val desconocido = client.enviar(tamizaje(matricula = "Z999"))
        assertEquals(HttpStatusCode.UnprocessableEntity, desconocido.status)
        assertTrue("ALUMNO_DESCONOCIDO" in desconocido.bodyAsText())
    }

    @Test
    fun amarilloAbreCasoAvisaAlTutorYGuardaCifrado() = testApplication {
        val client = servidor(servicios)
        val r = client.enviar(tamizaje())
        assertEquals(HttpStatusCode.Created, r.status)
        val recibido = r.body<TamizajeRecibidoDto>()
        assertFalse(recibido.duplicado)
        assertEquals("EYS-${LocalDate.parse(hoy).year}-0001", recibido.casoFolio)
        assertEquals(1, recibido.tutoresAvisados) // la cuenta inactiva del mismo tutor no recibe nada

        // Campaña vigente y origen.
        assertEquals(1, BasePrueba.cuenta("select campana_id from tamizajes where folio = 'ESC-001'"))
        assertEquals("escritorio", BasePrueba.consulta("select origen from tamizajes") { it.getString(1) })
        // C-RC-04: datos sensibles cifrados en la base, legibles con la llave compartida.
        val crudo = BasePrueba.consulta("select observaciones from tamizajes") { it.getString(1) }!!
        assertTrue(crudo.startsWith("v1:"))
        assertEquals("Entrecierra los ojos al leer el pizarrón", BasePrueba.descifrar(crudo))
        assertTrue(BasePrueba.consulta("select descripcion from eventos_caso limit 1") { it.getString(1) }!!.startsWith("v1:"))
        assertEquals(2, BasePrueba.cuenta("select count(*) from eventos_caso")) // Tamizaje + AvisoTutor

        // El tutor lo ve en su bandeja; la notificación queda pendiente de entrega (bandeja de salida).
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        val n = client.get("/api/notificaciones") { bearerAuth(tutor) }.body<BandejaDto>().notificaciones.single()
        assertEquals("RESULTADO_TAMIZAJE", n.tipo)
        assertEquals("tamizaje/${recibido.tamizajeId}", n.datos["ruta"])
        assertFalse("AMARILLO" in n.mensaje.uppercase()) // sin el resultado en la push
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where pendiente_envio"))
        // El otro tutor no recibe nada.
        assertEquals(0, client.get("/api/notificaciones") { bearerAuth(client.tokenDe("tutor2@eyesofter.mx")) }.body<BandejaDto>().noLeidas)

        val t = client.get("/api/tamizajes/${recibido.tamizajeId}") { bearerAuth(tutor) }.body<TamizajeDto>()
        assertEquals("AMARILLO", t.semaforo)
        assertEquals("En observación", t.semaforoTexto)
        assertEquals("ABIERTO", t.caso!!.estado)
        assertFalse(t.caso!!.destinoDefinido)
        assertEquals(listOf("TAMIZAJE", "AVISO_TUTOR"), t.caso!!.eventos.map { it.tipo })
    }

    @Test
    fun reenviarElMismoFolioNoDuplica() = testApplication {
        val client = servidor(servicios)
        val primero = client.enviar(tamizaje()).body<TamizajeRecibidoDto>()
        val segundo = client.enviar(tamizaje())
        assertEquals(HttpStatusCode.OK, segundo.status)
        val dto = segundo.body<TamizajeRecibidoDto>()
        assertTrue(dto.duplicado)
        assertEquals(primero.tamizajeId, dto.tamizajeId)
        assertEquals(1, BasePrueba.cuenta("select count(*) from tamizajes"))
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones"))
    }

    @Test
    fun verdeNoAbreCasoPeroSiAvisa() = testApplication {
        val client = servidor(servicios)
        val r = client.enviar(tamizaje(semaforo = "verde")).body<TamizajeRecibidoDto>()
        assertNull(r.casoId)
        assertEquals(0, BasePrueba.cuenta("select count(*) from casos"))
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones"))
    }

    @Test
    fun nuevoTamizajeDeUnAlumnoConCasoAbiertoActualizaEseCaso() = testApplication {
        val client = servidor(servicios)
        val primero = client.enviar(tamizaje()).body<TamizajeRecibidoDto>()
        val segundo = client.enviar(tamizaje(folio = "ESC-002", semaforo = "ROJO")).body<TamizajeRecibidoDto>()
        assertEquals(primero.casoId, segundo.casoId)
        assertEquals(1, BasePrueba.cuenta("select count(*) from casos"))
        assertEquals("Rojo", BasePrueba.consulta("select semaforo from casos") { it.getString(1) })
        assertEquals(segundo.tamizajeId.toInt(), BasePrueba.cuenta("select tamizaje_id from casos"))
        // Folios distintos para alumnos distintos.
        val otro = client.enviar(tamizaje(folio = "ESC-003", matricula = "a002")).body<TamizajeRecibidoDto>()
        assertTrue(otro.casoFolio!!.endsWith("-0002"))
    }

    @Test
    fun tutorSinCuentaAlertaAEnfermeria() = testApplication {
        val client = servidor(servicios)
        BasePrueba.ejecutar("update asp_net_users set tutor_id = null where tutor_id = 2")
        val r = client.enviar(tamizaje(matricula = "A002", semaforo = "ROJO")).body<TamizajeRecibidoDto>()
        assertEquals(0, r.tutoresAvisados)
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA'"))
    }

    @Test
    fun incidenciaDeSincronizacionAvisaAEnfermeria() = testApplication {
        val client = servidor(servicios)
        val r = client.post("/api/escritorio/incidencias") {
            header("X-Api-Key", API_KEY_PRUEBA); contentType(ContentType.Application.Json)
            setBody("""{"mensaje":"Sin conexión a internet","pendientes":12,"equipo":"Enfermería PC1"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val enf = client.tokenDe("enfermeria@eyesofter.mx")
        val n = client.get("/api/notificaciones") { bearerAuth(enf) }.body<BandejaDto>().notificaciones.single()
        assertEquals("Falla de sincronización", n.titulo)
        assertTrue("12 tamizaje" in n.mensaje)
    }

    @Test
    fun listaAlumnosParaValidarMatricula() = testApplication {
        val client = servidor(servicios)
        val todos = client.get("/api/escritorio/alumnos") { header("X-Api-Key", API_KEY_PRUEBA) }.body<List<AlumnoEscritorioDto>>()
        assertEquals(3, todos.size)
        val uno = client.get("/api/escritorio/alumnos?q=a002") { header("X-Api-Key", API_KEY_PRUEBA) }.body<List<AlumnoEscritorioDto>>()
        assertEquals("Luis", uno.single().nombre)
        assertNotNull(uno.single().grado)
    }
}
