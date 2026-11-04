package com.example.eyesofterapp

import com.example.eyesofterapp.data.distanciaHaversineKm
import com.example.eyesofterapp.model.Optica
import com.example.eyesofterapp.model.SaludDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pruebas de GET /salud y GET /opticas/cercanas (Fase 2). */
class OpticasRoutesTest {

    // Centro de Tulancingo: es la misma coordenada que la óptica 1 del catálogo.
    private val tulancingoLat = 20.0825
    private val tulancingoLng = -98.3622

    private fun ApplicationTestBuilder.jsonClient(): HttpClient {
        application { module() }
        return createClient { install(ContentNegotiation) { json() } }
    }

    /** Comprueba que la respuesta sea 400 y que el mensaje contenga el texto esperado. */
    private suspend fun assertError400(response: HttpResponse, textoEsperado: String) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val cuerpo = response.bodyAsText()
        assertTrue(cuerpo.contains(textoEsperado), "El mensaje fue: $cuerpo")
    }

    // ---------- /salud ----------

    @Test
    fun saludRespondeOkSinBaseDeDatos() = testApplication {
        val response = jsonClient().get("/salud")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SaludDto(status = "ok", db = false), response.body<SaludDto>())
    }

    // ---------- Fórmula de Haversine ----------

    @Test
    fun haversineDaDistanciasRazonables() {
        // Mismo punto → 0 km.
        assertEquals(0.0, distanciaHaversineKm(20.0825, -98.3622, 20.0825, -98.3622), 0.0001)
        // Tulancingo → Zócalo de CDMX: en línea recta son unos 108 km.
        val km = distanciaHaversineKm(20.0825, -98.3622, 19.432608, -99.133209)
        assertTrue(km in 100.0..115.0, "Distancia calculada: $km")
    }

    // ---------- Búsqueda por coordenadas ----------

    @Test
    fun porCoordenadasOrdenaDeMasCercanaAMasLejana() = testApplication {
        val response = jsonClient().get("/opticas/cercanas") {
            parameter("lat", tulancingoLat)
            parameter("lng", tulancingoLng)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val opticas = response.body<List<Optica>>()
        assertEquals(8, opticas.size)
        // La primera es la que está en el mismo punto, y la última la de Monterrey.
        assertEquals(1, opticas.first().id)
        assertEquals(0.0, opticas.first().distanciaKm)
        assertEquals("Monterrey", opticas.last().ciudad)
        // Cada distancia es mayor o igual a la anterior.
        assertEquals(opticas.map { it.distanciaKm }.sorted(), opticas.map { it.distanciaKm })
    }

    @Test
    fun coordenadasYQueryJuntosFiltranYOrdenan() = testApplication {
        val opticas = jsonClient().get("/opticas/cercanas") {
            parameter("lat", tulancingoLat)
            parameter("lng", tulancingoLng)
            parameter("query", "CDMX")
        }.body<List<Optica>>()

        assertEquals(3, opticas.size)
        assertTrue(opticas.all { it.ciudad == "CDMX" && it.distanciaKm > 0.0 })
    }

    // ---------- Búsqueda por texto ----------

    @Test
    fun porQueryFiltraPorCiudad() = testApplication {
        val opticas = jsonClient().get("/opticas/cercanas?query=cdmx").body<List<Optica>>()
        assertEquals(setOf(4, 5, 6), opticas.map { it.id }.toSet())
        // Sin coordenadas no hay distancia (la app no la muestra si vale 0.0).
        assertTrue(opticas.all { it.distanciaKm == 0.0 })
    }

    @Test
    fun porQueryIgnoraAcentosYBuscaCodigoPostal() = testApplication {
        val client = jsonClient()
        // "oftalmico" sin acento encuentra "Centro Oftálmico del Norte".
        val porNombre = client.get("/opticas/cercanas?query=oftalmico").body<List<Optica>>()
        assertEquals(listOf(6), porNombre.map { it.id })
        // Código postal de Tulancingo.
        val porCp = client.get("/opticas/cercanas?query=43600").body<List<Optica>>()
        assertEquals(3, porCp.size)
    }

    @Test
    fun porQuerySinResultadosDevuelveListaVacia() = testApplication {
        val response = jsonClient().get("/opticas/cercanas?query=Pachuca")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(emptyList(), response.body<List<Optica>>())
    }

    // ---------- Validación (400) ----------

    @Test
    fun sinParametrosDevuelve400() = testApplication {
        assertError400(jsonClient().get("/opticas/cercanas"), "Faltan parámetros")
    }

    @Test
    fun soloLatDevuelve400() = testApplication {
        assertError400(jsonClient().get("/opticas/cercanas?lat=20.08"), "lat y lng juntos")
    }

    @Test
    fun coordenadasQueNoSonNumerosDevuelven400() = testApplication {
        val client = jsonClient()
        assertError400(client.get("/opticas/cercanas?lat=abc&lng=-98.36"), "deben ser números")
        // Coma decimal en vez de punto.
        assertError400(client.get("/opticas/cercanas?lat=20,08&lng=-98.36"), "deben ser números")
    }

    @Test
    fun coordenadasFueraDeRangoDevuelven400() = testApplication {
        val client = jsonClient()
        assertError400(client.get("/opticas/cercanas?lat=95&lng=-98.36"), "fuera de rango")
        assertError400(client.get("/opticas/cercanas?lat=NaN&lng=-98.36"), "fuera de rango")
    }

    @Test
    fun queryVacioDevuelve400() = testApplication {
        assertError400(jsonClient().get("/opticas/cercanas?query=%20"), "no puede estar vacío")
    }
}
