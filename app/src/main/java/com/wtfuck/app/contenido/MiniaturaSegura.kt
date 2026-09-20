package com.wtfuck.app.contenido

/**
 * Las decisiones para decodificar una miniatura que mando otra persona.
 *
 * ## El agujero que cierra
 *
 * Una miniatura llega dentro del sobre, en base64, y se dibuja pasandola por
 * `BitmapFactory.decodeByteArray`. El sobre lo escribio otra persona y el
 * servidor no puede mirarlo, asi que esos bytes son entrada no confiable.
 *
 * Un PNG de 30.000 x 30.000 pixeles de un solo color ocupa unos pocos KB
 * comprimido —entra de sobra en un sobre— y al decodificarlo pide
 * `30000 * 30000 * 4` bytes, o sea **3,6 GB**. No hace falta mala intencion
 * sofisticada: es el formato haciendo su trabajo.
 *
 * Envolverlo en `runCatching` no alcanza. Captura el `OutOfMemoryError`, si, pero
 * para cuando salta, el proceso ya intento reservar esa memoria: en un telefono
 * eso es la app congelada o muerta antes de que nadie atrape nada. Lo que hay
 * que evitar no es la excepcion, es **la reserva**.
 *
 * ## Por que la tecnica correcta ya estaba escrita en el sitio equivocado
 *
 * `Media.imagenReducida` —la que CREA una miniatura de una foto propia— ya mide
 * primero con `inJustDecodeBounds` y luego decodifica con `inSampleSize`. Estaba
 * bien pensado, pero aplicado en el lado que produce.
 *
 * El lado que produce es justamente el que no hace falta proteger: ahi la imagen
 * es del telefono. El que recibe, que es donde el dato es ajeno, decodificaba a
 * pelo. Es el mismo patron que los topes del contenido de un sobre, que estaban
 * en un campo de seis.
 *
 * ## Que vive aqui y que no
 *
 * Aqui van las decisiones, que son aritmetica y se pueden probar sin Android.
 * Las llamadas a `BitmapFactory` viven en `Media`, que necesita el framework.
 */

/**
 * Lo mas grande que se acepta decodificar, en pixeles.
 *
 * Una miniatura de esta app mide 240 px de lado. Se deja margen de sobra para
 * un cliente distinto que use otro tamano, y aun asi un millon de pixeles son
 * 4 MB de bitmap: molesto, no letal.
 */
const val TOPE_PIXELES_MINIATURA = 1_000_000

/**
 * El lado maximo que se acepta declarar.
 *
 * Va aparte del tope de pixeles porque una imagen de 100.000 x 1 tiene pocos
 * pixeles y sigue siendo absurda, y porque algunos decodificadores reservan por
 * fila antes de llegar al total.
 */
const val TOPE_LADO_MINIATURA = 8_000

/**
 * Lo mas grande que se acepta como miniatura comprimida.
 *
 * Al crearla, esta app la deja en 20 KB. El tope de aqui es mas alto para no
 * romper con otro cliente, pero acotado: un sobre admite 64 KB y una miniatura
 * que se los coma entera no es una miniatura.
 */
const val TOPE_BYTES_MINIATURA = 48 * 1024

/**
 * Si unas dimensiones declaradas por el decodificador son creibles.
 *
 * Se mira **antes** de decodificar de verdad, con lo que salio de medir. Un 0 o
 * un negativo significa que no se pudo medir; mas alla del tope, que eso no es
 * una miniatura.
 */
fun miniaturaCreible(ancho: Int, alto: Int): Boolean {
    if (ancho <= 0 || alto <= 0) return false
    if (ancho > TOPE_LADO_MINIATURA || alto > TOPE_LADO_MINIATURA) return false
    // `toLong()` a proposito: `30000 * 30000` se desborda en Int y da negativo,
    // que pasaria la comprobacion. El desbordamiento silencioso es justo la
    // clase de detalle que convierte una defensa en un adorno.
    return ancho.toLong() * alto.toLong() <= TOPE_PIXELES_MINIATURA
}

/**
 * El `inSampleSize` que hay que pedirle al decodificador.
 *
 * Es potencia de dos porque es lo unico que `BitmapFactory` respeta de verdad:
 * cualquier otro valor lo redondea hacia abajo a la potencia de dos anterior, y
 * entonces el calculo de arriba deja de valer.
 *
 * Devuelve el paso mas chico con el que el bitmap resultante entra en
 * [TOPE_PIXELES_MINIATURA].
 */
fun muestreoPara(ancho: Int, alto: Int, tope: Int = TOPE_PIXELES_MINIATURA): Int {
    if (ancho <= 0 || alto <= 0) return 1
    var paso = 1
    while (
        (ancho.toLong() / paso) * (alto.toLong() / paso) > tope &&
        paso < 1 shl 16
    ) {
        paso *= 2
    }
    return paso
}

/**
 * Si unos bytes de miniatura valen la pena de intentar decodificarse.
 *
 * Se mira antes de tocarlos. Es la comprobacion mas barata de las tres.
 */
fun bytesDeMiniaturaCreibles(cuantos: Int): Boolean =
    cuantos in 1..TOPE_BYTES_MINIATURA
