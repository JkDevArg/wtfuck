package com.wtfuck.app.datos

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copia de seguridad cifrada del historial de chats.
 *
 * ## Por que existe
 *
 * El historial vive SOLO en el telefono (el servidor es un buzon tonto). Se
 * pierde el telefono y se pierde todo. La sincronizacion entre dispositivos
 * (modulo de copias) resuelve el caso de "tengo el viejo y el nuevo a la vez";
 * no resuelve el de "se me rompio y no tengo otro". Para eso esta esto: un
 * archivo cifrado que la persona guarda donde quiera (la nube, el PC) y
 * restaura en un telefono nuevo.
 *
 * ## Por que la cripto va aparte y es pura
 *
 * Todo lo que decide algo de cripto esta en este archivo, sin `Context` ni
 * dependencias de Android, para poder probarlo en JUnit normal contra el caso
 * que importa: que la frase correcta recupere, que una frase mala NO, y que un
 * archivo manipulado se rechace. Una copia que no se puede restaurar es peor
 * que no tener copia; por eso el round-trip se prueba, no se supone.
 *
 * ## Esto NO es sealed sender ni toca el nucleo E2EE
 *
 * Es cifrado simetrico de un archivo con una clave derivada de una frase
 * (PBKDF2 + AES-256-GCM). Rutina, bien entendida. El servidor nunca ve nada de
 * esto: la copia se cifra y descifra entera en el telefono.
 */
object CopiaSeguridad {

    // El adjunto de un mensaje, si lo tiene y su archivo se incluyo en la copia.
    // La `llave` es la del CifradorArchivo con que se cifro el archivo dentro del
    // zip: vive AQUI, en el manifiesto, que va cifrado con la frase. Sin la
    // frase no hay manifiesto, sin manifiesto no hay llaves, y los archivos del
    // zip son bytes opacos. Es el mismo cifrado de sobre que usa la app para los
    // adjuntos normales.
    @Serializable
    data class AdjuntoRespaldo(
        val clase: String,
        val mime: String,
        val nombre: String,
        val bytes: Long,
        val ancho: Int = 0,
        val alto: Int = 0,
        val duracionMs: Int = 0,
        val claveB64: String,
        val nonceB64: String,
    )

    @Serializable
    data class MensajeRespaldo(
        val id: String,
        val autor: String,
        val esMio: Boolean,
        val texto: String,
        val creadoEn: Long,
        // null = mensaje de solo texto, o el archivo ya no estaba en el telefono.
        val adjunto: AdjuntoRespaldo? = null,
    )

    @Serializable
    data class ConversacionRespaldo(
        val id: String,
        val tipo: String,
        val nombre: String,
        val participantes: String,
        val mensajes: List<MensajeRespaldo>,
    )

    @Serializable
    data class Respaldo(
        // 2 desde que la copia puede llevar adjuntos. Una copia v1 (solo texto)
        // se sigue leyendo: no tiene adjuntos y ya esta.
        val version: Int = 2,
        val creado: Long,
        // Para AVISAR si se importa en una cuenta distinta a la que lo creo. No
        // lo impide -a veces es lo que se quiere- pero lo dice.
        val cuenta: String,
        val conversaciones: List<ConversacionRespaldo>,
    )

    // --- formato del archivo ------------------------------------------
    //
    //   MAGIA(8) · salt(16) · iteraciones(4) · nonce(12) · cifrado(resto)
    //
    // La MAGIA identifica el archivo y su version; si algun dia cambia el
    // formato, un archivo viejo se reconoce y se rechaza con un mensaje claro
    // en vez de reventar a mitad del descifrado.
    private val MAGIA = "WTFBKP01".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val CLAVE_BITS = 256
    // OWASP para PBKDF2-HMAC-SHA256. Alto a proposito: la copia se descifra una
    // vez cada tanto, no en un bucle; el costo lo paga quien intenta adivinar la
    // frase a lo bruto, no la persona.
    private const val ITERACIONES = 210_000

    private val azar = SecureRandom()

    private fun derivar(frase: CharArray, salt: ByteArray, iteraciones: Int): SecretKeySpec {
        val spec = PBEKeySpec(frase, salt, iteraciones, CLAVE_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Cifra los bytes del JSON con una clave derivada de la frase. */
    fun cifrar(claro: ByteArray, frase: CharArray): ByteArray {
        val salt = ByteArray(SALT).also { azar.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { azar.nextBytes(it) }
        val clave = derivar(frase, salt, ITERACIONES)
        val cifrado = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(TAG_BITS, nonce))
            // La MAGIA y la cabecera van AUTENTICADAS: si alguien le cambia el
            // numero de iteraciones para debilitarla, GCM lo detecta al abrir.
            updateAAD(MAGIA)
            doFinal(claro)
        }
        return ByteBuffer.allocate(MAGIA.size + SALT + 4 + NONCE + cifrado.size).apply {
            put(MAGIA)
            put(salt)
            putInt(ITERACIONES)
            put(nonce)
            put(cifrado)
        }.array()
    }

    /** Motivo por el que una restauracion no se puede leer. */
    enum class Fallo { FORMATO, FRASE_O_DANADO }

    /**
     * Descifra el archivo. Devuelve los bytes del JSON, o un [Fallo].
     *
     * No distingue "frase mal" de "archivo manipulado" a proposito: las dos son
     * la misma respuesta para quien restaura -no sirve- y separarlas le daria
     * pistas a quien probara frases contra un archivo robado.
     */
    fun descifrar(archivo: ByteArray, frase: CharArray): Result<ByteArray> {
        val minimo = MAGIA.size + SALT + 4 + NONCE + 16
        if (archivo.size < minimo) return Result.failure(ErrorCopia(Fallo.FORMATO))
        val buf = ByteBuffer.wrap(archivo)
        val magia = ByteArray(MAGIA.size).also { buf.get(it) }
        if (!magia.contentEquals(MAGIA)) return Result.failure(ErrorCopia(Fallo.FORMATO))

        val salt = ByteArray(SALT).also { buf.get(it) }
        val iteraciones = buf.getInt()
        // Un archivo honesto trae las iteraciones con que se cifro. Se acota
        // para que uno manipulado no pida mil millones y cuelgue el telefono.
        if (iteraciones !in 1..2_000_000) return Result.failure(ErrorCopia(Fallo.FORMATO))
        val nonce = ByteArray(NONCE).also { buf.get(it) }
        val cifrado = ByteArray(buf.remaining()).also { buf.get(it) }

        val clave = derivar(frase, salt, iteraciones)
        return try {
            val claro = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(MAGIA)
                doFinal(cifrado)
            }
            Result.success(claro)
        } catch (e: Exception) {
            // AEADBadTagException: o la frase no deriva la clave correcta, o el
            // contenido se toco. Cualquiera de las dos cae aqui.
            Result.failure(ErrorCopia(Fallo.FRASE_O_DANADO))
        }
    }

    class ErrorCopia(val fallo: Fallo) : Exception(fallo.name)
}
