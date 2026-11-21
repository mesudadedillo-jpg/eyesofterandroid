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
private const val CLAIM_EQUIPO = "equipo"

/**
 * Audiencia de los tokens de un equipo de escritorio vinculado. Un token así SOLO sirve en
 * /api/escritorio/...; las rutas de la app lo rechazan (y al revés: el token de la app no
 * sirve como equipo de escritorio).
 */
const val AUDIENCIA_ESCRITORIO = "escritorio"

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

    /** Solo acepta tokens de equipo de escritorio (audiencia [AUDIENCIA_ESCRITORIO]). */
    val verificadorEscritorio = JWT.require(algoritmo).withIssuer(EMISOR).withAudience(AUDIENCIA_ESCRITORIO).build()

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

    /**
     * Token de un equipo de escritorio, emitido cuando Dirección o Enfermería aprueba la
     * vinculación en el navegador. Va ligado a ESA cuenta: si se desactiva, cambia su
     * contraseña o deja de ser personal escolar, el equipo queda desvinculado al instante.
     */
    fun crearEscritorio(usuario: Usuario, equipo: String): String {
        val ahora = reloj()
        return JWT.create()
            .withIssuer(EMISOR)
            .withAudience(AUDIENCIA_ESCRITORIO)
            .withSubject(usuario.id)
            .withClaim(CLAIM_USUARIO, usuario.email)
            .withClaim(CLAIM_SELLO, huellaSello(usuario.sello))
            .withClaim(CLAIM_EQUIPO, equipo)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + config.escritorioDias * 24 * 60 * 60 * 1000))
            .sign(algoritmo)
    }
}

/** Equipo de escritorio vinculado: la cuenta que lo autorizó y el nombre del equipo. */
data class SesionEscritorio(val usuario: Usuario, val equipo: String)

/**
 * Valida un token de equipo de escritorio con los datos ACTUALES de la cuenta.
 * null = no hay token, es de otro tipo, venció, o la cuenta ya no puede autorizar equipos.
 */
suspend fun Tokens.validarEscritorio(token: String?, usuarios: UsuarioRepository): SesionEscritorio? {
    if (token.isNullOrBlank()) return null
    val jwt = runCatching { verificadorEscritorio.verify(token) }.getOrNull() ?: return null
    val usuario = jwt.subject?.let { usuarios.porId(it) } ?: return null
    if (!usuario.activo || !usuario.esPersonal || jwt.getClaim(CLAIM_SELLO).asString() != huellaSello(usuario.sello)) return null
    return SesionEscritorio(usuario, jwt.getClaim(CLAIM_EQUIPO).asString().orEmpty())
}

fun Application.configureSecurity(tokens: Tokens, usuarios: UsuarioRepository) {
    install(Authentication) {
        jwt(AUTH_APP) {
            realm = "eyesofter"
            verifier(tokens.verificador)
            validate { credencial ->
                // Un token de equipo de escritorio no abre las rutas de la app.
                if (credencial.payload.audience?.contains(AUDIENCIA_ESCRITORIO) == true) return@validate null
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
