package com.example.eyesofterapp

import com.example.eyesofterapp.model.BandejaDto
import com.example.eyesofterapp.model.ConsentimientoDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.model.SaludDto
import com.example.eyesofterapp.repos.Usuario
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Inicio de sesión con las cuentas del portal (C-RFN-01, C-RFNN-01, C-RC-05). */
class AutenticacionTest {
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj)

    @Test
    fun loginConCorreoDevuelveRolDeLaApp() = testApplication {
        val client = servidor(servicios)
        val esperados = mapOf(
            "direccion@eyesofter.mx" to "ADMINISTRADOR", "enfermeria@eyesofter.mx" to "SUPERVISOR",
            "TUTOR@eyesofter.mx" to "CLIENTE", "especialista@eyesofter.mx" to "DOCTOR", "alumno@eyesofter.mx" to "ALUMNO",
        )
        for ((correo, rol) in esperados) {
            val r = client.login(correo)
            assertEquals(HttpStatusCode.OK, r.status, correo)
            val u = r.body<LoginResponseDto>().user
            assertEquals(rol, u.role)
            assertEquals(correo.lowercase(), u.username)
            assertEquals(BasePrueba.idDe(correo.lowercase()), u.id)
            assertFalse(u.requiereConsentimiento)
        }
        assertTrue(BasePrueba.cuenta("select count(*) from auditoria where accion = 'LOGIN' and origen = 'app'") >= 5)
    }

    @Test
    fun credencialesInvalidas() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.login("tutor@eyesofter.mx", "otra").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("nadie@eyesofter.mx").status)
        assertEquals(HttpStatusCode.BadRequest, client.login("tutor@eyesofter.mx", "").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/login") { contentType(ContentType.Application.Json); setBody("{malo") }.status)
        assertEquals(1, BasePrueba.cuenta("select access_failed_count from asp_net_users where email = 'tutor@eyesofter.mx'"))
        // Un acierto reinicia el contador compartido con la web.
        assertEquals(HttpStatusCode.OK, client.login("tutor@eyesofter.mx").status)
        assertEquals(0, BasePrueba.cuenta("select access_failed_count from asp_net_users where email = 'tutor@eyesofter.mx'"))
    }

    @Test
    fun tercerIntentoFallidoBloqueaLaCuentaQuinceMinutos() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.login("tutor@eyesofter.mx", "x1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("tutor@eyesofter.mx", "x2").status)
        val tercero = client.login("tutor@eyesofter.mx", "x3")
        assertEquals(HttpStatusCode.TooManyRequests, tercero.status)
        assertTrue("15 min" in tercero.bodyAsText())
        // Bloqueada aunque ahora ponga la contraseña correcta; mismas columnas que Identity (la web también la ve bloqueada).
        assertEquals(HttpStatusCode.TooManyRequests, client.login("tutor@eyesofter.mx").status)
        assertNotNull(BasePrueba.consulta("select lockout_end from asp_net_users where email = 'tutor@eyesofter.mx'") { it.getTimestamp(1) })
        // Otra cuenta no se ve afectada.
        assertEquals(HttpStatusCode.OK, client.login("tutor2@eyesofter.mx").status)
        // Pasados los 15 minutos vuelve a entrar.
        reloj.ahora = reloj.ahora.plusSeconds(16 * 60)
        assertEquals(HttpStatusCode.OK, client.login("tutor@eyesofter.mx").status)
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where accion = 'CUENTA_BLOQUEADA'"))
    }

    @Test
    fun cuentaDesactivadaNoEntraYPierdeSuSesion() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Forbidden, client.login("inactivo@eyesofter.mx").status)

        val token = client.tokenDe("tutor@eyesofter.mx")
        assertEquals(HttpStatusCode.OK, client.get("/api/notificaciones") { bearerAuth(token) }.status)
        BasePrueba.ejecutar("update asp_net_users set activo = false where email = 'tutor@eyesofter.mx'")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(token) }.status)
    }

    @Test
    fun cambiarLaContrasenaEnElPortalInvalidaLosTokens() = testApplication {
        val client = servidor(servicios)
        val token = client.tokenDe("tutor@eyesofter.mx")
        // Identity cambia el security_stamp al cambiar contraseña o rol.
        BasePrueba.ejecutar("update asp_net_users set security_stamp = 'NUEVO' where email = 'tutor@eyesofter.mx'")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(token) }.status)
    }

    @Test
    fun tokensFalsosOAjenosSeRechazan() = testApplication {
        val client = servidor(servicios)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth("jwt-mock-token-123") }.status)
        val u = Usuario(BasePrueba.idDe("tutor@eyesofter.mx"), "tutor@eyesofter.mx", "x", "Tutor", 1, null, null, true, "SELLO-tutor@eyesofter.mx")
        val ajeno = Servicios(configPrueba().copy(jwtSecret = "otro"), db = BasePrueba.db, canales = { emptyList() }).tokens.crear(u)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(ajeno) }.status)
        assertEquals(0, client.get("/api/notificaciones") { bearerAuth(servicios.tokens.crear(u)) }.body<BandejaDto>().noLeidas)
    }

    @Test
    fun tutorSinAvisoAceptadoDebeAceptarloAntesDeVerDatos() = testApplication {
        val client = servidor(servicios)
        BasePrueba.ejecutar("delete from consentimientos")
        val r = client.login("tutor@eyesofter.mx").body<LoginResponseDto>()
        assertTrue(r.user.requiereConsentimiento)
        val bloqueado = client.get("/api/tamizajes") { bearerAuth(r.token) }
        assertEquals(HttpStatusCode.Forbidden, bloqueado.status)
        assertTrue("CONSENTIMIENTO_PENDIENTE" in bloqueado.bodyAsText())

        val estado = client.get("/api/consentimiento") { bearerAuth(r.token) }.body<ConsentimientoDto>()
        assertFalse(estado.aceptado)
        assertEquals("https://portal.prueba/Privacidad/Aviso", estado.urlAviso)
        // Una versión vieja no cuenta.
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/consentimiento") {
            bearerAuth(r.token); contentType(ContentType.Application.Json); setBody("""{"version":"2020-01"}""")
        }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/consentimiento") {
            bearerAuth(r.token); contentType(ContentType.Application.Json); setBody("""{"version":"$VERSION_AVISO","acepto":true}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/tamizajes") { bearerAuth(r.token) }.status)
        assertEquals("app", BasePrueba.consulta("select medio from consentimientos") { it.getString(1) })
        assertFalse(client.login("tutor@eyesofter.mx").body<LoginResponseDto>().user.requiereConsentimiento)
        // El personal no necesita aceptar el aviso para trabajar.
        assertEquals(HttpStatusCode.OK, client.get("/api/tamizajes") { bearerAuth(client.tokenDe("enfermeria@eyesofter.mx")) }.status)
    }

    @Test
    fun saludRevisaLaBase() = testApplication {
        val client = servidor(servicios)
        val s = client.get("/salud").body<SaludDto>()
        assertEquals("ok", s.status)
        assertTrue(s.db)
        assertTrue(s.escritorio)
        assertFalse(s.push)
        val cabeceras = client.get("/salud").headers
        assertEquals("nosniff", cabeceras["X-Content-Type-Options"])
        assertEquals("no-store", cabeceras["Cache-Control"])
    }

    @Test
    fun reportesConYSinSesion() = testApplication {
        val client = servidor(servicios)
        fun cuerpo(correo: String?) = """{"tipo":"falla","mensaje":"La app se cierra al abrir el mapa","plataforma":"android"${correo?.let { ",\"correo\":\"$it\"" } ?: ""}}"""
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/reportes") { contentType(ContentType.Application.Json); setBody(cuerpo(null)) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/reportes") { contentType(ContentType.Application.Json); setBody(cuerpo("papa@example.com")) }.status)
        val token = client.tokenDe("tutor@eyesofter.mx")
        assertEquals(HttpStatusCode.Created, client.post("/api/reportes") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(cuerpo(null)) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/reportes") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"tipo":"otro","mensaje":"La app se cierra al abrir el mapa"}""")
        }.status)
        assertEquals(2, BasePrueba.cuenta("select count(*) from reportes_falla where tipo = 'Falla' and plataforma = 'android'"))
        assertEquals("tutor@eyesofter.mx", BasePrueba.consulta("select correo from reportes_falla where usuario_id is not null") { it.getString(1) })
        // Dirección recibe el aviso en su bandeja.
        assertEquals(2, BasePrueba.cuenta("select count(*) from notificaciones where usuario_id = ?", BasePrueba.idDe("direccion@eyesofter.mx")))
    }
}
