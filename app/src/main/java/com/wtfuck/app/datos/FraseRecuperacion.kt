package com.wtfuck.app.datos

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fase 1 de recuperacion con frase: envuelve el CODIGO de recuperacion con una
 * clave derivada de una frase que eligio la persona.
 *
 * El codigo (128 bits) sigue siendo la raiz; esto no lo reemplaza, solo lo
 * guarda cifrado para no depender del papel. La frase pasa por un PBKDF2 caro
 * con sal, y el codigo se cifra con AES-256-GCM. El servidor guarda el sobre
 * resultante pero NO la frase: no puede abrirlo. En un telefono nuevo, con la
 * frase, se descifra el codigo y se sigue la recuperacion de siempre.
 *
 * ## Por que PBKDF2 y no Argon2
 *
 * Una frase la elige una persona, asi que tiene poca entropia y hay que
 * estirarla con un KDF caro: es el caso para el que existen PBKDF2/Argon2 (lo
 * contrario del codigo de 128 bits, que no necesita estirarse). Se usa PBKDF2
 * por venir en la plataforma sin dependencias nativas. Argon2 resiste mejor a
 * GPU y queda como mejora futura; el formato del sobre lleva la sal, asi que
 * cambiar de KDF despues no rompe los sobres viejos si se versiona.
 *
 * ## Formato del sobre
 *
 * `[sal 16][nonce 12][codigo cifrado + tag GCM 16]`. La sal y el nonce van en
 * claro a proposito: no son secretos, solo evitan claves y cifrados repetidos.
 */
object FraseRecuperacion {
    private const val SAL = 16
    private const val NONCE = 12
    // 600k: del orden de lo que usan los gestores de contrasenas hoy. Tarda ~1s
    // en un telefono, que para algo que se hace una vez al configurar y una al
    // recuperar es imperceptible, y encarece mucho la fuerza bruta offline.
    private const val ITERACIONES = 600_000
    private const val BITS_CLAVE = 256
    private const val BITS_TAG = 128
    private val azar = SecureRandom()

    private fun clave(frase: String, sal: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(frase.toCharArray(), sal, ITERACIONES, BITS_CLAVE)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** Cifra el codigo con la frase. Devuelve el sobre en bytes. */
    fun envolver(codigo: String, frase: String): ByteArray {
        val sal = ByteArray(SAL).also { azar.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { azar.nextBytes(it) }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, clave(frase, sal), GCMParameterSpec(BITS_TAG, nonce))
        }
        val ct = cifrador.doFinal(codigo.toByteArray(Charsets.US_ASCII))
        return sal + nonce + ct
    }

    /**
     * Abre el sobre con la frase. Devuelve el codigo, o `null` si la frase no es
     * la correcta: GCM comprueba el tag, asi que una frase mala no descifra nada
     * -no entrega un codigo basura que luego fallaria en el servidor-.
     */
    fun abrir(sobre: ByteArray, frase: String): String? {
        if (sobre.size < SAL + NONCE + 16) return null
        val sal = sobre.copyOfRange(0, SAL)
        val nonce = sobre.copyOfRange(SAL, SAL + NONCE)
        val ct = sobre.copyOfRange(SAL + NONCE, sobre.size)
        return runCatching {
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, clave(frase, sal), GCMParameterSpec(BITS_TAG, nonce))
            }
            String(cifrador.doFinal(ct), Charsets.US_ASCII)
        }.getOrNull()
    }
}
