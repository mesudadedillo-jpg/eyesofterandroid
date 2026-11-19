package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.data.NotificacionRepository
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.data.UserRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Rol que el sistema usa para el personal de Enfermería. */
const val ROL_ENFERMERIA = "SUPERVISOR"

/**
 * Revisa periódicamente los resultados de tamizaje que el tutor no ha leído
 * (C-RFN-08, C-RFNN-04). Si pasaron [diasPlazo] días desde el último aviso:
 *  - reenvía el aviso al tutor (hasta [maxRecordatorios] veces), y
 *  - la primera vez, alerta a todo el personal de Enfermería.
 */
class Recordatorios(
    private val repo: NotificacionRepository,
    private val notificador: Notificador,
    private val usuariosConRol: (String) -> List<UserRecord>,
    private val diasPlazo: Long,
    private val maxRecordatorios: Int,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Recordatorios::class.java)

    /** Hace una revisión. Devuelve cuántos avisos se reenviaron. */
    suspend fun revisar(): Int {
        val ahora = reloj()
        val limite = ahora - diasPlazo * 24 * 60 * 60 * 1000
        val pendientes = repo.pendientesDeRecordatorio(limite, maxRecordatorios)
        for (original in pendientes) {
            val actualizada = repo.registrarRecordatorio(original.id, ahora) ?: continue
            notificador.despachar(actualizada, esRecordatorio = true)

            if (original.recordatorios == 0) {
                val alumno = original.datos["alumno"] ?: "un alumno"
                usuariosConRol(ROL_ENFERMERIA).forEach { enfermeria ->
                    notificador.notificar(
                        destinatario = enfermeria.username,
                        tipo = TipoNotificacion.ALERTA_ENFERMERIA,
                        titulo = "Aviso sin leer",
                        mensaje = "El tutor ${original.destinatario} no ha revisado el resultado del tamizaje de " +
                            "$alumno después de $diasPlazo días.",
                        datos = buildMap {
                            put("notificacionId", original.id)
                            put("tutor", original.destinatario)
                            original.datos["tamizajeId"]?.let { put("tamizajeId", it) }
                            put("ruta", "seguimiento")
                        },
                    )
                }
            }
        }
        if (pendientes.isNotEmpty()) log.info("Recordatorios: se reenviaron ${pendientes.size} avisos sin leer")
        return pendientes.size
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
