package mx.eyesofter.suite

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import mx.eyesofter.suite.plugins.configureCORS
import mx.eyesofter.suite.plugins.configureRouting
import mx.eyesofter.suite.plugins.configureSerialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutesTest {

    @Test
    fun testLoginExitosoAdmin() = testApplication {
        application {
            configureSerialization()
            configureCORS()
            configureRouting()
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"123456"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val bodyText = response.bodyAsText()
        assertTrue(bodyText.contains("ADMINISTRADOR"), "Debe contener el rol ADMINISTRADOR")
        assertTrue(bodyText.contains("Administrador del Sistema"), "Debe contener el fullName del usuario")
        assertTrue(bodyText.contains("true"), "Debe indicar éxito true")
    }

    @Test
    fun testLoginExitosoDoctor() = testApplication {
        application {
            configureSerialization()
            configureCORS()
            configureRouting()
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"doctor","password":"123456"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val bodyText = response.bodyAsText()
        assertTrue(bodyText.contains("DOCTOR"), "Debe contener el rol DOCTOR")
        assertTrue(bodyText.contains("Dr. Médico Especialista"), "Debe contener el fullName del doctor")
    }

    @Test
    fun testLoginCredencialesIncorrectas() = testApplication {
        application {
            configureSerialization()
            configureCORS()
            configureRouting()
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"000000"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val bodyText = response.bodyAsText()
        assertTrue(bodyText.contains("false"), "Debe indicar éxito false")
    }

    @Test
    fun testLoginUsuarioNoExistente() = testApplication {
        application {
            configureSerialization()
            configureCORS()
            configureRouting()
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"no_existe","password":"123456"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val bodyText = response.bodyAsText()
        assertTrue(bodyText.contains("false"), "Debe indicar éxito false")
    }
}
