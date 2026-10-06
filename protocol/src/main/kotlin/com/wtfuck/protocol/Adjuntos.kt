package com.wtfuck.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modulo D: adjuntos.
 *
 * El flujo tiene tres pasos, y el orden importa:
 *
 *   1. RESERVAR  el cliente dice "voy a subir una imagen de N bytes".
 *                El servidor comprueba permiso, limite y cuota, y devuelve
 *                una URL firmada para subir.
 *   2. SUBIR     el cliente cifra el archivo y lo sube a esa URL. Los bytes
 *                NO pasan por el servidor de la aplicacion.
 *   3. CONFIRMAR el cliente avisa que termino. Recien ahi el adjunto cuenta.
 *
 * Reservar antes de subir es lo que permite rechazar por permiso o cuota SIN
 * haber transferido 40 MB para nada.
 */

const val RUTA_ADJUNTOS = "/v1/adjuntos"

/** Clases de adjunto y el permiso que cada una exige. */
object ClaseAdjunto {
    const val IMAGEN = "imagen"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val NOTA_VOZ = "nota_voz"
    const val DOCUMENTO = "documento"
    const val STICKER = "sticker"

    val TODAS = listOf(IMAGEN, VIDEO, AUDIO, NOTA_VOZ, DOCUMENTO, STICKER)

    /** Limite por clase, en bytes. Un sticker de 60 MB no es un sticker. */
    fun limite(clase: String): Long = when (clase) {
        IMAGEN -> 16L * 1024 * 1024
        VIDEO -> 64L * 1024 * 1024
        AUDIO -> 32L * 1024 * 1024
        NOTA_VOZ -> 16L * 1024 * 1024
        DOCUMENTO -> 64L * 1024 * 1024
        STICKER -> 2L * 1024 * 1024
        else -> 0L
    }

    fun permiso(clase: String): String = when (clase) {
        IMAGEN -> "media.imagen"
        VIDEO -> "media.video"
        AUDIO -> "media.audio"
        NOTA_VOZ -> "media.nota_voz"
        DOCUMENTO -> "media.documento"
        STICKER -> "media.sticker_gif"
        else -> "media.documento"
    }
}

@Serializable
data class ReservarAdjuntoReq(
    /**
     * La conversacion, o vacio si el archivo es de una historia.
     *
     * Exactamente uno de los dos: de ahi sale quien puede bajarlo despues, y
     * son dos preguntas distintas —participante de un chat, o destinatario de
     * una historia—. La base lo impone con un CHECK; ver V30.
     */
    val conversacionId: String = "",
    /** La historia, cuando el archivo va en una. */
    val historiaId: String? = null,
    val clase: String,
    /** Bytes del archivo YA CIFRADO: es lo que se va a transferir. */
    val bytes: Long,
    val mime: String = "",
    val nombre: String = "",
    val ancho: Int = 0,
    val alto: Int = 0,
    val duracionMs: Int = 0,
)

@Serializable
data class AdjuntoReservado(
    val adjuntoId: String,
    /** URL firmada para hacer PUT del archivo cifrado. Caduca pronto. */
    val urlSubida: String,
    val expiraEn: Long,
)

@Serializable
data class AdjuntoInfo(
    val adjuntoId: String,
    val clase: String,
    val bytes: Long,
    val mime: String,
    val nombre: String,
    val ancho: Int,
    val alto: Int,
    val duracionMs: Int,
    /** URL firmada para descargar. Caduca: hay que pedirla cuando se usa. */
    val urlDescarga: String,
    val expiraEn: Long,
)

@Serializable
data class UsoAlmacenamiento(
    val archivos: Int,
    val bytes: Long,
    val cuotaBytes: Long,
)

// ============================================================
//  Carga que viaja en el sobre
// ============================================================

/**
 * Referencia a un adjunto, dentro del mensaje.
 *
 * La CLAVE de descifrado viaja aqui, dentro del sobre, y por tanto cifrada de
 * extremo a extremo desde el modulo E. El servidor guarda el archivo pero
 * jamas ve con que abrirlo.
 */
