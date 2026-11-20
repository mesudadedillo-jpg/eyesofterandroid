package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.repos.EntregaRecord
import com.example.eyesofterapp.repos.NotificacionRecord
import com.example.eyesofterapp.repos.NotificacionRepository
import com.example.eyesofterapp.repos.UsuarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Entrega las notificaciones de la bandeja de salida (pendiente_envio = true), las haya creado
 * este servidor o el portal web. Así hay un solo lugar que habla con Firebase y Resend.
 *
 *  - Se prueban los canales EN ORDEN y se detiene en el primero que funcione:
 *    push → correo (M-RFNN-04: si el tutor no autorizó las push, le llega por correo).
 *  - Revisa la bandeja cada pocos segundos (M-RC-01: la push sale en menos de un minuto) y,
 *    cuando este mismo servidor crea notificaciones, se le "despierta" para no esperar.
 *  - Cada intento queda en entregas_notificacion (el portal muestra por dónde llegó).
 */
class Despachador(
    private val repo: NotificacionRepository,
    private val usuarios: UsuarioRepository,
    private val canales: List<CanalNotificacion>,
    private val reloj: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(Despachador::class.java)
    private val timbre = Channel<Unit>(Channel.CONFLATED)
    private val enParalelo = Semaphore(8)

    /** Pide una vuelta inmediata (no bloquea). */
    fun despertar() { timbre.trySend(Unit) }

    /** Entrega lo pendiente. Devuelve cuántas notificaciones procesó. */
    suspend fun despacharPendientes(): Int {
        var total = 0
        while (true) {
            val lote = repo.tomarPendientes(50)
            if (lote.isEmpty()) return total
            coroutineScope { lote.map { n -> async { enParalelo.withPermit { entregar(n) } } }.awaitAll() }
            total += lote.size
        }
    }

    private suspend fun entregar(n: NotificacionRecord) {
        if (canales.isEmpty()) return // Sin push ni correo configurados: queda solo en la bandeja.
        val usuario = usuarios.porId(n.destinatario)
        if (usuario == null || !usuario.activo) {
            log.info("Notificación ${n.id}: la cuenta ${n.destinatario} no existe o está desactivada; no se entrega")
            return
        }
        val destinatario = Destinatario(usuario.id, usuario.email.ifBlank { null }, usuario.nombre)
        for (canal in canales) {
            val resultado = try {
                canal.enviar(n, destinatario, n.esRecordatorio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Canal ${canal.nombre} falló para la notificación ${n.id}", e)
                ResultadoEnvio(false, "error: ${e.message}")
            }
            repo.registrarEntrega(n.id, EntregaRecord(canal.nombre, resultado.exito, reloj(), resultado.detalle))
            log.info("Notificación ${n.id} (${n.tipo}) por ${canal.nombre}: ${if (resultado.exito) "OK" else "no"} (${resultado.detalle})")
            if (resultado.exito) return
        }
    }

    /** Ciclo de entrega mientras el servidor esté encendido. */
    fun iniciar(scope: CoroutineScope, cadaSegundos: Long): Job = scope.launch {
        log.info("Despachador activo: revisa la bandeja de salida cada $cadaSegundos s")
        while (true) {
            try {
                despacharPendientes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Falló una vuelta del despachador; se reintenta", e)
            }
            withTimeoutOrNull(cadaSegundos * 1000) { timbre.receive() }
        }
    }
}
