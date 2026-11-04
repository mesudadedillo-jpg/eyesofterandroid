package com.example.eyesofterapp.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Radio promedio de la Tierra en kilómetros. */
private const val RADIO_TIERRA_KM = 6371.0

/**
 * Fórmula de Haversine: distancia "en línea recta sobre la esfera" entre
 * dos puntos dados en grados de latitud y longitud. Devuelve kilómetros.
 *
 * Pasos:
 *  1. Convertir las diferencias de latitud y longitud a radianes.
 *  2. a = sen²(Δlat/2) + cos(lat1)·cos(lat2)·sen²(Δlng/2)
 *  3. c = 2·arcsen(√a)   ← el ángulo entre los dos puntos visto desde el centro
 *  4. distancia = radio · c
 *
 * OJO: la copia de la app (OpticasApi.kt) usa acos en el paso 3 en lugar de
 * asin, lo cual da distancias incorrectas y ordena al revés. Aquí va la
 * fórmula correcta.
 */
fun distanciaHaversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    // min(1.0, ...) evita errores de redondeo que harían √a un poquito mayor que 1.
    val c = 2 * asin(min(1.0, sqrt(a)))
    return RADIO_TIERRA_KM * c
}
