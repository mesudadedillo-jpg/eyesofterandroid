package com.example.eyesofterapp.repos

import com.example.eyesofterapp.data.distanciaHaversineKm
import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.doble
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import java.sql.Connection
import java.sql.ResultSet

data class Clinica(
    val id: Int,
    val nombre: String,
    val tipo: String,
    val direccion: String,
    val telefono: String,
    val ciudad: String,
    val codigoPostal: String?,
    val latitud: Double?,
    val longitud: Double?,
    val horario: String?,
    val asociada: Boolean,
    val activa: Boolean,
)

/**
 * Directorio de consultorios y ópticas (C-RFN-03). Lo administra Dirección/Enfermería en el
 * portal; aquí solo se lee. Las inactivas no se muestran.
 */
class ClinicaRepository(private val db: BaseDeDatos) {

    suspend fun activas(): List<Clinica> = db.leer { c -> c.lista("select * from clinicas where activa order by es_asociada desc, nombre", fila = ::clinica) }

    suspend fun porId(id: Int): Clinica? = db.leer { c -> porId(c, id) }

    /**
     * Texto en nombre, ciudad, dirección o CP, sin importar mayúsculas ni acentos.
     * Asociadas primero (M-RFN-07, M-RFNN-05).
     */
    suspend fun buscar(texto: String?): List<Clinica> {
        val q = texto?.let(::normalizar)?.takeIf { it.isNotBlank() }
        return activas().filter { q == null || normalizar("${it.nombre} ${it.ciudad} ${it.direccion} ${it.codigoPostal.orEmpty()}").contains(q) }
    }

    /** Asociadas primero; dentro de cada grupo, de la más cercana a la más lejana. */
    suspend fun cercanas(lat: Double, lng: Double, texto: String?): List<Pair<Clinica, Double>> =
        buscar(texto).filter { it.latitud != null && it.longitud != null }
            .map { it to distanciaHaversineKm(lat, lng, it.latitud!!, it.longitud!!) }
            .sortedWith(compareByDescending<Pair<Clinica, Double>> { it.first.asociada }.thenBy { it.second })

    companion object {
        fun porId(c: Connection, id: Int): Clinica? = c.uno("select * from clinicas where id = ?", id, fila = ::clinica)

        fun clinica(rs: ResultSet) = Clinica(
            id = rs.getInt("id"), nombre = rs.getString("nombre"), tipo = rs.getString("tipo"), direccion = rs.getString("direccion"),
            telefono = rs.getString("telefono"), ciudad = rs.getString("ciudad"), codigoPostal = rs.getString("codigo_postal"),
            latitud = rs.doble("latitud"), longitud = rs.doble("longitud"), horario = rs.getString("horario"),
            asociada = rs.getBoolean("es_asociada"), activa = rs.getBoolean("activa"),
        )

        fun normalizar(s: String): String =
            java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()
    }
}
