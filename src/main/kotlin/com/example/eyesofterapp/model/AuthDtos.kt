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

/**
 * Lo que la app envía en POST /api/login. "username" acepta el CORREO de la cuenta
 * (las cuentas las crea Dirección en el portal) o el nombre de usuario de Identity.
 */
@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    /** Código de 6 dígitos de la app autenticadora, si la cuenta tiene verificación en dos pasos. */
    val codigo: String? = null,
)

/** Datos públicos del usuario (nunca incluye la contraseña). */
@Serializable
data class UserDto(
    /** Correo de la cuenta. */
    val username: String,
    val fullName: String,
    /** ADMINISTRADOR (Dirección), SUPERVISOR (Enfermería), CLIENTE (Tutor), DOCTOR (Especialista) o ALUMNO. */
    val role: String,
    /** Id de la cuenta (el mismo del portal). */
    val id: String = "",
    /** true = es tutor y no ha aceptado la versión vigente del aviso de privacidad (C-RC-08). */
    val requiereConsentimiento: Boolean = false,
)

/** Lo que el servidor responde cuando el login es correcto. */
@Serializable
data class LoginResponseDto(
    val token: String,
    val user: UserDto
)

/** GET /api/consentimiento. */
@Serializable
data class ConsentimientoDto(
    val versionVigente: String,
    val aceptado: Boolean,
    val aceptadoEn: String? = null,
    /** Textos completos en el portal (C-RS-07). */
    val urlAviso: String,
    val urlTerminos: String,
)

/** POST /api/consentimiento. */
@Serializable
data class AceptarConsentimientoDto(val version: String, val acepto: Boolean = true)

/** POST /api/reportes: falla o duda desde la app (C-RS-04). */
@Serializable
data class ReporteEntradaDto(
    /** FALLA o DUDA. */
    val tipo: String,
    val mensaje: String,
    val correo: String? = null,
    /** ANDROID o IOS. */
    val plataforma: String = "ANDROID",
)

@Serializable
data class ReporteRecibidoDto(val id: Int)
