package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.data.NotificacionRecord
import com.example.eyesofterapp.data.UserRecord
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.notificaciones.CanalNotificacion
import com.example.eyesofterapp.notificaciones.ResultadoEnvio
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder

const val API_KEY_PRUEBA = "clave-escritorio-prueba"

/** Configuración de pruebas: todo en memoria, sin canales reales ni revisión automática. */
fun configPrueba() = Configuracion(
    jwtSecret = "secreto-de-prueba",
    apiKeyEscritorio = API_KEY_PRUEBA,
    dataDir = null,
    firebaseServiceAccountJson = null,
    resendApiKey = null,
    correoRemitente = null,
    recordatorioRevisionMin = null,
)

/** Canal que no envía nada: anota lo que le pidieron y responde lo que se le diga. */
class CanalFalso(override val nombre: String, var exito: Boolean = true) : CanalNotificacion {
    data class Envio(val usuario: String, val notificacion: NotificacionRecord, val esRecordatorio: Boolean)

    val envios = mutableListOf<Envio>()

    override suspend fun enviar(notificacion: NotificacionRecord, usuario: UserRecord, esRecordatorio: Boolean): ResultadoEnvio {
        envios += Envio(usuario.username, notificacion, esRecordatorio)
        return ResultadoEnvio(exito, if (exito) "ok" else "falló a propósito")
    }
}

/** Reloj que las pruebas pueden adelantar. */
class RelojFalso(var ahora: Long = 1_800_000_000_000) {
    fun avanzarDias(dias: Long) { ahora += dias * 24 * 60 * 60 * 1000 }
}

/** Levanta el servidor en memoria y devuelve un cliente JSON. */
fun ApplicationTestBuilder.servidor(servicios: Servicios = Servicios(configPrueba(), scope = null)): HttpClient {
    application { modulo(servicios) }
    return createClient { install(ContentNegotiation) { json() } }
}

suspend fun HttpClient.tokenDe(username: String, password: String = "123456"): String =
    post("/api/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequestDto(username, password))
    }.body<LoginResponseDto>().token
