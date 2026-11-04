package com.example.eyesofterapp

import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas del servidor. testApplication levanta el servidor EN MEMORIA
 * (sin abrir un puerto real) y le hace peticiones como si fuera la app.
 * Usan los usuarios de src/main/resources/users.json.
 */
class ApplicationTest {

    /** Crea un cliente que sabe enviar y leer JSON. */
    private fun ApplicationTestBuilder.jsonClient(): HttpClient {
        application { module() }
        return createClient {
            install(ContentNegotiation) { json() }
        }
    }

    @Test
    fun raizResponde() = testApplication {
        val response = jsonClient().get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor Server!", response.bodyAsText())
    }

    @Test
    fun loginCorrectoDevuelveUsuarioYToken() = testApplication {
        val response = jsonClient().post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto("admin", "123456"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = response.body<LoginResponseDto>()
        assertEquals("admin", dto.user.username)
        assertEquals("ADMINISTRADOR", dto.user.role)
        assertTrue(dto.token.startsWith("jwt-mock-token-"))
    }

    @Test
    fun loginConContrasenaIncorrectaDevuelve401() = testApplication {
        val response = jsonClient().post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto("admin", "wrong_password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Credenciales inválidas"))
    }

    @Test
    fun loginConContrasenaVaciaDevuelve400() = testApplication {
        val response = jsonClient().post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto("admin", ""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Contraseña requerida"))
    }
}
