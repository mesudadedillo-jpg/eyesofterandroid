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


class AuthApi(private val client: HttpClient) {

    suspend fun login(request: LoginRequestDto): LoginResponseDto {
        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return when {
            response.status == HttpStatusCode.Unauthorized -> throw InvalidCredentialsException()
            response.status.isSuccess() -> response.body<LoginResponseDto>()
            else -> error("Error del servidor: ${response.status}")
        }
    }
}