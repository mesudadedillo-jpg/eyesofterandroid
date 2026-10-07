package com.example.eyesofterapp

import com.example.eyesofterapp.data.network.AuthApi
import com.example.eyesofterapp.data.network.HttpClientFactory
import com.example.eyesofterapp.data.repository.AuthRepositoryImpl
import com.example.eyesofterapp.domain.repository.AuthRepository
import com.example.eyesofterapp.domain.usecase.LoginUseCase

class AppContainer {
    private val httpClient = HttpClientFactory.create()
    private val authRepository: AuthRepository = AuthRepositoryImpl(AuthApi(httpClient))

    val loginUseCase = LoginUseCase(authRepository)
}