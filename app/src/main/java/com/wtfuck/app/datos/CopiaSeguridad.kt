package com.wtfuck.app.datos

import com.wtfuck.protocol.CodigoRecuperacion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /**
     * La identidad Signal, sellada con la clave del CODIGO DE RECUPERACION.
     *
     * ## Por que la copia la lleva
     *
     * Porque sin ella, restaurar en un telefono nuevo generaba una identidad
     * nueva -`AlmacenSignal.asegurarIdentidad` la crea si no hay- y a **todos
     * los contactos les saltaba el aviso de que la clave cambio**. Esa alarma
     * es la que avisa de un intento de suplantacion; dispararla cada vez que
     * alguien cambia de telefono ensena a ignorarla, y entonces deja de servir
     * el dia que es de verdad.
     *
     * ## Por que DOS cerraduras y no una
     *
     * Esto ya viaja dentro del manifiesto, que va cifrado con la frase. El
     * sellado con la clave del codigo es una segunda cerradura encima, y es
     * deliberado: la clave privada de identidad es **el secreto mas peligroso
     * de la app**. Con el historial robado se lee lo que se dijo; con la
     * identidad robada se puede *ser* esa persona de aqui en adelante, firmar
     * en su nombre y abrir lo que le manden.
     *
     * Las dos cerraduras son secretos de naturaleza distinta a proposito: la
     * frase se teclea y se recuerda, el codigo se escribe en papel una vez y
     * se guarda. Que un descuido con una no entregue la otra.
     *
     * Consecuencia honesta: **sin el codigo no se recupera la identidad**,
     * aunque se tenga la frase y el archivo. Se recuperan los mensajes, que ya
     * es la mayor parte de lo que la gente teme perder.
     */
    @Serializable
    data class IdentidadRespaldo(
        val nonceB64: String,
        val selladoB64: String,
    )

    /** Lo que hay dentro del sello, una vez abierto. */
    @Serializable
    data class IdentidadClara(
        /** `IdentityKeyPair` serializado: la publica y **la privada**. */
        val parClavesB64: String,
        val registrationId: Int,
        /**
         * Los contadores de ids de prekey siguen donde estaban.
         *
         * Podrian volver a 1 y no se hace: si el servidor todavia tiene
         * prekeys del aparato anterior con esos ids, las nuevas chocarian con
         * ellas. Seguir contando no cuesta nada y evita el choque.
         */
        val proximoPreKeyId: Int = 1,
        val proximoFirmadaId: Int = 1,
        val proximoKyberId: Int = 1,
    )

    @Serializable
    data class Respaldo(
        // 3 desde que la copia puede llevar la identidad Signal. Una copia v1
        // (solo texto) o v2 (con adjuntos) se sigue leyendo: le falta el campo
        // y `identidad` queda en null, que es "esta copia no la trae".
        val version: Int = 3,
        val creado: Long,
        // Para AVISAR si se importa en una cuenta distinta a la que lo creo. No
        // lo impide -a veces es lo que se quiere- pero lo dice.
        val cuenta: String,
        val conversaciones: List<ConversacionRespaldo>,
        /** null = la copia se hizo sin codigo de recuperacion, o es v1/v2. */
        val identidad: IdentidadRespaldo? = null,
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

    /**
     * El JSON de este archivo, propio y no el `jsonApp` de `Api.kt`.
     *
     * `Api.kt` arrastra `Context` y este objeto se declara libre de Android
     * para poder probarlo en JUnit normal -ver la cabecera-. Un `Json` son dos
     * lineas; importar Android entero para reusarlas seria un mal cambio.
     *
     * `ignoreUnknownKeys` para que una copia escrita por una version mas nueva
     * -con campos que esta no conoce- se pueda abrir igual en vez de reventar.
     */
    private val jsonCopia = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    // ------------------------------------------------------------------
    //  La identidad Signal dentro de la copia
    // ------------------------------------------------------------------
    //
    // Cifrado aparte del manifiesto y con OTRA clave: la que sale del codigo
    // de recuperacion. Ver `IdentidadRespaldo` para el por que de las dos
    // cerraduras.

    /**
     * Etiqueta autenticada del sello.
     *
     * Va como AAD, asi que forma parte de lo que GCM verifica. Sirve para que
     * un bloque cifrado no se pueda mover de sitio: si alguien pegara aqui un
     * sello sacado de otro contexto -o de una version futura del formato- la
     * verificacion falla en vez de devolver bytes que se interpretarian como
     * una identidad ajena.
     */
    private val MAGIA_IDENTIDAD = "WTFIDN01".toByteArray(Charsets.US_ASCII)

    /**
     * Sella la identidad con la clave de 32 bytes derivada del codigo.
     *
     * @param clave lo que devuelve `CodigoRecuperacion.claveDeIdentidad`.
     */
    fun sellarIdentidad(clara: IdentidadClara, clave: ByteArray): IdentidadRespaldo {
        require(clave.size == 32) { "La clave de identidad debe ser de 32 bytes." }
        val nonce = ByteArray(NONCE).also { azar.nextBytes(it) }
        val json = jsonCopia.encodeToString(IdentidadClara.serializer(), clara)
        val sellado = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(MAGIA_IDENTIDAD)
            doFinal(json.toByteArray(Charsets.UTF_8))
        }
        return IdentidadRespaldo(
            nonceB64 = b64(nonce),
            selladoB64 = b64(sellado),
        )
    }

    /**
     * Abre el sello. `null` si el codigo no es el que lo cerro, o si lo tocaron.
     *
     * Devuelve `null` en vez de lanzar porque "el codigo no es este" es un
     * resultado esperado -la persona puede tener dos codigos anotados, o
     * haberse equivocado- y no una anomalia. Quien llama decide que decir.
     */
    fun abrirIdentidad(sellada: IdentidadRespaldo, clave: ByteArray): IdentidadClara? {
        if (clave.size != 32) return null
        return runCatching {
            val nonce = deB64(sellada.nonceB64)
            val cifrado = deB64(sellada.selladoB64)
            val claro = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(MAGIA_IDENTIDAD)
                doFinal(cifrado)
            }
            jsonCopia.decodeFromString(
                IdentidadClara.serializer(), claro.toString(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    // `java.util.Base64` y no `android.util.Base64`: este archivo no depende de
    // Android a proposito, para que las pruebas del sellado corran en JUnit
    // normal. Ver la cabecera del objeto.
    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun deB64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

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
