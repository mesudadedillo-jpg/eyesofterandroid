package com.example.eyesofterapp.seguridad

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Verifica (y genera, para pruebas) contraseñas en el formato de ASP.NET Core Identity v3,
 * que es como el portal web guarda las contraseñas (C-RC-05: hash seguro, nunca en claro).
 *
 * Formato (Base64): 0x01 | prf (4 bytes, big-endian; 1 = HMAC-SHA256, 2 = HMAC-SHA512)
 *                   | iteraciones (4) | largo de sal (4) | sal | subclave
 * .NET 10 usa HMAC-SHA512 con 100 000 iteraciones, sal de 16 bytes y subclave de 32.
 */
object PasswordIdentity {

    fun verificar(hashBase64: String?, password: String): Boolean {
        if (hashBase64.isNullOrBlank()) return false
        val h = try { Base64.getDecoder().decode(hashBase64) } catch (_: IllegalArgumentException) { return false }
        if (h.size < 13 || h[0] != 0x01.toByte()) return false
        val prf = leerEntero(h, 1)
        val iteraciones = leerEntero(h, 5)
        val largoSal = leerEntero(h, 9)
        if (largoSal < 16 || 13 + largoSal >= h.size || iteraciones <= 0) return false
        val sal = h.copyOfRange(13, 13 + largoSal)
        val esperado = h.copyOfRange(13 + largoSal, h.size)
        val algoritmo = when (prf) {
            0 -> "PBKDF2WithHmacSHA1"
            1 -> "PBKDF2WithHmacSHA256"
            2 -> "PBKDF2WithHmacSHA512"
            else -> return false
        }
        val calculado = pbkdf2(algoritmo, password, sal, iteraciones, esperado.size)
        return MessageDigest.isEqual(calculado, esperado) // tiempo constante
    }

    /** Mismo resultado que PasswordHasher de .NET 10. Se usa en las pruebas y en datos de ejemplo. */
    fun hash(password: String, iteraciones: Int = 100_000): String {
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val subclave = pbkdf2("PBKDF2WithHmacSHA512", password, sal, iteraciones, 32)
        val salida = ByteArray(13 + sal.size + subclave.size)
        salida[0] = 0x01
        escribirEntero(salida, 1, 2)
        escribirEntero(salida, 5, iteraciones)
        escribirEntero(salida, 9, sal.size)
        sal.copyInto(salida, 13)
        subclave.copyInto(salida, 13 + sal.size)
        return Base64.getEncoder().encodeToString(salida)
    }

    private fun pbkdf2(algoritmo: String, password: String, sal: ByteArray, iteraciones: Int, bytes: Int): ByteArray =
        SecretKeyFactory.getInstance(algoritmo).generateSecret(PBEKeySpec(password.toCharArray(), sal, iteraciones, bytes * 8)).encoded

    private fun leerEntero(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF shl 24) or (b[i + 1].toInt() and 0xFF shl 16) or (b[i + 2].toInt() and 0xFF shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun escribirEntero(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
}
