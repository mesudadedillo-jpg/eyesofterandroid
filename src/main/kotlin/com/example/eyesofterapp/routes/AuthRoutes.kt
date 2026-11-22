package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.AceptarConsentimientoDto
import com.example.eyesofterapp.model.CodigoSolicitadoDto
import com.example.eyesofterapp.model.ConsentimientoDto
import com.example.eyesofterapp.model.LoginRequestDto
import com.example.eyesofterapp.model.LoginResponseDto
import com.example.eyesofterapp.model.ReporteEntradaDto
import com.example.eyesofterapp.model.ReporteRecibidoDto
import com.example.eyesofterapp.model.RestablecerPasswordDto
import com.example.eyesofterapp.model.SolicitarCodigoDto
import com.example.eyesofterapp.model.UserDto
import com.example.eyesofterapp.plugins.sesion
import com.example.eyesofterapp.plugins.sesionOpcional
import com.example.eyesofterapp.repos.RolesIdentity
import com.example.eyesofterapp.repos.Usuario
import com.example.eyesofterapp.seguridad.PasswordIdentity
import com.example.eyesofterapp.seguridad.RecuperacionPassword
import com.example.eyesofterapp.seguridad.Totp
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Duration

/**
 * POST /api/login con las MISMAS cuentas del portal (tabla asp_net_users de Identity):
 *  - 400 si falta el usuario o la contraseña.
 *  - 401 si el correo no existe o la contraseña no coincide.
 *  - 429 si la cuenta está bloqueada: 3 intentos fallidos seguidos → 15 min (C-RFNN-01).
 *    El contador es el mismo de la web: fallar en la app también cuenta allá.
 *  - 403 si la cuenta está desactivada.
 *  - 200 con el JWT y los datos del usuario.
 */
fun Route.authRoutes(servicios: Servicios) {
    post("/api/login") {
        val request = call.receive<LoginRequestDto>()
        val u = when (val r = autenticar(servicios, request.username, request.password, call.ip(), request.codigo)) {
            is Autenticacion.Fallida -> { call.error(r.codigo, r.mensaje, r.clave); return@post }
            is Autenticacion.Correcta -> r.usuario
        }
        servicios.auditoria.registrar(u.email, "LOGIN", "Usuario", u.id, ip = call.ip())

        val requiere = u.rol == RolesIdentity.TUTOR && servicios.consentimientos.aceptado(u.id, servicios.config.versionAviso) == null
        call.respond(
            LoginResponseDto(
                token = servicios.tokens.crear(u),
                user = UserDto(username = u.email, fullName = u.nombre, role = u.rolApp, id = u.id, requiereConsentimiento = requiere),
            )
        )
    }
}

sealed interface Autenticacion {
    data class Correcta(val usuario: Usuario) : Autenticacion
    /** [clave]: para que la app reaccione sin leer el texto (REQUIERE_2FA, CODIGO_2FA_INVALIDO, ACTIVAR_2FA). */
    data class Fallida(val codigo: HttpStatusCode, val mensaje: String, val clave: String? = null) : Autenticacion
}

/**
 * Verifica correo y contraseña contra Identity con las MISMAS reglas que el portal:
 * bloqueo tras 3 fallos (C-RFNN-01), cuenta desactivada, cuenta sin rol. Registra los
 * fallos en la bitácora. Lo usan el login de la app y la vinculación de equipos de escritorio.
 */
