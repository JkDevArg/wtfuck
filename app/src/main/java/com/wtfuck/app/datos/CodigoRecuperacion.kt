package com.wtfuck.app.datos

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * El codigo de recuperacion: la unica salida cuando se pierde el telefono.
 *
 * ## El problema que resuelve
 *
 * La cuenta esta atada al hardware a proposito: para ingresar hay que ser un
 * dispositivo registrado, vincular uno nuevo exige un codigo emitido desde el
 * principal, y el principal no se puede revocar. Es una defensa fuerte y
 * deliberada — con la contrasena robada, nadie entra desde su telefono.
 *
 * El precio era absoluto: **telefono perdido = cuenta perdida para siempre**,
 * con copia de seguridad o sin ella. Y eso dejaba la copia coja, porque su
 * pantalla decia "para restaurar en un telefono nuevo, primero entra a tu
 * cuenta" y en un telefono nuevo no se podia entrar. La funcion existia para
 * un caso que no tenia camino.
 *
 * Este codigo es **una** salida, y una sola: se genera al registrarse, se
 * escribe en papel, y con el —mas el SMS y el segundo factor— se puede
 * vincular un telefono nuevo. El atado al hardware sigue valiendo para todo lo
 * demas.
 *
 * ## Por que un codigo y no doce palabras
 *
 * Doce palabras se transcriben mejor, y es la razon por la que existe BIP-39.
 * Pero piden una lista fija de 2048 palabras revisada -sin duplicados, con
 * prefijos de cuatro letras unicos- y traerla significaba meter un archivo
 * externo al repositorio para ganar comodidad al escribir, no seguridad.
 *
 * Esto da los mismos 128 bits sin dependencia nueva. No esta pensado para
 * dictarlo de memoria: se anota una vez y se guarda.
 *
 * ## El formato
 *
 * ```
 *   XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX     28 simbolos, 7 grupos de 4
 * ```
 *
 * 16 bytes de azar + 1 byte de control = 17 bytes = 136 bits, que en base32
 * son 28 simbolos. Alineado a byte a proposito: asi los 16 bytes de entropia
 * entran tal cual en el HKDF y no hay que recortar bits sueltos.
 *
 * **Alfabeto Crockford**, que quita las cuatro letras que se confunden al
 * copiar a mano: no hay `I`, `L`, `O` ni `U`. Al leer, una `I` o una `L` se
 * entienden como `1` y una `O` como `0`, porque quien lo escribio en papel
 * puede haber dibujado cualquiera de las dos. La `U` se excluye por lo mismo
 * que en el original: sin ella no salen palabras desafortunadas por azar.
 *
 * El byte de control detecta 255 de cada 256 erratas que sobrevivan a lo
 * anterior. No es integridad criptografica —no la necesita, el codigo no lo
 * elige un atacante— sino que la pantalla pueda decir "lo copiaste mal" en vez
 * de "no se pudo restaurar".
 *
 * ## Las dos claves, y por que estan separadas
 *
 * Del mismo codigo salen dos cosas que **nunca** deben poder deducirse una de
 * la otra:
 *
 *  - [claveDeIdentidad]: cifra la identidad Signal dentro de la copia. No sale
 *    del telefono jamas.
 *  - [verificadorServidor]: lo que el servidor guarda -hasheado- para
 *    autorizar el alta de un telefono nuevo.
 *
 * Se derivan con HKDF y etiquetas distintas. Esa separacion es lo que hace que
 * **el servidor no pueda descifrar tu identidad** aunque tenga su mitad y
 * aunque le roben la base: de `verificadorServidor` no se llega a
 * `claveDeIdentidad`, porque HKDF no es invertible y las etiquetas producen
 * salidas independientes.
 *
 * ## Lo que este codigo NO hace
 *
 * No devuelve el historial. Eso lo devuelve la copia de seguridad, y solo si
 * existe. Sin copia, el codigo devuelve **la cuenta**: el username, los
 * contactos y los grupos. Los mensajes viejos no estan en ningun servidor y no
 * hay de donde sacarlos.
 */
object CodigoRecuperacion {

    /**
     * Crockford base32. Sin `I`, `L`, `O` ni `U`.
     *
     * El orden importa y es el del estandar: el simbolo N vale N.
     */
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val BYTES_AZAR = 16
    private const val BYTES_TOTAL = BYTES_AZAR + 1   // + el de control
    /** 17 bytes = 136 bits; en grupos de 5 bits son 28 simbolos (140, con 4 de relleno). */
    const val SIMBOLOS = 28
    private const val POR_GRUPO = 4

    private val azar = SecureRandom()

    // ------------------------------------------------------------ generar

    /** Un codigo nuevo, ya con guiones. */
    fun generar(): String {
        val entropia = ByteArray(BYTES_AZAR).also { azar.nextBytes(it) }
        return conGuiones(aBase32(entropia + control(entropia)))
    }

    /** `ABCD-EFGH-...`, como se muestra y se escribe en papel. */
    fun conGuiones(simbolos: String): String =
        simbolos.chunked(POR_GRUPO).joinToString("-")

    // ------------------------------------------------------------ leer

