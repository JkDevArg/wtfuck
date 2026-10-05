package com.wtfuck.app.datos

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Lo que se le hizo a una foto en el editor de estados, sin nada de Android.
 *
 * ## Por que esta aparte
 *
 * La vista previa la dibuja Compose y el archivo final lo dibuja un `Canvas`
 * de Android a 1080x1920. Si cada uno calculara la geometria a su manera, lo
 * publicado no seria lo que se vio: un sticker unos milimetros corrido, una
 * foto con otro encuadre. Asi que la geometria vive aqui una sola vez, en
 * coordenadas NORMALIZADAS al lienzo, y los dos la usan. Y se puede probar.
 *
 * ## El lienzo
 *
 * Siempre 9:16, vertical: es la forma de un estado en cualquier app, y la que
 * pidio quien lo encargo ("siempre deben verse vertical"). Una foto apaisada
 * no se deforma: se ENCUADRA, y lo que queda fuera del marco es el recorte.
 * Por eso recortar no es una herramienta aparte: es mover y acercar la foto
 * dentro del marco, que es como lo hace Instagram.
 */
object Lienzo {
    const val ANCHO = 1080
    const val ALTO = 1920
    const val PROPORCION = ANCHO.toFloat() / ALTO

    /** Hasta donde se deja acercar. Mas de 5x ya es pixel suelto. */
    const val ZOOM_MAX = 5f
}

/**
 * Una capa encima de la foto: un texto o un sticker.
 *
 * `x` e `y` son el CENTRO de la capa, de 0 a 1 sobre el ancho y el alto del
 * lienzo. `escala` multiplica el tamano base; `giro` va en grados.
 */
sealed class Capa {
    abstract val id: Long
    abstract val x: Float
    abstract val y: Float
    abstract val escala: Float
    abstract val giro: Float

    data class Texto(
        override val id: Long,
        val texto: String,
        /** ARGB. */
        val color: Long,
        /** Con caja oscura detras: se lee sobre cualquier foto. */
        val conFondo: Boolean = true,
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val escala: Float = 1f,
        override val giro: Float = 0f,
    ) : Capa()

    /** Un emoji grande o un sticker propio (ruta del archivo). */
    data class Sticker(
        override val id: Long,
        val emoji: String = "",
        val ruta: String = "",
        override val x: Float = 0.5f,
        override val y: Float = 0.5f,
        override val escala: Float = 1f,
        override val giro: Float = 0f,
    ) : Capa()
}

/**
 * El estado completo de la edicion de una foto.
 *
 * `giro` es el giro de la FOTO en pasos de 90 grados (0, 90, 180, 270): la
 * herramienta "girar" de una foto mal orientada. El giro libre es de las
 * capas, no de la foto.
 *
 * `zoom` es sobre la escala que cubre el lienzo: 1 = la foto justo lo cubre.
 * `desplX`/`desplY` mueven la foto, en fraccion del ancho y del alto del
 * lienzo.
 */
data class EdicionFoto(
    val giro: Int = 0,
    val zoom: Float = 1f,
    val desplX: Float = 0f,
    val desplY: Float = 0f,
    val filtro: FiltroFoto = FiltroFoto.ORIGINAL,
    val capas: List<Capa> = emptyList(),
)

/** Una transformacion afin: x' = a*x + c*y + tx, y' = b*x + d*y + ty. */
data class Afin(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun aplicar(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + tx) to (b * x + d * y + ty)
}

object Encuadre {

    /** Giro normalizado a 0, 90, 180 o 270. */
    fun giroNormal(giro: Int): Int = ((giro % 360) + 360) % 360 / 90 * 90

    /** Ancho y alto de la foto YA girada. */
    fun dimensionesGiradas(ancho: Int, alto: Int, giro: Int): Pair<Int, Int> =
        if (giroNormal(giro) % 180 == 0) ancho to alto else alto to ancho

    /**
     * La escala con la que la foto (girada) cubre el lienzo sin dejar huecos.
     * Es "cubrir" y no "contener": un estado no lleva franjas negras.
     */
    fun escalaCubrir(ancho: Int, alto: Int, giro: Int, anchoLienzo: Float, altoLienzo: Float): Float {
        val (w, h) = dimensionesGiradas(ancho, alto, giro)
        if (w <= 0 || h <= 0) return 1f
        return max(anchoLienzo / w, altoLienzo / h)
    }

