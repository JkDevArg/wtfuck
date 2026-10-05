package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo C: mensajes ricos.
 *
 * Reparto de responsabilidades, que es lo importante aqui:
 *
 * - El CONTENIDO (texto, media) viaja dentro del sobre, opaco, y desde el
 *   modulo E va cifrado. El servidor nunca lo ve.
 * - Las ACCIONES sobre un mensaje (retirar, editar, fijar, reaccionar) pasan
 *   por HTTP y las autoriza el servidor. No pueden ir cifradas entre clientes:
 *   si fueran, el servidor no podria comprobar que quien borra el mensaje de
 *   otro tenga de verdad el permiso para hacerlo.
 *
 * Por eso el servidor guarda METADATOS de mensaje (quien, cuando) pero jamas
 * el contenido. Ver la cabecera de V6__mensajes.sql.
 */

const val RUTA_MENSAJES = "/v1/mensajes"

// ============================================================
//  Lo que viaja dentro del sobre (cifrado desde el modulo E)
// ============================================================

/** Metadatos del mensaje que solo los clientes necesitan entender. */
@Serializable
data class SobreTexto(
    val texto: String,
    /** Id del mensaje al que responde. */
    val respondeA: String? = null,
    /** Usernames mencionados, ya extraidos por el remitente. */
    val menciones: List<String> = emptyList(),
    /** Si es un reenvio, de quien venia. */
    val reenviadoDe: String? = null,
)

// ============================================================
//  Menciones
// ============================================================

/**
 * Que cuenta como una mencion dentro de un texto.
 *
 * ## Por que vive en el contrato y no en la app
 *
 * Porque **hay dos sitios que tienen que estar de acuerdo**, y hasta ahora no
 * habia nada que los obligara: el remitente extrae las menciones de su propio
 * texto y las manda en `menciones`, y la pantalla las pinta de otro color. Si
 * cada uno usara su regla, la burbuja resaltaria un nombre que nunca se
 * registro —una mencion que no avisa a nadie, dibujada como si avisara— o al
 * reves.
 *
 * `[a-z0-9_]{3,24}` es el formato de un username. En minusculas porque el
 * servidor los guarda asi y `mencionesEn` baja el texto antes de buscar: quien
 * escribe "@Tatiana" menciona a `tatiana`.
 *
 * ## Lo que NO hace
 *
 * No comprueba que la persona exista ni que este en la conversacion. Eso lo
 * hace el servidor contra los participantes reales, que es donde se puede: el
 * cliente propone y el servidor decide. Mencionar a alguien que no esta
 * simplemente no registra nada.
 */
val PATRON_MENCION = Regex("@([a-z0-9_]{3,24})")

/** Los usernames mencionados en un texto, en minusculas y sin repetir. */
fun mencionesEn(texto: String): List<String> =
    PATRON_MENCION.findAll(texto.lowercase())
        .map { it.groupValues[1] }
        .distinct()
        .toList()

// ============================================================
//  Acciones sobre un mensaje (HTTP, autorizadas por el servidor)
// ============================================================

@Serializable
data class RegistrarMensajeReq(
    val mensajeId: String,
    val conversacionId: String,
    val respondeA: String? = null,
    val menciones: List<String> = emptyList(),
    /**
     * Si es un reenvio, el **username** de quien lo escribio originalmente.
     *
     * Es un username y no un id, y hubo que decidirlo: el cliente guarda el
     * autor de cada mensaje por su nombre —es lo que pinta en la burbuja— y no
     * su id, asi que pedirle un id lo obligaria a una consulta al servidor por
     * cada reenvio. Lo resuelve el servidor contra `usuario`, igual que ya hacia
     * con [menciones].
     *
     * Estuvo roto desde el modulo C hasta el N: el cliente mandaba el username
     * y el servidor hacia `UUID.fromString` con el, asi que **todo reenvio se
     * rechazaba con 400**. No lo vio ninguna prueba porque las que tocaban
     * `reenviadoDe` pasaban un uuid a mano, que es justo lo que el cliente no
     * manda.
     */
    val reenviadoDe: String? = null,
    /**
     * Adjunto que acompana a este mensaje, si lo hay.
     *
     * El enlace se hace AQUI y no al confirmar el adjunto porque el orden real
     * es al revES: el archivo se sube primero -el sobre necesita su id y su
     * clave- y el mensaje se registra despues. Al confirmar, el mensaje todavia
     * no existe en el servidor.
     */
    val adjuntoId: String? = null,

    /**
     * Que clase de contenido dice llevar el sobre. Ver [ClaseContenido].
     *
     * Vacio -el caso normal- significa texto o adjunto, y no se pide nada mas
     * que `mensaje.enviar`. Se declara solo lo que hace falta para autorizar:
     * una ubicacion o un contacto no piden permiso propio, asi que un cliente
     * no tiene por que anunciarlos.
     */
    val clase: String = ClaseContenido.TEXTO,
)

@Serializable
data class EditarMensajeReq(
    /**
     * El texto nuevo NO viaja aqui: va cifrado en un sobre aparte. Esta
     * peticion solo pide permiso y marca el mensaje como editado.
     */
    val mensajeId: String,
)

@Serializable
data class ReaccionReq(
    val mensajeId: String,
    val emoji: String,
    /** false = quitar la reaccion. */
    val poner: Boolean = true,
)

/** El id del mensaje viaja en la ruta; repetirlo aqui solo crea la duda de
 *  cual gana si no coinciden. */
@Serializable
data class FijarReq(val fijar: Boolean = true)

@Serializable
data class TemporalesReq(
    /** Segundos de vida. null = mensajes permanentes. */
    val segundos: Int? = null,
)

// ============================================================
//  Lo que el servidor devuelve
// ============================================================

@Serializable
data class MensajeMeta(
    val id: String,
    val conversacionId: String,
    val autorId: String,
    val autorUsername: String,
    val creadoEn: Long,
    val respondeA: String? = null,
    val reenviadoDe: String? = null,
    val editadoEn: Long? = null,
    val retiradoEn: Long? = null,
    val fijadoEn: Long? = null,
    val expiraEn: Long? = null,
    val reacciones: List<ReaccionAgrupada> = emptyList(),
)

/** Reacciones ya contadas por emoji: la UI no deberia sumar a mano. */
@Serializable
data class ReaccionAgrupada(
    val emoji: String,
    val total: Int,
    /** true si yo reaccione con este emoji. */
    val mia: Boolean,
    /** Los primeros usernames, para el "tu y 3 mas". */
    val quienes: List<String> = emptyList(),
)

/**
 * "Info del mensaje" en un grupo: a quien le llego y quien lo leyo.
 *
 * Solo la pide quien lo escribio. Ver `Mensajes.info` en el servidor.
 */
@Serializable
data class InfoMensaje(
    val mensajeId: String,
    val miembros: List<EstadoEnMensaje>,
    /**
     * Falso si quien pregunta tiene apagadas las confirmaciones de lectura:
     * son reciprocas, y entonces tampoco ve las de los demas. La pantalla lo
     * dice en vez de mostrar a todos como "no leido".
     */
    val lecturasVisibles: Boolean = true,
)

@Serializable
data class EstadoEnMensaje(
    val username: String,
    /** Cuando le llego a su primer aparato. Null = todavia no. */
    val entregadoEn: Long? = null,
    /** Cuando lo leyo. Null = no lo leyo, o no comparte confirmaciones. */
    val leidoEn: Long? = null,
)
