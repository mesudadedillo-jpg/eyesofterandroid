package com.example.eyesofterapp.seguridad

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.entero
import com.example.eyesofterapp.db.lista
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.notificaciones.EnvioCodigos
import com.example.eyesofterapp.repos.Auditoria
import com.example.eyesofterapp.repos.Usuario
import com.example.eyesofterapp.repos.UsuarioRepository
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** Resultado de cambiar la contraseña con el código. [clave] es la que viaja a la app en "codigo". */
data class ResultadoRecuperacion(val exito: Boolean, val clave: String? = null, val mensaje: String? = null)

/**
 * "¿Olvidaste tu contraseña?" con un código de 6 dígitos al correo o, si hay proveedor de SMS,
 * al celular (C-RFNN-02). Son las MISMAS reglas del portal (EyesofterWeb/Servicios/RecuperacionPassword.cs)
 * sobre la misma tabla codigos_recuperacion: un código pedido en la app sirve en la web y al revés.
 * Si cambias una regla aquí, cámbiala allá.
 *
 *  - Vence en 10 minutos, 5 intentos; pedir otro anula el anterior.
 *  - Máximo un código por minuto y 5 por hora por cuenta.
 *  - Solo se guarda la huella HMAC con la llave de datos, nunca el código.
 *  - La respuesta hacia fuera es la misma exista o no la cuenta.
 *  - No quita la verificación en dos pasos: el personal sigue necesitando su app autenticadora.
 */
