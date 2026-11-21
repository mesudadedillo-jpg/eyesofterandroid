package com.example.eyesofterapp.config

import com.example.eyesofterapp.seguridad.CifradoDatos
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
    /** Base PostgreSQL compartida con el portal web (DATABASE_URL de Railway). */
    val databaseUrl: String,
    /** Llave AES-256 de los datos sensibles; la MISMA que usa el portal (C-RC-04). */
    val claveDatos: String,
    /** Secreto con el que se firman los JWT. En producción DEBE venir de JWT_SECRET. */
    val jwtSecret: String,
    /** Cuántos días dura una sesión de la app. */
    val jwtDias: Long = 30,
    /**
     * Cuántos días dura la vinculación de un equipo de escritorio. Ya no hay una clave
     * compartida: cada equipo se vincula con una cuenta de Dirección o Enfermería.
     */
    val escritorioDias: Long = 180,
    /** Contenido JSON de la cuenta de servicio de Firebase. null = push desactivada. */
    val firebaseServiceAccountJson: String?,
    /** API key de Resend para el correo de respaldo. null = correo desactivado. */
    val resendApiKey: String?,
    /** Remitente del correo, p. ej. "EYESOFTER <avisos@tudominio.com>". */
    val correoRemitente: String?,
    /** URL pública del portal web: va en el QR del pase y en los enlaces de privacidad. */
    val urlPortal: String = "http://localhost:5080",
    /** Versión vigente del aviso de privacidad; la misma que Eyesofter:VersionAvisoPrivacidad del portal. */
    val versionAviso: String = "2026-11",
    /** Días que dura un pase médico antes de expirar. */
    val diasVigenciaPase: Long = 60,
    /** Días sin leer antes de reenviar el aviso y alertar a Enfermería (C-RFNN-04). */
    val recordatorioDias: Long = 3,
    /** Cuántas veces como máximo se reenvía un mismo aviso. */
    val recordatorioMax: Int = 3,
    /** Cada cuántos minutos se revisan los avisos sin leer. null = no se revisa solo (pruebas). */
    val recordatorioRevisionMin: Long? = 60,
    /** Cada cuántos segundos se entregan las notificaciones pendientes. null = no hay ciclo (pruebas). */
    val despachoSegundos: Long? = 3,
    /** Intentos fallidos seguidos antes de bloquear la cuenta (C-RFNN-01). Igual que el portal. */
    val loginIntentosMax: Int = 3,
    /** Minutos que dura el bloqueo. Igual que el portal. */
    val loginBloqueoMin: Long = 15,
    /** Commit desplegado; Railway lo pone en RAILWAY_GIT_COMMIT_SHA. */
    val version: String = "dev",
    /** Crear las tablas del portal si faltan (esquema.sql). CREAR_ESQUEMA=false lo desactiva. */
    val crearEsquema: Boolean = false,
    /** Cuenta de administrador que se crea al arrancar si no existe (ADMIN_EMAIL / ADMIN_PASSWORD). */
    val adminEmail: String? = null,
    val adminPassword: String? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(Configuracion::class.java)

        fun desdeEntorno(env: (String) -> String? = System::getenv): Configuracion {
            fun texto(nombre: String) = env(nombre)?.takeIf { it.isNotBlank() }
            val produccion = texto("RAILWAY_ENVIRONMENT") != null || texto("ENTORNO") == "produccion"

            val secreto = texto("JWT_SECRET") ?: run {
                if (produccion) error("Falta JWT_SECRET: sin ella cualquier reinicio invalida las sesiones y no es seguro.")
                log.warn("JWT_SECRET no está definida: se generó un secreto temporal (solo desarrollo).")
                ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
            }
            val clave = texto("EYESOFTER_CLAVE_DATOS") ?: run {
                if (produccion) error("Falta EYESOFTER_CLAVE_DATOS: debe ser la misma llave del portal web.")
                log.warn("EYESOFTER_CLAVE_DATOS no está definida: se usa la llave de DESARROLLO.")
                CifradoDatos.CLAVE_DESARROLLO
            }
            val firebase = texto("FIREBASE_SERVICE_ACCOUNT")
                ?: texto("FIREBASE_SERVICE_ACCOUNT_FILE")?.let { File(it).readText() }

            return Configuracion(
                databaseUrl = texto("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/eyesofter?user=postgres&password=postgres",
                claveDatos = clave,
                jwtSecret = secreto,
                jwtDias = texto("JWT_DIAS")?.toLongOrNull() ?: 30,
                escritorioDias = texto("ESCRITORIO_DIAS")?.toLongOrNull() ?: 180,
                firebaseServiceAccountJson = firebase,
                resendApiKey = texto("RESEND_API_KEY"),
                correoRemitente = texto("CORREO_REMITENTE"),
                urlPortal = texto("URL_PORTAL") ?: "http://localhost:5080",
                versionAviso = texto("VERSION_AVISO_PRIVACIDAD") ?: "2026-11",
                diasVigenciaPase = texto("DIAS_VIGENCIA_PASE")?.toLongOrNull() ?: 60,
                recordatorioDias = texto("RECORDATORIO_DIAS")?.toLongOrNull() ?: 3,
                recordatorioMax = texto("RECORDATORIO_MAX")?.toIntOrNull() ?: 3,
                recordatorioRevisionMin = texto("RECORDATORIO_REVISION_MIN")?.toLongOrNull() ?: 60,
                despachoSegundos = texto("DESPACHO_SEGUNDOS")?.toLongOrNull() ?: 3,
                loginIntentosMax = texto("LOGIN_INTENTOS_MAX")?.toIntOrNull() ?: 3,
                loginBloqueoMin = texto("LOGIN_BLOQUEO_MIN")?.toLongOrNull() ?: 15,
                version = texto("RAILWAY_GIT_COMMIT_SHA")?.take(7) ?: "dev",
                crearEsquema = texto("CREAR_ESQUEMA")?.lowercase() != "false",
                adminEmail = texto("ADMIN_EMAIL"),
                adminPassword = texto("ADMIN_PASSWORD"),
            )
        }
    }
}
