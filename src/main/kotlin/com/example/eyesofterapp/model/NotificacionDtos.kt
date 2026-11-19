package com.example.eyesofterapp.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// CONTRATO CON LA APP: notificaciones y dispositivos (ver docs/API.md §5-§7).
// Si cambias un campo aquí, cámbialo también en la app y en las dos copias de API.md.
// ---------------------------------------------------------------------------

/** Una notificación tal como la ve la app en su bandeja. Fechas en ISO-8601 UTC. */
@Serializable
data class NotificacionDto(
    val id: String,
    /** RESULTADO_TAMIZAJE, ALERTA_ENFERMERIA o AVISO. */
    val tipo: String,
    val titulo: String,
    val mensaje: String,
    /** Datos para navegar al tocarla. Siempre trae "ruta"; p. ej. "tamizaje/<id>". */
    val datos: Map<String, String> = emptyMap(),
    val creadaEn: String,
    /** Fecha y hora en que el usuario la abrió (C-RFN-07). null = sin leer. */
    val leidaEn: String? = null,
)

/** Respuesta de GET /api/notificaciones. */
@Serializable
data class BandejaDto(
    val noLeidas: Int,
    val notificaciones: List<NotificacionDto>,
)

/** Cuerpo de POST /api/dispositivos. */
@Serializable
data class RegistroDispositivoDto(
    /** Token de Firebase Cloud Messaging del teléfono. */
    val token: String,
    /** ANDROID o IOS. */
    val plataforma: String,
)

/** Cuerpo de POST /api/notificaciones (solo Dirección y Enfermería). */
@Serializable
data class AvisoEntradaDto(
    val titulo: String,
    val mensaje: String,
    /** A quién va. Se puede usar esta lista, el rol, o ambos. */
    val usernames: List<String> = emptyList(),
    /** Enviar a todos los usuarios de un rol, p. ej. "CLIENTE" = todos los tutores. */
    val rol: String? = null,
)

@Serializable
data class AvisoEnviadoDto(val enviadas: Int, val noEncontrados: List<String> = emptyList())

/** Una fila de GET /api/notificaciones/seguimiento: qué familias ya leyeron el resultado. */
@Serializable
data class SeguimientoDto(
    val notificacionId: String,
    val tutor: String,
    val alumno: String,
    val tamizajeId: String,
    val creadaEn: String,
    val leidaEn: String? = null,
    val recordatoriosEnviados: Int,
    /** Canales por los que sí llegó al menos una vez: "push", "correo". Vacío = solo bandeja. */
    val entregadaPor: List<String>,
)
