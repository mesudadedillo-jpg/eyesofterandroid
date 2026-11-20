package com.example.eyesofterapp.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// CONTRATO CON LA APP (copia del lado del servidor)
//
// Mismos nombres de campos, tipos y valores por defecto que
//   app/shared/src/commonMain/kotlin/com/example/eyesofterapp/domain/model/Optica.kt
// La app convierte la respuesta de GET /opticas/cercanas en List<Optica>,
// así que si un nombre no coincide, ese dato se pierde o la lectura falla.
// ---------------------------------------------------------------------------

@Serializable
data class Optica(
    val id: Int,
    val nombre: String,
    val direccion: String,
    val ciudad: String = "",
    val codigoPostal: String = "",
    val lat: Double,
    val lng: Double,
    /** Lo calcula el servidor al buscar por coordenadas; 0.0 = "sin distancia" (la app no la muestra). */
    val distanciaKm: Double = 0.0,
    val telefono: String = "7751234567",
    /** true = óptica asociada al programa EYESOFTER. */
    val verificada: Boolean = false,
    /** Optica, Consultorio, Oftalmologia... */
    val tipo: String = "",
    val horario: String? = null,
)

/** Respuesta de GET /salud. */
@Serializable
data class SaludDto(
    val status: String,
    val db: Boolean,
    /** Commit desplegado (Railway lo da en RAILWAY_GIT_COMMIT_SHA). "dev" en local. */
    val version: String = "dev",
    /** Qué está configurado en el servidor. Sirve para revisar Railway sin entrar al panel. */
    val push: Boolean = false,
    val correo: Boolean = false,
    val escritorio: Boolean = false,
    /** Notificaciones esperando entrega (bandeja de salida). Si crece, algo falla con push/correo. */
    val pendientesEnvio: Int = 0,
)

/** GET /api/clinicas: todo el directorio activo, también las que no tienen coordenadas (M-RFNN-05, M-RFNN-06). */
@Serializable
data class ClinicaDto(
    val id: Int,
    val nombre: String,
    val tipo: String,
    val direccion: String,
    val ciudad: String,
    val codigoPostal: String? = null,
    val telefono: String,
    val horario: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Asociada al programa: con ella hay pase con QR. */
    val asociada: Boolean,
)
