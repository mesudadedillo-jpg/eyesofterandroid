package com.example.eyesofterapp.data

import com.example.eyesofterapp.model.Optica
import kotlinx.serialization.json.Json
import java.text.Normalizer
import kotlin.math.round

/**
 * Encargado de las ópticas. Por ahora las lee de resources/opticas.json
 * (el mismo catálogo de 8 ópticas que tiene la app como respaldo).
 * Cuando exista la base de datos, solo cambia este archivo.
 */
object OpticaRepository {

    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    /**
     * El catálogo se lee UNA sola vez, la primera vez que se usa ("by lazy"),
     * y se queda en memoria. A diferencia de users.json, aquí no hace falta
     * releerlo en cada petición.
     */
    private val catalogo: List<Optica> by lazy {
        val texto = OpticaRepository::class.java.classLoader.getResourceAsStream("opticas.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("No se encontró opticas.json en los recursos del servidor")
        jsonDecoder.decodeFromString<List<Optica>>(texto)
    }

    /**
     * Ópticas ordenadas de la más cercana a la más lejana desde (lat, lng).
     * Si viene "query", primero filtra por texto y luego ordena.
     */
    fun cercanas(lat: Double, lng: Double, query: String? = null): List<Optica> =
        filtrar(query)
            .map { it.copy(distanciaKm = redondear(distanciaHaversineKm(lat, lng, it.lat, it.lng))) }
            .sortedBy { it.distanciaKm }

    /**
     * Busca el texto en nombre, ciudad, dirección y código postal.
     * Sin importar mayúsculas ni acentos: "optica" encuentra "Óptica".
     * Las distancias quedan en 0.0 porque no sabemos dónde está el usuario.
     */
    fun buscar(query: String): List<Optica> = filtrar(query)

    private fun filtrar(query: String?): List<Optica> {
        if (query.isNullOrBlank()) return catalogo
        val q = normalizar(query)
        return catalogo.filter { o ->
            listOf(o.nombre, o.ciudad, o.direccion, o.codigoPostal).any { normalizar(it).contains(q) }
        }
    }

    /** "Óptica  " → "optica": quita acentos, espacios de los lados y mayúsculas. */
    private fun normalizar(texto: String): String =
        Normalizer.normalize(texto.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "") // \p{M} = las marcas de acento que separó NFD

    // SUPOSICIÓN: se redondea a 2 decimales (10 m) para que el JSON sea legible;
    // la app de todos modos muestra 1 decimal.
    private fun redondear(km: Double): Double = round(km * 100) / 100
}
