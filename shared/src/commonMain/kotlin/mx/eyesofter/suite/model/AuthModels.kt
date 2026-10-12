// DTOs para la autenticación de usuarios en el backend y cliente.
// Soporta los campos username, password, fullName y role definidos en users.json.
package mx.eyesofter.suite.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String? = null,
    val pin: String? = null
) {
    val credencial: String
        get() = password ?: pin ?: ""
}

@Serializable
data class LoginResponse(
    val exito: Boolean,
    val mensaje: String,
    val username: String? = null,
    val fullName: String? = null,
    val role: String? = null
)

@Serializable
data class Usuario(
    val username: String,
    val password: String? = null,
    val pin: String? = null,
    val fullName: String? = null,
    val nombre: String? = null,
    val role: String? = null,
    val rol: String? = null
) {
    val passwordEfectivo: String
        get() = password ?: pin ?: ""

    val nombreEfectivo: String
        get() = fullName ?: nombre ?: ""

    val rolEfectivo: String
        get() = role ?: rol ?: ""
}
