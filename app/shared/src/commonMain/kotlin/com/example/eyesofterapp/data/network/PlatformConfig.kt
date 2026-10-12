package com.example.eyesofterapp.data.network

expect fun platformBaseUrls(): List<String>

fun platformBaseUrl(): String = platformBaseUrls().first()