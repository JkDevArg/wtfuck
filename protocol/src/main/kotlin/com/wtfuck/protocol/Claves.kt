package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo E: distribucion de claves publicas para E2EE.
 *
 * El problema que resuelve: para escribirle a alguien hace falta material
 * criptografico suyo, y esa persona puede tener el telefono apagado. X3DH
 * -PQXDH en la version actual- lo resuelve dejando que cada quien publique de
 * antemano un juego de claves PUBLICAS, y que el servidor las reparta.
 *
 * Lo que el servidor guarda y reparte es SOLO material publico. Las claves
 * privadas no salen del telefono. Por eso el servidor puede hacer todo su
 * trabajo -repartir, cobrar cuota, moderar metadatos- sin poder leer un
 * mensaje.
 *
 * El limite honesto de esto: el servidor decide QUE clave entrega. Un servidor
 * malicioso podria entregar la suya y ponerse en medio. Contra eso no hay
 * criptografia que alcance; hay que comparar la huella por fuera del canal, y
 * de ahi salen [Huella] y el aviso de cambio de identidad.
 */

const val RUTA_CLAVES = "/v1/claves"

/** Cuantas prekeys de un solo uso conviene tener publicadas. */
const val PREKEYS_OBJETIVO = 100

/** Por debajo de esto el cliente repone. No se espera a quedarse en cero. */
const val PREKEYS_MINIMO = 20

@Serializable
data class ClavePublica(val keyId: Int, val publica: String)

@Serializable
data class ClaveFirmada(val keyId: Int, val publica: String, val firma: String)

/**
 * Lo que un dispositivo publica al registrarse y cada vez que rota claves.
 *
 * `unicas` se agrega a lo que ya hubiera; el resto reemplaza. Reponer prekeys
 * no debe borrar las que otra persona ya se llevo pero todavia no uso.
 */
@Serializable
data class PublicarClavesReq(
    val registrationId: Int,
    /** Base64 de la clave de identidad publica. */
    val identidad: String,
    val firmada: ClaveFirmada,
    val kyber: ClaveFirmada,
    val unicas: List<ClavePublica> = emptyList(),
)

/**
 * El paquete con el que se abre una sesion criptografica.
 *
 * `unica` puede venir nula: son de un solo uso y pueden agotarse. La sesion se
 * puede abrir igual, con algo menos de garantia hacia adelante, y es mejor eso
 * que no poder escribirle a alguien.
 */
@Serializable
data class PaqueteClaves(
    val usuarioId: String,
    val username: String,
    val dispositivoId: String,
    val registrationId: Int,
    val identidad: String,
    val firmada: ClaveFirmada,
    val kyber: ClaveFirmada,
    val unica: ClavePublica? = null,
)

@Serializable
data class EstadoClaves(
    val unicasDisponibles: Int,
    val objetivo: Int = PREKEYS_OBJETIVO,
    val minimo: Int = PREKEYS_MINIMO,
)

/**
 * Un dispositivo al que hay que entregarle una copia del mensaje.
 *
 * Aqui se ve por que E2EE cambia el transporte: con cifrado de extremo a
 * extremo no hay "un" cuerpo que el servidor copie a N buzones. Hay N cuerpos,
 * uno cifrado para cada dispositivo, y el servidor solo enruta.
 */
@Serializable
data class DestinoDispositivo(
    val usuarioId: String,
    val username: String,
    val dispositivoId: String,
    val registrationId: Int,
    /** Base64 de la identidad publica, para detectar que cambio. */
    val identidad: String,
    /**
     * Como se llama ese aparato: "Pixel 7", "sdk_gphone64".
     *
     * Existe para la verificacion de identidad. Con multi-dispositivo hay una
     * huella POR APARATO, y una pantalla que ofrece verificar
     * "01a0b13c-c025-7feb..." es una pantalla que nadie usa. El nombre lo puso
     * la propia persona al vincular, asi que es el que reconoce.
     */
    val etiqueta: String = "",
)

@Serializable
data class DestinosConversacion(
    val conversacionId: String,
    val destinos: List<DestinoDispositivo> = emptyList(),
)

/** Huella comparable de una identidad (E.5). */
@Serializable
data class Huella(
    val username: String,
    /** Los 60 digitos que se leen en voz alta o se comparan a ojo. */
    val digitos: String,
    /** Base64 de la forma escaneable, para el QR. */
    val escaneable: String,
    /** Si esta identidad cambio desde la ultima vez que se vio. */
    val cambio: Boolean = false,
)
