package com.example.eyesofterapp.routes

import com.example.eyesofterapp.data.NotificacionRecord
import com.example.eyesofterapp.data.TamizajeRecord
import com.example.eyesofterapp.model.NotificacionDto
import com.example.eyesofterapp.model.TamizajeDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.time.Instant

/** Milisegundos → "2026-11-19T15:04:05.123Z" (ISO-8601, UTC). */
fun Long.iso(): String = Instant.ofEpochMilli(this).toString()

fun NotificacionRecord.toDto() = NotificacionDto(
    id = id,
    tipo = tipo.name,
    titulo = titulo,
    mensaje = mensaje,
    datos = datos,
    creadaEn = creadaEn.iso(),
    leidaEn = leidaEn?.iso(),
)

fun TamizajeRecord.toDto() = TamizajeDto(
    id = id,
    folio = folio,
    alumnoNombre = alumnoNombre,
    grado = grado,
    grupo = grupo,
    fecha = fecha,
    semaforo = semaforo.name,
    semaforoTexto = semaforo.texto,
    observaciones = observaciones,
)

suspend fun ApplicationCall.error(codigo: HttpStatusCode, mensaje: String) =
    respond(codigo, mapOf("error" to mensaje))

/**
 * Bitácora de intentos de acceso a datos ajenos (C-RFNN-06, C-RC-07).
 * Va a la consola con el nombre "auditoria" para poder filtrarla en Railway.
 */
val auditoria = LoggerFactory.getLogger("auditoria")
