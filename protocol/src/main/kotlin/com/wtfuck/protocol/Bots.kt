package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Bots gestionados (estilo BotFather, pero E2EE).
 *
 * Un bot es una cuenta de cifrado headless que crea un DUENO. El servidor no
 * descifra nada: el dueno corre el cliente del bot donde quiera (Lambda, EC2,
 * una VM) y ahi viven las claves. Ver docs/14-BOTS.md y `V59__bots.sql`.
 *
 * El alta tiene dos pasos: el dueno CREA el bot (reserva el username y recibe un
 * codigo de un solo uso) y despues REGISTRA la cuenta del bot con ese codigo —
 * en vez de con atestacion de hardware, que un bot no tiene—.
 */

const val RUTA_BOTS = "/v1/bots"

/** Tope de bots por persona. Mas que esto es un vivero, no una herramienta. */
const val MAX_BOTS_POR_DUENO = 3

@Serializable
data class CrearBotReq(
    /** El `@usuario` del bot. Mismas reglas que un username normal. */
    val nombre: String,
    val publico: Boolean = false,
    val descripcion: String = "",
)

/** Lo que se devuelve al crear: el codigo va UNA sola vez, no se guarda en claro. */
@Serializable
data class BotCreado(
    val username: String,
    /** De un solo uso: con el, el dueno registra la cuenta del bot. */
    val codigo: String,
)

@Serializable
data class BotResumen(
    val username: String,
    val publico: Boolean,
    val descripcion: String,
    /** Si el dueno ya dio de alta la cuenta del bot con el codigo. */
    val registrado: Boolean,
    val creadoEn: Long,
)

@Serializable
data class MisBots(val bots: List<BotResumen> = emptyList())

/** Un bot publico, visible para cualquiera que quiera escribirle. */
@Serializable
data class BotEnDirectorio(
    val username: String,
    val descripcion: String,
)

@Serializable
data class DirectorioBots(val bots: List<BotEnDirectorio> = emptyList())
