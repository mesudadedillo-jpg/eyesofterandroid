package com.example.eyesofterapp.notificaciones

import com.example.eyesofterapp.repos.NotificacionRecord

/** A quién se le entrega: lo mínimo que necesitan los canales. */
data class Destinatario(val id: String, val email: String?, val nombre: String)

/**
 * Un medio por el que se le avisa a alguien: push, correo y, en el futuro,
 * SMS o WhatsApp (C-RE-02). Para agregar uno nuevo basta con implementar esta
 * interfaz y agregarlo a la lista de canales en Servicios.kt.
 *
 * La bandeja dentro de la app y el portal NO es un canal: la notificación se guarda siempre,
 * y los canales solo sirven para que el usuario se entere de que hay algo nuevo.
 */
interface CanalNotificacion {
    /** Nombre corto que se guarda en el historial de entregas: "push", "correo"... */
    val nombre: String

    suspend fun enviar(notificacion: NotificacionRecord, destinatario: Destinatario, esRecordatorio: Boolean): ResultadoEnvio
}

data class ResultadoEnvio(val exito: Boolean, val detalle: String? = null)

/** Título que se muestra en el teléfono o en el asunto del correo. */
fun NotificacionRecord.tituloVisible(esRecordatorio: Boolean) =
    if (esRecordatorio) "Recordatorio: $titulo" else titulo
