package com.wtfuck.server

import com.wtfuck.protocol.Bajada
import java.sql.Connection
import java.util.UUID

/**
 * Avisos que genera el SERVIDOR, no un usuario.
 *
 * Viven en `evento_pendiente`, aparte del buzon de mensajes, por una razon de
 * fondo: el cuerpo de un sobre es opaco y desde la fase E va cifrado entre
 * clientes, asi que el servidor no puede fabricar uno. Estos avisos, en cambio,
 * son metadatos que el servidor ya conoce y viajan en claro a proposito.
 *
 * Se persisten ANTES de empujarse por el socket: si la persona esta offline, el
 * aviso la espera al reconectar. Un evento que solo viva en el socket se pierde.
 */
object Eventos {

    // ============================================================
    //  Eventos del sistema
    // ============================================================

    /**
     * Guarda un evento para cada dispositivo activo de los usuarios indicados.
     *
     * Se persiste ANTES de empujarlo por el socket: si la persona esta offline
     * -o la app muere justo ahora- el aviso la espera al reconectar, igual que
     * un mensaje. Un evento que solo viva en el socket se pierde.
     */
    fun emitir(
        c: Connection,
        usuarios: List<UUID>,
        tipo: String,
        /**
         * Nulo cuando el aviso no pertenece a ninguna conversacion. Pasa con las
         * advertencias de moderacion: sancionar a una persona no es un hecho de
         * un grupo.
         */
        conversacionId: UUID?,
        actor: String,
        detalle: String? = null,
    ): List<Pair<UUID, Bajada.Evento>> {
        if (usuarios.isEmpty()) return emptyList()

        val dispositivos = c.prepareStatement(
            "SELECT id, usuario_id FROM dispositivo WHERE usuario_id = ANY(?) AND revocado_en IS NULL"
        ).use { st ->
            st.setArray(1, c.createArrayOf("uuid", usuarios.toTypedArray()))
            st.executeQuery().use { rs ->
                rs.mapear { it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) }
            }
        }
        if (dispositivos.isEmpty()) return emptyList()

        val nombre = if (conversacionId == null) "wtfuck" else c.prepareStatement(
            "SELECT coalesce(nombre, 'Conversacion') FROM conversacion WHERE id = ?"
        ).use { st ->
            st.setObject(1, conversacionId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } } ?: "Conversacion"
        }

        val ahora = System.currentTimeMillis()
        val salida = mutableListOf<Pair<UUID, Bajada.Evento>>()

        c.prepareStatement(
            """INSERT INTO evento_pendiente
                   (destino_dispositivo, tipo, conversacion_id, actor_username, detalle)
               VALUES (?, ?, ?, ?, ?) RETURNING id"""
        ).use { st ->
            for ((dispositivoId, _) in dispositivos) {
                st.setObject(1, dispositivoId)
                st.setString(2, tipo)
                st.setObject(3, conversacionId)
                st.setString(4, actor)
                st.setString(5, detalle)
                val eventoId = st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
                salida += dispositivoId to Bajada.Evento(
                    eventoId = eventoId.toString(),
                    tipo = tipo,
                    conversacionId = conversacionId?.toString().orEmpty(),
                    nombreConversacion = nombre,
                    actor = actor,
                    creadoEn = ahora,
                    detalle = detalle,
                )
            }
        }
        return salida
    }

    fun pendientes(dispositivoId: UUID): List<Bajada.Evento> = Db.query { c ->
        c.prepareStatement(
            """SELECT e.id, e.tipo, e.conversacion_id, coalesce(cv.nombre, 'Grupo'),
                      e.actor_username, extract(epoch FROM e.creado_en) * 1000, e.detalle
               FROM evento_pendiente e
                 LEFT JOIN conversacion cv ON cv.id = e.conversacion_id
               WHERE e.destino_dispositivo = ?
               ORDER BY e.id"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    Bajada.Evento(
                        eventoId = it.getObject(1, UUID::class.java).toString(),
                        tipo = it.getString(2),
                        // Puede venir null: una advertencia no pertenece a
                        // ninguna conversacion.
                        conversacionId = it.getObject(3, UUID::class.java)?.toString().orEmpty(),
                        nombreConversacion = it.getString(4),
                        actor = it.getString(5),
                        creadoEn = it.getDouble(6).toLong(),
                        detalle = it.getString(7),
                    )
                }
            }
        }
    }

    fun acusar(dispositivoId: UUID, ids: List<String>) {
        val uuids = ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuids.isEmpty()) return
        Db.tx { c ->
            c.prepareStatement(
                "DELETE FROM evento_pendiente WHERE destino_dispositivo = ? AND id = ANY(?)"
            ).use { st ->
                st.setObject(1, dispositivoId)
                st.setArray(2, c.createArrayOf("uuid", uuids.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

}
