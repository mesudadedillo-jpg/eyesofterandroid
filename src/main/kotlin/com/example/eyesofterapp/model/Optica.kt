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
    val verificada: Boolean = false
)

/** Respuesta de GET /salud. */
@Serializable
data class SaludDto(
    val status: String,
    val db: Boolean
)
