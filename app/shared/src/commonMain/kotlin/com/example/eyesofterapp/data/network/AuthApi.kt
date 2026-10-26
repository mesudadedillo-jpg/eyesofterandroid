package com.example.eyesofterapp.data.network

import com.example.eyesofterapp.data.model.LoginRequestDto
import com.example.eyesofterapp.data.model.LoginResponseDto
import com.example.eyesofterapp.domain.model.InvalidCredentialsException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

class AuthApi(private val client: HttpClient) {

    private var activeBaseUrl: String? = null

    suspend fun login(request: LoginRequestDto): LoginResponseDto {
        val urlsToTry = buildList {
            activeBaseUrl?.let { add(it) }
            addAll(platformBaseUrls().filter { it != activeBaseUrl })
        }

        for (baseUrl in urlsToTry) {
            try {
                val urlPath = if (baseUrl.endsWith("/")) "${baseUrl}api/login" else "$baseUrl/api/login"
                val response = client.post(urlPath) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                if (response.status == HttpStatusCode.Unauthorized) {
                    activeBaseUrl = baseUrl
                    throw InvalidCredentialsException()
                }

                if (response.status.isSuccess()) {
                    activeBaseUrl = baseUrl
                    return response.body<LoginResponseDto>()
                }

                error("Error del servidor Ktor: ${response.status}")
            } catch (e: InvalidCredentialsException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Servidor en $baseUrl no respondió (${e.message}), intentando siguiente dirección...")
            }
        }
        throw IllegalStateException(
            "No se pudo conectar con el servidor ($BASE_URL). " +
                    "Asegúrate de tener conexión a internet."
        )
    }
}