    /** El zoom, dentro de lo permitido: nunca menos que cubrir. */
    fun zoomValido(zoom: Float): Float = zoom.coerceIn(1f, Lienzo.ZOOM_MAX)

    /**
     * Cuanto se puede mover la foto sin destapar el borde del lienzo, en
     * fraccion del lienzo. Con zoom 1 en el lado justo es 0: no hay margen.
     */
    fun margen(ancho: Int, alto: Int, e: EdicionFoto, anchoLienzo: Float, altoLienzo: Float): Pair<Float, Float> {
        val s = escalaCubrir(ancho, alto, e.giro, anchoLienzo, altoLienzo) * zoomValido(e.zoom)
        val (w, h) = dimensionesGiradas(ancho, alto, e.giro)
        val sobraX = (w * s - anchoLienzo).coerceAtLeast(0f) / 2f / anchoLienzo
        val sobraY = (h * s - altoLienzo).coerceAtLeast(0f) / 2f / altoLienzo
        return sobraX to sobraY
    }

    /** La edicion con el desplazamiento recortado para no destapar bordes. */
    fun acotar(ancho: Int, alto: Int, e: EdicionFoto, anchoLienzo: Float, altoLienzo: Float): EdicionFoto {
        val z = zoomValido(e.zoom)
        val conZoom = e.copy(zoom = z)
        val (mx, my) = margen(ancho, alto, conZoom, anchoLienzo, altoLienzo)
        return conZoom.copy(desplX = e.desplX.coerceIn(-mx, mx), desplY = e.desplY.coerceIn(-my, my))
    }

    /**
     * Donde va cada pixel de la foto ORIGINAL dentro del lienzo.
     *
     * En orden: centrar la foto en el origen, girarla, escalarla y llevarla al
     * centro del lienzo mas el desplazamiento. La misma cuenta sirve para la
     * vista previa (lienzo en pixeles de pantalla) y para el archivo final
     * (1080x1920): por eso el desplazamiento va normalizado.
     */
    fun transformacion(ancho: Int, alto: Int, e: EdicionFoto, anchoLienzo: Float, altoLienzo: Float): Afin {
        val s = escalaCubrir(ancho, alto, e.giro, anchoLienzo, altoLienzo) * zoomValido(e.zoom)
        val rad = Math.toRadians(giroNormal(e.giro).toDouble())
        val co = cos(rad).toFloat()
        val si = sin(rad).toFloat()
        // Redondeo de los cosenos de 90 y 270: sin esto queda un 6e-17 que
        // tuerce la foto una fraccion de pixel.
        val c0 = if (kotlin.math.abs(co) < 1e-6f) 0f else co
        val s0 = if (kotlin.math.abs(si) < 1e-6f) 0f else si
        val a = s * c0
        val b = s * s0
        val c = -s * s0
        val d = s * c0
        val cx = ancho / 2f
        val cy = alto / 2f
        val tx = anchoLienzo / 2f + e.desplX * anchoLienzo - (a * cx + c * cy)
        val ty = altoLienzo / 2f + e.desplY * altoLienzo - (b * cx + d * cy)
        return Afin(a, b, c, d, tx, ty)
    }
}

/**
 * Los filtros, como matrices de color de 4x5 (el formato de `ColorMatrix`,
 * igual en Compose y en Android).
 *
 * Son matrices y no "efectos" con nombre porque asi el mismo numero sirve
 * para la vista previa y para el archivo, y una matriz se puede probar.
 */
