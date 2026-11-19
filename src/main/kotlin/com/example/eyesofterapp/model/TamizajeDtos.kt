package com.example.eyesofterapp.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// CONTRATO con el sistema de escritorio (entrada) y con la app (salida).
// Ver docs/API.md §8-§9.
// ---------------------------------------------------------------------------

/** Lo que manda el sistema de escritorio en POST /api/escritorio/tamizajes (C-RFN-02). */
@Serializable
data class TamizajeEntradaDto(
    /** Identificador del tamizaje en el sistema de escritorio. Si se reenvía, no se duplica. */
    val folio: String,
    val alumnoNombre: String,
    val grado: String = "",
    val grupo: String = "",
    /** Usuario (rol CLIENTE) del tutor que debe recibir el aviso. */
    val tutorUsername: String,
    /** Fecha del tamizaje, AAAA-MM-DD. */
    val fecha: String,
    /** VERDE, AMARILLO o ROJO. */
    val semaforo: String,
    val observaciones: String = "",
)

@Serializable
data class TamizajeRecibidoDto(
    val tamizajeId: String,
    val notificacionId: String?,
    /** true = ese folio ya se había recibido; no se volvió a notificar. */
    val duplicado: Boolean,
)

/** Lo que ve el tutor (C-RFN-06: fecha, semáforo y observaciones, sin diagnóstico). */
@Serializable
data class TamizajeDto(
    val id: String,
    val folio: String,
    val alumnoNombre: String,
    val grado: String,
    val grupo: String,
    val fecha: String,
    val semaforo: String,
    /** Texto que acompaña al color (C-RC-10: el semáforo no depende solo del color). */
    val semaforoTexto: String,
    val observaciones: String,
)
