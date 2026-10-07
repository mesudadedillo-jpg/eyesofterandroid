package com.example.eyesofterapp

import com.example.eyesofterapp.data.model.LoginRequestDto
import com.example.eyesofterapp.data.model.LoginResponseDto
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApplicationTest {

    @Test
    fun testLoginSuccessAdmin() = testApplication {
        application {
            module()
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto("admin", "123456"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val dto = response.body<LoginResponseDto>()
        assertEquals("admin", dto.user.username)
        assertEquals("ADMINISTRADOR", dto.user.role)
        assertNotNull(dto.token)
    }

    @Test
    fun testLoginUnauthorized() = testApplication {
        application {
            module()
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto("admin", "wrong_password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
