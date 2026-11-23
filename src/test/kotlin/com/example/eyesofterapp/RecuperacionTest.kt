package com.example.eyesofterapp

import com.example.eyesofterapp.model.CodigoSolicitadoDto
import com.example.eyesofterapp.model.RestablecerPasswordDto
import com.example.eyesofterapp.model.SolicitarCodigoDto
import com.example.eyesofterapp.seguridad.CifradoDatos
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

/** Cuerpo de los errores: {"error": "...", "codigo": "..."}. */
@Serializable
data class ErrorDto(val error: String = "", val codigo: String? = null)

/** "¿Olvidaste tu contraseña?" con código al correo o SMS (C-RFNN-02). */
class RecuperacionTest {
    private val reloj = RelojFalso()
    private val envio = EnvioFalso()
    private val servicios = serviciosPrueba(reloj, envio = envio)
    private val nueva = "NuevaClave2026"

    private suspend fun HttpClient.solicitar(cuenta: String) = post("/api/recuperar/solicitar") {
        contentType(ContentType.Application.Json); setBody(SolicitarCodigoDto(cuenta))
    }

    private suspend fun HttpClient.restablecer(cuenta: String, codigo: String, password: String = nueva): HttpResponse =
        post("/api/recuperar/restablecer") { contentType(ContentType.Application.Json); setBody(RestablecerPasswordDto(cuenta, codigo, password)) }

    private fun pasarMinutos(m: Long) { reloj.ahora = reloj.ahora.plusSeconds(m * 60) }

    @Test
    fun codigoPorCorreoCambiaLaContrasenaYCierraSesiones() = testApplication {
        val client = servidor(servicios)
        val tokenViejo = client.tokenDe("tutor@eyesofter.mx")
        val r = client.solicitar("Tutor@Eyesofter.mx")
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals("correo", r.body<CodigoSolicitadoDto>().canal)
        assertEquals(EnvioFalso.Envio("correo", "tutor@eyesofter.mx", envio.ultimoCodigo), envio.envios.single())
        // En la base solo queda la huella (la misma que calcula el portal).
        val hash = BasePrueba.consulta("select codigo_hash from codigos_recuperacion") { it.getString(1) }
        assertEquals(CifradoDatos(CifradoDatos.CLAVE_DESARROLLO).firma("recuperacion", "${BasePrueba.idDe("tutor@eyesofter.mx")}:${envio.ultimoCodigo}"), hash)

        assertEquals(HttpStatusCode.NoContent, client.restablecer("tutor@eyesofter.mx", envio.ultimoCodigo).status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("tutor@eyesofter.mx").status)
        assertEquals(HttpStatusCode.OK, client.login("tutor@eyesofter.mx", nueva).status)
        // El nuevo sello invalida la sesión que ya estaba abierta.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notificaciones") { bearerAuth(tokenViejo) }.status)
        // El mismo código no sirve dos veces.
        assertEquals("CODIGO_VENCIDO", client.restablecer("tutor@eyesofter.mx", envio.ultimoCodigo, "OtraClave2026").body<ErrorDto>().codigo)
        assertEquals(1, BasePrueba.cuenta("select count(*) from auditoria where accion = 'PASSWORD_RESTABLECIDA' and origen = 'app'"))
    }

    @Test
    fun noDiceSiLaCuentaExiste() = testApplication {
        val client = servidor(servicios)
        val existe = client.solicitar("tutor@eyesofter.mx").body<CodigoSolicitadoDto>()
        val noExiste = client.solicitar("nadie@eyesofter.mx").body<CodigoSolicitadoDto>()
        val inactiva = client.solicitar("inactivo@eyesofter.mx").body<CodigoSolicitadoDto>()
        assertEquals(existe, noExiste)
        assertEquals(existe, inactiva)
        assertEquals(1, envio.envios.size)
        assertEquals("CODIGO_VENCIDO", client.restablecer("nadie@eyesofter.mx", "123456").body<ErrorDto>().codigo)
        assertEquals(HttpStatusCode.BadRequest, client.solicitar("hola").status)
    }