suspend fun autenticar(servicios: Servicios, usuario: String, password: String, ip: String, codigo: String? = null): Autenticacion {
    val login = usuario.trim()
    if (login.isEmpty() || password.isEmpty()) return Autenticacion.Fallida(HttpStatusCode.BadRequest, "Escribe tu correo y tu contraseña")
    val ahora = servicios.reloj()
    val cred = servicios.usuarios.credenciales(login)
    if (cred == null) {
        // Se calcula un hash igual: la respuesta tarda lo mismo exista o no la cuenta.
        PasswordIdentity.verificar(HASH_SEÑUELO, password)
        servicios.auditoria.registrar(login.take(256), "LOGIN_FALLIDO", detalle = "Cuenta inexistente", ip = ip, exito = false)
        return Autenticacion.Fallida(HttpStatusCode.Unauthorized, "Credenciales inválidas")
    }
    val u = cred.usuario
    val bloqueo = cred.bloqueadaHasta?.takeIf { cred.bloqueoHabilitado && it.isAfter(ahora) }
    if (bloqueo != null) {
        val minutos = Duration.between(ahora, bloqueo).toMinutes() + 1
        return Autenticacion.Fallida(HttpStatusCode.TooManyRequests, "Cuenta bloqueada por intentos fallidos. Intenta de nuevo en $minutos min.")
    }
    if (!PasswordIdentity.verificar(cred.passwordHash, password)) {
        val bloqueada = servicios.usuarios.registrarFallo(u.id, servicios.config.loginIntentosMax, servicios.config.loginBloqueoMin, ahora)
        servicios.auditoria.registrar(u.email, if (bloqueada) "CUENTA_BLOQUEADA" else "LOGIN_FALLIDO", "Usuario", u.id, ip = ip, exito = false)
        return if (bloqueada) Autenticacion.Fallida(HttpStatusCode.TooManyRequests, "Cuenta bloqueada por intentos fallidos. Intenta de nuevo en ${servicios.config.loginBloqueoMin} min.")
        else Autenticacion.Fallida(HttpStatusCode.Unauthorized, "Credenciales inválidas")
    }
    if (!u.activo) return Autenticacion.Fallida(HttpStatusCode.Forbidden, "Tu cuenta está desactivada. Comunícate con la escuela.")
    if (u.rolApp == "DESCONOCIDO") return Autenticacion.Fallida(HttpStatusCode.Forbidden, "Tu cuenta no tiene un rol asignado. Comunícate con la escuela.")
    // Verificación en dos pasos: con una contraseña robada no basta.
    if (u.dosPasos) {
        if (codigo.isNullOrBlank())
            return Autenticacion.Fallida(HttpStatusCode.Unauthorized, "Escribe el código de 6 dígitos de tu app autenticadora.", "REQUIERE_2FA")
        val clave = servicios.usuarios.claveAutenticador(u.id)
        if (clave == null || !Totp.valido(clave, codigo, ahora)) {
            // Un código equivocado cuenta como intento fallido (mismo bloqueo de 3 intentos).
            val bloqueada = servicios.usuarios.registrarFallo(u.id, servicios.config.loginIntentosMax, servicios.config.loginBloqueoMin, ahora)
            servicios.auditoria.registrar(u.email, if (bloqueada) "CUENTA_BLOQUEADA" else "LOGIN_2FA_FALLIDO", "Usuario", u.id, ip = ip, exito = false)
            return if (bloqueada) Autenticacion.Fallida(HttpStatusCode.TooManyRequests, "Cuenta bloqueada por intentos fallidos. Intenta de nuevo en ${servicios.config.loginBloqueoMin} min.")
            else Autenticacion.Fallida(HttpStatusCode.Unauthorized, "El código no es correcto o ya venció. Escribe el que aparece ahora en tu app.", "CODIGO_2FA_INVALIDO")
        }
    } else if (u.esPersonal && servicios.config.exigir2faPersonal) {
        return Autenticacion.Fallida(HttpStatusCode.Forbidden,
            "Por seguridad, activa primero la verificación en dos pasos en el portal web (menú de tu cuenta → Verificación en dos pasos).", "ACTIVAR_2FA")
    }
    servicios.usuarios.registrarExito(u.id)
    return Autenticacion.Correcta(u)
}

/** Hash Identity de una contraseña al azar: solo para igualar tiempos de respuesta. */
private val HASH_SEÑUELO = PasswordIdentity.hash(java.util.UUID.randomUUID().toString())

/**
 *  GET  /api/consentimiento   ¿Ya aceptó la versión vigente del aviso? (C-RC-08, C-RS-07)
 *  POST /api/consentimiento   {"version": "2026-11", "acepto": true}
 *  POST /api/reportes         Falla o duda desde la app (también sin sesión, desde el login)
 */
