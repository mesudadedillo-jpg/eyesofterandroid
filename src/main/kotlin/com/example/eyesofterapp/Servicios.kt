package com.example.eyesofterapp

import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.notificaciones.CanalCorreo
import com.example.eyesofterapp.notificaciones.EnviadorCorreo
import com.example.eyesofterapp.notificaciones.CanalNotificacion
import com.example.eyesofterapp.notificaciones.CanalPushFcm
import com.example.eyesofterapp.notificaciones.Despachador
import com.example.eyesofterapp.notificaciones.EnvioCodigos
import com.example.eyesofterapp.notificaciones.EnvioCodigosReal
import com.example.eyesofterapp.notificaciones.Recordatorios
import com.example.eyesofterapp.plugins.Tokens
import com.example.eyesofterapp.repos.Auditoria
import com.example.eyesofterapp.repos.ClinicaRepository
import com.example.eyesofterapp.repos.ComprobanteRepository
import com.example.eyesofterapp.repos.ConsentimientoRepository
import com.example.eyesofterapp.repos.DispositivoRepository
import com.example.eyesofterapp.repos.ExpedienteRepository
import com.example.eyesofterapp.repos.NotificacionRepository
import com.example.eyesofterapp.repos.UsuarioRepository
import com.example.eyesofterapp.seguridad.CifradoDatos
import com.example.eyesofterapp.seguridad.RecuperacionPassword
import com.example.eyesofterapp.seguridad.Vinculaciones
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Arma todas las piezas del servidor y las conecta entre sí. Es el único lugar que decide
 * QUÉ implementación se usa (push real o ninguna, qué base). Las rutas reciben este objeto.
 */
class Servicios(
    val config: Configuracion,
    /** La base compartida con el portal. Las pruebas pasan una PostgreSQL embebida. */
    val db: BaseDeDatos = BaseDeDatos.desdeUrl(config.databaseUrl),
    /** Las pruebas pasan canales falsos para no llamar a Firebase de verdad. */
    canales: ((DispositivoRepository) -> List<CanalNotificacion>)? = null,
    val reloj: () -> Instant = Instant::now,
    /** Las pruebas pasan uno falso que anota los códigos en vez de mandarlos. */
    envioCodigos: EnvioCodigos? = null,
) {
    private val log = LoggerFactory.getLogger(Servicios::class.java)

    val cifrado = CifradoDatos(config.claveDatos)
    // Los JWT usan siempre la hora real: el verificador la usa para revisar vencimiento.
    val tokens = Tokens(config)

    val usuarios = UsuarioRepository(db)
    val auditoria = Auditoria(db)
    val notificaciones = NotificacionRepository(db)
    val dispositivos = DispositivoRepository(db)
    val clinicas = ClinicaRepository(db)
    val expedientes = ExpedienteRepository(db, cifrado, config.diasVigenciaPase)
    val comprobantes = ComprobanteRepository(db, cifrado)
    val consentimientos = ConsentimientoRepository(db)
    /** Solicitudes para vincular equipos de escritorio (en la base, duran 10 min). */
    val vinculaciones = Vinculaciones(db, usuarios, reloj)

    /** Cliente HTTP para hablar con Firebase y el proveedor de correo (Brevo o Resend). Se crea solo si algún canal lo usa. */
    private val httpCreado = lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
    private val http: HttpClient by httpCreado

    /** Brevo o Resend, según las variables; null = sin correo. */
    private val correo: EnviadorCorreo? =
        EnviadorCorreo.crear({ http }, config.brevoApiKey, config.resendApiKey, config.correoRemitente)

    private val listaCanales: List<CanalNotificacion> = canales?.invoke(dispositivos) ?: canalesReales()

    /** Nombres de los canales que sí quedaron configurados ("push", "correo"). Los muestra /salud. */
    val canalesActivos: List<String> = listaCanales.map { it.nombre }

    /** "¿Olvidaste tu contraseña?": código al correo o por SMS (misma tabla y reglas que el portal). */
    val recuperacion = RecuperacionPassword(
        db, usuarios, cifrado, auditoria,
        envioCodigos ?: EnvioCodigosReal({ http }, correo, config.twilioSid, config.twilioToken, config.twilioNumero, config.smsPrefijoPais),
        reloj,
    )

    val despachador = Despachador(notificaciones, usuarios, listaCanales, reloj)

    val recordatorios = Recordatorios(
        db = db,
        repo = notificaciones,
        expedientes = expedientes,
        despachador = despachador,
        diasPlazo = config.recordatorioDias,
        maxRecordatorios = config.recordatorioMax,
        reloj = reloj,
    )

    /** Se llama al apagar el servidor. */
    fun cerrar() {
        if (httpCreado.isInitialized()) http.close()
        db.cerrar()
    }

    /** Orden = prioridad: primero push y, si no llega, correo. */
    private fun canalesReales(): List<CanalNotificacion> = buildList {
        config.firebaseServiceAccountJson?.let { json ->
            try {
                add(CanalPushFcm.desdeCuentaDeServicio(json, dispositivos, http))
            } catch (e: Exception) {
                log.error("FIREBASE_SERVICE_ACCOUNT no es válida; la push queda desactivada", e)
            }
        } ?: log.warn("FIREBASE_SERVICE_ACCOUNT no está definida: no se enviarán notificaciones push")

        if (correo != null) {
            add(CanalCorreo(correo))
            log.info("Correo de respaldo con ${correo.proveedor}")
        } else {
            log.warn("Falta BREVO_API_KEY (o RESEND_API_KEY) o CORREO_REMITENTE: no habrá correo de respaldo")
        }
    }
}
