package com.example.eyesofterapp.routes

import com.example.eyesofterapp.Servicios
import com.example.eyesofterapp.model.VinculacionCreadaDto
import com.example.eyesofterapp.model.VinculacionEntradaDto
import com.example.eyesofterapp.model.VinculacionEstadoDto
import com.example.eyesofterapp.model.VinculacionEstadoEntradaDto
import com.example.eyesofterapp.seguridad.Vinculaciones
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Vincular un equipo de escritorio con una cuenta del portal (Dirección o Enfermería).
 * Así, instalar el escritorio y entrar con "root" NO da acceso a los datos de la escuela:
 * hace falta que alguien de la escuela lo autorice con SU cuenta, en el navegador.
 *
 *  POST /api/escritorio/vinculaciones          (escritorio) Pide un código. Con límite por IP.
 *  POST /api/escritorio/vinculaciones/estado   (escritorio) ¿Ya lo aprobaron? Entrega el token una vez.
 *  GET  /escritorio/vincular?codigo=           (navegador)  Página para iniciar sesión y aprobar.
 *  POST /escritorio/vincular                   (navegador)  Envío del formulario. Con límite por IP.
 */
fun Route.vinculacionRoutes(servicios: Servicios) {
    post("/api/escritorio/vinculaciones") {
        val e = runCatching { call.receive<VinculacionEntradaDto>() }.getOrElse { VinculacionEntradaDto() }
        val equipo = e.equipo.trim().filter { !it.isISOControl() }.take(60).ifBlank { "Equipo de escritorio" }
        val s = servicios.vinculaciones.crear(equipo)
        if (s == null) {
            call.error(HttpStatusCode.ServiceUnavailable, "Hay demasiadas solicitudes de vinculación abiertas. Intenta en unos minutos.")
            return@post
        }
        call.respond(HttpStatusCode.Created, VinculacionCreadaDto(s.id, s.codigo, "/escritorio/vincular?codigo=${s.codigo}", servicios.vinculaciones.vigenciaSegundos))
    }
}

/** El escritorio pregunta cada pocos segundos; va fuera del límite por IP (lo protege el id secreto de 256 bits). */
fun Route.vinculacionEstadoRoutes(servicios: Servicios) {
    post("/api/escritorio/vinculaciones/estado") {
        val e = runCatching { call.receive<VinculacionEstadoEntradaDto>() }.getOrElse { VinculacionEstadoEntradaDto() }
        when (val r = servicios.vinculaciones.canjear(e.solicitud)) {
            Vinculaciones.Estado.Pendiente -> call.respond(HttpStatusCode.Accepted, VinculacionEstadoDto("PENDIENTE"))
            Vinculaciones.Estado.Inexistente -> call.error(HttpStatusCode.Gone, "La solicitud venció o ya se usó. Pulsa «Vincular este equipo» otra vez.", "VINCULACION_EXPIRADA")
            is Vinculaciones.Estado.Aprobada -> call.respond(
                VinculacionEstadoDto("APROBADA", servicios.tokens.crearEscritorio(r.usuario, r.equipo), r.usuario.email, r.usuario.nombre, r.equipo)
            )
        }
    }

    get("/escritorio/vincular") {
        val codigo = call.request.queryParameters["codigo"]
        val s = servicios.vinculaciones.porCodigo(codigo)
        call.pagina(
            if (s == null) formularioCodigo(if (codigo.isNullOrBlank()) null else "Ese código no existe o ya venció. Revisa el que aparece en el escritorio.")
            else formularioAcceso(s, null, "")
        )
    }
}

/** Envío del formulario: dentro del límite por IP, igual que el login. */
fun Route.vinculacionFormularioRoutes(servicios: Servicios) {
    post("/escritorio/vincular") {
        val p = call.receiveParameters()
        val s = servicios.vinculaciones.porCodigo(p["codigo"])
        if (s == null) {
            call.pagina(formularioCodigo("La solicitud venció o ya se aprobó. Pulsa «Vincular este equipo» otra vez en el escritorio."), HttpStatusCode.Gone)
            return@post
        }
        val correo = p["correo"].orEmpty()
        if (p["confirmo"] != "si") {
            call.pagina(formularioAcceso(s, "Confirma que el código coincide con el que ves en la pantalla del escritorio.", correo), HttpStatusCode.BadRequest)
            return@post
        }
        val u = when (val r = autenticar(servicios, correo, p["password"].orEmpty(), call.ip())) {
            is Autenticacion.Fallida -> {
                call.pagina(formularioAcceso(s, r.mensaje, correo), r.codigo)
                return@post
            }
            is Autenticacion.Correcta -> r.usuario
        }
        if (!u.esPersonal) {
            servicios.auditoria.registrar(u.email, "VINCULAR_EQUIPO", detalle = "Rechazado: la cuenta no es de Dirección ni Enfermería (equipo ${s.equipo})",
                ip = call.ip(), origen = "escritorio", exito = false)
            call.pagina(formularioAcceso(s, "Solo Dirección o Enfermería pueden autorizar un equipo de escritorio.", correo), HttpStatusCode.Forbidden)
            return@post
        }
        servicios.vinculaciones.aprobar(s, u)
        servicios.auditoria.registrar(u.email, "VINCULAR_EQUIPO", detalle = "Equipo «${s.equipo}» (código ${s.codigo})", ip = call.ip(), origen = "escritorio")
        call.pagina(listo(s, u.nombre))
    }
}