    @Test
    fun cincoIntentosYVence() = testApplication {
        val client = servidor(servicios)
        client.solicitar("tutor@eyesofter.mx")
        val bueno = envio.ultimoCodigo
        val malo = if (bueno == "000000") "111111" else "000000"
        val mensajes = (1..5).map { client.restablecer("tutor@eyesofter.mx", malo).body<ErrorDto>() }
        assertEquals("El código no es correcto. Te quedan 4 intentos.", mensajes.first().error)
        assertEquals("El código no es correcto y ya no sirve. Pide uno nuevo.", mensajes.last().error)
        // Ya agotado, ni el código correcto sirve.
        assertEquals("CODIGO_VENCIDO", client.restablecer("tutor@eyesofter.mx", bueno).body<ErrorDto>().codigo)

        pasarMinutos(2)
        client.solicitar("tutor@eyesofter.mx")
        val otro = envio.ultimoCodigo
        pasarMinutos(11)
        assertEquals("CODIGO_VENCIDO", client.restablecer("tutor@eyesofter.mx", otro).body<ErrorDto>().codigo)
    }

    @Test
    fun contrasenaDebilNoGastaElCodigo() = testApplication {
        val client = servidor(servicios)
        client.solicitar("tutor@eyesofter.mx")
        val r = client.restablecer("tutor@eyesofter.mx", envio.ultimoCodigo, "corta").body<ErrorDto>()
        assertEquals("PASSWORD_INVALIDA", r.codigo)
        assertTrue(r.error.contains("al menos 8 caracteres"))
        assertEquals(HttpStatusCode.NoContent, client.restablecer("tutor@eyesofter.mx", envio.ultimoCodigo).status)
    }

    @Test
    fun limiteDeCodigosYElNuevoAnulaAlAnterior() = testApplication {
        val client = servidor(servicios)
        client.solicitar("tutor@eyesofter.mx")
        val primero = envio.ultimoCodigo
        client.solicitar("tutor@eyesofter.mx") // antes de un minuto: no se manda otro
        assertEquals(1, envio.envios.size)
        pasarMinutos(2)
        client.solicitar("tutor@eyesofter.mx")
        assertEquals(2, envio.envios.size)
        // El primero ya no sirve: se compara contra el nuevo (cuenta como intento equivocado).
        if (primero != envio.ultimoCodigo)
            assertEquals("CODIGO_INCORRECTO", client.restablecer("tutor@eyesofter.mx", primero).body<ErrorDto>().codigo)
        repeat(5) { pasarMinutos(2); client.solicitar("tutor@eyesofter.mx") }
        assertEquals(5, envio.envios.size) // máximo 5 por hora
    }

    @Test
    fun porCelularDelTutor() = testApplication {
        val client = servidor(servicios)
        // Sin proveedor de SMS el código va al correo de la cuenta.
        assertEquals("correo", client.solicitar("775 111 0000").body<CodigoSolicitadoDto>().canal)
        assertEquals("tutor@eyesofter.mx", envio.envios.last().destino)
        pasarMinutos(2)
        envio.smsDisponible = true
        assertEquals("sms", client.solicitar("+52 (775) 111-0000").body<CodigoSolicitadoDto>().canal)
        assertEquals(EnvioFalso.Envio("sms", "+52 (775) 111-0000", envio.ultimoCodigo), envio.envios.last())
        assertEquals(HttpStatusCode.NoContent, client.restablecer("7751110000", envio.ultimoCodigo).status)
        assertEquals(HttpStatusCode.OK, client.login("tutor@eyesofter.mx", nueva).status)
    }

    @Test
    fun noQuitaLaVerificacionEnDosPasos() = testApplication {
        val client = servidor(servicios)
        BasePrueba.ejecutar("update asp_net_users set two_factor_enabled = true where email = 'enfermeria@eyesofter.mx'")
        client.solicitar("enfermeria@eyesofter.mx")
        assertEquals(HttpStatusCode.NoContent, client.restablecer("enfermeria@eyesofter.mx", envio.ultimoCodigo).status)
        assertEquals("REQUIERE_2FA", client.login("enfermeria@eyesofter.mx", nueva).body<ErrorDto>().codigo)
    }

    /** Mismo resultado que CifradoDatos.Firma del portal (C#): si no, un código pedido en un lado no serviría en el otro. */
    @Test
    fun huellaIgualQueElPortal() {
        assertEquals("61dca583d8640145aaf99c700173245a1550b296c5c77410cace89c26bb6b14c", CifradoDatos(CifradoDatos.CLAVE_DESARROLLO).firma("recuperacion", "id-tutor:123456"))
    }
}
