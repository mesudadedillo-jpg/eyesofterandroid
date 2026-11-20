package com.example.eyesofterapp

import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.CitaCreadaDto
import com.example.eyesofterapp.model.DestinoDto
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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Expediente desde la app: ver, destino con pase o resumen, QR y citas (M-RFN-03, 08..11, C-RFN-04..06). */
class ExpedienteTest {
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj)
    private val hoy = LocalDate.now(ZoneId.of("America/Mexico_City"))

    private suspend fun HttpClient.recibir(folio: String = "ESC-1", matricula: String = "A001", semaforo: String = "ROJO") =
        post("/api/escritorio/tamizajes") {
            header("X-Api-Key", API_KEY_PRUEBA); contentType(ContentType.Application.Json)
            setBody(TamizajeEntradaDto(folio, matricula, hoy.toString(), semaforo, "20/60", "20/50", "Dolor de cabeza al leer"))
        }.body<TamizajeRecibidoDto>().tamizajeId

    private suspend fun HttpClient.destino(id: String, token: String, cuerpo: String): HttpResponse =
        post("/api/tamizajes/$id/destino") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(cuerpo) }

    @Test
    fun cadaQuienVeSoloLoSuyo() = testApplication {
        val client = servidor(servicios)
        val deAna = client.recibir()
        val deLuis = client.recibir("ESC-2", "A002")
        val tutor = client.tokenDe("tutor@eyesofter.mx")

        val lista = client.get("/api/tamizajes") { bearerAuth(tutor) }.body<List<TamizajeDto>>()
        assertEquals(listOf(deAna), lista.map { it.id })
        assertNotNull(lista.single().caso) // con caso, para guardarlo sin conexión

        // C-RFNN-06: el expediente de otro alumno → 404 y queda registrado.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/$deLuis") { bearerAuth(tutor) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/$deLuis/pase.png") { bearerAuth(tutor) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/abc") { bearerAuth(tutor) }.status)
        assertEquals(2, BasePrueba.cuenta("select count(*) from auditoria where accion = 'ACCESO_DENEGADO' and entidad = 'Tamizaje' and not exito"))

        val alumno = client.tokenDe("alumno@eyesofter.mx")
        assertEquals(listOf(deAna), client.get("/api/tamizajes") { bearerAuth(alumno) }.body<List<TamizajeDto>>().map { it.id })
        assertEquals(2, client.get("/api/tamizajes") { bearerAuth(client.tokenDe("enfermeria@eyesofter.mx")) }.body<List<TamizajeDto>>().size)
        assertTrue(client.get("/api/tamizajes") { bearerAuth(client.tokenDe("especialista@eyesofter.mx")) }.body<List<TamizajeDto>>().isEmpty())
    }

    @Test
    fun clinicaAsociadaGeneraPaseConQr() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")

        val r = client.destino(id, tutor, """{"clinicaId":1}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val d = r.body<DestinoDto>()
        assertTrue(d.conPase)
        val caso = d.tamizaje.caso!!
        assertEquals("EN_SEGUIMIENTO", caso.estado)
        assertTrue(caso.clinicaAsociada)
        val pase = caso.pase!!
        assertEquals("VIGENTE", pase.estado)
        assertEquals(26, pase.codigo.length)
        assertEquals("https://portal.prueba/Pases/Validar?codigo=${pase.codigo}", pase.urlValidacion)
        assertEquals("Óptica Tulancingo Centro", pase.clinicaNombre)
        assertTrue("DESTINO" in caso.eventos.map { it.tipo })

        val png = client.get("/api/tamizajes/$id/pase.png") { bearerAuth(tutor) }
        assertEquals(HttpStatusCode.OK, png.status)
        assertEquals(ContentType.Image.PNG, png.contentType())
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.readRawBytes().copyOfRange(0, 4))

        // Cambiar de destino cancela el pase anterior: solo uno vigente.
        val otra = client.destino(id, tutor, """{"clinicaId":5}""").body<DestinoDto>()
        assertTrue(otra.tamizaje.caso!!.pase!!.codigo != pase.codigo)
        assertEquals(1, BasePrueba.cuenta("select count(*) from pases where estado = 'Vigente'"))
        assertEquals(1, BasePrueba.cuenta("select count(*) from pases where estado = 'Cancelado'"))

        // El pase vence a los 60 días.
        reloj.avanzarDias(61)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/$id/pase.png") { bearerAuth(tutor) }.status)
        assertEquals("EXPIRADO", client.get("/api/tamizajes/$id") { bearerAuth(tutor) }.body<TamizajeDto>().caso!!.pase!!.estado)
    }

    @Test
    fun clinicaNoAsociadaONoRegistradaSoloDaResumen() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")

        val noAsociada = client.destino(id, tutor, """{"clinicaId":2}""").body<DestinoDto>()
        assertFalse(noAsociada.conPase)
        assertTrue("no está asociada" in noAsociada.mensaje)
        assertNull(noAsociada.tamizaje.caso!!.pase)
        assertEquals("Consultorio Valle", noAsociada.tamizaje.caso!!.clinicaNombre)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/$id/pase.png") { bearerAuth(tutor) }.status)

        // M-RFNN-06 / C-RFNN-07: escrita a mano y no registrada → no asociada, solo resumen.
        val manual = client.destino(id, tutor, """{"nombreManual":"Óptica Mi Barrio"}""").body<DestinoDto>()
        assertFalse(manual.conPase)
        assertTrue("no está registrada" in manual.mensaje)
        assertEquals("Óptica Mi Barrio", manual.tamizaje.caso!!.clinicaNombre)

        // Escrita a mano pero SÍ está en el directorio (sin acentos ni mayúsculas) → se reconoce y da pase.
        val reconocida = client.destino(id, tutor, """{"nombreManual":"optica tulancingo centro"}""").body<DestinoDto>()
        assertTrue(reconocida.conPase)
        assertEquals(1, reconocida.tamizaje.caso!!.clinicaId)

        // Asociada pero desactivada, o inexistente → error.
        assertEquals(HttpStatusCode.BadRequest, client.destino(id, tutor, """{"clinicaId":3}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.destino(id, tutor, """{"clinicaId":999}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.destino(id, tutor, """{}""").status)
    }

    @Test
    fun soloElTutorDelAlumnoEligeDestinoYVerdeNoTieneCaso() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        assertEquals(HttpStatusCode.NotFound, client.destino(id, client.tokenDe("tutor2@eyesofter.mx"), """{"clinicaId":1}""").status)
        assertEquals(HttpStatusCode.NotFound, client.destino(id, client.tokenDe("alumno@eyesofter.mx"), """{"clinicaId":1}""").status)
        // Enfermería sí puede capturarlo (como en el portal).
        assertEquals(HttpStatusCode.OK, client.destino(id, client.tokenDe("enfermeria@eyesofter.mx"), """{"clinicaId":2}""").status)

        val verde = client.recibir("ESC-V", "A003", "VERDE")
        assertEquals(HttpStatusCode.Conflict, client.destino(verde, client.tokenDe("tutor@eyesofter.mx"), """{"clinicaId":1}""").status)
    }

    @Test
    fun solicitarCita() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        val tutor = client.tokenDe("tutor@eyesofter.mx")
        suspend fun cita(cuerpo: String, token: String = tutor) =
            client.post("/api/tamizajes/$id/citas") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(cuerpo) }

        assertEquals(HttpStatusCode.BadRequest, cita("""{"clinicaId":1,"fechaPreferida":"${hoy.minusDays(1)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, cita("""{"clinicaId":3,"fechaPreferida":"${hoy.plusDays(3)}"}""").status)
        assertEquals(HttpStatusCode.Forbidden, cita("""{"clinicaId":1,"fechaPreferida":"${hoy.plusDays(3)}"}""", client.tokenDe("enfermeria@eyesofter.mx")).status)

        val r = cita("""{"clinicaId":1,"fechaPreferida":"${hoy.plusDays(3)}","horario":"MANANA","comentario":"Después de clases"}""")
        assertEquals(HttpStatusCode.Created, r.status)
        assertTrue("7751000001" in r.body<CitaCreadaDto>().mensaje)

        val caso = client.get("/api/tamizajes/$id") { bearerAuth(tutor) }.body<TamizajeDto>().caso!!
        assertEquals("SOLICITADA", caso.citas.single().estado)
        assertTrue("CITA" in caso.eventos.map { it.tipo })
        // Avisan a Enfermería y al especialista de esa clínica.
        for (quien in listOf("enfermeria@eyesofter.mx", "especialista@eyesofter.mx")) {
            val n = client.get("/api/notificaciones") { bearerAuth(client.tokenDe(quien)) }.body<BandejaDto>().notificaciones
            assertEquals("CITA", n.single { it.tipo == "CITA" }.tipo, quien)
        }
    }

    @Test
    fun casoCerradoNoAceptaCambios() = testApplication {
        val client = servidor(servicios)
        val id = client.recibir()
        BasePrueba.ejecutar("update casos set estado = 'Cerrado'")
        val r = client.destino(id, client.tokenDe("tutor@eyesofter.mx"), """{"clinicaId":1}""")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue("cerrado" in r.bodyAsText())
    }
}
