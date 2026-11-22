package com.example.eyesofterapp

import com.example.eyesofterapp.model.AlumnoEscritorioDto
import com.example.eyesofterapp.model.AvisoEnviadoDto
import com.example.eyesofterapp.model.ClinicaDto
import com.example.eyesofterapp.model.DestinoDto
import com.example.eyesofterapp.model.Optica
import com.example.eyesofterapp.model.SeguimientoDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import com.example.eyesofterapp.model.VinculacionCreadaDto
import com.example.eyesofterapp.model.VinculacionEntradaDto
import com.example.eyesofterapp.model.VinculacionEstadoEntradaDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Varias escuelas en el mismo sistema (C-RE-01). La base de prueba tiene dos:
 *  - escuela 1: Ana (A001), Luis (A002), Sofía (A003); Dirección, enfermeria@, tutor@, tutor2@.
 *  - escuela 2: Bruno (también A001); enfermeria2@, tutor3@.
 *  - global@: administrador global (todas).
 */
class MultiescuelaTest {
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj)
    private val hoy = LocalDate.now(ZoneId.of("America/Mexico_City")).toString()

    private suspend fun HttpClient.enviar(token: String, folio: String, matricula: String = "A001", semaforo: String = "ROJO"): HttpResponse =
        post("/api/escritorio/tamizajes") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(TamizajeEntradaDto(folio, matricula, hoy, semaforo, "20/60", "20/50"))
        }

    @Test
    fun cadaEquipoGuardaEnSuEscuelaAunqueLaMatriculaSeRepita() = testApplication {
        val client = servidor(servicios)
        val equipo1 = servicios.tokenEscritorio("enfermeria@eyesofter.mx")
        val equipo2 = servicios.tokenEscritorio("enfermeria2@eyesofter.mx")
        assertEquals(HttpStatusCode.Created, client.enviar(equipo1, "E1-001").status)
        assertEquals(HttpStatusCode.Created, client.enviar(equipo2, "E2-001").status)
        // Misma matrícula A001, alumnos distintos: cada uno en su escuela.
        assertEquals(1, BasePrueba.cuenta("select alumno_id from tamizajes where folio = 'E1-001'"))
        assertEquals(4, BasePrueba.cuenta("select alumno_id from tamizajes where folio = 'E2-001'"))
        // Un equipo de alguien con las dos escuelas no sabe a cuál A001 se refiere: lo dice claro.
        BasePrueba.ejecutar("insert into usuario_planteles (usuario_id, plantel_id) values (?, 2)", BasePrueba.idDe("enfermeria@eyesofter.mx"))
        val ambiguo = client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "E12-001")
        assertEquals(HttpStatusCode.Conflict, ambiguo.status)
        assertTrue("MATRICULA_AMBIGUA" in ambiguo.bodyAsText())
        // Y un equipo ligado al administrador global no funciona.
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(servicios.tokenEscritorio("global@eyesofter.mx"), "G-001").status)
        // La bitácora queda con la escuela de cada alumno.
        assertEquals(2, BasePrueba.cuenta("select plantel_id from auditoria where accion = 'RECIBIR_TAMIZAJE' and entidad_id = (select id::text from tamizajes where folio = 'E2-001')"))
    }

    @Test
    fun elEscritorioSoloBuscaYEnviaAlumnosDeSuEscuela() = testApplication {
        val client = servidor(servicios)
        val equipo1 = servicios.tokenEscritorio("enfermeria@eyesofter.mx")
        val equipo2 = servicios.tokenEscritorio("enfermeria2@eyesofter.mx")
        assertEquals(0, client.get("/api/escritorio/alumnos?q=Vargas") { bearerAuth(equipo1) }.body<List<AlumnoEscritorioDto>>().size)
        assertEquals(listOf("Bruno"), client.get("/api/escritorio/alumnos?q=Vargas") { bearerAuth(equipo2) }.body<List<AlumnoEscritorioDto>>().map { it.nombre })
        // A002 solo existe en la escuela 1: desde la escuela 2 "no existe".
        val r = client.enviar(equipo2, "E2-002", matricula = "A002")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertEquals(0, BasePrueba.cuenta("select count(*) from tamizajes"))
    }

    @Test
    fun elPersonalSoloVeTamizajesDeSuEscuela() = testApplication {
        val client = servidor(servicios)
        val t1 = client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "E1-001").body<TamizajeRecibidoDto>()
        val t2 = client.enviar(servicios.tokenEscritorio("enfermeria2@eyesofter.mx"), "E2-001").body<TamizajeRecibidoDto>()
        val enf1 = client.tokenDe("enfermeria@eyesofter.mx")
        val enf2 = client.tokenDe("enfermeria2@eyesofter.mx")
        val global = client.tokenDe("global@eyesofter.mx")
        val direccion = client.tokenDe("direccion@eyesofter.mx")

        fun ids(l: List<TamizajeDto>) = l.map { it.id }.toSet()
        assertEquals(setOf(t1.tamizajeId), ids(client.get("/api/tamizajes") { bearerAuth(enf1) }.body()))
        assertEquals(setOf(t1.tamizajeId), ids(client.get("/api/tamizajes") { bearerAuth(direccion) }.body()))
        assertEquals(setOf(t2.tamizajeId), ids(client.get("/api/tamizajes") { bearerAuth(enf2) }.body()))
        assertEquals(setOf(t1.tamizajeId, t2.tamizajeId), ids(client.get("/api/tamizajes") { bearerAuth(global) }.body()))

        // Pedir por id el de la otra escuela: 404 y queda registrado como acceso denegado.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tamizajes/${t1.tamizajeId}") { bearerAuth(enf2) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/tamizajes/${t2.tamizajeId}") { bearerAuth(enf2) }.status)
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where accion = 'ACCESO_DENEGADO' and usuario = 'enfermeria2@eyesofter.mx'"))
        // Tampoco puede asignar destino a un caso de otra escuela.
        val destino = client.post("/api/tamizajes/${t1.tamizajeId}/destino") { bearerAuth(enf2); contentType(ContentType.Application.Json); setBody("""{"clinicaId":1}""") }
        assertEquals(HttpStatusCode.NotFound, destino.status)
    }

    @Test
    fun lasAlertasLleganSoloALaEnfermeriaDeLaEscuelaDelAlumno() = testApplication {
        val client = servidor(servicios)
        // Tutor de la escuela 2 sin cuenta: la alerta "Tutor sin cuenta" es solo para enfermeria2@.
        BasePrueba.ejecutar("update asp_net_users set tutor_id = null where tutor_id = 3")
        client.enviar(servicios.tokenEscritorio("enfermeria2@eyesofter.mx"), "E2-001")
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA' and usuario_id = ?", BasePrueba.idDe("enfermeria2@eyesofter.mx")))
        assertEquals(0, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA' and usuario_id = ?", BasePrueba.idDe("enfermeria@eyesofter.mx")))
        // Una incidencia de sincronización de un equipo de la escuela 1 no le llega a la 2.
        val r = client.post("/api/escritorio/incidencias") {
            bearerAuth(servicios.tokenEscritorio("enfermeria@eyesofter.mx")); contentType(ContentType.Application.Json)
            setBody("""{"mensaje":"Sin conexión","pendientes":3}""")
        }
        assertTrue("\"enfermeriaAvisada\":1" in r.bodyAsText().replace(" ", ""))
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA'and usuario_id = ?", BasePrueba.idDe("enfermeria2@eyesofter.mx")))
    }

    @Test
    fun avisosYSeguimientoRespetanLaEscuela() = testApplication {
        val client = servidor(servicios)
        client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "E1-001")
        client.enviar(servicios.tokenEscritorio("enfermeria2@eyesofter.mx"), "E2-001")
        val enf2 = client.tokenDe("enfermeria2@eyesofter.mx")
        // Seguimiento de lectura: solo la familia de su escuela.
        val seguimiento = client.get("/api/notificaciones/seguimiento") { bearerAuth(enf2) }.body<List<SeguimientoDto>>()
        assertEquals(listOf("tutor3@eyesofter.mx"), seguimiento.map { it.tutor })
        // Aviso "a todos los tutores" desde la escuela 2: solo tutor3@. Un correo de otra escuela = no encontrado.
        val aviso = client.post("/api/notificaciones") {
            bearerAuth(enf2); contentType(ContentType.Application.Json)
            setBody("""{"titulo":"Junta","mensaje":"Junta de padres el viernes","rol":"CLIENTE","usernames":["tutor@eyesofter.mx"]}""")
        }.body<AvisoEnviadoDto>()
        assertEquals(1, aviso.enviadas)
        assertEquals(listOf("tutor@eyesofter.mx"), aviso.noEncontrados)
        assertEquals(0, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'AVISO' and usuario_id = ?", BasePrueba.idDe("tutor@eyesofter.mx")))
    }

    @Test
    fun laVinculacionFuncionaAunqueLaAtiendaOtraInstanciaDelServidor() = testApplication {
        // Dos "instancias" del servidor sobre la misma base (como con dos réplicas en Railway).
        val otra = Servicios(configPrueba(), db = BasePrueba.db, canales = { emptyList() }, reloj = { reloj.ahora })
        val instanciaA = servidor(servicios)
        val creada = instanciaA.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("PC ST5")) }
            .body<VinculacionCreadaDto>()
        // La aprobación y el canje los atiende la otra instancia.
        val aprobacion = otra.vinculaciones.porCodigo(creada.codigo)!!
        assertTrue(otra.vinculaciones.aprobar(aprobacion, otra.usuarios.porId(BasePrueba.idDe("enfermeria2@eyesofter.mx"))!!))
        val estado = instanciaA.post("/api/escritorio/vinculaciones/estado") { contentType(ContentType.Application.Json); setBody(VinculacionEstadoEntradaDto(creada.solicitud)) }
        assertEquals(HttpStatusCode.OK, estado.status)
        // En la base solo queda la huella del secreto, nunca el secreto.
        assertEquals(0, BasePrueba.cuenta("select count(*) from vinculaciones_escritorio"))
        val otraCreada = otra.vinculaciones.crear("PC dos")!!
        assertEquals(64, BasePrueba.consulta("select id from vinculaciones_escritorio") { it.getString(1) }!!.length)
        assertTrue(BasePrueba.cuenta("select count(*) from vinculaciones_escritorio where id = ?", otraCreada.secreto) == 0)
    }

    @Test
    fun elConvenioSeMuestraSoloALasFamiliasDeEsaEscuela() = testApplication {
        val client = servidor(servicios)
        BasePrueba.ejecutar("insert into convenios (plantel_id, clinica_id, descuento, beneficio, activo) values (1, 1, 20, '20 % en armazones', true)")
        val tutor1 = client.tokenDe("tutor@eyesofter.mx")
        val tutor3 = client.tokenDe("tutor3@eyesofter.mx")
        fun convenio(l: List<ClinicaDto>) = l.single { it.id == 1 }.convenio
        assertEquals("20 % en armazones", convenio(client.get("/api/clinicas") { bearerAuth(tutor1) }.body()))
        assertNull(convenio(client.get("/api/clinicas") { bearerAuth(tutor3) }.body()))
        assertNull(convenio(client.get("/api/clinicas").body()))
        // Un token inválido no rompe el directorio público.
        assertEquals(HttpStatusCode.OK, client.get("/api/clinicas") { bearerAuth("token-viejo") }.status)
        val mapa = client.get("/opticas/cercanas?lat=20.0825&lng=-98.3622") { bearerAuth(tutor1) }.body<List<Optica>>()
        assertEquals("20 % en armazones", mapa.single { it.id == 1 }.convenio)

        // Y en el pase: el especialista y la familia ven el beneficio.
        val t = client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "E1-001").body<TamizajeRecibidoDto>()
        val d = client.post("/api/tamizajes/${t.tamizajeId}/destino") { bearerAuth(tutor1); contentType(ContentType.Application.Json); setBody("""{"clinicaId":1}""") }
            .body<DestinoDto>()
        assertEquals("20 % en armazones", d.tamizaje.caso!!.pase!!.convenio)
    }

    @Test
    fun laPaginaDeVinculacionDejaElRegistroEnLaEscuelaDeQuienAprueba() = testApplication {
        val client = servidor(servicios)
        val creada = client.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("PC ST5")) }
            .body<VinculacionCreadaDto>()
        val r = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo); append("correo", "enfermeria2@eyesofter.mx"); append("password", PASSWORD); append("confirmo", "si")
        })
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(2, BasePrueba.cuenta("select plantel_id from auditoria where accion = 'VINCULAR_EQUIPO' and exito"))

        // El administrador global no vincula equipos: no se sabría de qué escuela es cada alumno.
        val otra = client.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("PC")) }
            .body<VinculacionCreadaDto>()
        val global = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", otra.codigo); append("correo", "global@eyesofter.mx"); append("password", PASSWORD); append("confirmo", "si")
        })
        assertEquals(HttpStatusCode.Forbidden, global.status)
        assertEquals(HttpStatusCode.Accepted,
            client.post("/api/escritorio/vinculaciones/estado") { contentType(ContentType.Application.Json); setBody(VinculacionEstadoEntradaDto(otra.solicitud)) }.status)
    }

    @Test
    fun elMismoFolioEnDosEscuelasNoSePierde() = testApplication {
        val client = servidor(servicios)
        val r1 = client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "ESC-2026-AAAA1111")
        val r2 = client.enviar(servicios.tokenEscritorio("enfermeria2@eyesofter.mx"), "ESC-2026-AAAA1111")
        assertEquals(HttpStatusCode.Created, r1.status)
        assertEquals(HttpStatusCode.Created, r2.status)
        val t1 = r1.body<TamizajeRecibidoDto>()
        val t2 = r2.body<TamizajeRecibidoDto>()
        assertTrue(t1.tamizajeId != t2.tamizajeId && t1.casoId != t2.casoId)
        // Reenviar desde la escuela 2 es duplicado DE SU tamizaje, nunca el de la otra escuela.
        val otraVez = client.enviar(servicios.tokenEscritorio("enfermeria2@eyesofter.mx"), "ESC-2026-AAAA1111").body<TamizajeRecibidoDto>()
        assertTrue(otraVez.duplicado)
        assertEquals(t2.tamizajeId, otraVez.tamizajeId)
        assertEquals(2, BasePrueba.cuenta("select count(*) from tamizajes"))
    }

    @Test
    fun elSeguimientoSeFiltraPorLaEscuelaDelAlumnoNoDelTutor() = testApplication {
        val client = servidor(servicios)
        // Rosa (tutor 3) también tiene una hija en la escuela 1.
        BasePrueba.ejecutar("update alumnos set tutor_id = 3 where id = 3")
        client.enviar(servicios.tokenEscritorio("enfermeria@eyesofter.mx"), "E1-SOFIA", matricula = "A003")
        val enf2 = client.tokenDe("enfermeria2@eyesofter.mx")
        assertEquals(0, client.get("/api/notificaciones/seguimiento") { bearerAuth(enf2) }.body<List<SeguimientoDto>>().size)
    }

    @Test
    fun siLaEscuelaNoTieneEnfermeriaLaAlertaLlegaASuDireccion() = testApplication {
        val client = servidor(servicios)
        BasePrueba.ejecutar("delete from usuario_planteles where usuario_id = ?", BasePrueba.idDe("enfermeria@eyesofter.mx"))
        BasePrueba.ejecutar("update asp_net_users set tutor_id = null where tutor_id = 2")
        client.enviar(servicios.tokenEscritorio("direccion@eyesofter.mx"), "E1-LUIS", matricula = "A002")
        assertEquals(1, BasePrueba.cuenta("select count(*) from notificaciones where tipo = 'ALERTA_ENFERMERIA' and usuario_id = ?", BasePrueba.idDe("direccion@eyesofter.mx")))
    }

    @Test
    fun siLaCuentaQueVinculoPasaASerGlobalElEquipoDejaDeFuncionar() = testApplication {
        val client = servidor(servicios)
        val equipo = servicios.tokenEscritorio("direccion@eyesofter.mx")
        assertEquals(HttpStatusCode.OK, client.get("/api/escritorio/equipo") { bearerAuth(equipo) }.status)
        BasePrueba.ejecutar("update asp_net_users set administrador_global = true where email = 'direccion@eyesofter.mx'")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/escritorio/equipo") { bearerAuth(equipo) }.status)
    }
}