    /**
     * Limpia lo que escribio una persona y comprueba el control.
     *
     * Devuelve los 28 simbolos en limpio, o `null` si no es un codigo valido.
     * Un `null` significa "esto no lo vas a poder usar" y la pantalla puede
     * decirlo **antes** de mandar nada al servidor.
     *
     * Tolera lo que la gente escribe de verdad: minusculas, espacios en vez de
     * guiones, guiones de mas o de menos, y las confusiones clasicas de
     * Crockford (`I`/`l` por `1`, `O` por `0`).
     */
    fun normalizar(escrito: String): String? {
        val limpio = buildString {
            for (c in escrito.uppercase()) {
                when (c) {
                    '-', ' ', '\t', '\n', '\r' -> Unit          // separadores
                    'I', 'L' -> append('1')
                    'O' -> append('0')
                    else -> if (c in ALFABETO) append(c) else return null
                }
            }
        }
        if (limpio.length != SIMBOLOS) return null

        val bytes = deBase32(limpio) ?: return null
        val entropia = bytes.copyOfRange(0, BYTES_AZAR)
        // Comparacion normal y no en tiempo constante: el codigo lo escribio
        // quien lo tiene, aqui no hay secreto que filtrar por el reloj. El
        // secreto se compara en el servidor, contra un hash.
        if (bytes[BYTES_AZAR] != control(entropia)) return null
        return limpio
    }

    fun valido(escrito: String): Boolean = normalizar(escrito) != null

    // ------------------------------------------------------------ claves

    /**
     * La clave que cifra la identidad Signal dentro de la copia. 32 bytes.
     *
     * @param codigo lo que devolvio [normalizar]. Pasar texto sin normalizar
     *   daria una clave distinta para el MISMO codigo escrito de otra forma.
     */
    fun claveDeIdentidad(codigo: String): ByteArray =
        derivar(codigo, "wtfuck/copia/identidad/v1")

    /**
     * Lo que se le manda al servidor para probar que se tiene el codigo.
     *
     * El servidor guarda un **hash** de esto, no esto: asi una fuga de su base
     * no entrega el verificador de nadie. Y de aqui no se llega a
     * [claveDeIdentidad] ni con la base entera delante.
     */
    fun verificadorServidor(codigo: String): ByteArray =
        derivar(codigo, "wtfuck/servidor/verificador/v1")

    /**
     * HKDF-SHA256 (RFC 5869), extract + una vuelta de expand.
     *
     * Una vuelta basta porque se piden 32 bytes y el hash da 32.
     *
     * Sin PBKDF2 ni Argon2, y es deliberado: esos existen para estirar
     * secretos **de baja entropia**, como una contrasena que eligio una
     * persona. Aqui la entrada son 128 bits de `SecureRandom`, que no se
     * adivinan a lo bruto ni con todo el hardware del mundo. Poner 210.000
     * iteraciones encima costaria tiempo y no compraria nada.
     *
     * Sin sal: la sal sirve para que dos secretos iguales no den la misma
     * clave, y dos codigos generados con 128 bits nunca son iguales. La
     * separacion que si hace falta -entre la clave de la identidad y el
     * verificador del servidor- la da la etiqueta.
     */
    private fun derivar(codigo: String, etiqueta: String): ByteArray {
        val ikm = codigo.toByteArray(Charsets.US_ASCII)
        val prk = hmac(ByteArray(32), ikm)
        return hmac(prk, etiqueta.toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
    }

    private fun hmac(clave: ByteArray, datos: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(clave, "HmacSHA256"))
            doFinal(datos)
        }

    // ------------------------------------------------------------ piezas

    /** El byte de control: el primero del SHA-256 de la entropia. */
    private fun control(entropia: ByteArray): Byte =
        MessageDigest.getInstance("SHA-256").digest(entropia)[0]

    private fun aBase32(bytes: ByteArray): String {
        val sb = StringBuilder(SIMBOLOS)
        var acumulado = 0L
        var bits = 0
        for (b in bytes) {
            acumulado = (acumulado shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALFABETO[((acumulado shr bits) and 0x1F).toInt()])
            }
        }
        // Los 136 bits no son multiplo de 5: sobran 4, que se completan con
        // ceros a la derecha para cerrar el ultimo simbolo.
        if (bits > 0) sb.append(ALFABETO[((acumulado shl (5 - bits)) and 0x1F).toInt()])
        return sb.toString()
    }

    private fun deBase32(simbolos: String): ByteArray? {
        val out = ByteArray(BYTES_TOTAL)
        var acumulado = 0L
        var bits = 0
        var i = 0
        for (c in simbolos) {
            val v = ALFABETO.indexOf(c)
            if (v < 0) return null
            acumulado = (acumulado shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                // El ultimo simbolo aporta bits de relleno que no forman
                // byte: sin este guard, escribirlos desbordaria el arreglo.
                if (i < BYTES_TOTAL) out[i++] = ((acumulado shr bits) and 0xFF).toByte()
            }
        }
        if (i != BYTES_TOTAL) return null

        // Los bits de relleno TIENEN que ser cero, y comprobarlo no es
        // pedanteria de formato.
        //
        // 17 bytes son 136 bits; 28 simbolos son 140. Sobran 4, que al
        // generar se escriben a cero. Si no se comprobaran, esos 4 bits del
        // ultimo simbolo darian igual: de los 31 simbolos equivocados que se
        // pueden escribir ahi, 15 producirian los MISMOS 17 bytes, pasarian
        // el byte de control y el codigo se daria por bueno.
        //
        // O sea que casi la mitad de las erratas en el ultimo caracter se
        // aceptaban en silencio. Lo encontro la prueba que mide la tasa de
        // deteccion: fallaba de vez en cuando, y el motivo no era el azar.
        if (bits > 0 && (acumulado and ((1L shl bits) - 1)) != 0L) return null
        return out
    }
}
