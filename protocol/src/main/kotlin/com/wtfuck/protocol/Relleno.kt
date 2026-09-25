package com.wtfuck.protocol

/**
 * El tamano de lo que se manda tambien dice cosas.
 *
 * ## Que se filtra sin esto
 *
 * El servidor no puede abrir un sobre — eso es todo el producto. Pero **si
 * puede medirlo**, y la longitud del texto cifrado sigue de cerca a la del
 * claro: el cifrado de Signal usa bloques de 16 bytes y no esconde el tamano.
 *
 * Eso es mas de lo que parece. Un sobre de 40 bytes no es un parrafo: es "ok",
 * "si", "ya voy". Uno de 4 KB en mitad de una conversacion de sobres de 50
 * bytes es alguien pegando algo. Y la secuencia de tamanos y tiempos dibuja la
 * forma de la charla sin leer una sola palabra — quien pregunta y quien
 * contesta, cuando alguien duda, cuando alguien pega una direccion.
 *
 * No hace falta romper el cifrado para eso. Basta con `SELECT length(cuerpo)`.
 *
 * ## Como
 *
 * Se rellena el JSON **antes de cifrar**, hasta el siguiente cubo. Asi el
 * relleno queda **dentro** del cifrado: va autenticado como el resto, y nadie
 * en el camino puede quitarlo ni medir por debajo de el.
 *
 * ## Por que dos escalas de cubo
 *
 * Al doble hasta 8 KiB, y de 8 en 8 KiB despues. La razon es donde esta la
 * informacion: los mensajes de texto viven en los primeros cientos de bytes, y
 * ahi hay que ser grueso —entre 40 y 256 bytes se pierde la diferencia entre
 * "ok" y una frase—. Arriba, el tamano ya lo domina una miniatura y no lo que
 * alguien escribio, asi que seguir duplicando regalaria hasta 30 KiB por sobre
 * para esconder algo que ya no dice nada.
 *
 * ## Lo que NO esconde
 *
 * Que hubo un mensaje, cuando, entre quienes y en que conversacion. El relleno
 * tapa el tamano, no la existencia. Para lo otro haria falta trafico de
 * cobertura —sobres vacios todo el tiempo—, que es otra decision y mucho mas
 * cara.
 *
 * ## Por que ceros y no espacios
 *
 * Se penso en espacios, porque un parser de JSON los ignora al final. Eso
 * depende de que el parser sea tolerante, que es una propiedad de la
 * biblioteca y puede cambiar en una actualizacion sin que nadie lo note. Un
 * byte cero no aparece nunca crudo en JSON —dentro de una cadena viaja
 * escapado como `\u0000`, seis caracteres, ninguno de ellos cero—, asi que
 * recortarlos al final no puede comerse nada del contenido.
 */
object Relleno {

    /**
     * Hasta donde se rellena.
     *
     * El techo de verdad lo pone el SERVIDOR: `manejarEnvio` rechaza un cuerpo
     * cifrado de mas de 64 KiB. Este numero se queda por debajo para dejar
     * sitio a la cabecera de Signal, que se suma despues de rellenar.
     *
     * 60 KiB cubre el caso legitimo mas pesado con holgura: un texto con dos
     * miniaturas de 20 KiB —la del adjunto y la de una historia citada—, que
     * con el Base64 puesto rondan 54 KiB.
     */
    const val MAXIMO = 60 * 1024

    /** Los tamanos posibles. Ver la nota de las dos escalas. */
    val CUBOS: IntArray = buildList {
        var v = 256
        while (v <= 8 * 1024) { add(v); v *= 2 }
        v = 16 * 1024
        while (v <= MAXIMO) { add(v); v += 8 * 1024 }
        if (last() != MAXIMO) add(MAXIMO)
    }.toIntArray()

    /** El cubo al que le toca un tamano, o -1 si no cabe en ninguno. */
    fun cubo(bytes: Int): Int = CUBOS.firstOrNull { it >= bytes } ?: -1

    /**
     * El JSON rellenado hasta su cubo.
     *
     * Si no entra en ningun cubo **se devuelve tal cual, sin rellenar**, y no
     * se lanza. Parece al reves de lo prudente y hubo que pensarlo:
     *
     *  - Lanzar romperia un envio que hoy funciona. El tope real es el del
     *    servidor, y un sobre de 62 KiB pasa hoy; convertirlo en una excepcion
     *    seria cambiar una mejora de privacidad por una regresion de
     *    funcionamiento.
     *  - Y no deja un hueco: si de verdad es demasiado grande, el servidor lo
     *    rechaza igual, que es donde tiene que decidirse.
     *
     * El costo es que un sobre enorme viaja sin rellenar y su tamano se ve. Es
     * aceptable: ahi el tamano ya no dice nada util, porque lo domina una
     * miniatura y no lo que alguien escribio.
     */
    fun poner(claro: ByteArray): ByteArray {
        val destino = cubo(claro.size)
        if (destino <= 0) return claro
        return claro.copyOf(destino)  // el resto ya viene en cero
    }

    /**
     * Quita el relleno.
     *
     * Tolerante con lo que llega sin el: los dos lados pueden tener versiones
     * distintas de la app, y un sobre de una version anterior no lleva ceros
     * al final. Sin relleno que quitar devuelve lo mismo que recibio.
     */
    fun quitar(claro: ByteArray): ByteArray {
        var fin = claro.size
        while (fin > 0 && claro[fin - 1] == 0.toByte()) fin--
        return if (fin == claro.size) claro else claro.copyOf(fin)
    }
}
