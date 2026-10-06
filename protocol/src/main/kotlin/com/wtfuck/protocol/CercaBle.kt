package com.wtfuck.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Modo cerca, fase 1: encontrarse por Bluetooth LE SIN emparejar.
 *
 * ## El problema que resuelve
 *
 * La fase 0 exige emparejar los telefonos en los ajustes de Android, porque el
 * cifrado del enlace lo pone el emparejamiento. Sin emparejar hacen falta dos
 * cosas que el sistema ya no da:
 *
 *  1. **Reconocerse sin delatarse.** Un anuncio Bluetooth lo oye cualquiera. Si
 *     dijera "soy @ana", cualquiera con un escaner sabria quien esta en la sala
 *     y la seguiria de sala en sala. Ver [Baliza].
 *  2. **Un enlace cifrado y autenticado.** Ver [Apreton] y [Sello].
 *
 * Todo aqui es Kotlin puro: se prueba en la JVM sin radio. La curva se inyecta
 * ([Curva]) porque en el telefono la pone libsignal y en las pruebas el JDK.
 */

/**
 * La baliza: lo que se anuncia por el aire.
 *
 * ## Como se reconoce a un contacto sin delatarlo
 *
 * Cada aparato tiene una **clave de baliza** al azar que solo conocen sus
 * contactos (viaja dentro de los mensajes, cifrada de punta a punta). Lo que se
 * anuncia es `HMAC(clave, epoca)` truncado: cambia cada quince minutos, y sin
 * la clave es ruido. Un contacto calcula ese HMAC con cada clave que conoce y
 * ve si coincide; un extraño ve un numero distinto cada cuarto de hora, sin
 * forma de saber de quien es ni de unir dos cuartos de hora entre si. Android,
 * ademas, cambia la direccion Bluetooth del anuncio por su cuenta.
 *
 * ## Lo que SI se ve
 *
 * Que hay un telefono con wtfuck cerca: la marca del anuncio es fija, porque el
 * escaner necesita algo por donde filtrar. Es el mismo precio que paga
 * cualquier app que use Bluetooth LE.
 */
object Baliza {

    /**
     * Identificador de fabricante 0xFFFF: el que el Bluetooth SIG reserva para
     * pruebas y que no pertenece a nadie. Una app sin identificador propio no
     * puede usar el de otro fabricante.
     */
    const val EMPRESA = 0xFFFF
    const val MARCA: Byte = 0x57 // 'W'
    const val VERSION: Byte = 1
    const val EPOCA_MS = 15 * 60 * 1000L
    const val LARGO_TOKEN = 8
    const val LARGO_CLAVE = 32

    /** Los primeros bytes del anuncio: por esto filtra el escaner. */
    val PREFIJO = byteArrayOf(MARCA, VERSION)

    fun epoca(ahoraMs: Long): Long = ahoraMs / EPOCA_MS

    fun token(clave: ByteArray, epoca: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave, "HmacSHA256"))
        mac.update("wtfuck/baliza/v1".toByteArray())
        mac.update(ByteBuffer.allocate(8).putLong(epoca).array())
        return mac.doFinal().copyOf(LARGO_TOKEN)
    }

    /** Lo que va en los datos de fabricante: marca, version, token y canal. */
    fun anuncio(token: ByteArray, psm: Int): ByteArray {
        require(token.size == LARGO_TOKEN)
        require(psm in 0..0xFFFF)
        return PREFIJO + token + byteArrayOf((psm ushr 8).toByte(), psm.toByte())
    }

    data class Anuncio(val token: String, val psm: Int)

    /**
     * Lee un anuncio ajeno, o `null`. Lo escribio cualquiera: se comprueba el
     * largo, la marca y la version, y que el canal este en el rango dinamico de
     * L2CAP LE (0x80-0xFF), que es donde Android abre los suyos.
     */
    fun leer(datos: ByteArray?): Anuncio? {
        if (datos == null || datos.size != 2 + LARGO_TOKEN + 2) return null
        if (datos[0] != MARCA || datos[1] != VERSION) return null
        val psm = ((datos[2 + LARGO_TOKEN].toInt() and 0xFF) shl 8) or (datos[3 + LARGO_TOKEN].toInt() and 0xFF)
        if (psm !in 0x80..0xFF) return null
        return Anuncio(hex(datos.copyOfRange(2, 2 + LARGO_TOKEN)), psm)
    }

    /**
     * Para cada clave conocida, sus tokens del cuarto de hora anterior, el
     * actual y el siguiente: dos relojes con unos minutos de diferencia tienen
     * que reconocerse igual. Token -> aparato.
     */
    fun indice(claves: Map<String, ByteArray>, ahoraMs: Long): Map<String, String> {
        val e = epoca(ahoraMs)
        val out = HashMap<String, String>()
        for ((aparato, clave) in claves) {
            for (d in -1L..1L) out[hex(token(clave, e + d))] = aparato
        }
        return out
    }

    fun nuevaClave(): ByteArray = ByteArray(LARGO_CLAVE).also { SecureRandom().nextBytes(it) }

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** X25519. En el telefono, libsignal; en las pruebas, el JDK. */
interface Curva {
    /** Un par efimero: (privada, publica), 32 bytes cada una. */
    fun par(): Pair<ByteArray, ByteArray>
    fun acordar(privada: ByteArray, publica: ByteArray): ByteArray
}

