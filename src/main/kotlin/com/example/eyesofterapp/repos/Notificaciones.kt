package com.example.eyesofterapp.repos

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.instante
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Tipos: RESULTADO_TAMIZAJE (tutor), ALERTA_ENFERMERIA, AVISO, COMPROBANTE_RECIBIDO,
 * COMPROBANTE_REVISADO, CITA, CASO_ACTUALIZADO, RECORDATORIO_CASO.
 */
object TipoNotificacion {
    const val RESULTADO_TAMIZAJE = "RESULTADO_TAMIZAJE"
    const val ALERTA_ENFERMERIA = "ALERTA_ENFERMERIA"
    const val AVISO = "AVISO"
    const val COMPROBANTE_RECIBIDO = "COMPROBANTE_RECIBIDO"
    const val COMPROBANTE_REVISADO = "COMPROBANTE_REVISADO"
    const val CITA = "CITA"
    const val CASO_ACTUALIZADO = "CASO_ACTUALIZADO"
    const val RECORDATORIO_CASO = "RECORDATORIO_CASO"
}

data class EntregaRecord(val canal: String, val exito: Boolean, val fecha: Instant, val detalle: String? = null)

data class NotificacionRecord(
    val id: UUID,
    val destinatario: String,
    val tipo: String,
    val titulo: String,
    val mensaje: String,
    val datos: Map<String, String>,
    val creadaEn: Instant,
    val leidaEn: Instant?,
    val recordatorios: Int,
    val ultimoAvisoEn: Instant,
    val esRecordatorio: Boolean = false,
    val entregas: List<EntregaRecord> = emptyList(),
)

/**
 * Bandeja compartida (tabla notificaciones). Funciona como "bandeja de salida": las filas con
 * pendiente_envio = true las entrega el despachador (push o correo), vengan de este servidor o
 * del portal web.
 */
class NotificacionRepository(private val db: BaseDeDatos) {

    suspend fun crear(destinatario: String, tipo: String, titulo: String, mensaje: String, datos: Map<String, String>, ahora: Instant): NotificacionRecord =
        db.transaccion { c -> crearEn(c, destinatario, tipo, titulo, mensaje, datos, ahora) }

    suspend fun buscar(id: UUID): NotificacionRecord? = db.leer { c -> c.uno("select * from notificaciones where id = ?", id, fila = ::registro) }

    suspend fun deUsuario(usuarioId: String, limite: Int = 100): List<NotificacionRecord> = db.leer { c ->
        c.lista("select * from notificaciones where usuario_id = ? order by creada_en desc limit ?", usuarioId, limite, fila = ::registro)
    }

    suspend fun noLeidas(usuarioId: String): Int = db.leer { c ->
        c.uno("select count(*) from notificaciones where usuario_id = ? and leida_en is null", usuarioId) { it.getInt(1) } ?: 0
    }

    /** C-RFN-07: conserva la primera fecha de lectura. null si no existe o no es del usuario. */
    suspend fun marcarLeida(id: UUID, usuarioId: String, ahora: Instant): NotificacionRecord? = db.transaccion { c ->
        c.ejecutar("update notificaciones set leida_en = coalesce(leida_en, ?) where id = ? and usuario_id = ?", ahora, id, usuarioId)
        c.uno("select * from notificaciones where id = ? and usuario_id = ?", id, usuarioId, fila = ::registro)
    }

    suspend fun marcarTodasLeidas(usuarioId: String, ahora: Instant): Int = db.transaccion { c ->
        c.ejecutar("update notificaciones set leida_en = ? where usuario_id = ? and leida_en is null", ahora, usuarioId)
    }

    /**
     * Toma hasta [limite] notificaciones por entregar y las marca como tomadas en la misma
     * transacción. FOR UPDATE SKIP LOCKED: si hubiera dos instancias del servidor, no se reparten
     * la misma notificación.
     */
    suspend fun tomarPendientes(limite: Int = 50): List<NotificacionRecord> = db.transaccion { c ->
        val filas = c.lista("select * from notificaciones where pendiente_envio order by creada_en limit ? for update skip locked", limite, fila = ::registro)
        filas.forEach { c.ejecutar("update notificaciones set pendiente_envio = false where id = ?", it.id) }
        filas
    }

