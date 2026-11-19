package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.data.DispositivoRepository
import com.example.eyesofterapp.data.NotificacionRepository
import com.example.eyesofterapp.data.TamizajeRepository
import com.example.eyesofterapp.data.UserRepository
import com.example.eyesofterapp.notificaciones.CanalCorreoResend
import com.example.eyesofterapp.notificaciones.CanalNotificacion
import com.example.eyesofterapp.notificaciones.CanalPushFcm
import com.example.eyesofterapp.notificaciones.Notificador
import com.example.eyesofterapp.notificaciones.Recordatorios
import com.example.eyesofterapp.plugins.Tokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Arma todas las piezas del servidor y las conecta entre sí. Es el único lugar
 * que decide QUÉ implementación se usa (archivo JSON hoy, MySQL mañana; push
 * real o ninguna). Las rutas reciben este objeto y no saben de dónde salen.
 */
class Servicios(
    val config: Configuracion,
    /** Dónde corren los envíos en segundo plano. null = en la misma petición (pruebas). */
    scope: CoroutineScope?,
    /** Las pruebas pasan canales falsos para no llamar a Firebase de verdad. */
    canales: ((DispositivoRepository) -> List<CanalNotificacion>)? = null,
    val reloj: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Servicios::class.java)

    // Los JWT usan siempre la hora real: el verificador la usa para revisar vencimiento.
    val tokens = Tokens(config)
    val usuarios = UserRepository
    val notificaciones = NotificacionRepository(config.dataDir?.let { File(it, "notificaciones.json") })
    val dispositivos = DispositivoRepository(config.dataDir?.let { File(it, "dispositivos.json") })
    val tamizajes = TamizajeRepository(config.dataDir?.let { File(it, "tamizajes.json") })

    /** Cliente HTTP para hablar con Firebase y Resend. Se crea solo si algún canal lo usa. */
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }

    val notificador = Notificador(
        repo = notificaciones,
        buscarUsuario = usuarios::findByUsername,
        canales = canales?.invoke(dispositivos) ?: canalesReales(),
        scope = scope,
        reloj = reloj,
    )

    val recordatorios = Recordatorios(
        repo = notificaciones,
        notificador = notificador,
        usuariosConRol = usuarios::findByRole,
        diasPlazo = config.recordatorioDias,
        maxRecordatorios = config.recordatorioMax,
        reloj = reloj,
    )

    /** Orden = prioridad: primero push y, si no llega, correo. */
    private fun canalesReales(): List<CanalNotificacion> = buildList {
        config.firebaseServiceAccountJson?.let { json ->
            try {
                add(CanalPushFcm.desdeCuentaDeServicio(json, dispositivos, http))
            } catch (e: Exception) {
                log.error("FIREBASE_SERVICE_ACCOUNT no es válida; la push queda desactivada", e)
            }
        } ?: log.warn("FIREBASE_SERVICE_ACCOUNT no está definida: no se enviarán notificaciones push")

        if (config.resendApiKey != null && config.correoRemitente != null) {
            add(CanalCorreoResend(http, config.resendApiKey, config.correoRemitente))
        } else {
            log.warn("RESEND_API_KEY o CORREO_REMITENTE no están definidas: no habrá correo de respaldo")
        }
    }
}
