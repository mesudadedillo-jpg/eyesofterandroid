package com.example.eyesofterapp

import com.auth0.jwt.JWT
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.model.VinculacionCreadaDto
import com.example.eyesofterapp.model.VinculacionEntradaDto
import com.example.eyesofterapp.seguridad.Totp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verificación en dos pasos, sesiones cortas del personal y cabeceras de seguridad. */
class SeguridadTest {
    private val reloj = RelojFalso()
    private val servicios = serviciosPrueba(reloj)

    /** Clave Base32 de prueba, como la guarda ASP.NET Identity en asp_net_user_tokens. */
    private val clave = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"

    private fun activarDosPasos(email: String) {
        val id = BasePrueba.idDe(email)
        BasePrueba.ejecutar("update asp_net_users set two_factor_enabled = true where id = ?", id)
        BasePrueba.ejecutar("insert into asp_net_user_tokens (user_id, login_provider, name, value) values (?, '[AspNetUserStore]', 'AuthenticatorKey', ?)", id, clave)
    }

    private fun codigoActual() = Totp.generar(Totp.base32(clave), Instant.now().epochSecond / 30)

    private suspend fun HttpClient.entrar(usuario: String, codigo: String? = null): HttpResponse = post("/api/login") {
        contentType(ContentType.Application.Json); setBody(LoginRequestDto(usuario, PASSWORD, codigo))
    }

    @Test
    fun totpCoincideConElEstandarRfc6238() {
        // Vector de prueba del RFC 6238 (SHA-1, T = 59 s): 94287082 → últimos 6 dígitos.
        val secreto = "12345678901234567890".toByteArray()
        assertEquals("287082", Totp.generar(secreto, 59L / 30))
        assertEquals("081804", Totp.generar(secreto, 1111111109L / 30))
        // Tolera relojes desfasados ±60 s, pero no más.
        val ahora = Instant.ofEpochSecond(1_700_000_000)
        val codigo = Totp.generar(Totp.base32(clave), ahora.epochSecond / 30)
        assertTrue(Totp.valido(clave, codigo, ahora.plusSeconds(55)))
        assertTrue(!Totp.valido(clave, codigo, ahora.plusSeconds(120)))
        assertTrue(!Totp.valido(clave, "12345", ahora))
    }

    @Test
    fun conDosPasosLaContrasenaSolaNoBasta() = testApplication {
        val client = servidor(servicios)
        activarDosPasos("enfermeria@eyesofter.mx")
        val sinCodigo = client.entrar("enfermeria@eyesofter.mx")
        assertEquals(HttpStatusCode.Unauthorized, sinCodigo.status)
        assertTrue("REQUIERE_2FA" in sinCodigo.bodyAsText())
        val malo = client.entrar("enfermeria@eyesofter.mx", "000000".takeIf { it != codigoActual() } ?: "111111")
        assertEquals(HttpStatusCode.Unauthorized, malo.status)
        assertTrue("CODIGO_2FA_INVALIDO" in malo.bodyAsText())
        assertEquals(1, BasePrueba.cuenta("select access_failed_count from asp_net_users where email = 'enfermeria@eyesofter.mx'"))
        assertEquals(HttpStatusCode.OK, client.entrar("enfermeria@eyesofter.mx", codigoActual()).status)
    }

    @Test
    fun tresCodigosMalosBloqueanLaCuenta() = testApplication {
        val client = servidor(servicios)
        activarDosPasos("direccion@eyesofter.mx")
        val malo = if (codigoActual() == "000000") "111111" else "000000"
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.entrar("direccion@eyesofter.mx", malo).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.entrar("direccion@eyesofter.mx", malo).status)
        // Ni con el código bueno mientras dura el bloqueo.
        assertEquals(HttpStatusCode.TooManyRequests, client.entrar("direccion@eyesofter.mx", codigoActual()).status)
    }

    @Test
    fun soloDireccionDebeActivarDosPasosSiSeExige() = testApplication {
        val estricto = Servicios(configPrueba().copy(exigir2faPersonal = true), db = BasePrueba.db, canales = { emptyList() }, reloj = { reloj.ahora })
        val client = servidor(estricto)
        val r = client.entrar("direccion@eyesofter.mx")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue("ACTIVAR_2FA" in r.bodyAsText())
        // Para Enfermería, clínicas, tutores y alumnos es opcional.
        listOf("enfermeria@eyesofter.mx", "especialista@eyesofter.mx", "tutor@eyesofter.mx", "alumno@eyesofter.mx").forEach {
            assertEquals(HttpStatusCode.OK, client.entrar(it).status, it)
        }
        activarDosPasos("direccion@eyesofter.mx")
        assertEquals(HttpStatusCode.OK, client.entrar("direccion@eyesofter.mx", codigoActual()).status)
    }

    @Test
    fun laSesionDelPersonalDuraHorasYLaDelTutorDias() = testApplication {
        val client = servidor(servicios)
        fun horas(token: String) = (JWT.decode(token).expiresAt.time - JWT.decode(token).issuedAt.time) / 3_600_000
        assertEquals(12, horas(client.entrar("enfermeria@eyesofter.mx").body<LoginResponseDto>().token))
        assertEquals(30L * 24, horas(client.entrar("tutor@eyesofter.mx").body<LoginResponseDto>().token))
    }

    @Test
    fun vincularUnEquipoTambienPideElCodigo() = testApplication {
        val client = servidor(servicios)
        activarDosPasos("enfermeria@eyesofter.mx")
        val creada = client.post("/api/escritorio/vinculaciones") { contentType(ContentType.Application.Json); setBody(VinculacionEntradaDto("PC")) }
            .body<VinculacionCreadaDto>()
        suspend fun enviar(codigo: String?) = client.submitForm("/escritorio/vincular", parameters {
            append("codigo", creada.codigo); append("correo", "enfermeria@eyesofter.mx"); append("password", PASSWORD); append("confirmo", "si")
            codigo?.let { append("codigo2fa", it) }
        })
        val sin = enviar(null)
        assertEquals(HttpStatusCode.Unauthorized, sin.status)
        assertTrue("código de 6 dígitos" in sin.bodyAsText())
        assertEquals(HttpStatusCode.OK, enviar(codigoActual()).status)
    }

    @Test
    fun cabecerasDeSeguridad() = testApplication {
        val client = servidor(servicios)
        val r = client.get("/salud")
        assertTrue("max-age" in r.headers["Strict-Transport-Security"].orEmpty())
        assertEquals("no-store", r.headers["Cache-Control"])
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
    }
}