enum class FiltroFoto(val etiqueta: String, val matriz: FloatArray) {
    ORIGINAL("Original", Matrices.identidad()),
    BN("B/N", Matrices.saturacion(0f)),
    SEPIA(
        "Sepia",
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
    CALIDO("Cálido", Matrices.escalaCanales(1.10f, 1.02f, 0.88f)),
    FRIO("Frío", Matrices.escalaCanales(0.90f, 1.0f, 1.12f)),
    VIVIDO("Vívido", Matrices.saturacion(1.45f)),
    SUAVE("Suave", Matrices.desvanecido(0.82f, 28f)),
    NOCHE("Noche", Matrices.producto(Matrices.saturacion(0.6f), Matrices.escalaCanales(0.85f, 0.9f, 1.08f))),
    ;
}

/**
 * Las piezas con las que se arman los filtros.
 *
 * En un `object` aparte y NO en el `companion` del enum: las entradas de un
 * enum se construyen antes que su companion, y llamarlo desde los argumentos
 * de una entrada da un NullPointerException al cargar la clase.
 */
object Matrices {
    fun identidad(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Saturacion con los pesos de luminancia de Rec. 709. */
    fun saturacion(s: Float): FloatArray {
        val r = 0.2126f * (1 - s)
        val g = 0.7152f * (1 - s)
        val b = 0.0722f * (1 - s)
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun escalaCanales(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Baja el contraste y levanta los negros: el aspecto "lavado". */
    fun desvanecido(contraste: Float, levante: Float): FloatArray = floatArrayOf(
        contraste, 0f, 0f, 0f, levante,
        0f, contraste, 0f, 0f, levante,
        0f, 0f, contraste, 0f, levante,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Aplica `primero` y despues `luego`. */
    fun producto(primero: FloatArray, luego: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (fila in 0 until 4) {
            for (col in 0 until 5) {
                var v = 0f
                for (k in 0 until 4) v += luego[fila * 5 + k] * primero[k * 5 + col]
                if (col == 4) v += luego[fila * 5 + 4]
                r[fila * 5 + col] = v
            }
        }
        return r
    }
}

/**
 * La forma del recorte. Un estado es siempre 9:16; una foto del chat puede
 * quedarse como vino o recortarse a las formas de siempre.
 *
 * `valor` es ancho/alto; null = la de la propia foto (ya girada).
 */
enum class Proporcion(val etiqueta: String, val valor: Float?) {
    ORIGINAL("Original", null),
    CUADRADA("1:1", 1f),
    RETRATO("4:5", 4f / 5f),
    VERTICAL("9:16", 9f / 16f),
    APAISADA("16:9", 16f / 9f),
    ;

    /** Ancho/alto efectivo para una foto de ese tamano con ese giro. */
    fun efectiva(anchoFoto: Int, altoFoto: Int, giro: Int): Float {
        valor?.let { return it }
        val (w, h) = Encuadre.dimensionesGiradas(anchoFoto, altoFoto, giro)
        return if (w > 0 && h > 0) w.toFloat() / h else 1f
    }

    companion object {
        /**
         * El lado mayor mas grande que se puede sacar SIN agrandar la foto.
         *
         * Un recorte cuadrado de una foto de 2400x1500 solo tiene 1500 pixeles
         * de lado de verdad; sacarlo a 2400 era inflarlo: mas peso para
         * mandar y ni un detalle mas. Lo mismo con el zoom: acercar 2x deja
         * la mitad de pixeles reales.
         *
         * @param proporcion ancho/alto del recorte.
         * @param zoom el de la edicion (1 = la foto justo cubre el recorte).
         */
        fun ladoSinAgrandar(proporcion: Float, anchoFoto: Int, altoFoto: Int, giro: Int, zoom: Float): Int {
            val (w, h) = Encuadre.dimensionesGiradas(anchoFoto, altoFoto, giro)
            if (w <= 0 || h <= 0) return 0
            val p = if (proporcion > 0f) proporcion else 1f
            // El recorte con lado mayor 1: cuanto de la foto (girada) cubre.
            val (w1, h1) = if (p >= 1f) 1f to 1f / p else p to 1f
            val k = maxOf(w1 / w, h1 / h) * Encuadre.zoomValido(zoom)
            return (1f / k).toInt()
        }

        /**
         * El tamano del archivo final: el lado mayor mide `ladoMayor`.
         * Redondeado a par: algunos codificadores se quejan de lados impares.
         */
        fun salida(proporcion: Float, ladoMayor: Int): Pair<Int, Int> {
            val p = if (proporcion > 0f) proporcion else 1f
            val (w, h) = if (p >= 1f) ladoMayor.toFloat() to ladoMayor / p else ladoMayor * p to ladoMayor.toFloat()
            fun par(x: Float) = (Math.round(x / 2f) * 2).coerceAtLeast(2)
            return par(w) to par(h)
        }
    }
}

/** Lo que dura como maximo un estado de audio. Un minuto, como una nota de voz larga. */
const val AUDIO_ESTADO_MAX_MS = 60_000
