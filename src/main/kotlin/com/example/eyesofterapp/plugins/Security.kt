package com.example.eyesofterapp.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.eyesofterapp.config.Configuracion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.Date

/** Nombre del esquema de autenticación que usan las rutas de la app: authenticate(AUTH_APP) { ... } */
const val AUTH_APP = "app"

private const val EMISOR = "eyesofter-backend"
private const val CLAIM_USUARIO = "username"
private const val CLAIM_ROL = "role"

/** Quién está llamando, sacado del token. */
data class Sesion(val username: String, val rol: String) {
    /** Dirección (ADMINISTRADOR) y Enfermería (SUPERVISOR) pueden ver y avisar a todos. */
    val esPersonalEscolar: Boolean get() = rol == "ADMINISTRADOR" || rol == "SUPERVISOR"
}

/**
 * Crea y verifica los tokens JWT. Antes el login devolvía "jwt-mock-token-…";
 * ahora es un JWT firmado con HMAC-SHA256 que lleva el usuario y su rol.
 * El formato de la respuesta del login NO cambia: token sigue siendo un String.
 */
class Tokens(private val config: Configuracion, private val reloj: () -> Long = System::currentTimeMillis) {
    private val algoritmo = Algorithm.HMAC256(config.jwtSecret)

    val verificador = JWT.require(algoritmo).withIssuer(EMISOR).build()

    fun crear(username: String, rol: String): String {
        val ahora = reloj()
        return JWT.create()
            .withIssuer(EMISOR)
            .withSubject(username)
            .withClaim(CLAIM_USUARIO, username)
            .withClaim(CLAIM_ROL, rol)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + config.jwtDias * 24 * 60 * 60 * 1000))
            .sign(algoritmo)
    }
}

fun Application.configureSecurity(tokens: Tokens) {
    install(Authentication) {
        jwt(AUTH_APP) {
            realm = "eyesofter"
            verifier(tokens.verificador)
            validate { credencial ->
                val usuario = credencial.payload.getClaim(CLAIM_USUARIO).asString()
                val rol = credencial.payload.getClaim(CLAIM_ROL).asString()
                if (usuario.isNullOrBlank() || rol.isNullOrBlank()) null else JWTPrincipal(credencial.payload)
            }
            // Sin token, token vencido o firma inválida → 401 con el mismo formato de error de siempre.
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Sesión inválida o vencida. Inicia sesión de nuevo.")
                )
            }
        }
    }
}

/** Solo se llama dentro de authenticate(AUTH_APP), donde el principal siempre existe. */
fun ApplicationCall.sesion(): Sesion {
    val payload = principal<JWTPrincipal>()!!.payload
    return Sesion(
        username = payload.getClaim(CLAIM_USUARIO).asString(),
        rol = payload.getClaim(CLAIM_ROL).asString()
    )
}