/**
 * El cifrado de un enlace ya establecido: AES-256-GCM, una clave por sentido y
 * un contador por trama.
 *
 * El contador es estricto: la trama N tiene que llegar despues de la N-1. El
 * enlace es ordenado, asi que una trama repetida, reordenada o borrada no es
 * mala suerte: es alguien en el medio, y el enlace se corta.
 */
class Sello internal constructor(
    private val claveEnviar: ByteArray,
    private val claveRecibir: ByteArray,
    private var contadorEnviar: Long,
    private var contadorRecibir: Long,
) {
    fun cerrar(plano: ByteArray): ByteArray = synchronized(this) {
        aead(Cipher.ENCRYPT_MODE, claveEnviar, contadorEnviar++, plano, ByteArray(0))
    }

    /** Abre la trama que toca, o lanza. */
    fun abrir(cifrado: ByteArray): ByteArray = synchronized(this) {
        aead(Cipher.DECRYPT_MODE, claveRecibir, contadorRecibir++, cifrado, ByteArray(0))
    }

    internal companion object {
        fun aead(modo: Int, clave: ByteArray, contador: Long, datos: ByteArray, aad: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12).putInt(0).putLong(contador).array()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(modo, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            return c.doFinal(datos)
        }
    }
}

/**
 * El apretón de manos del enlace sin emparejar.
 *
 * ## De donde sale la confianza
 *
 * De las claves de IDENTIDAD de Signal, que los dos ya tienen del otro si
 * alguna vez hablaron con internet: es la misma condicion que el modo cerca
 * ya tenia ("solo con quien ya hablaste"). El secreto del enlace mezcla:
 *
 *  - `DH(identidad mia, identidad suya)`: solo lo pueden calcular esos dos
 *    aparatos. Es lo que autentica.
 *  - `DH(efimera mia, efimera suya)`: claves nuevas en cada enlace. Si una
 *    identidad se filtra mañana, lo grabado hoy sigue cerrado.
 *  - Un nonce de cada lado, para que dos enlaces nunca compartan claves.
 *
 * ## Los tres mensajes
 *
 *  1. Quien llama manda su efimera, un nonce y **quien es**, cifrado con una
 *     clave derivada de la baliza de quien atiende. Asi quien atiende sabe a
 *     quien buscarle la identidad, y alguien que solo escucha no se entera de
 *     quien llama.
 *  2. Quien atiende busca esa identidad entre las que conoce -si no la conoce,
 *     corta-, manda su efimera, su nonce y una prueba de que tiene SU clave
 *     privada: lo que solo podria cifrar quien calculo el secreto.
 *  3. Quien llama comprueba la prueba y manda la suya.
 *
 * Despues de eso, cada trama va cerrada con [Sello].
 *
 * Un mensaje repetido no sirve de nada: el otro lado contesta con una efimera
 * nueva, y sin la privada de la del mensaje original no se puede seguir.
 */
class Apreton(private val curva: Curva, private val azar: SecureRandom = SecureRandom()) {

    class Rechazo(motivo: String) : Exception(motivo)

    /**
     * Quien llama. Sabe a quien: lo reconocio por su baliza.
     *
     * @param miEstatico DH entre MI identidad y la clave publica que se le pase.
     *   Se inyecta para que la privada de identidad no salga de donde vive.
     * @return el sello del enlace, o lanza [Rechazo].
     */
    fun llamar(
        entrada: InputStream,
        salida: OutputStream,
        yo: String,
        el: String,
        suBaliza: ByteArray,
        suIdentidad: ByteArray,
        miEstatico: (ByteArray) -> ByteArray,
    ): Sello {
        val (ePriv, ePub) = curva.par()
        val nYo = ByteArray(16).also { azar.nextBytes(it) }
        val kQuien = hkdf(suBaliza, nYo, "wtfuck/cerca/quien/v1".toByteArray(), 32)
        val cabecera1 = byteArrayOf(VERSION) + ePub + nYo
        val quien = Sello.aead(Cipher.ENCRYPT_MODE, kQuien, 0, yo.toByteArray(), cabecera1)
        val m1 = cabecera1 + quien
        Trama.escribir(salida, m1)

        val m2 = Trama.leer(entrada)
        if (m2.size < 1 + 32 + 16 + 16 || m2[0] != VERSION) throw Rechazo("respuesta invalida")
        val eSuya = m2.copyOfRange(1, 33)
        val nEl = m2.copyOfRange(33, 49)
        val prueba = m2.copyOfRange(49, m2.size)

        val (kYoEl, kElYo) = claves(
            estatico = miEstatico(suIdentidad),
            efimero = curva.acordar(ePriv, eSuya),
            nLlama = nYo, nAtiende = nEl, llama = yo, atiende = el,
        )
        val suId = runCatching {
            String(Sello.aead(Cipher.DECRYPT_MODE, kElYo, 0, prueba, resumen(m1, m2.copyOfRange(0, 49))))
        }.getOrElse { throw Rechazo("no prueba ser quien dice") }
        if (suId != el) throw Rechazo("la prueba es de otro aparato")

        val m3 = Sello.aead(Cipher.ENCRYPT_MODE, kYoEl, 0, LISTO, resumen(m1, m2))
        Trama.escribir(salida, m3)
        return Sello(kYoEl, kElYo, contadorEnviar = 1, contadorRecibir = 1)
    }

