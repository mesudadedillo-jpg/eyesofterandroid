package com.example.eyesofterapp.seguridad

import com.example.eyesofterapp.db.BaseDeDatos
import com.example.eyesofterapp.db.ejecutar
import com.example.eyesofterapp.db.instante
import com.example.eyesofterapp.db.uno
import com.example.eyesofterapp.repos.Usuario
import com.example.eyesofterapp.repos.UsuarioRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Solicitudes para vincular un equipo de escritorio (como cuando una tele pide "entra a
 * esta página y escribe el código").
 *
 *  1. El escritorio pide una solicitud: recibe un secreto (solo lo conoce él) y un
 *     [Solicitud.codigo] corto que se muestra en pantalla.
 *  2. Se abre el navegador en /escritorio/vincular?codigo=…; alguien de Dirección o
 *     Enfermería inicia sesión con su cuenta del portal y aprueba.
 *  3. El escritorio pregunta con su secreto y, una sola vez, recibe su token.
 *
 * Viven en la tabla vinculaciones_escritorio (la crea el portal) para que funcione con
 * varias instancias del servidor: el código puede pedirse en una y aprobarse en otra.
 * Se guarda el SHA-256 del secreto, nunca el secreto, ni contraseñas, ni el token.
 */
class Vinculaciones(
    private val db: BaseDeDatos,
    private val usuarios: UsuarioRepository,
    private val reloj: () -> Instant,
    private val vigencia: Duration = Duration.ofMinutes(10),
    private val maximo: Int = 500,
) {
    /** [id] es el SHA-256 del secreto. */
    data class Solicitud(val id: String, val codigo: String, val equipo: String, val expira: Instant)

    /** Lo que recibe el escritorio al pedir la solicitud. */
    data class Creada(val secreto: String, val solicitud: Solicitud)

    sealed interface Estado {
        data object Pendiente : Estado
        data class Aprobada(val usuario: Usuario, val equipo: String) : Estado
        /** Venció, ya se canjeó o nunca existió: hay que empezar de nuevo. */
        data object Inexistente : Estado
    }

    private val azar = SecureRandom()

    val vigenciaSegundos: Long get() = vigencia.seconds

    /** null si hay demasiadas solicitudes abiertas (alguien está abusando del servicio). */
    suspend fun crear(equipo: String): Creada? = db.transaccion { c ->
        val ahora = reloj()
        c.ejecutar("delete from vinculaciones_escritorio where expira_en <= ?", ahora)
        val abiertas = c.uno("select count(*) from vinculaciones_escritorio") { it.getInt(1) } ?: 0
        if (abiertas >= maximo) return@transaccion null
        val secreto = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(azar::nextBytes))
        val id = huella(secreto)
        val expira = ahora.plus(vigencia)
        // El código es único entre las solicitudes abiertas (índice único): si choca, se genera otro.
        repeat(8) {
            val codigo = nuevoCodigo()
            val insertada = c.uno(
                """insert into vinculaciones_escritorio (id, codigo, equipo, creada_en, expira_en) values (?, ?, ?, ?, ?)
                   on conflict (codigo) do nothing returning id""",
                id, codigo, equipo, ahora, expira
            ) { it.getString(1) }
            if (insertada != null) return@transaccion Creada(secreto, Solicitud(id, codigo, equipo, expira))
        }
        null
    }

    /** La solicitud vigente y sin aprobar con ese código (lo que se escribe en el navegador). */
    suspend fun porCodigo(codigo: String?): Solicitud? {
        val buscado = normalizar(codigo) ?: return null
        val ahora = reloj()
        return db.leer { c ->
            c.uno("select * from vinculaciones_escritorio where codigo = ? and expira_en > ? and aprobada_por is null", buscado, ahora) {
                Solicitud(it.getString("id"), it.getString("codigo"), it.getString("equipo"), it.instante("expira_en")!!)
            }
        }
    }

    /** false si mientras tanto venció o alguien más la aprobó. */
    suspend fun aprobar(solicitud: Solicitud, usuario: Usuario): Boolean = db.transaccion { c ->
        c.ejecutar(
            "update vinculaciones_escritorio set aprobada_por = ? where id = ? and aprobada_por is null and expira_en > ?",
            usuario.id, solicitud.id, reloj()
        ) == 1
    }

    /** Lo pregunta el escritorio. Si ya se aprobó, la solicitud se borra: el token se entrega UNA vez. */
    suspend fun canjear(secreto: String?): Estado {
        if (secreto.isNullOrBlank() || secreto.length > 100) return Estado.Inexistente
        val id = huella(secreto)
        val ahora = reloj()
        val resultado = db.transaccion { c ->
            val aprobada = c.uno(
                "delete from vinculaciones_escritorio where id = ? and aprobada_por is not null and expira_en > ? returning aprobada_por, equipo",
                id, ahora
            ) { it.getString("aprobada_por") to it.getString("equipo") }
            when {
                aprobada != null -> aprobada
                c.uno("select 1 from vinculaciones_escritorio where id = ? and expira_en > ?", id, ahora) { true } == true -> PENDIENTE
                else -> null
            }
        } ?: return Estado.Inexistente
        if (resultado === PENDIENTE) return Estado.Pendiente
        val usuario = usuarios.porId(resultado.first) ?: return Estado.Inexistente
        return Estado.Aprobada(usuario, resultado.second)
    }

    private fun nuevoCodigo(): String {
        val c = CharArray(8) { ALFABETO[azar.nextInt(ALFABETO.length)] }
        return String(c, 0, 4) + "-" + String(c, 4, 4)
    }

    companion object {
        /** Sin letras que se confunden (0/O, 1/I/L): el código se lee en voz alta o se copia a mano. */
        private const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        private val PENDIENTE = "" to ""

        fun normalizar(codigo: String?): String? {
            val limpio = codigo?.uppercase()?.filter { it in ALFABETO } ?: return null
            return if (limpio.length == 8) limpio.substring(0, 4) + "-" + limpio.substring(4) else null
        }

        fun huella(secreto: String): String =
            MessageDigest.getInstance("SHA-256").digest(secreto.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
