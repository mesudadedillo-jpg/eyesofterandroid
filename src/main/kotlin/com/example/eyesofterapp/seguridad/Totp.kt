package com.example.eyesofterapp.seguridad

import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Códigos de verificación en dos pasos (TOTP, RFC 6238): 6 dígitos, cambian cada 30 s.
 * Compatible con ASP.NET Identity: misma clave (Base32, tabla asp_net_user_tokens, nombre
 * "AuthenticatorKey"), HMAC-SHA1 y la misma tolerancia de ±2 intervalos (±60 s) para relojes
 * desfasados. Así el código de la app autenticadora sirve igual en el portal y en la app.
 */
object Totp {
    private const val ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun valido(claveBase32: String, codigo: String, ahora: Instant): Boolean {
        val limpio = codigo.filter(Char::isDigit)
        if (limpio.length != 6) return false
        val clave = runCatching { base32(claveBase32) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val paso = ahora.epochSecond / 30
        // Comparación sin atajos en todos los intervalos: el tiempo no revela cuál coincidió.
        var coincide = false
        for (i in -2L..2L) coincide = coincide or java.security.MessageDigest.isEqual(generar(clave, paso + i).toByteArray(), limpio.toByteArray())
        return coincide
    }

    fun generar(clave: ByteArray, contador: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(clave, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(contador).array())
        val o = h[h.size - 1].toInt() and 0x0f
        val binario = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (binario % 1_000_000).toString().padStart(6, '0')
    }

    fun base32(texto: String): ByteArray {
        val limpio = texto.uppercase().filter { it != '=' && !it.isWhitespace() }
        val salida = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in limpio) {
            val v = ALFABETO.indexOf(c)
            require(v >= 0) { "Base32 inválido" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                salida.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return salida.toByteArray()
    }
}
