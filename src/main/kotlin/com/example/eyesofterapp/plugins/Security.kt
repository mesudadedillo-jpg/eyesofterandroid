package com.example.eyesofterapp.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.eyesofterapp.config.Configuracion
import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.repos.Usuario
import com.example.eyesofterapp.repos.UsuarioRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

/** Nombre del esquema de autenticación que usan las rutas de la app: authenticate(AUTH_APP) { ... } */
const val AUTH_APP = "app"

private const val EMISOR = "eyesofter-backend"
private const val CLAIM_USUARIO = "username"
private const val CLAIM_ROL = "role"
private const val CLAIM_SELLO = "sello"

/**
 * Quién está llamando. Se arma en cada petición con los datos ACTUALES de la base, no con lo
 * que dice el token: si Dirección desactiva la cuenta o le cambia el rol, aplica de inmediato.
 */
data class Sesion(val usuario: Usuario) {
    val id get() = usuario.id
    val email get() = usuario.email
    /** Rol con el nombre de la app: ADMINISTRADOR, SUPERVISOR, CLIENTE, DOCTOR, ALUMNO. */
    val rol get() = usuario.rolApp
    val esPersonalEscolar get() = usuario.esPersonal
    val esTutor get() = usuario.rol == RolesIdentity.TUTOR && usuario.tutorId != null
    val tutorId get() = usuario.tutorId
    val alumnoId get() = usuario.alumnoId
}

/** Huella corta del security_stamp: el token no lleva el sello real. */
internal fun huellaSello(sello: String?): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest((sello ?: "").toByteArray())).take(16)

/** Crea y verifica los JWT (HMAC-SHA256). */
class Tokens(private val config: Configuracion, private val reloj: () -> Long = System::currentTimeMillis) {
    private val algoritmo = Algorithm.HMAC256(config.jwtSecret)

    val verificador = JWT.require(algoritmo).withIssuer(EMISOR).build()

    fun crear(usuario: Usuario): String {
        val ahora = reloj()
        return JWT.create()
            .withIssuer(EMISOR)
            .withSubject(usuario.id)
            .withClaim(CLAIM_USUARIO, usuario.email)
            .withClaim(CLAIM_ROL, usuario.rolApp)
            .withClaim(CLAIM_SELLO, huellaSello(usuario.sello))
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + config.jwtDias * 24 * 60 * 60 * 1000))
            .sign(algoritmo)
    }
}

fun Application.configureSecurity(tokens: Tokens, usuarios: UsuarioRepository) {
    install(Authentication) {
        jwt(AUTH_APP) {
            realm = "eyesofter"
            verifier(tokens.verificador)
            validate { credencial ->
                val id = credencial.payload.subject ?: return@validate null
                val usuario = usuarios.porId(id) ?: return@validate null
                // Cuenta desactivada, o contraseña/rol cambiados desde que se emitió el token → sesión inválida.
                if (!usuario.activo || credencial.payload.getClaim(CLAIM_SELLO).asString() != huellaSello(usuario.sello)) null
                else Sesion(usuario)
            }
            // Sin token, token vencido, firma inválida o cuenta desactivada → 401.
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sesión inválida o vencida. Inicia sesión de nuevo."))
            }
        }
    }
}

/** Solo se llama dentro de authenticate(AUTH_APP), donde el principal siempre existe. */
fun ApplicationCall.sesion(): Sesion = principal<Sesion>()!!

/** Dentro de authenticate(AUTH_APP, optional = true). */
fun ApplicationCall.sesionOpcional(): Sesion? = principal<Sesion>()
