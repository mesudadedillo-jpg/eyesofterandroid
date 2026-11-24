package com.example.eyesofterapp

import com.example.eyesofterapp.model.AlumnoEscritorioDto
import com.example.eyesofterapp.model.GrupoEscritorioDto
import com.example.eyesofterapp.model.EquipoEscritorioDto
import com.example.eyesofterapp.model.VinculacionCreadaDto
import com.example.eyesofterapp.model.VinculacionEntradaDto
import com.example.eyesofterapp.model.VinculacionEstadoDto
import com.example.eyesofterapp.model.VinculacionEstadoEntradaDto
import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.TamizajeDto
import com.example.eyesofterapp.model.TamizajeEntradaDto
import com.example.eyesofterapp.model.TamizajeRecibidoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
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

    /** Token del equipo de prueba (vinculado por Enfermería). */
    private val tokenEquipo: String by lazy { kotlinx.coroutines.runBlocking { servicios.tokenEscritorio() } }

    private suspend fun HttpClient.enviar(t: TamizajeEntradaDto, token: String? = tokenEquipo): HttpResponse =
        post("/api/escritorio/tamizajes") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(t)
        }

    /** Flujo completo: el escritorio pide código, alguien aprueba en el navegador y el escritorio canjea. */
    private suspend fun HttpClient.vincular(correo: String = "enfermeria@eyesofter.mx", password: String = PASSWORD): Pair<HttpResponse, VinculacionCreadaDto> {
        val creada = post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("Enfermería PC1")) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body<VinculacionCreadaDto>()
        val pagina = submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo); append("correo", correo); append("password", password); append("confirmo", "si")
        })
        return pagina to creada
    }

    private suspend fun HttpClient.estado(solicitud: String) =
        post("/api/escritorio/vinculaciones/estado") { contentType(ContentType.Application.Json); setBody(VinculacionEstadoEntradaDto(solicitud)) }

    @Test
    fun sinVincularNoHayAcceso() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(), token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(), token = "mala").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/escritorio/alumnos?q=Ana").status)
        // La antigua clave compartida ya no sirve para nada.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/escritorio/alumnos?q=Ana") { header("X-Api-Key", "clave-escritorio-prueba") }.status)
        assertEquals(0, BasePrueba.cuenta("select count(*) from tamizajes"))
        assertEquals(4, BasePrueba.cuenta("select count(*) from auditoria where accion = 'ACCESO_DENEGADO' and origen = 'escritorio'"))
    }

    @Test
    fun vinculacionEnElNavegadorConCuentaDeEnfermeria() = testApplication {
        val client = servidor(servicios)
        val creada = client.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("Enfermería PC1")) }
            .body<VinculacionCreadaDto>()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(creada.codigo))
        // Mientras nadie aprueba, el escritorio espera.
        assertEquals(HttpStatusCode.Accepted, client.estado(creada.solicitud).status)

        // La página muestra el equipo y el código, con la advertencia.
        val pagina = client.get(creada.ruta)
        assertEquals(HttpStatusCode.OK, pagina.status)
        val html = pagina.bodyAsText()
        assertTrue(creada.codigo in html && "Enfermería PC1" in html && "Solo continúa si" in html)
        assertTrue("frame-ancestors 'none'" in pagina.headers["Content-Security-Policy"].orEmpty())

        // Sin confirmar el código no se aprueba.
        val sinConfirmar = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo); append("correo", "enfermeria@eyesofter.mx"); append("password", PASSWORD)
        })
        assertEquals(HttpStatusCode.BadRequest, sinConfirmar.status)
        assertEquals(HttpStatusCode.Accepted, client.estado(creada.solicitud).status)

        val ok = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo.lowercase().replace("-", "")); append("correo", "enfermeria@eyesofter.mx"); append("password", PASSWORD); append("confirmo", "si")
        })
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue("Equipo vinculado" in ok.bodyAsText())

        val aprobada = client.estado(creada.solicitud)
        assertEquals(HttpStatusCode.OK, aprobada.status)
        val dto = aprobada.body<VinculacionEstadoDto>()
        assertEquals("APROBADA", dto.estado)
        assertEquals("enfermeria@eyesofter.mx", dto.usuario)
        // El token se entrega UNA sola vez.
        assertEquals(HttpStatusCode.Gone, client.estado(creada.solicitud).status)

        // Con ese token el equipo ya trabaja, y la bitácora dice quién lo autorizó.
        val equipo = client.get("/api/escritorio/equipo") { bearerAuth(dto.token!!) }.body<EquipoEscritorioDto>()
        assertEquals("Enfermería PC1", equipo.equipo)
        assertEquals(HttpStatusCode.Created, client.enviar(tamizaje(), token = dto.token).status)
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where usuario = 'enfermeria@eyesofter.mx' and accion = 'RECIBIR_TAMIZAJE'"))
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where accion = 'VINCULAR_EQUIPO' and exito"))
    }

    @Test
    fun soloDireccionOEnfermeriaPuedenVincular() = testApplication {
        val client = servidor(servicios)
        for (correo in listOf("tutor@eyesofter.mx", "alumno@eyesofter.mx", "especialista@eyesofter.mx")) {
            val (pagina, creada) = client.vincular(correo)
            assertEquals(HttpStatusCode.Forbidden, pagina.status, correo)
            assertEquals(HttpStatusCode.Accepted, client.estado(creada.solicitud).status, correo)
        }
        val (malaClave, creada) = client.vincular(password = "incorrecta")
        assertEquals(HttpStatusCode.Unauthorized, malaClave.status)
        assertEquals(HttpStatusCode.Accepted, client.estado(creada.solicitud).status)
        val (direccion, _) = client.vincular("direccion@eyesofter.mx")
        assertEquals(HttpStatusCode.OK, direccion.status)
    }

    @Test
    fun codigoDesconocidoOVencido() = testApplication {
        val client = servidor(servicios)
        assertTrue("no existe o ya venció" in client.get("/escritorio/vincular?codigo=AAAA-BBBB").bodyAsText())
        assertEquals(HttpStatusCode.Gone, client.estado("no-existe").status)
        val creada = client.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("PC")) }.body<VinculacionCreadaDto>()
        reloj.ahora = reloj.ahora.plusSeconds(11 * 60)
        val tarde = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo); append("correo", "enfermeria@eyesofter.mx"); append("password", PASSWORD); append("confirmo", "si")
        })
        assertEquals(HttpStatusCode.Gone, tarde.status)
        assertEquals(HttpStatusCode.Gone, client.estado(creada.solicitud).status)
    }

    @Test
    fun tokensDeAppYDeEscritorioNoSeMezclan() = testApplication {
        val client = servidor(servicios)
        // El token del login de la app (aunque sea de Enfermería) no sirve como equipo...
        val app = client.tokenDe("enfermeria@eyesofter.mx")
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(), token = app).status)
        // ...y el token del equipo no abre las rutas de la app.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(tokenEquipo) }.status)
    }

    @Test
    fun cambiarLaContrasenaODesactivarLaCuentaDesvinculaElEquipo() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Created, client.enviar(tamizaje()).status)
        BasePrueba.ejecutar("update asp_net_users set security_stamp = 'NUEVO' where email = 'enfermeria@eyesofter.mx'")
        val r = client.enviar(tamizaje(folio = "ESC-002"))
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue("EQUIPO_NO_VINCULADO" in r.bodyAsText())

        val otro = servicios.tokenEscritorio("direccion@eyesofter.mx")
        BasePrueba.ejecutar("update asp_net_users set activo = false where email = 'direccion@eyesofter.mx'")
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(tamizaje(folio = "ESC-003"), token = otro).status)
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
            bearerAuth(tokenEquipo); contentType(ContentType.Application.Json)
            setBody("""{"mensaje":"Sin conexión a internet","pendientes":12,"equipo":"Enfermería PC1"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val enf = client.tokenDe("enfermeria@eyesofter.mx")
        val n = client.get("/api/notificaciones") { bearerAuth(enf) }.body<BandejaDto>().notificaciones.single()
        assertEquals("Falla de sincronización", n.titulo)
        assertTrue("12 tamizaje" in n.mensaje)
    }

    @Test
    fun buscarAlumnosPideAlMenosTresLetrasYNoDevuelveElPadron() = testApplication {
        val client = servidor(servicios)
        // Sin texto (o con comodines) ya no se puede bajar la lista completa.
        for (q in listOf("", "a", "%25%25%25")) {
            val r = client.get("/api/escritorio/alumnos?q=$q") { bearerAuth(tokenEquipo) }
            if (r.status == HttpStatusCode.OK) assertEquals(0, r.body<List<AlumnoEscritorioDto>>().size, q)
            else assertEquals(HttpStatusCode.BadRequest, r.status, q)
        }
        val uno = client.get("/api/escritorio/alumnos?q=a002") { bearerAuth(tokenEquipo) }.body<List<AlumnoEscritorioDto>>()
        assertEquals("Luis", uno.single().nombre)
        assertNotNull(uno.single().grado)
        val lopez = client.get("/api/escritorio/alumnos?q=López") { bearerAuth(tokenEquipo) }.body<List<AlumnoEscritorioDto>>()
        assertEquals(2, lopez.size)
        // Cada búsqueda queda en la bitácora con la cuenta que vinculó el equipo ("%%%", "a002", "López").
        assertEquals(3, BasePrueba.cuenta("select count(*) from auditoria where accion = 'BUSCAR_ALUMNOS' and usuario = 'enfermeria@eyesofter.mx'"))
    }

    @Test
    fun laBusquedaLlenaElRegistroConDatosDelAlumnoYSuTutor() = testApplication {
        val client = servidor(servicios)
        val ana = client.get("/api/escritorio/alumnos?q=A001") { bearerAuth(tokenEquipo) }.body<List<AlumnoEscritorioDto>>().single()
        assertEquals("2017-03-01", ana.fechaNacimiento)
        assertEquals("María López", ana.tutorNombre)
        assertEquals("7751110000", ana.tutorTelefono)
        assertEquals("tutor@eyesofter.mx", ana.tutorCorreo)
    }

    @Test
    fun jornadaPorGrupoSoloDeLaEscuelaDelEquipo() = testApplication {
        val client = servidor(servicios)
        val grupos = client.get("/api/escritorio/grupos") { bearerAuth(tokenEquipo) }.body<List<GrupoEscritorioDto>>()
        // El equipo es de la escuela 1: no ve el grupo de Secundaria de la escuela 2.
        assertTrue(grupos.none { it.nivel == "Secundaria" })
        assertEquals(3, grupos.sumOf { it.alumnos })
        val tercero = client.get("/api/escritorio/grupo?nivel=Primaria&grado=3&grupo=b") { bearerAuth(tokenEquipo) }.body<List<AlumnoEscritorioDto>>()
        assertEquals(listOf("A001"), tercero.map { it.matricula })
        val ajeno = client.get("/api/escritorio/grupo?nivel=Secundaria&grado=2&grupo=B") { bearerAuth(tokenEquipo) }.body<List<AlumnoEscritorioDto>>()
        assertTrue(ajeno.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/escritorio/grupo?nivel=Primaria") { bearerAuth(tokenEquipo) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/escritorio/grupos").status)
        assertEquals(2, BasePrueba.cuenta("select count(*) from auditoria where accion = 'VER_GRUPO'"))
    }
}
