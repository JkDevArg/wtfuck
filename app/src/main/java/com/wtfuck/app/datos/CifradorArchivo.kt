package com.wtfuck.app.datos

import android.util.Base64
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de archivos adjuntos. AES-256-GCM, una clave nueva por archivo.
 *
 * Es un cifrado APARTE del sobre ([Cifrador]) y esa separacion es intencional:
 *
 *   - El archivo se cifra con su propia clave y se sube al almacen. El almacen
 *     guarda bytes que no puede interpretar.
 *   - La clave viaja DENTRO del sobre, junto a la referencia al adjunto. Desde
 *     el modulo E el sobre va cifrado de extremo a extremo, asi que la clave
 *     tambien.
 *
 * Por eso el servidor puede cobrar cuota y borrar huerfanos sin poder abrir un
 * solo archivo: tiene el candado pero nunca la llave.
 *
 * Clave de un solo uso: al no repetirse nunca, no hay riesgo de reutilizar el
 * nonce, que es la forma clasica de romper GCM.
 */
object CifradorArchivo {

    private const val BITS_ETIQUETA = 128
    private const val BYTES_NONCE = 12

    /** Lo que GCM agrega al final: la etiqueta de autenticacion. */
    const val SOBRECOSTO = BITS_ETIQUETA / 8

    private val azar = SecureRandom()

    /** Clave y nonce de un archivo, listos para viajar en el sobre. */
    data class Llave(val claveB64: String, val nonceB64: String)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun deB64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /**
     * Cifra `entrada` hacia `destino` sin cargar el archivo en memoria.
     *
     * Se trabaja en streaming a proposito: un video de 64 MB cifrado de golpe
     * son mas de 128 MB de heap entre claro y cifrado, y eso mata el proceso en
     * un telefono de gama media.
     */
    fun cifrarA(entrada: InputStream, destino: File): Llave {
        val clave = ByteArray(32).also { azar.nextBytes(it) }
        val nonce = ByteArray(BYTES_NONCE).also { azar.nextBytes(it) }
        val cifra = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(BITS_ETIQUETA, nonce))
        }
        entrada.use { ent ->
            CipherOutputStream(destino.outputStream().buffered(), cifra).use { sal ->
                ent.copyTo(sal, 64 * 1024)
            }
        }
        return Llave(b64(clave), b64(nonce))
    }

    /**
     * Descifra `entrada` hacia `destino`.
     *
     * Devuelve false si GCM rechaza el contenido. Eso NO es un fallo cualquiera:
     * significa que los bytes no son los que se cifraron, es decir que alguien
     * los cambio en el almacen o en el camino. El archivo a medio escribir se
     * borra para que no quede nada que pueda abrirse por error.
     */
    fun descifrarA(entrada: InputStream, llave: Llave, destino: File): Boolean {
        val cifra = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deB64(llave.claveB64), "AES"),
                GCMParameterSpec(BITS_ETIQUETA, deB64(llave.nonceB64)),
            )
        }
        return try {
            entrada.use { ent ->
                // El descifrado va del lado de la LECTURA y no de la escritura:
                // asi la excepcion de GCM salta al leer y el archivo destino no
                // se queda con basura que parezca valida.
                javax.crypto.CipherInputStream(ent, cifra).use { claro ->
                    destino.outputStream().buffered().use { sal -> claro.copyTo(sal, 64 * 1024) }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("CifradorArchivo", "Contenido rechazado por GCM: ${e.message}")
            destino.delete()
            false
        }
    }

    /** Cifra bytes en memoria. Solo para cosas chicas: miniaturas, stickers. */
    fun cifrar(claro: ByteArray): Pair<ByteArray, Llave> {
        val clave = ByteArray(32).also { azar.nextBytes(it) }
        val nonce = ByteArray(BYTES_NONCE).also { azar.nextBytes(it) }
        val cifra = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(BITS_ETIQUETA, nonce))
        }
        return cifra.doFinal(claro) to Llave(b64(clave), b64(nonce))
    }

    fun descifrar(cifrado: ByteArray, llave: Llave): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deB64(llave.claveB64), "AES"),
                GCMParameterSpec(BITS_ETIQUETA, deB64(llave.nonceB64)),
            )
        }.doFinal(cifrado)
    } catch (e: Exception) {
        android.util.Log.w("CifradorArchivo", "Bytes rechazados por GCM: ${e.message}")
        null
    }
}
