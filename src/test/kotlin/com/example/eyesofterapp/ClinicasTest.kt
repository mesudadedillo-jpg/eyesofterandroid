package com.example.eyesofterapp

import com.example.eyesofterapp.model.ClinicaDto
import com.example.eyesofterapp.model.Optica
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Directorio de clínicas desde la base (M-RFN-06/07, M-RFNN-05/06, C-RFN-03). */
class ClinicasTest {
    private val servicios = serviciosPrueba()

    @Test
    fun cercanasAsociadasPrimeroYLuegoPorDistancia() = testApplication {
        val client = servidor(servicios)
        val lista = client.get("/opticas/cercanas?lat=20.0825&lng=-98.3622").body<List<Optica>>()
        // Inactiva (3) y sin coordenadas (4) no salen en el mapa.
        assertEquals(listOf(1, 5, 2), lista.map { it.id })
        assertTrue(lista[0].verificada && lista[1].verificada && !lista[2].verificada)
        assertTrue(lista[0].distanciaKm < 1.0)
        assertTrue(lista[1].distanciaKm > 30.0)
        assertEquals("Optica", lista[0].tipo)
    }

    @Test
    fun busquedaSinAcentos() = testApplication {
        val client = servidor(servicios)
        assertEquals(listOf(5, 1), client.get("/opticas/cercanas?query=optica").body<List<Optica>>().map { it.id })
        assertEquals(listOf(5), client.get("/opticas/cercanas?query=PACHUCA").body<List<Optica>>().map { it.id })
    }

    @Test
    fun directorioCompletoIncluyeLasQueNoTienenUbicacion() = testApplication {
        val client = servidor(servicios)
        val todas = client.get("/api/clinicas").body<List<ClinicaDto>>()
        assertEquals(setOf(1, 2, 4, 5), todas.map { it.id }.toSet())
        assertTrue(todas.take(2).all { it.asociada })
        assertEquals(listOf(4), client.get("/api/clinicas?query=oftalmologia").body<List<ClinicaDto>>().map { it.id })
    }

    @Test
    fun parametrosInvalidos() = testApplication {
        val client = servidor(servicios)
        for (q in listOf("", "?lat=20", "?lat=abc&lng=1", "?lat=95&lng=0", "?lat=NaN&lng=0", "?query=%20")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/opticas/cercanas$q").status, q)
        }
    }
}
