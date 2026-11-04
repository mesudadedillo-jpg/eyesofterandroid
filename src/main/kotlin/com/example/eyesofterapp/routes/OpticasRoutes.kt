package com.example.eyesofterapp.routes

import com.example.eyesofterapp.data.OpticaRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /opticas/cercanas
 *
 * Dos formas de usarla (ver docs/API.md):
 *  - ?lat=20.0825&lng=-98.3622  → todas las ópticas, de la más cercana a la más lejana,
 *                                 con distanciaKm calculada.
 *  - ?query=Tulancingo          → solo las que contienen el texto en nombre, ciudad,
 *                                 dirección o código postal (distanciaKm = 0.0).
 *  Si vienen las dos, filtra por query y ordena por distancia.
 *
 * Cualquier otro parámetro (la app también manda "ciudad" y "cp") se ignora.
 * Si los parámetros están mal, responde 400 con {"error": "..."} igual que el login.
 */
fun Route.opticasRoutes() {
    get("/opticas/cercanas") {
        val params = call.request.queryParameters
        val latTexto = params["lat"]
        val lngTexto = params["lng"]
        val query = params["query"]

        suspend fun error400(mensaje: String) =
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to mensaje))

        // Caso 1: viene al menos una coordenada → se exigen las dos y que sean válidas.
        if (latTexto != null || lngTexto != null) {
            if (latTexto == null || lngTexto == null) {
                error400("Envía lat y lng juntos, por ejemplo ?lat=20.0825&lng=-98.3622")
                return@get
            }
            // toDoubleOrNull devuelve null si el texto no es un número (por ejemplo "abc" o "20,08").
            val lat = latTexto.toDoubleOrNull()
            val lng = lngTexto.toDoubleOrNull()
            if (lat == null || lng == null) {
                error400("lat y lng deben ser números con punto decimal, por ejemplo lat=20.0825")
                return@get
            }
            // La latitud va de -90 a 90 y la longitud de -180 a 180.
            // (Esto también rechaza "NaN", que toDoubleOrNull sí acepta.)
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                error400("Coordenadas fuera de rango: lat debe estar entre -90 y 90, y lng entre -180 y 180")
                return@get
            }
            call.respond(OpticaRepository.cercanas(lat, lng, query))
            return@get
        }

        // Caso 2: solo búsqueda por texto.
        if (query != null) {
            if (query.isBlank()) {
                error400("El parámetro query no puede estar vacío")
                return@get
            }
            call.respond(OpticaRepository.buscar(query))
            return@get
        }

        // Caso 3: no vino nada útil.
        error400("Faltan parámetros: envía lat y lng, o query")
    }
}
