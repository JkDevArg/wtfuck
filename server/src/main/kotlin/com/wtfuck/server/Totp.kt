package com.wtfuck.server

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238), el segundo factor de toda la vida.
 *
 * Se implementa en vez de traer una libreria porque son cuarenta lineas y la
 * norma no cambia: HMAC-SHA1 sobre el contador de 30 segundos, y los ultimos
 * cuatro bits del hash dicen de donde recortar. Una dependencia para esto seria
 * mas superficie que codigo.
 */
object Totp {

    private const val DIGITOS = 6
    private const val PERIODO_S = 30L

    /**
     * Cuantos periodos de tolerancia hacia atras y hacia adelante.
     *
     * Uno, no cero: los relojes de los telefonos se desvian unos segundos y sin
     * tolerancia el codigo falla justo en el cambio de ventana, que se lee como
     * "la app esta rota". Tampoco mas de uno: cada periodo extra alarga la vida
     * util de un codigo interceptado.
     */
    private const val TOLERANCIA = 1

    /** SHA-1 por norma. No es una eleccion de seguridad: es lo que exige TOTP. */
    private const val ALGO = "HmacSHA1"

    fun valido(secreto: ByteArray, codigo: String, ahoraMs: Long = System.currentTimeMillis()): Boolean {
        val limpio = codigo.trim().replace(" ", "")
        if (limpio.length != DIGITOS || limpio.any { !it.isDigit() }) return false

        val contador = ahoraMs / 1000 / PERIODO_S
        for (d in -TOLERANCIA..TOLERANCIA) {
            // Comparacion en tiempo constante. El margen que se filtra por
            // tiempo es minimo, pero no hay razon para regalarlo.
            if (MessageDigest.isEqual(generar(secreto, contador + d).toByteArray(), limpio.toByteArray())) {
                return true
            }
        }
        return false
    }

    fun generar(secreto: ByteArray, contador: Long): String {
        val msg = ByteArray(8)
        var v = contador
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xff).toByte()
            v = v shr 8
        }

        val mac = Mac.getInstance(ALGO).apply { init(SecretKeySpec(secreto, ALGO)) }
        val h = mac.doFinal(msg)

        // Truncamiento dinamico: los 4 bits bajos del ultimo byte dicen el
        // desplazamiento desde donde tomar los 4 bytes del codigo.
        val off = (h[h.size - 1].toInt() and 0x0f)
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)

        return "%0${DIGITOS}d".format(bin % 1_000_000)
    }

    // ============================================================
    //  Base32
    // ============================================================
    //
    // Las apps de autenticacion esperan el secreto en base32, no en base64 ni
    // en hexadecimal. Es la unica razon por la que esto existe aqui.

    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun aBase32(datos: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in datos) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ABC[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ABC[(buffer shl (5 - bits)) and 31])
        // Sin relleno con '=': las URIs otpauth lo aceptan sin relleno y varias
        // apps se atragantan con el.
        return sb.toString()
    }

    fun deBase32(s: String): ByteArray {
        val limpio = s.trim().uppercase().replace("=", "").replace(" ", "")
        val salida = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in limpio) {
            val i = ABC.indexOf(ch)
            require(i >= 0) { "Caracter invalido en base32: $ch" }
            buffer = (buffer shl 5) or i
            bits += 5
            if (bits >= 8) {
                salida.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return salida.toByteArray()
    }
}
