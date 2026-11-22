package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.ClinicaDto
import com.example.eyesofterapp.model.Optica
import com.example.eyesofterapp.repos.Clinica
import com.example.eyesofterapp.plugins.validarApp
import com.example.eyesofterapp.repos.RolesIdentity
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private fun Clinica.toOptica(distanciaKm: Double = 0.0, convenio: String? = null) = Optica(
    id = id, nombre = nombre, direccion = direccion, ciudad = ciudad, codigoPostal = codigoPostal.orEmpty(),
    lat = latitud ?: 0.0, lng = longitud ?: 0.0, distanciaKm = Math.round(distanciaKm * 100) / 100.0,
    telefono = telefono, verificada = asociada, tipo = tipo, horario = horario, convenio = convenio,
)

private fun Clinica.toDto(convenio: String? = null) = ClinicaDto(id, nombre, tipo, direccion, ciudad, codigoPostal, telefono, horario, latitud, longitud, asociada, convenio)

/**
 * Convenios para quien consulta: si trae la sesión de un tutor, los de las escuelas de sus hijos.
 * La ruta sigue siendo pública: un token ausente, vencido o de otro rol simplemente no agrega nada.
 */
private suspend fun ApplicationCall.conveniosDeLaSesion(servicios: Servicios): Map<Int, String> {
    val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    val usuario = servicios.tokens.validarApp(token, servicios.usuarios) ?: return emptyMap()
    val tutorId = usuario.tutorId?.takeIf { usuario.rol == RolesIdentity.TUTOR } ?: return emptyMap()
    return servicios.clinicas.conveniosDelTutor(tutorId)
}

/**
 * Directorio de clínicas (lo mantiene el portal; aquí solo se lee). Público: no tiene datos personales.
 * Si llega la sesión de un tutor, cada clínica trae el beneficio del convenio con la escuela de sus hijos.
 *
 *  GET /opticas/cercanas?lat=&lng=[&query=]  Para el mapa: SOLO las que tienen coordenadas,
 *                                             asociadas primero y luego por distancia (M-RFN-07).
 *  GET /opticas/cercanas?query=               Búsqueda por texto (distanciaKm = 0.0).
 *  GET /api/clinicas[?query=]                 Todo el directorio activo, con o sin coordenadas:
 *                                             lista sin ubicación (M-RFNN-05) y verificación de una
 *                                             clínica escrita a mano (M-RFNN-06).
 */
fun Route.opticasRoutes(servicios: Servicios) {
    get("/opticas/cercanas") {
        val params = call.request.queryParameters
        val latTexto = params["lat"]
        val lngTexto = params["lng"]
        val query = params["query"]

        if (latTexto != null || lngTexto != null) {
            if (latTexto == null || lngTexto == null) {
                call.error(HttpStatusCode.BadRequest, "Envía lat y lng juntos, por ejemplo ?lat=20.0825&lng=-98.3622")
                return@get
            }
            val lat = latTexto.toDoubleOrNull()
            val lng = lngTexto.toDoubleOrNull()
            if (lat == null || lng == null) {
                call.error(HttpStatusCode.BadRequest, "lat y lng deben ser números con punto decimal, por ejemplo lat=20.0825")
                return@get
            }
            // También rechaza "NaN", que toDoubleOrNull sí acepta.
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                call.error(HttpStatusCode.BadRequest, "Coordenadas fuera de rango: lat debe estar entre -90 y 90, y lng entre -180 y 180")
                return@get
            }
            val convenios = call.conveniosDeLaSesion(servicios)
            call.respond(servicios.clinicas.cercanas(lat, lng, query).map { (c, km) -> c.toOptica(km, convenios[c.id]) })
            return@get
        }
        if (query != null) {
            if (query.isBlank()) {
                call.error(HttpStatusCode.BadRequest, "El parámetro query no puede estar vacío")
                return@get
            }
            val convenios = call.conveniosDeLaSesion(servicios)
            call.respond(servicios.clinicas.buscar(query).filter { it.latitud != null && it.longitud != null }.map { it.toOptica(convenio = convenios[it.id]) })
            return@get
        }
        call.error(HttpStatusCode.BadRequest, "Faltan parámetros: envía lat y lng, o query")
    }

    get("/api/clinicas") {
        val query = call.request.queryParameters["query"]?.take(80)
        val convenios = call.conveniosDeLaSesion(servicios)
        call.respond(servicios.clinicas.buscar(query).map { it.toDto(convenios[it.id]) })
    }
}
