package com.wtfuck.server

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Cripto del servidor. Deliberadamente pequena: el servidor NO cifra mensajes,
 * solo protege contrasenas y tokens de sesion.
 */
object Cripto {

    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    // Parametros Argon2id. 64 MiB / 3 pasadas / 4 hilos: recomendacion OWASP.
    private const val MEM_KIB = 65536
    private const val PASADAS = 3
    private const val PARALELISMO = 4
    private const val LARGO_HASH = 32
    private const val LARGO_SAL = 16

    /** Devuelve "argon2id$<sal_b64>$<hash_b64>". La sal va junta, como debe ser. */
    fun hashPassword(password: String): String {
        val sal = ByteArray(LARGO_SAL).also { rnd.nextBytes(it) }
        return "argon2id\$${b64.encodeToString(sal)}\$${b64.encodeToString(derivar(password, sal))}"
    }

    fun verificarPassword(password: String, almacenado: String): Boolean {
        val partes = almacenado.split('$')
        if (partes.size != 3 || partes[0] != "argon2id") return false
        return try {
            val sal = b64d.decode(partes[1])
            val esperado = b64d.decode(partes[2])
            MessageDigest.isEqual(derivar(password, sal), esperado)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derivar(password: String, sal: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(PASADAS)
            .withMemoryAsKB(MEM_KIB)
            .withParallelism(PARALELISMO)
            .withSalt(sal)
            .build()
        val gen = Argon2BytesGenerator().apply { init(params) }
        return ByteArray(LARGO_HASH).also { gen.generateBytes(password.toCharArray(), it) }
    }

    /** Token de sesion opaco de 256 bits. */
    fun nuevoToken(): String =
        b64.encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
            .replace('+', '-').replace('/', '_')

    /** En la base se guarda SOLO el hash del token, nunca el token. */
    fun hashToken(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
}