fun Route.consentimientoRoutes(servicios: Servicios) {
    get("/api/consentimiento") {
        val s = call.sesion()
        val version = servicios.config.versionAviso
        val en = servicios.consentimientos.aceptado(s.id, version)
        val portal = servicios.config.urlPortal.trimEnd('/')
        call.respond(ConsentimientoDto(version, en != null, en?.iso(), "$portal/Privacidad/Aviso", "$portal/Privacidad/Terminos"))
    }

    post("/api/consentimiento") {
        val s = call.sesion()
        val e = call.receive<AceptarConsentimientoDto>()
        if (!e.acepto || e.version != servicios.config.versionAviso) {
            call.error(HttpStatusCode.BadRequest, "Debes aceptar la versión vigente del aviso (${servicios.config.versionAviso}).")
            return@post
        }
        if (servicios.consentimientos.aceptado(s.id, e.version) == null) {
            servicios.consentimientos.aceptar(s.id, e.version, call.ip(), servicios.reloj())
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private val TIPOS_REPORTE = mapOf("FALLA" to "Falla", "DUDA" to "Duda")
private val PLATAFORMAS_REPORTE = setOf("ANDROID", "IOS")

fun Route.reportesRoutes(servicios: Servicios) {
    post("/api/reportes") {
        val s = call.sesionOpcional()
        val e = call.receive<ReporteEntradaDto>()
        val tipo = TIPOS_REPORTE[e.tipo.trim().uppercase()]
        val mensaje = e.mensaje.trim()
        val correo = e.correo?.trim()?.ifBlank { null } ?: s?.email
        val problema = when {
            tipo == null -> "tipo debe ser FALLA o DUDA"
            mensaje.length < 10 -> "Cuéntanos qué pasó (al menos 10 caracteres)."
            mensaje.length > 1000 -> "El mensaje no puede pasar de 1000 caracteres."
            correo != null && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(correo) -> "El correo no es válido."
            s == null && correo == null -> "Escribe un correo para poder responderte."
            else -> null
        }
        if (problema != null) {
            call.error(HttpStatusCode.BadRequest, problema)
            return@post
        }
        val plataforma = e.plataforma.trim().uppercase().takeIf { it in PLATAFORMAS_REPORTE } ?: "ANDROID"
        val id = servicios.consentimientos.reportar(s?.id, correo, tipo!!, mensaje, plataforma.lowercase(), servicios.reloj())
        servicios.despachador.despertar()
        call.respond(HttpStatusCode.Created, ReporteRecibidoDto(id))
    }
}

/**
 * "¿Olvidaste tu contraseña?" desde la app (C-RFNN-02). Mismas reglas y tabla que el portal:
 * un código pedido aquí también sirve en la web.
 *  POST /api/recuperar/solicitar   {"cuenta": "correo o celular"} → 202 (igual exista o no la cuenta)
 *  POST /api/recuperar/restablecer {"cuenta", "codigo", "password"} → 204, o 400 con
 *       codigo = CODIGO_INCORRECTO | CODIGO_VENCIDO | PASSWORD_INVALIDA
 */
fun Route.recuperacionRoutes(servicios: Servicios) {
    post("/api/recuperar/solicitar") {
        val cuenta = call.receive<SolicitarCodigoDto>().cuenta.trim()
        if ('@' !in cuenta && !RecuperacionPassword.esTelefono(cuenta)) {
            call.error(HttpStatusCode.BadRequest, "Escribe tu correo o tu celular de 10 dígitos.")
            return@post
        }
        servicios.recuperacion.solicitar(cuenta, call.ip())
        val porSms = RecuperacionPassword.esTelefono(cuenta) && servicios.recuperacion.smsDisponible
        val donde = when {
            porSms -> "por SMS a tu celular"
            RecuperacionPassword.esTelefono(cuenta) -> "al correo registrado en tu cuenta"
            else -> "a tu correo"
        }
        call.respond(
            HttpStatusCode.Accepted,
            CodigoSolicitadoDto(
                mensaje = "Si los datos corresponden a una cuenta, te enviamos un código de 6 dígitos $donde. " +
                    "Vence en ${RecuperacionPassword.MINUTOS_VIGENCIA} minutos.",
                canal = if (porSms) "sms" else "correo",
                minutosVigencia = RecuperacionPassword.MINUTOS_VIGENCIA.toInt(),
            )
        )
    }

    post("/api/recuperar/restablecer") {
        val e = call.receive<RestablecerPasswordDto>()
        if (e.cuenta.isBlank() || e.codigo.isBlank() || e.password.isEmpty()) {
            call.error(HttpStatusCode.BadRequest, "Escribe tu correo o celular, el código y la contraseña nueva.")
            return@post
        }
        val r = servicios.recuperacion.restablecer(e.cuenta, e.codigo, e.password, call.ip())
        if (r.exito) call.respond(HttpStatusCode.NoContent)
        else call.error(HttpStatusCode.BadRequest, r.mensaje ?: "No se pudo cambiar la contraseña.", r.clave)
    }
}