class RecuperacionPassword(
    private val db: BaseDeDatos,
    private val usuarios: UsuarioRepository,
    private val cifrado: CifradoDatos,
    private val auditoria: Auditoria,
    private val envio: EnvioCodigos,
    private val reloj: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(RecuperacionPassword::class.java)
    private val azar = SecureRandom()

    val smsDisponible: Boolean get() = envio.smsDisponible

    /** Cuenta activa por correo o por celular (el del tutor que representa o el de la cuenta). */
    suspend fun buscar(cuenta: String): Usuario? {
        val texto = cuenta.trim()
        if (texto.isEmpty()) return null
        if (!esTelefono(texto)) return usuarios.credenciales(texto)?.usuario?.takeIf { it.activo && it.email.equals(texto, ignoreCase = true) }
        val tel = telefono10(texto)!!
        val ids = db.leer { c ->
            c.lista(
                """select distinct u.id from asp_net_users u left join tutores t on t.id = u.tutor_id
                   where u.activo and (right(regexp_replace(coalesce(u.phone_number, ''), '\D', '', 'g'), 10) = ?
                                    or right(regexp_replace(coalesce(t.telefono, ''), '\D', '', 'g'), 10) = ?)""",
                tel, tel
            ) { it.getString(1) }
        }
        if (ids.size != 1) {
            if (ids.size > 1) log.info("Recuperación por celular: el número es de ${ids.size} cuentas; se pide usar el correo")
            return null
        }
        return usuarios.porId(ids[0])
    }

    /** Manda un código nuevo si la cuenta existe y no rebasó el límite. No dice nada hacia fuera. */
    suspend fun solicitar(cuenta: String, ip: String?) {
        val u = buscar(cuenta) ?: return
        val ahora = reloj()
        val porSms = esTelefono(cuenta) && envio.smsDisponible
        if (!porSms && u.email.isBlank()) return
        val codigo = "%06d".format(azar.nextInt(1_000_000))

        val creado = db.transaccion { c ->
            val recientes = c.lista("select creado_en from codigos_recuperacion where usuario_id = ? and creado_en > ?", u.id, ahora.minusSeconds(3600)) {
                it.getTimestamp(1).toInstant()
            }
            if (recientes.size >= CODIGOS_POR_HORA || recientes.any { it.isAfter(ahora.minusSeconds(SEGUNDOS_ENTRE_CODIGOS)) }) return@transaccion false
            // Pedir otro código anula el anterior.
            c.ejecutar("update codigos_recuperacion set usado_en = ? where usuario_id = ? and usado_en is null", ahora, u.id)
            c.ejecutar(
                "insert into codigos_recuperacion (usuario_id, codigo_hash, canal, creado_en, vence_en, intentos, ip) values (?, ?, ?, ?, ?, 0, ?)",
                u.id, huella(u.id, codigo), if (porSms) "sms" else "correo", ahora, ahora.plusSeconds(MINUTOS_VIGENCIA * 60), ip?.take(45)
            )
            true
        }
        if (!creado) {
            log.info("Recuperación: límite de códigos para la cuenta ${u.id}")
            return
        }
        auditoria.registrar(u.email, "RECUPERAR_PASSWORD", "Usuario", u.id, if (porSms) "Código por SMS" else "Código por correo", ip = ip)
        val enviado = if (porSms) envio.sms(cuenta, textoSms(codigo)) else envio.correo(u.email, u.nombre, codigo)
        if (!enviado) log.warn("No se pudo enviar el código de recuperación a la cuenta ${u.id}")
    }

    /** Cambia la contraseña si el código es el vigente de esa cuenta. */
    suspend fun restablecer(cuenta: String, codigo: String, nueva: String, ip: String?): ResultadoRecuperacion {
        val u = buscar(cuenta) ?: return VENCIDO
        // Primero la contraseña: si no cumple las reglas, el código no se gasta.
        val errores = erroresPassword(nueva)
        if (errores.isNotEmpty()) return ResultadoRecuperacion(false, CLAVE_PASSWORD_INVALIDA, errores.joinToString(" "))

        val ahora = reloj()
        val limpio = codigo.filter(Char::isDigit)
        val resultado = db.transaccion { c ->
            // "for update": dos peticiones a la vez no se reparten los intentos.
            val vigente = c.uno(
                "select id, codigo_hash, intentos from codigos_recuperacion where usuario_id = ? and usado_en is null and vence_en > ? order by creado_en desc limit 1 for update",
                u.id, ahora
            ) { Triple(it.getInt("id"), it.getString("codigo_hash"), it.entero("intentos") ?: 0) } ?: return@transaccion VENCIDO
            val (id, hash, intentos) = vigente
            if (intentos >= INTENTOS_MAX) return@transaccion ResultadoRecuperacion(false, CLAVE_CODIGO_VENCIDO, "Ese código ya no sirve por demasiados intentos. Pide uno nuevo.")
            c.ejecutar("update codigos_recuperacion set intentos = intentos + 1 where id = ?", id)
            if (!MessageDigest.isEqual(huella(u.id, limpio).toByteArray(), hash.toByteArray())) {
                val quedan = INTENTOS_MAX - (intentos + 1)
                return@transaccion ResultadoRecuperacion(false, CLAVE_CODIGO_INCORRECTO, when {
                    quedan > 1 -> "El código no es correcto. Te quedan $quedan intentos."
                    quedan == 1 -> "El código no es correcto. Te queda 1 intento."
                    else -> "El código no es correcto y ya no sirve. Pide uno nuevo."
                })
            }
            c.ejecutar("update codigos_recuperacion set usado_en = ? where id = ?", ahora, id)
            // Nuevo sello de seguridad: se cierran las sesiones abiertas en la app y en el portal.
            c.ejecutar(
                "update asp_net_users set password_hash = ?, security_stamp = ?, concurrency_stamp = ?, access_failed_count = 0, lockout_end = null where id = ?",
                PasswordIdentity.hash(nueva), nuevoSello(), UUID.randomUUID().toString(), u.id
            )
            ResultadoRecuperacion(true)
        }
        if (resultado.exito) auditoria.registrar(u.email, "PASSWORD_RESTABLECIDA", "Usuario", u.id, "Con código de recuperación", ip = ip)
        else if (resultado.clave == CLAVE_CODIGO_INCORRECTO) auditoria.registrar(u.email, "RECUPERAR_CODIGO_FALLIDO", "Usuario", u.id, ip = ip, exito = false)
        return resultado
    }

    private fun huella(usuarioId: String, codigo: String) = cifrado.firma(PROPOSITO, "$usuarioId:$codigo")

    /** Como el SecurityStamp de Identity: 32 caracteres Base32. */
    private fun nuevoSello(): String {
        val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        return (1..32).map { alfabeto[azar.nextInt(32)] }.joinToString("")
    }

    companion object {
        const val MINUTOS_VIGENCIA = 10L
        const val INTENTOS_MAX = 5
        const val SEGUNDOS_ENTRE_CODIGOS = 60L
        const val CODIGOS_POR_HORA = 5
        private const val PROPOSITO = "recuperacion"

        const val CLAVE_CODIGO_INCORRECTO = "CODIGO_INCORRECTO"
        const val CLAVE_CODIGO_VENCIDO = "CODIGO_VENCIDO"
        const val CLAVE_PASSWORD_INVALIDA = "PASSWORD_INVALIDA"

        private val VENCIDO = ResultadoRecuperacion(false, CLAVE_CODIGO_VENCIDO, "El código no es válido o ya venció. Pide uno nuevo.")

        /** Lo que no es correo y tiene al menos 10 dígitos se toma como celular. */
        fun esTelefono(cuenta: String) = '@' !in cuenta && cuenta.count(Char::isDigit) >= 10
        fun telefono10(cuenta: String) = cuenta.filter(Char::isDigit).takeIf { it.length >= 10 }?.takeLast(10)

        fun textoSms(codigo: String) =
            "EYESOFTER: tu código para cambiar tu contraseña es $codigo. Vence en $MINUTOS_VIGENCIA min. No lo compartas con nadie."

        /** Mismas reglas y textos que Identity en el portal (Program.cs e IdentityErroresEspanol). */
        fun erroresPassword(p: String): List<String> = buildList {
            if (p.length < 8) add("La contraseña debe tener al menos 8 caracteres.")
            if (p.length > 64) add("La contraseña no puede pasar de 64 caracteres.")
            if (p.none { it in '0'..'9' }) add("La contraseña debe incluir al menos un número (0-9).")
            if (p.none(Char::isUpperCase)) add("La contraseña debe incluir al menos una letra mayúscula.")
            if (p.none(Char::isLowerCase)) add("La contraseña debe incluir al menos una letra minúscula.")
        }
    }
}
