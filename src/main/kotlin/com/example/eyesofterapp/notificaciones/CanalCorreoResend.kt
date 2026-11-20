package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.repos.NotificacionRecord
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Correo de respaldo (M-RFNN-04, C-RI-02) con la API HTTP de Resend.
 * Se usa HTTP y no SMTP porque Railway bloquea el puerto SMTP en sus planes básicos.
 */
class CanalCorreoResend(
    private val http: HttpClient,
    private val apiKey: String,
    private val remitente: String,
) : CanalNotificacion {

    override val nombre = "correo"

    override suspend fun enviar(notificacion: NotificacionRecord, destinatario: Destinatario, esRecordatorio: Boolean): ResultadoEnvio {
        val email = destinatario.email?.takeIf { it.isNotBlank() }
            ?: return ResultadoEnvio(false, "el usuario no tiene correo registrado")

        val cuerpo = buildJsonObject {
            put("from", remitente)
            putJsonArray("to") { add(email) }
            put("subject", "EYESOFTER: ${notificacion.tituloVisible(esRecordatorio)}")
            put(
                "text",
                "Hola, ${destinatario.nombre}:\n\n${notificacion.mensaje}\n\n" +
                    "Abre la aplicación EYESOFTER Suite o el portal para ver el detalle.\n\n" +
                    "Este es un mensaje automático; no respondas a este correo."
            )
        }
        val respuesta = http.post("https://api.resend.com/emails") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(cuerpo.toString())
        }
        return if (respuesta.status.isSuccess()) ResultadoEnvio(true, "correo enviado")
        else ResultadoEnvio(false, "Resend ${respuesta.status.value}: ${respuesta.bodyAsText().take(200)}")
    }
}
