package com.example.eyesofterapp.seguridad

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de los datos sensibles de los alumnos (C-RC-04) con AES-256-GCM.
 *
 * Es el MISMO formato del portal web (eyesofterweb/portal/EyesofterWeb/Seguridad/CifradoDatos.cs),
 * porque los dos leen y escriben las mismas columnas:
 *   texto:  "v1:" + Base64( nonce[12] || cifrado || etiqueta[16] )
 *   bytes:  "E1" + nonce[12] || cifrado || etiqueta[16]
 * La llave (32 bytes en Base64) viene de EYESOFTER_CLAVE_DATOS y debe ser la misma en ambos.
 */
class CifradoDatos(claveBase64: String) {
    private val clave = Base64.getDecoder().decode(claveBase64).also {
        require(it.size == 32) { "EYESOFTER_CLAVE_DATOS debe ser de 32 bytes en Base64 (openssl rand -base64 32)" }
    }
    private val azar = SecureRandom()

    fun cifrar(texto: String?): String? =
        texto?.let { PREFIJO_TEXTO + Base64.getEncoder().encodeToString(cifrarBytes(it.toByteArray(Charsets.UTF_8))) }

    /** Sin prefijo = dato anterior al cifrado: se devuelve tal cual. */
    fun descifrar(valor: String?): String? {
        if (valor == null || !valor.startsWith(PREFIJO_TEXTO)) return valor
        return String(descifrarBytes(Base64.getDecoder().decode(valor.removePrefix(PREFIJO_TEXTO))), Charsets.UTF_8)
    }

    fun cifrarArchivo(datos: ByteArray): ByteArray = PREFIJO_BYTES + cifrarBytes(datos)

    fun descifrarArchivo(valor: ByteArray): ByteArray =
        if (valor.size >= 2 && valor[0] == PREFIJO_BYTES[0] && valor[1] == PREFIJO_BYTES[1]) descifrarBytes(valor.copyOfRange(2, valor.size))
        else valor

    /**
     * Huella HMAC-SHA256 (hex en minúsculas) de "proposito\ntexto" con la llave de datos.
     * La MISMA que CifradoDatos.Firma del portal: así los dos comparan los códigos de recuperación.
     */
    fun firma(proposito: String, texto: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(clave, "HmacSHA256")) }
        return mac.doFinal("$proposito\n$texto".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun cifrarBytes(datos: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { azar.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(datos) // Java pone la etiqueta al final, igual que el formato de .NET
    }

    private fun descifrarBytes(paquete: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, paquete, 0, 12))
        return c.doFinal(paquete, 12, paquete.size - 12)
    }

    companion object {
        private const val PREFIJO_TEXTO = "v1:"
        private val PREFIJO_BYTES = byteArrayOf(0x45, 0x31)

        /** Llave SOLO para desarrollo y pruebas (la misma del portal en desarrollo). */
        const val CLAVE_DESARROLLO = "ZGVzYXJyb2xsby1zb2xvLW5vLXVzYXItZW4tcHJvZC0="
    }
}
