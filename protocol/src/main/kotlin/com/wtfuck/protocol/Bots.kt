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

// ---------------------------------------------------------------------------
//  Delegacion: dejar que el bot oficial (@rootbot) gestione tus bots
// ---------------------------------------------------------------------------

/**
 * Autorizacion ACOTADA para que un bot de recepcion cree y liste bots en tu
 * nombre, sin tu sesion. El dueno genera un token (desde la app), se lo pasa al
 * bot por el chat E2EE, y el bot lo presenta al servidor. Alcance fijo: SOLO
 * crear y listar bots (nunca borrar, ni nada fuera de los bots), con tus mismos
 * limites (MAX_BOTS_POR_DUENO). Vence y se puede revocar.
 *
 * El token viaja en el CUERPO de los POST, no en la URL ni en una query: un
 * token en la URL queda en los registros de acceso.
 */
const val RUTA_DELEGACIONES_BOT = "/v1/delegaciones/bot"
const val RUTA_BOTS_DELEGADO = "/v1/bots/delegado"

/** Vida de una delegacion: 24 horas. Corta a proposito; se renueva generando otra. */
const val DELEGACION_BOT_VIDA_MS = 24L * 3600 * 1000

@Serializable
data class DelegacionCreada(
    /** El token en claro, UNA sola vez: no se guarda en claro, solo su hash. */
    val token: String,
    val venceEn: Long,
)

@Serializable
data class DelegacionActiva(
    val id: String,
    val creadoEn: Long,
    val venceEn: Long,
)

@Serializable
data class MisDelegaciones(val delegaciones: List<DelegacionActiva> = emptyList())

/** El bot crea un bot en nombre del dueno del token. */
@Serializable
data class CrearBotDelegadoReq(
    val token: String,
    val nombre: String,
    val publico: Boolean = false,
    val descripcion: String = "",
)

/** El bot lista los bots del dueno del token. */
@Serializable
data class ListarBotsDelegadoReq(val token: String)
