package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.data.DispositivoRepository
import com.example.eyesofterapp.data.NotificacionRecord
import com.example.eyesofterapp.data.UserRecord
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** Canal Android en el que la app debe crear sus notificaciones (NotificationChannel). */
const val CANAL_ANDROID = "eyesofter_avisos"

/**
 * Push con Firebase Cloud Messaging, API HTTP v1 (M-RI-02). Sirve para Android
 * e iOS: en iOS, Firebase reenvía a APNs si el proyecto de Firebase tiene la
 * llave de APNs cargada.
 *
 * Privacidad: el texto de la push no incluye el resultado del semáforo; solo
 * avisa que hay algo nuevo. El resultado se ve dentro de la app, con sesión.
 */
class CanalPushFcm(
    private val dispositivos: DispositivoRepository,
    private val http: HttpClient,
    private val proyectoId: String,
    /** Devuelve un token OAuth vigente de Google. Se inyecta para poder probarlo. */
    private val tokenAcceso: suspend () -> String,
) : CanalNotificacion {

    override val nombre = "push"

    override suspend fun enviar(notificacion: NotificacionRecord, usuario: UserRecord, esRecordatorio: Boolean): ResultadoEnvio {
        val destinos = dispositivos.deUsuario(usuario.username)
        if (destinos.isEmpty()) return ResultadoEnvio(false, "sin dispositivos registrados")

        val acceso = tokenAcceso()
        var enviados = 0
        val errores = mutableListOf<String>()
        for (d in destinos) {
            val respuesta = http.post("https://fcm.googleapis.com/v1/projects/$proyectoId/messages:send") {
                bearerAuth(acceso)
                contentType(ContentType.Application.Json)
                setBody(mensaje(d.token, notificacion, esRecordatorio).toString())
            }
            if (respuesta.status.isSuccess()) {
                enviados++
                continue
            }
            val cuerpo = respuesta.bodyAsText()
            // El token ya no sirve (app desinstalada o datos borrados): se olvida.
            if (respuesta.status == HttpStatusCode.NotFound || "UNREGISTERED" in cuerpo) {
                dispositivos.eliminarToken(d.token)
                errores += "token vencido eliminado"
            } else {
                log.warn("FCM respondió ${respuesta.status} para ${usuario.username}: $cuerpo")
                errores += "FCM ${respuesta.status.value}"
            }
        }
        return ResultadoEnvio(
            exito = enviados > 0,
            detalle = "enviada a $enviados de ${destinos.size} dispositivos" +
                if (errores.isEmpty()) "" else " (${errores.joinToString()})"
        )
    }

    private fun mensaje(token: String, n: NotificacionRecord, esRecordatorio: Boolean) = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("notification") {
                put("title", n.tituloVisible(esRecordatorio))
                put("body", n.mensaje)
            }
            // "data" llega a la app aunque esté cerrada; con esto abre la pantalla correcta (M-RC-02).
            putJsonObject("data") {
                put("notificacionId", n.id)
                put("tipo", n.tipo.name)
                n.datos.forEach { (k, v) -> put(k, v) }
            }
            putJsonObject("android") {
                put("priority", "HIGH")
                putJsonObject("notification") { put("channel_id", CANAL_ANDROID) }
            }
            putJsonObject("apns") {
                putJsonObject("payload") { putJsonObject("aps") { put("sound", "default") } }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CanalPushFcm::class.java)

        /** Arma el canal a partir del JSON de la cuenta de servicio que da la consola de Firebase. */
        fun desdeCuentaDeServicio(json: String, dispositivos: DispositivoRepository, http: HttpClient): CanalPushFcm {
            val credenciales = ServiceAccountCredentials.fromStream(json.byteInputStream())
            val proyecto = credenciales.projectId
                ?: error("La cuenta de servicio de Firebase no trae project_id")
            val conAlcance: GoogleCredentials =
                credenciales.createScoped("https://www.googleapis.com/auth/firebase.messaging")
            return CanalPushFcm(dispositivos, http, proyecto) {
                // refreshIfExpired hace una petición de red bloqueante solo cuando el token venció (~1 h).
                withContext(Dispatchers.IO) {
                    conAlcance.refreshIfExpired()
                    conAlcance.accessToken?.tokenValue ?: error("Google no devolvió un token de acceso para FCM")
                }
            }
        }
    }
}
