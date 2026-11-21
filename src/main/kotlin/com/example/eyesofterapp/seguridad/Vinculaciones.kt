package com.example.eyesofterapp.seguridad

import com.example.eyesofterapp.repos.Usuario
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Solicitudes para vincular un equipo de escritorio (como cuando una tele pide "entra a
 * esta página y escribe el código").
 *
 *  1. El escritorio pide una solicitud: recibe un [Solicitud.id] secreto (solo lo conoce
 *     él) y un [Solicitud.codigo] corto que se muestra en pantalla.
 *  2. Se abre el navegador en /escritorio/vincular?codigo=…; alguien de Dirección o
 *     Enfermería inicia sesión con su cuenta del portal y aprueba.
 *  3. El escritorio pregunta con su id secreto y, una sola vez, recibe su token.
 *
 * Viven en memoria y duran [vigencia]: si el servidor se reinicia, basta con volver a
 * pulsar "Vincular". Nunca se guarda aquí una contraseña ni el token.
 */
class Vinculaciones(
    private val reloj: () -> Instant,
    private val vigencia: Duration = Duration.ofMinutes(10),
    private val maximo: Int = 500,
) {
    class Solicitud(val id: String, val codigo: String, val equipo: String, val expira: Instant) {
        @Volatile var aprobadaPor: Usuario? = null
    }

    sealed interface Estado {
        data object Pendiente : Estado
        data class Aprobada(val usuario: Usuario, val equipo: String) : Estado
        /** Venció, ya se canjeó o nunca existió: hay que empezar de nuevo. */
        data object Inexistente : Estado
    }

    private val porId = ConcurrentHashMap<String, Solicitud>()
    private val azar = SecureRandom()

    val vigenciaSegundos: Long get() = vigencia.seconds

    /** null si hay demasiadas solicitudes abiertas (alguien está abusando del servicio). */
    fun crear(equipo: String): Solicitud? {
        limpiar()
        if (porId.size >= maximo) return null
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(azar::nextBytes))
        var codigo: String
        do { codigo = nuevoCodigo() } while (porId.values.any { it.codigo == codigo })
        return Solicitud(id, codigo, equipo, reloj().plus(vigencia)).also { porId[id] = it }
    }

    /** La solicitud vigente y sin aprobar con ese código (lo que se escribe en el navegador). */
    fun porCodigo(codigo: String?): Solicitud? {
        val buscado = normalizar(codigo) ?: return null
        val ahora = reloj()
        return porId.values.firstOrNull { it.codigo == buscado && it.expira.isAfter(ahora) && it.aprobadaPor == null }
    }

    fun aprobar(solicitud: Solicitud, usuario: Usuario) { solicitud.aprobadaPor = usuario }

    /** Lo pregunta el escritorio. Si ya se aprobó, la solicitud se borra: el token se entrega UNA vez. */
    fun canjear(id: String?): Estado {
        if (id.isNullOrBlank()) return Estado.Inexistente
        val s = porId[id] ?: return Estado.Inexistente
        if (!s.expira.isAfter(reloj())) { porId.remove(id); return Estado.Inexistente }
        val usuario = s.aprobadaPor ?: return Estado.Pendiente
        return if (porId.remove(id, s)) Estado.Aprobada(usuario, s.equipo) else Estado.Inexistente
    }

    private fun limpiar() {
        val ahora = reloj()
        porId.values.removeIf { !it.expira.isAfter(ahora) }
    }

    private fun nuevoCodigo(): String {
        val c = CharArray(8) { ALFABETO[azar.nextInt(ALFABETO.length)] }
        return String(c, 0, 4) + "-" + String(c, 4, 4)
    }

    companion object {
        /** Sin letras que se confunden (0/O, 1/I/L): el código se lee en voz alta o se copia a mano. */
        private const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun normalizar(codigo: String?): String? {
            val limpio = codigo?.uppercase()?.filter { it in ALFABETO } ?: return null
            return if (limpio.length == 8) limpio.substring(0, 4) + "-" + limpio.substring(4) else null
        }
    }
}
