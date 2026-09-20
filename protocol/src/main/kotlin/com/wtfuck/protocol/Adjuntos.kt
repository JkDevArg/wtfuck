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
) : Carga
