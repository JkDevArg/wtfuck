package com.wtfuck.server

import com.wtfuck.protocol.AlcanceResp
import com.wtfuck.protocol.PedirAlcanceReq
import java.util.UUID

/**
 * Pedidos de alcance de los bots de pentesting (ver docs/14-BOTS.md).
 *
 * El bot (una cuenta normal) registra pedidos y consulta lo aprobado; el admin
 * los aprueba/rechaza desde el panel, con el mismo rol de staff y la misma
 * auditoria que el resto de la moderacion. El objetivo lo valida el bot antes de
 * registrarlo; aqui solo se guarda, y el admin lo ve antes de aprobar.
 */
object PedidosAlcance {

    // ---------- lo que usa el BOT (sesion normal) ----------

    /**
     * Registra que `operador` pidio `objetivo` con este bot. Si ya existia, lo
     * reusa (no duplica) y devuelve su estado actual.
     */
    fun pedir(yo: Auth, req: PedirAlcanceReq): AlcanceResp = Db.tx { c ->
        val operador = req.operador.trim().removePrefix("@").lowercase()
        val objetivo = req.objetivo.trim().lowercase()
        if (operador.isEmpty() || objetivo.isEmpty() || objetivo.length > 255) {
            throw ErrorNegocio(400, "Operador u objetivo invalido.")
        }
        c.prepareStatement(
            """INSERT INTO pedido_alcance (bot_id, operador, objetivo)
               VALUES (?, ?, ?)
               ON CONFLICT (bot_id, operador, lower(objetivo)) DO NOTHING"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, operador)
            st.setString(3, objetivo)
            st.executeUpdate()
        }
        leer(c, yo.usuarioId, operador, objetivo)
            ?: throw ErrorNegocio(500, "No se pudo registrar el pedido.")
    }

    /** Los objetivos APROBADOS de un operador para este bot. */
    fun aprobadosDe(yo: Auth, operadorCrudo: String): List<String> = Db.query { c ->
        val operador = operadorCrudo.trim().removePrefix("@").lowercase()
        if (operador.isEmpty()) return@query emptyList()
        c.prepareStatement(
            "SELECT objetivo FROM pedido_alcance WHERE bot_id = ? AND operador = ? AND estado = 'aprobado'"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, operador)
            st.executeQuery().use { rs -> rs.mapear { it.getString(1) } }
        }
    }

    private fun leer(c: java.sql.Connection, botId: UUID, operador: String, objetivo: String): AlcanceResp? =
        c.prepareStatement(
            """SELECT p.id, p.operador, p.objetivo, p.estado,
                      (EXTRACT(EPOCH FROM p.creado_en) * 1000)::bigint, u.username
               FROM pedido_alcance p LEFT JOIN usuario u ON u.id = p.resuelto_por
               WHERE p.bot_id = ? AND p.operador = ? AND lower(p.objetivo) = lower(?)"""
        ).use { st ->
            st.setObject(1, botId)
            st.setString(2, operador)
            st.setString(3, objetivo)
            st.executeQuery().use { rs -> rs.primero { fila(it) } }
        }

    // ---------- lo que usa el ADMIN (panel, staff) ----------

    /** La cola de pendientes, de todos los bots. Solo admin. */
    fun pendientes(yo: Auth): List<AlcanceResp> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        c.prepareStatement(
            """SELECT p.id, p.operador, p.objetivo, p.estado,
                      (EXTRACT(EPOCH FROM p.creado_en) * 1000)::bigint, NULL::text
               FROM pedido_alcance p
               WHERE p.estado = 'pendiente'
               ORDER BY p.creado_en ASC
               LIMIT 200"""
        ).use { st ->
            st.executeQuery().use { rs -> rs.mapear { fila(it) } }
        }
    }

    fun aprobar(yo: Auth, id: UUID, motivo: String) = resolver(yo, id, "aprobado", motivo)
    fun rechazar(yo: Auth, id: UUID, motivo: String) = resolver(yo, id, "rechazado", motivo)

    private fun resolver(yo: Auth, id: UUID, nuevoEstado: String, motivoCrudo: String) = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        val motivo = motivoCrudo.trim().take(500).ifBlank { null }
        val filas = c.prepareStatement(
            """UPDATE pedido_alcance
               SET estado = ?, resuelto_por = ?, resuelto_en = now(), motivo = ?
               WHERE id = ? AND estado = 'pendiente'"""
        ).use { st ->
            st.setString(1, nuevoEstado)
            st.setObject(2, yo.usuarioId)
            st.setString(3, motivo)
            st.setObject(4, id)
            st.executeUpdate()
        }
        if (filas == 0) throw ErrorNegocio(404, "Ese pedido no existe o ya fue resuelto.")
        val detalle = motivo?.let { """{"motivo":"${it.replace('"', '\'')}"}""" }
        Autz.auditar(
            c, yo.usuarioId,
            accion = if (nuevoEstado == "aprobado") "alcance.aprobado" else "alcance.rechazado",
            recursoTipo = "pedido_alcance", recursoId = id, detalle = detalle,
        )
    }

    private fun fila(rs: java.sql.ResultSet) = AlcanceResp(
        id = rs.getObject(1, UUID::class.java).toString(),
        operador = rs.getString(2),
        objetivo = rs.getString(3),
        estado = rs.getString(4),
        creadoEn = rs.getLong(5),
        resueltoPor = rs.getString(6),
    )
}