    /**
     * Quien atiende. No sabe quien llama hasta abrir el primer mensaje.
     *
     * @param identidadDe la identidad publica de un aparato, o `null` si no se
     *   le conoce: entonces se corta.
     * @return el aparato que llamo -autenticado- y el sello.
     */
    fun atender(
        entrada: InputStream,
        salida: OutputStream,
        yo: String,
        miBaliza: ByteArray,
        identidadDe: (String) -> ByteArray?,
        miEstatico: (ByteArray) -> ByteArray,
    ): Pair<String, Sello> {
        val m1 = Trama.leer(entrada)
        if (m1.size < 1 + 32 + 16 + 16 || m1[0] != VERSION) throw Rechazo("llamada invalida")
        val eSuya = m1.copyOfRange(1, 33)
        val nEl = m1.copyOfRange(33, 49)
        val kQuien = hkdf(miBaliza, nEl, "wtfuck/cerca/quien/v1".toByteArray(), 32)
        val el = runCatching {
            String(Sello.aead(Cipher.DECRYPT_MODE, kQuien, 0, m1.copyOfRange(49, m1.size), m1.copyOfRange(0, 49)))
        }.getOrElse { throw Rechazo("no conoce mi baliza") }
        val suIdentidad = identidadDe(el) ?: throw Rechazo("no se quien es $el")

        val (ePriv, ePub) = curva.par()
        val nYo = ByteArray(16).also { azar.nextBytes(it) }
        val (kElYo, kYoEl) = claves(
            estatico = miEstatico(suIdentidad),
            efimero = curva.acordar(ePriv, eSuya),
            nLlama = nEl, nAtiende = nYo, llama = el, atiende = yo,
        )
        val cabecera2 = byteArrayOf(VERSION) + ePub + nYo
        val prueba = Sello.aead(Cipher.ENCRYPT_MODE, kYoEl, 0, yo.toByteArray(), resumen(m1, cabecera2))
        val m2 = cabecera2 + prueba
        Trama.escribir(salida, m2)

        val m3 = Trama.leer(entrada)
        val listo = runCatching {
            Sello.aead(Cipher.DECRYPT_MODE, kElYo, 0, m3, resumen(m1, m2))
        }.getOrElse { throw Rechazo("no prueba ser quien dice") }
        if (!listo.contentEquals(LISTO)) throw Rechazo("cierre invalido")
        return el to Sello(kYoEl, kElYo, contadorEnviar = 1, contadorRecibir = 1)
    }

    /** (llama -> atiende, atiende -> llama). Los dos lados las calculan igual. */
    private fun claves(
        estatico: ByteArray,
        efimero: ByteArray,
        nLlama: ByteArray,
        nAtiende: ByteArray,
        llama: String,
        atiende: String,
    ): Pair<ByteArray, ByteArray> {
        val info = "wtfuck/cerca/enlace/v1\u0000$llama\u0000$atiende".toByteArray()
        val k = hkdf(estatico + efimero, nLlama + nAtiende, info, 64)
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }

    private fun resumen(vararg partes: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        partes.forEach { d.update(it) }
        return d.digest()
    }

    companion object {
        const val VERSION: Byte = 1
        private val LISTO = "listo".toByteArray()

        /** HKDF-SHA256 (RFC 5869): extract con la sal y expand hasta [largo]. */
        fun hkdf(ikm: ByteArray, sal: ByteArray, info: ByteArray, largo: Int): ByteArray {
            val ext = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sal, "HmacSHA256")) }.doFinal(ikm)
            val out = ByteArray(largo)
            var t = ByteArray(0)
            var hecho = 0
            var i = 1
            while (hecho < largo) {
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ext, "HmacSHA256")) }
                mac.update(t); mac.update(info); mac.update(i.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, largo - hecho)
                System.arraycopy(t, 0, out, hecho, n)
                hecho += n
                i++
            }
            return out
        }
    }
}
