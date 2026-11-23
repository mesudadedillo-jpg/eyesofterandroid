package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.repos.NotificacionRecord
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Envío de correo por HTTP (no SMTP: Railway bloquea SMTP en sus planes básicos).
 * Mismas variables que el portal (EyesofterWeb/Servicios/Correo.cs):
 *  - BREVO_API_KEY → Brevo (no necesita dominio propio: el remitente puede ser un Gmail verificado en Brevo).
 *  - RESEND_API_KEY → Resend (para enviar a cualquiera necesita un dominio verificado).
 *  - CORREO_REMITENTE: "EYESOFTER <avisos@ejemplo.com>" o solo el correo. Si están las dos llaves, gana Brevo.
 */
interface EnviadorCorreo {
    /** "Brevo" o "Resend" (para los logs). */
    val proveedor: String

    /** null = enviado; si no, el motivo del error. */
    suspend fun enviar(para: String, asunto: String, texto: String): String?

    companion object {
        /** El proveedor configurado, o null si falta la llave o el remitente. */
        fun crear(cliente: () -> HttpClient, brevoApiKey: String?, resendApiKey: String?, remitente: String?): EnviadorCorreo? = when {
            remitente.isNullOrBlank() -> null
            !brevoApiKey.isNullOrBlank() -> CorreoBrevo(cliente, brevoApiKey.trim(), remitente)
            !resendApiKey.isNullOrBlank() -> CorreoResend(cliente, resendApiKey.trim(), remitente)
            else -> null
        }

        /** "EYESOFTER <avisos@ejemplo.com>" → ("EYESOFTER", "avisos@ejemplo.com"); "avisos@ejemplo.com" → ("EYESOFTER", "avisos@ejemplo.com"). */
        fun separarRemitente(remitente: String): Pair<String, String> {
            val r = remitente.trim()
            val abre = r.lastIndexOf('<')
            val cierra = r.lastIndexOf('>')
            if (abre >= 0 && cierra > abre) {
                val nombre = r.substring(0, abre).trim().trim('"').trim()
                return (nombre.ifEmpty { "EYESOFTER" }) to r.substring(abre + 1, cierra).trim()
            }
            return "EYESOFTER" to r
        }
    }
}

/** Brevo: gratis hasta 300 correos al día. https://developers.brevo.com/reference/sendtransacemail */
class CorreoBrevo(private val cliente: () -> HttpClient, private val apiKey: String, private val remitente: String) : EnviadorCorreo {
    override val proveedor = "Brevo"

    override suspend fun enviar(para: String, asunto: String, texto: String): String? {
        val (nombre, correo) = EnviadorCorreo.separarRemitente(remitente)
        val cuerpo = buildJsonObject {
            putJsonObject("sender") { put("name", nombre); put("email", correo) }
            putJsonArray("to") { addJsonObject { put("email", para) } }
            put("subject", asunto)
            put("textContent", texto)
        }
        val r = cliente().post(URL) {
            header("api-key", apiKey)
            header("accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(cuerpo.toString())
        }
        return if (r.status.isSuccess()) null else "Brevo ${r.status.value}: ${r.bodyAsText().take(200)}"
    }

    companion object { const val URL = "https://api.brevo.com/v3/smtp/email" }
}

/** Resend: gratis hasta 3 000 al mes, pero solo con dominio verificado (o al correo de la propia cuenta). */
class CorreoResend(private val cliente: () -> HttpClient, private val apiKey: String, private val remitente: String) : EnviadorCorreo {
    override val proveedor = "Resend"

    override suspend fun enviar(para: String, asunto: String, texto: String): String? {
        val cuerpo = buildJsonObject {
            put("from", remitente)
            putJsonArray("to") { add(para) }
            put("subject", asunto)
            put("text", texto)
        }
        val r = cliente().post(URL) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(cuerpo.toString())
        }
        return if (r.status.isSuccess()) null else "Resend ${r.status.value}: ${r.bodyAsText().take(200)}"
    }

    companion object { const val URL = "https://api.resend.com/emails" }
}

/** Correo de respaldo de las notificaciones (M-RFNN-04, C-RI-02): si la push no llega. */
class CanalCorreo(private val correo: EnviadorCorreo) : CanalNotificacion {

    override val nombre = "correo"

    override suspend fun enviar(notificacion: NotificacionRecord, destinatario: Destinatario, esRecordatorio: Boolean): ResultadoEnvio {
        val email = destinatario.email?.takeIf { it.isNotBlank() }
            ?: return ResultadoEnvio(false, "el usuario no tiene correo registrado")
        val error = correo.enviar(
            email,
            "EYESOFTER: ${notificacion.tituloVisible(esRecordatorio)}",
            "Hola, ${destinatario.nombre}:\n\n${notificacion.mensaje}\n\n" +
                "Abre la aplicación EYESOFTER Suite o el portal para ver el detalle.\n\n" +
                "Este es un mensaje automático; no respondas a este correo."
        )
        return if (error == null) ResultadoEnvio(true, "correo enviado (${correo.proveedor})") else ResultadoEnvio(false, error)
    }
}
