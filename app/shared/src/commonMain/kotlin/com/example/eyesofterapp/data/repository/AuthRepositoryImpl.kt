package com.example.eyesofterapp.data.repository

import com.example.eyesofterapp.data.model.LoginRequestDto
import com.example.eyesofterapp.data.model.toDomain
import com.example.eyesofterapp.data.network.AuthApi
import com.example.eyesofterapp.domain.model.Session
import com.example.eyesofterapp.domain.repository.AuthRepository
import kotlin.coroutines.cancellation.CancellationException

class AuthRepositoryImpl(private val api: AuthApi) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<Session> =
        try {
            val dto = api.login(LoginRequestDto(username, password))
            Result.success(dto.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e) 
        }
}