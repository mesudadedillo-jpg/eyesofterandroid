package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.repos.ExpedienteRepository
import com.example.eyesofterapp.repos.NotificacionRepository
import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.repos.TipoNotificacion
import com.example.eyesofterapp.repos.UsuarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Revisión periódica (C-RFN-08, C-RFNN-04):
 *  1. Resultados de tamizaje que el tutor no ha leído en [diasPlazo] días: se reenvía el aviso
 *     (hasta [maxRecordatorios] veces) y, la primera vez, se alerta a Enfermería.
 *  2. Casos amarillos o rojos sin atención ni comprobante aprobado: recordatorio al tutor cada
 *     [diasCaso] días (hasta [maxRecordatorios] veces).
 */
class Recordatorios(
    private val db: BaseDeDatos,
    private val repo: NotificacionRepository,
    private val expedientes: ExpedienteRepository,
    private val despachador: Despachador,
    private val diasPlazo: Long,
    private val maxRecordatorios: Int,
    private val diasCaso: Long = 7,
    private val reloj: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(Recordatorios::class.java)

    /** Hace una revisión. Devuelve cuántos recordatorios se programaron. */
    suspend fun revisar(): Int {
        val ahora = reloj()
        val pendientes = repo.pendientesDeRecordatorio(ahora.minusSeconds(diasPlazo * 86_400), maxRecordatorios)
        for (original in pendientes) {
            repo.registrarRecordatorio(original.id, ahora) ?: continue
            if (original.recordatorios == 0) {
                val alumno = original.datos["alumno"] ?: "un alumno"
                db.transaccion { c ->
                    val tutor = c.uno("select coalesce(email, user_name) from asp_net_users where id = ?", original.destinatario) { it.getString(1) } ?: "?"
                    val plantel = original.datos["tamizajeId"]?.toIntOrNull()?.let { t ->
                        c.uno("select a.plantel_id from tamizajes t join alumnos a on a.id = t.alumno_id where t.id = ?", t) { it.getInt(1) }
                    }
                    // Sin tamizaje no se sabe la escuela: avisa a quien administra el sistema.
                    (plantel?.let { UsuarioRepository.idsConRolEnPlantel(c, RolesIdentity.ENFERMERIA, it) } ?: UsuarioRepository.idsAdministracionSistema(c)).forEach {
                        NotificacionRepository.crearEn(c, it, TipoNotificacion.ALERTA_ENFERMERIA, "Aviso sin leer",
                            "El tutor $tutor no ha revisado el resultado del tamizaje de $alumno después de $diasPlazo días.",
                            buildMap {
                                put("notificacionId", original.id.toString()); put("tutor", tutor); put("ruta", "seguimiento")
                                original.datos["tamizajeId"]?.let { t -> put("tamizajeId", t) }
                            }, ahora)
                    }
                }
            }
        }
        var casos = 0
        for (p in expedientes.casosSinAtender(diasCaso, maxRecordatorios, ahora)) {
            if (expedientes.recordarCaso(p, ahora) > 0) casos++
        }
        if (pendientes.isNotEmpty() || casos > 0) {
            log.info("Recordatorios: ${pendientes.size} avisos sin leer reenviados, $casos casos sin atender recordados")
            despachador.despertar()
        }
        return pendientes.size + casos
    }

    /** Lanza la revisión cada [intervaloMin] minutos mientras el servidor esté encendido. */
    fun iniciar(scope: CoroutineScope, intervaloMin: Long): Job = scope.launch {
        log.info("Recordatorios activos: revisión cada $intervaloMin min, plazo de $diasPlazo días")
        while (isActive) {
            try {
                revisar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Falló la revisión de recordatorios; se reintenta en la siguiente vuelta", e)
            }
            delay(intervaloMin * 60 * 1000)
        }
    }
}
