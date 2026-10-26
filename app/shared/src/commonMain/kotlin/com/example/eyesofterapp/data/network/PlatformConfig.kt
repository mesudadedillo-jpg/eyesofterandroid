package com.example.eyesofterapp.data.network

const val BASE_URL = "https://eyesofterandroid-production.up.railway.app"

expect fun platformBaseUrls(): List<String>

fun platformBaseUrl(): String = BASE_URL
