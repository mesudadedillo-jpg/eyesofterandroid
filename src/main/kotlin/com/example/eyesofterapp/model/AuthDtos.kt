package com.example.eyesofterapp.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// CONTRATO CON LA APP (copia del lado del servidor)
//
// Estas clases describen el JSON que viaja entre la app y el servidor.
// La app tiene su propia copia en
//   app/shared/src/commonMain/kotlin/com/example/eyesofterapp/data/model/AuthDtos.kt
// Si cambias un nombre de campo aquí, cámbialo también allá y en docs/API.md;
// si no, la app ya no podrá leer la respuesta.
//
// Diferencias con la copia de la app:
//  - El paquete es ...model en vez de ...data.model (el JSON no usa el paquete,
//    así que no afecta).
//  - No tiene la función toDomain(), porque usa clases que solo existen en la app.
// ---------------------------------------------------------------------------

/** Lo que la app envía en POST /api/login. */
@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String
)

/** Datos públicos del usuario (nunca incluye la contraseña). */
@Serializable
data class UserDto(
    val username: String,
    val fullName: String,
    val role: String
)

/** Lo que el servidor responde cuando el login es correcto. */
@Serializable
data class LoginResponseDto(
    val token: String,
    val user: UserDto
)
