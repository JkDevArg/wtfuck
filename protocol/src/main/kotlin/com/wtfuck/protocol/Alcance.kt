package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Pedidos de alcance de los bots de pentesting (ver docs/14-BOTS.md).
 *
 * Dos personas: un operador le pide a un bot un objetivo (por chat), el bot lo
 * registra como pendiente, y un admin lo aprueba/rechaza desde el panel. Recien
 * lo aprobado se puede escanear. Dos familias de rutas:
 *
 *  - [RUTA_ALCANCE_BOT]: la usa el BOT (sesion normal). Registra un pedido y
 *    consulta que esta aprobado para un operador.
 *  - [RUTA_ALCANCE_PANEL]: la usa el ADMIN desde el panel (staff). Lista los
 *    pendientes y aprueba/rechaza.
 */

const val RUTA_ALCANCE_BOT = "/v1/bot/alcance"
const val RUTA_ALCANCE_PANEL = "/v1/panel/alcance"

/** El bot registra que un operador pidio auditar un objetivo. */
@Serializable
data class PedirAlcanceReq(
    val operador: String,
    val objetivo: String,
)

/** Un pedido, como lo ve el panel o el bot. */
@Serializable
data class AlcanceResp(
    val id: String,
    val operador: String,
    val objetivo: String,
    val estado: String, // pendiente | aprobado | rechazado
    val creadoEn: Long,
    val resueltoPor: String? = null,
)

/** La cola de pendientes para el panel. */
@Serializable
data class PedidosAlcanceResp(
    val pedidos: List<AlcanceResp>,
)

/** Lo que el bot consulta: los objetivos APROBADOS de un operador. */
@Serializable
data class AlcanceAprobadosResp(
    val aprobados: List<String>,
)

/** El admin aprueba o rechaza; el motivo es opcional. */
@Serializable
data class ResolverAlcanceReq(
    val motivo: String = "",
)