@Serializable
@SerialName("adjunto")
data class CargaAdjunto(
    val adjuntoId: String,
    val clase: String,
    /** Base64 de la clave AES-256 del archivo. */
    val clave: String,
    /** Base64 del nonce/IV. */
    val nonce: String,
    val mime: String = "",
    val nombre: String = "",
    val bytes: Long = 0,
    val ancho: Int = 0,
    val alto: Int = 0,
    val duracionMs: Int = 0,
    /**
     * La silueta de una nota de voz. Vacio si no la hay. Ver [Onda].
     *
     * Con valor por defecto para que una version vieja que no lo mande siga
     * entendiendose: lo que llega sin onda se dibuja como siempre.
     */
    val onda: String = "",
    /** Texto que acompana al archivo, si lo hay. */
    val pie: String = "",
    /**
     * Miniatura JPEG en base64, unos pocos KB.
     *
     * Viaja DENTRO del sobre y no en el almacen, y eso es deliberado: quien
     * recibe ve la foto al instante, sin pedir nada y sin gastar datos. El
     * archivo completo se descarga solo si lo abre. En 3G es la diferencia
     * entre un chat que responde y uno que no.
     */
    val miniatura: String = "",
    /** Ver `Carga.Texto.silencioso`. */
    val silencioso: Boolean = false,
    /** Si es un reenvio, de quien venia. Ver `Carga.Texto.reenviadoDe`. */
    val reenviadoDe: String? = null,
    /**
     * "Ver una vez": quien recibe la abre una vez y se borra de su telefono.
     *
     * Va sin miniatura y sin pie, a proposito: la miniatura es la foto en
     * chico y el pie se queda en el chat, en la lista y en la notificacion.
     * Quien recibe ignora los dos aunque vengan. Ver `VisorUnaVez`.
     */
    val unaVez: Boolean = false,
    /**
     * Como se dibuja. "circulo" = videonota, como las de Telegram. Vacio = como
     * siempre. Un cliente viejo lo ignora y la ve como un video normal, que es
     * exactamente lo que es.
     */
    val forma: String = "",
    /**
     * Foto o video "spoiler": quien recibe lo ve difuminado hasta tocarlo. Un
     * cliente viejo lo ignora y lo muestra como siempre.
     */
    val spoiler: Boolean = false,
) : Carga

// ============================================================
//  La forma de onda de una nota de voz
// ============================================================

/**
 * La silueta de una nota de voz, comprimida a un puñado de caracteres.
 *
 * ## Por qué viaja, si el audio ya viaja
 *
 * Porque dibujar la forma real exigiría **decodificar el archivo entero en
 * cada burbuja de la lista**, y una conversación con veinte notas de voz son
 * veinte decodificaciones cada vez que se hace scroll. Quien graba ya tiene
 * los niveles gratis —el micrófono se los da mientras graba— así que los manda
 * hechos.
 *
 * Cuarenta caracteres. Es menos que el nombre del archivo.
 *
 * ## Por qué se normaliza al máximo de la propia nota
 *
 * Si se guardaran los niveles absolutos, casi todas las notas se verían planas:
 * hablar normal no satura el micrófono ni de lejos, y la diferencia entre una
 * sílaba y un silencio quedaría en unos pocos píxeles.
 *
 * Normalizar hace que cada nota use todo el alto disponible, que es lo único
 * que esta figura tiene que comunicar: dónde hay voz y dónde no. No es un
 * medidor: nadie compara el volumen de dos notas mirando los dibujos.
 */
object Onda {

    /** Cuántas barras. Es también el largo exacto de la cadena. */
    const val BARRAS = 40

    /**
     * 32 niveles, un carácter cada uno.
     *
     * Base 32 y no base 64 para que la cadena sea legible en un volcado y no
     * lleve `+`, `/` ni `=`, que obligarían a pensar en escapes cada vez que
     * esto pase por un JSON o por una URL. Treinta y dos niveles de altura son
     * más de los que un ojo distingue en una barra de 20 dp.
     */
    private const val ALFABETO = "0123456789abcdefghijklmnopqrstuv"

    /**
     * Comprime las muestras a la cadena.
     *
     * Devuelve `""` cuando no hay nada que dibujar —sin muestras, o todas en
     * silencio—, y eso es deliberado: una cadena de cuarenta ceros sería una
     * línea plana dibujada con seguridad, y no tener figura es distinto de
     * tener una figura vacía. Quien la reciba usa la de siempre.
     */
    fun codificar(muestras: List<Float>): String {
        if (muestras.isEmpty()) return ""
        val tope = muestras.max()
        if (tope <= 0f) return ""

        val sb = StringBuilder(BARRAS)
        for (i in 0 until BARRAS) {
            // Los bordes se calculan por multiplicación y no acumulando un
            // paso: con pocas muestras, acumular deja el último cubo fuera del
            // rango por redondeo y se pierde el final de la nota.
            val desde = i * muestras.size / BARRAS
            val hasta = ((i + 1) * muestras.size / BARRAS).coerceAtLeast(desde + 1)

            // El MÁXIMO del tramo, no el promedio. Promediar borra justo lo
            // que se quiere ver: una sílaba corta entre dos silencios se
            // promedia hasta desaparecer, y la figura queda lisa.
            var pico = 0f
            for (j in desde until minOf(hasta, muestras.size)) {
                if (muestras[j] > pico) pico = muestras[j]
            }
            val nivel = ((pico / tope) * (ALFABETO.length - 1)).toInt()
                .coerceIn(0, ALFABETO.length - 1)
            sb.append(ALFABETO[nivel])
        }
        return sb.toString()
    }

    /**
     * Lee la cadena, o devuelve `null` si no es una.
     *
     * Devuelve null y no una lista vacía ni una por defecto: esto llega dentro
     * de un sobre que escribió otra persona, y lo único sensato con algo que
     * no se entiende es no usarlo. Quien dibuja ya sabe qué hacer sin figura.
     */
    fun decodificar(s: String): List<Float>? {
        if (s.length != BARRAS) return null
        val fuera = ALFABETO.length - 1f
        val salida = ArrayList<Float>(BARRAS)
        for (c in s) {
            val i = ALFABETO.indexOf(c)
            if (i < 0) return null
            salida.add(i / fuera)
        }
        return salida
    }
}