    suspend fun registrarEntrega(id: UUID, e: EntregaRecord) = db.transaccion { c ->
        c.ejecutar("insert into entregas_notificacion (notificacion_id, canal, exito, fecha, detalle) values (?, ?, ?, ?, ?)", id, e.canal, e.exito, e.fecha, e.detalle?.take(300))
    }

    suspend fun entregas(id: UUID): List<EntregaRecord> = db.leer { c ->
        c.lista("select canal, exito, fecha, detalle from entregas_notificacion where notificacion_id = ? order by fecha", id) {
            EntregaRecord(it.getString("canal"), it.getBoolean("exito"), it.instante("fecha")!!, it.getString("detalle"))
        }
    }

    /** Resultados sin leer cuyo último aviso fue hace más del plazo (C-RFNN-04). */
    suspend fun pendientesDeRecordatorio(limite: Instant, maxRecordatorios: Int): List<NotificacionRecord> = db.leer { c ->
        c.lista(
            "select * from notificaciones where tipo = ? and leida_en is null and recordatorios < ? and ultimo_aviso_en <= ?",
            TipoNotificacion.RESULTADO_TAMIZAJE, maxRecordatorios, limite, fila = ::registro
        )
    }

    /** Programa el reenvío como recordatorio: el despachador lo entrega con "Recordatorio: ". */
    suspend fun registrarRecordatorio(id: UUID, ahora: Instant): NotificacionRecord? = db.transaccion { c ->
        c.ejecutar("update notificaciones set recordatorios = recordatorios + 1, ultimo_aviso_en = ?, pendiente_envio = true, envio_es_recordatorio = true where id = ?", ahora, id)
        c.uno("select * from notificaciones where id = ?", id, fila = ::registro)
    }

    data class FilaSeguimiento(val notificacion: NotificacionRecord, val tutor: String, val entregadaPor: List<String>)

    /** Para Enfermería: resultados enviados a tutores y si los leyeron. */
    suspend fun seguimiento(soloNoLeidas: Boolean): List<FilaSeguimiento> = db.leer { c ->
        c.lista(
            """select n.*, coalesce(u.email, n.usuario_id) as tutor,
                      (select string_agg(distinct e.canal, ',') from entregas_notificacion e where e.notificacion_id = n.id and e.exito) as canales
               from notificaciones n left join asp_net_users u on u.id = n.usuario_id
               where n.tipo = ? and (not ? or n.leida_en is null)
               order by n.creada_en desc limit 300""",
            TipoNotificacion.RESULTADO_TAMIZAJE, soloNoLeidas
        ) { rs -> FilaSeguimiento(registro(rs), rs.getString("tutor"), rs.getString("canales")?.split(',') ?: emptyList()) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun crearEn(c: Connection, destinatario: String, tipo: String, titulo: String, mensaje: String, datos: Map<String, String>, ahora: Instant): NotificacionRecord {
            val id = UUID.randomUUID()
            // "ruta" le dice a la app a qué pantalla ir al tocarla (M-RC-02).
            val conRuta = if ("ruta" in datos) datos else datos + ("ruta" to "notificacion/$id")
            val texto = JsonObject(conRuta.mapValues { JsonPrimitive(it.value) }).toString()
            c.ejecutar(
                """insert into notificaciones (id, usuario_id, tipo, titulo, mensaje, datos, creada_en, recordatorios, ultimo_aviso_en, pendiente_envio, envio_es_recordatorio)
                   values (?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?, true, false)""",
                id, destinatario, tipo, titulo.take(120), mensaje.take(600), texto, ahora, ahora
            )
            return NotificacionRecord(id, destinatario, tipo, titulo, mensaje, conRuta, ahora, null, 0, ahora)
        }

        fun registro(rs: ResultSet) = NotificacionRecord(
            id = rs.getObject("id", UUID::class.java),
            destinatario = rs.getString("usuario_id"),
            tipo = rs.getString("tipo"),
            titulo = rs.getString("titulo"),
            mensaje = rs.getString("mensaje"),
            datos = (json.parseToJsonElement(rs.getString("datos")) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            creadaEn = rs.instante("creada_en")!!,
            leidaEn = rs.instante("leida_en"),
            recordatorios = rs.getInt("recordatorios"),
            ultimoAvisoEn = rs.instante("ultimo_aviso_en")!!,
            esRecordatorio = rs.getBoolean("envio_es_recordatorio"),
        )
    }
}
