package com.wtfuck.app.datos

import com.wtfuck.protocol.ClaseAdjunto

/**
 * Si un archivo cabe, decidido ANTES de crear el mensaje.
 *
 * ## El defecto que arregla
 *
 * El limite se comprobaba tarde: dentro de `subirAdjunto`, cuando el mensaje
 * **ya existia** en el chat. El resultado era una burbuja roja de "fallo" con
 * el motivo escondido, en vez de un "ese video no cabe" antes de empezar.
 *
 * Y no es un caso raro: el tope de video son 64 MB y un minuto en 4K de un
 * telefono moderno pasa de 300 MB. La gente lo encuentra a la primera.
 *
 * ## Por que aqui y no en la pantalla
 *
 * Porque hay tres sitios que mandan adjuntos -el chat, responder y reenviar- y
 * la comprobacion tiene que ser la misma en los tres. Puesta en cada pantalla,
 * la tercera se olvida.
 *
 * ## Lo que NO hace
 *
 * No recomprime el video. Eso pide un transcodificador de verdad
 * -`MediaCodec` a mano o Media3 Transformer- y es un modulo aparte con su
 * propio riesgo: un transcodificador mal hecho no falla, entrega un archivo
 * roto. Mientras tanto, lo honesto es decirlo a tiempo y con el numero
 * delante, no dejar que la persona lo descubra por una burbuja roja.
 */
object CabeAdjunto {

    /** El veredicto, con lo que hace falta para escribir el aviso. */
    data class Veredicto(
        val cabe: Boolean,
        val bytes: Long,
        val limite: Long,
    )

    /**
     * El sobrecosto del cifrado se cuenta AQUI tambien.
     *
     * `subirAdjunto` compara `local.length() + SOBRECOSTO` contra el limite.
     * Si esta comprobacion no sumara lo mismo, un archivo justo en el borde
     * pasaria por aqui y fallaria despues — que es exactamente el defecto que
     * esto viene a quitar, pero solo para los archivos del borde y por tanto
     * mucho mas dificil de ver.
     */
    fun evaluar(bytes: Long, clase: String): Veredicto {
        val limite = ClaseAdjunto.limite(clase)
        return Veredicto(
            cabe = bytes + CifradorArchivo.SOBRECOSTO <= limite,
            bytes = bytes,
            limite = limite,
        )
    }

    /**
     * El aviso, en palabras.
     *
     * Dice **los dos numeros**. "El archivo es demasiado grande" obliga a
     * adivinar cuanto hay que recortar; "pesa 340 MB y el limite son 64 MB" se
     * entiende de una vez y dice que hacer.
     */
    fun aviso(v: Veredicto, clase: String): String {
        val que = when (clase) {
            ClaseAdjunto.VIDEO -> "Ese video"
            ClaseAdjunto.IMAGEN -> "Esa imagen"
            ClaseAdjunto.AUDIO, ClaseAdjunto.NOTA_VOZ -> "Ese audio"
            else -> "Ese archivo"
        }
        val extra = if (clase == ClaseAdjunto.VIDEO) {
            " Prueba a grabarlo en menor calidad, o recórtalo antes de enviarlo."
        } else {
            ""
        }
        return "$que pesa ${Media.tamanoLegible(v.bytes)} y el límite son " +
            "${Media.tamanoLegible(v.limite)}.$extra"
    }
}
