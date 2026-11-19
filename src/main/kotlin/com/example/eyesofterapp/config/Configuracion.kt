package com.example.eyesofterapp.config

import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Todo lo que el servidor lee de variables de entorno, en un solo lugar.
 * Ninguna otra clase llama a System.getenv: reciben esta configuración.
 * Así las pruebas pueden armar una configuración a mano sin tocar el entorno.
 *
 * Ver la tabla "Variables de entorno" del README.
 */
data class Configuracion(
    /** Secreto con el que se firman los JWT. En producción DEBE venir de JWT_SECRET. */
    val jwtSecret: String,
    /** Cuántos días dura una sesión de la app. */
    val jwtDias: Long = 30,
    /** Clave que debe mandar el sistema de escritorio en X-Api-Key. null = ruta desactivada. */
    val apiKeyEscritorio: String?,
    /** Carpeta donde se guardan notificaciones, dispositivos y tamizajes. null = solo memoria. */
    val dataDir: File?,
    /** Contenido JSON de la cuenta de servicio de Firebase. null = push desactivada. */
    val firebaseServiceAccountJson: String?,
    /** API key de Resend para el correo de respaldo. null = correo desactivado. */
    val resendApiKey: String?,
    /** Remitente del correo, p. ej. "EYESOFTER <avisos@tudominio.com>". */
    val correoRemitente: String?,
    /** Días sin leer antes de reenviar el aviso y alertar a Enfermería (C-RFNN-04). */
    val recordatorioDias: Long = 3,
    /** Cuántas veces como máximo se reenvía un mismo aviso. */
    val recordatorioMax: Int = 3,
    /** Cada cuántos minutos se revisan los avisos sin leer. null = no se revisa solo (pruebas). */
    val recordatorioRevisionMin: Long? = 60,
    /** Intentos fallidos seguidos antes de bloquear la cuenta (C-RFNN-01). */
    val loginIntentosMax: Int = 3,
    /** Minutos que dura el bloqueo. */
    val loginBloqueoMin: Long = 15,
    /** Commit desplegado; Railway lo pone en RAILWAY_GIT_COMMIT_SHA. */
    val version: String = "dev",
) {
    companion object {
        private val log = LoggerFactory.getLogger(Configuracion::class.java)

        fun desdeEntorno(env: (String) -> String? = System::getenv): Configuracion {
            fun texto(nombre: String) = env(nombre)?.takeIf { it.isNotBlank() }

            val secreto = texto("JWT_SECRET") ?: run {
                log.warn(
                    "JWT_SECRET no está definida: se generó un secreto temporal. " +
                        "Las sesiones se invalidarán cada vez que el servidor reinicie."
                )
                ByteArray(32).also { SecureRandom().nextBytes(it) }
                    .let { Base64.getEncoder().encodeToString(it) }
            }

            val firebase = texto("FIREBASE_SERVICE_ACCOUNT")
                ?: texto("FIREBASE_SERVICE_ACCOUNT_FILE")?.let { File(it).readText() }

            return Configuracion(
                jwtSecret = secreto,
                jwtDias = texto("JWT_DIAS")?.toLongOrNull() ?: 30,
                apiKeyEscritorio = texto("ESCRITORIO_API_KEY"),
                dataDir = File(texto("DATA_DIR") ?: "data"),
                firebaseServiceAccountJson = firebase,
                resendApiKey = texto("RESEND_API_KEY"),
                correoRemitente = texto("CORREO_REMITENTE"),
                recordatorioDias = texto("RECORDATORIO_DIAS")?.toLongOrNull() ?: 3,
                recordatorioMax = texto("RECORDATORIO_MAX")?.toIntOrNull() ?: 3,
                recordatorioRevisionMin = texto("RECORDATORIO_REVISION_MIN")?.toLongOrNull() ?: 60,
                loginIntentosMax = texto("LOGIN_INTENTOS_MAX")?.toIntOrNull() ?: 3,
                loginBloqueoMin = texto("LOGIN_BLOQUEO_MIN")?.toLongOrNull() ?: 15,
                version = texto("RAILWAY_GIT_COMMIT_SHA")?.take(7) ?: "dev",
            )
        }
    }
}
