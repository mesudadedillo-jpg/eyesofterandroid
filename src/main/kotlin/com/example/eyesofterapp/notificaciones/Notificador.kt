package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.data.EntregaRecord
import com.example.eyesofterapp.data.NotificacionRecord
import com.example.eyesofterapp.data.NotificacionRepository
import com.example.eyesofterapp.data.TipoNotificacion
import com.example.eyesofterapp.data.UserRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Crea notificaciones y las hace llegar.
 *
 *  1. Siempre se guarda en la bandeja: aunque nada más funcione, el usuario la
 *     ve al abrir la app (M-RFNN-04: "mostrar los pendientes al iniciar sesión").
 *  2. Se prueban los canales EN ORDEN y se detiene en el primero que funcione:
 *     push → correo → (SMS/WhatsApp en el futuro). Así, si el tutor no autorizó
 *     las push o no tiene la app instalada, le llega por correo.
 *
 * El envío corre en segundo plano (scope) para que quien la crea no espere a
 * Firebase. Con scope = null se envía en la misma corrutina (pruebas).
 */
class Notificador(
    private val repo: NotificacionRepository,
    private val buscarUsuario: (String) -> UserRecord?,
    private val canales: List<CanalNotificacion>,
    private val scope: CoroutineScope?,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Notificador::class.java)

    suspend fun notificar(
        destinatario: String,
        tipo: TipoNotificacion,
        titulo: String,
        mensaje: String,
        datos: Map<String, String> = emptyMap(),
    ): NotificacionRecord {
        val n = repo.crear(destinatario, tipo, titulo, mensaje, datos, reloj())
        despachar(n, esRecordatorio = false)
        return n
    }

    suspend fun despachar(n: NotificacionRecord, esRecordatorio: Boolean) {
        if (scope == null) entregar(n, esRecordatorio)
        else scope.launch { entregar(n, esRecordatorio) }
    }

    private suspend fun entregar(n: NotificacionRecord, esRecordatorio: Boolean) {
        val usuario = buscarUsuario(n.destinatario) ?: run {
            log.warn("Notificación ${n.id}: el usuario ${n.destinatario} ya no existe")
            return
        }
        if (canales.isEmpty()) {
            log.info("Notificación ${n.id} para ${n.destinatario}: sin canales configurados, queda solo en la bandeja")
            return
        }
        for (canal in canales) {
            val resultado = try {
                canal.enviar(n, usuario, esRecordatorio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Canal ${canal.nombre} falló para la notificación ${n.id}", e)
                ResultadoEnvio(false, "error: ${e.message}")
            }
            repo.registrarEntrega(n.id, EntregaRecord(canal.nombre, resultado.exito, reloj(), resultado.detalle))
            log.info("Notificación ${n.id} → ${n.destinatario} por ${canal.nombre}: ${if (resultado.exito) "OK" else "no"} (${resultado.detalle})")
            if (resultado.exito) return
        }
    }
}
