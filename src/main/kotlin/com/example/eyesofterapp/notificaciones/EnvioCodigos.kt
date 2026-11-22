package com.example.eyesofterapp.notificaciones

import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

/**
 * Envío DIRECTO del código de recuperación de contraseña. No pasa por la bandeja de
 * notificaciones: el código no debe quedar guardado en claro ni verse en la bandeja.
 */
interface EnvioCodigos {
    /** false = no hay proveedor de SMS; a quien escribe su celular se le manda a su correo. */
    val smsDisponible: Boolean
    suspend fun correo(email: String, nombre: String, codigo: String): Boolean
    suspend fun sms(numero: String, texto: String): Boolean
}

/**
 * Correo con Resend (gratis hasta 3 000 al mes) y, opcional, SMS con Twilio.
 * Mismas variables que el portal: RESEND_API_KEY, CORREO_REMITENTE, TWILIO_ACCOUNT_SID,
 * TWILIO_AUTH_TOKEN, TWILIO_NUMERO y SMS_PREFIJO_PAIS (+52).
 */
class EnvioCodigosReal(
    /** Se pide al usarlo: el cliente HTTP se crea solo si de verdad se envía algo. */
    private val cliente: () -> HttpClient,
    private val resendApiKey: String?,
    private val remitente: String?,
    private val twilioSid: String?,
    private val twilioToken: String?,
    private val twilioNumero: String?,
    private val prefijoPais: String = "+52",
) : EnvioCodigos {
    private val log = LoggerFactory.getLogger(EnvioCodigosReal::class.java)

    override val smsDisponible = !twilioSid.isNullOrBlank() && !twilioToken.isNullOrBlank() && !twilioNumero.isNullOrBlank()

    override suspend fun correo(email: String, nombre: String, codigo: String): Boolean {
        if (resendApiKey == null || remitente == null) {
            log.warn("No se envió el código de recuperación a $email: falta RESEND_API_KEY o CORREO_REMITENTE")
            return false
        }
        val cuerpo = buildJsonObject {
            put("from", remitente)
            putJsonArray("to") { add(email) }
            put("subject", "EYESOFTER: tu código para cambiar la contraseña")
            put("text", textoCorreo(nombre, codigo))
        }
        return try {
            val r = cliente().post("https://api.resend.com/emails") {
                bearerAuth(resendApiKey)
                contentType(ContentType.Application.Json)
                setBody(cuerpo.toString())
            }
            if (!r.status.isSuccess()) log.error("Resend respondió ${r.status.value} al enviar el código de recuperación")
            r.status.isSuccess()
        } catch (e: Exception) {
            log.error("Falló el correo con el código de recuperación", e)
            false
        }
    }

    override suspend fun sms(numero: String, texto: String): Boolean {
        if (!smsDisponible) return false
        return try {
            val r = cliente().submitForm(
                "https://api.twilio.com/2010-04-01/Accounts/$twilioSid/Messages.json",
                parameters { append("To", internacional(numero, prefijoPais)); append("From", twilioNumero!!); append("Body", texto) }
            ) { basicAuth(twilioSid!!, twilioToken!!) }
            if (!r.status.isSuccess()) log.error("Twilio respondió ${r.status.value} al enviar el SMS")
            r.status.isSuccess()
        } catch (e: Exception) {
            log.error("Falló el SMS con el código de recuperación", e)
            false
        }
    }

    companion object {
        /** "7751234567" → "+527751234567". Si ya trae "+", se deja igual (solo dígitos). */
        fun internacional(numero: String, prefijo: String = "+52"): String {
            val limpio = numero.trim()
            val digitos = limpio.filter(Char::isDigit)
            return if (limpio.startsWith("+")) "+$digitos" else prefijo + digitos.takeLast(10)
        }

        fun textoCorreo(nombre: String, codigo: String) =
            "Hola, $nombre:\n\nTu código para crear una contraseña nueva en EYESOFTER es:\n\n    $codigo\n\n" +
                "Escríbelo en la app o en el portal. Vence en 10 minutos.\n\n" +
                "Si no lo pediste, ignora este correo: tu contraseña no cambia. Nadie de la escuela te pedirá este código."
    }
}