// ---------------------------------------------------------------------------- página HTML

private suspend fun ApplicationCall.pagina(cuerpo: String, codigo: HttpStatusCode = HttpStatusCode.OK) {
    // Nada externo, no se puede incrustar en otra página (clickjacking) y el formulario solo va a este servidor.
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
    response.headers.append("X-Frame-Options", "DENY")
    respondText(plantilla(cuerpo), ContentType.Text.Html.withParameter("charset", "utf-8"), codigo)
}

private fun esc(t: String?) = (t ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun aviso(error: String?) = if (error == null) "" else """<p class="error" role="alert">${esc(error)}</p>"""

private fun formularioCodigo(error: String?) = """
    <h1>Vincular equipo de escritorio</h1>
    <p>Escribe el código que aparece en la aplicación de escritorio EYESOFTER.</p>
    ${aviso(error)}
    <form method="get" action="/escritorio/vincular">
      <label for="codigo">Código</label>
      <input id="codigo" name="codigo" autocomplete="off" autocapitalize="characters" placeholder="ABCD-2345" required>
      <button type="submit">Continuar</button>
    </form>
""".trimIndent()

private fun formularioAcceso(s: Vinculaciones.Solicitud, error: String?, correo: String) = """
    <h1>Autorizar equipo de escritorio</h1>
    <p>El equipo <strong>${esc(s.equipo)}</strong> pide permiso para enviar tamizajes al portal y buscar alumnos de la escuela.</p>
    <p class="codigo" aria-label="Código de vinculación">${esc(s.codigo)}</p>
    <div class="alerta">
      <strong>Solo continúa si TÚ pulsaste «Vincular este equipo» en una computadora de la escuela</strong> y ves este mismo código en su pantalla.
      Si alguien te mandó este enlace, ciérralo: le darías acceso a los datos de los alumnos.
    </div>
    ${aviso(error)}
    <form method="post" action="/escritorio/vincular">
      <input type="hidden" name="codigo" value="${esc(s.codigo)}">
      <label for="correo">Correo de tu cuenta del portal (Dirección o Enfermería)</label>
      <input id="correo" name="correo" type="email" autocomplete="username" value="${esc(correo)}" required>
      <label for="password">Contraseña</label>
      <input id="password" name="password" type="password" autocomplete="current-password" required>
      <label class="check"><input type="checkbox" name="confirmo" value="si" required> El código coincide con el de la pantalla del escritorio</label>
      <button type="submit">Iniciar sesión y autorizar</button>
    </form>
""".trimIndent()

private fun listo(s: Vinculaciones.Solicitud, nombre: String) = """
    <h1>✓ Equipo vinculado</h1>
    <p><strong>${esc(s.equipo)}</strong> quedó autorizado por ${esc(nombre)}.</p>
    <p>Ya puedes cerrar esta pestaña y volver a la aplicación de escritorio.</p>
    <p class="nota">Para retirar el acceso de un equipo, cambia la contraseña de la cuenta que lo autorizó o desactívala en el portal.</p>
""".trimIndent()

private fun plantilla(cuerpo: String) = """
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Vincular equipo · EYESOFTER</title>
<style>
  :root { color-scheme: light dark; --fondo:#f4f6fb; --tarjeta:#fff; --texto:#1b2333; --suave:#5b6577; --marca:#1f5fbf; --borde:#d5dbe6; --error:#b3261e; --alerta:#fff4d6; --alerta-borde:#e0b400; }
  @media (prefers-color-scheme: dark) { :root { --fondo:#11151d; --tarjeta:#1b2130; --texto:#e8ecf4; --suave:#a7b0c2; --marca:#7aa7ff; --borde:#334058; --error:#ff8a80; --alerta:#3a3116; --alerta-borde:#a88a1c; } }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--fondo); color:var(--texto); font:16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; padding:16px; }
  main { max-width:460px; margin:6vh auto; background:var(--tarjeta); border:1px solid var(--borde); border-radius:14px; padding:28px; }
  .marca { color:var(--marca); font-weight:700; letter-spacing:.08em; font-size:13px; margin:0 0 8px; }
  h1 { font-size:22px; margin:0 0 12px; }
  label { display:block; margin:14px 0 4px; font-weight:600; font-size:14px; }
  label.check { font-weight:400; display:flex; gap:8px; align-items:flex-start; }
  input[type=email], input[type=password], input[name=codigo]:not([type=hidden]) { width:100%; padding:10px 12px; border:1px solid var(--borde); border-radius:8px; font:inherit; background:transparent; color:inherit; }
  button { margin-top:18px; width:100%; padding:12px; border:0; border-radius:8px; background:var(--marca); color:#fff; font:inherit; font-weight:600; cursor:pointer; }
  .codigo { font:700 30px/1.2 ui-monospace, Consolas, monospace; letter-spacing:.12em; text-align:center; padding:12px; border:2px dashed var(--borde); border-radius:10px; margin:16px 0; }
  .alerta { background:var(--alerta); border:1px solid var(--alerta-borde); border-radius:8px; padding:12px; font-size:14px; }
  .error { color:var(--error); font-weight:600; }
  .nota { color:var(--suave); font-size:14px; }
</style>
</head>
<body><main><p class="marca">EYESOFTER SUITE</p>
$cuerpo
</main></body>
</html>
""".trimIndent()
