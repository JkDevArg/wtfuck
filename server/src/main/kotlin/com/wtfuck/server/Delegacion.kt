package com.wtfuck.server

import com.wtfuck.protocol.DELEGACION_BOT_VIDA_MS
import com.wtfuck.protocol.DelegacionActiva
import com.wtfuck.protocol.DelegacionCreada
import java.security.SecureRandom
import java.util.UUID

/**
 * Delegacion acotada para gestionar bots (ver protocol/Bots.kt y V60).
 *
 * El dueno genera un token desde la app, se lo pasa al bot de recepcion por el
 * chat E2EE, y el bot lo presenta para CREAR y LISTAR bots en su nombre. Alcance
 * fijo (solo esas dos cosas, nunca borrar), mismos limites del dueno, vence en
 * 24h y se puede revocar. El token se guarda hasheado; el claro va una sola vez.
 */
object Delegacion {

    private val rnd = SecureRandom()

    /** Tope de tokens vivos a la vez, para que no se acumulen autorizaciones olvidadas. */
    private const val MAX_ACTIVAS = 5

    fun crear(yo: Auth): DelegacionCreada = Db.tx { c ->
        val vivas = c.prepareStatement(
            "SELECT count(*) FROM delegacion_bot WHERE dueno_id = ? AND revocado_en IS NULL AND vence_en > now()"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }
        if (vivas >= MAX_ACTIVAS) {
            throw ErrorNegocio(409, "Tenes demasiadas autorizaciones activas. Revoca alguna primero.")
        }

        val token = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
        val venceEn = System.currentTimeMillis() + DELEGACION_BOT_VIDA_MS
        c.prepareStatement(
            "INSERT INTO delegacion_bot (dueno_id, token_hash, vence_en) VALUES (?, ?, ?)"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setBytes(2, Cripto.hashToken(token))
            st.setObject(3, java.sql.Timestamp(venceEn))
            st.executeUpdate()
        }
        Autz.auditar(c, yo.usuarioId, "delegacion.crear", "bot", null)
        DelegacionCreada(token, venceEn)
    }

    fun activas(yo: Auth): List<DelegacionActiva> = Db.query { c ->
        c.prepareStatement(
            """SELECT id, extract(epoch FROM creado_en) * 1000, extract(epoch FROM vence_en) * 1000
               FROM delegacion_bot
               WHERE dueno_id = ? AND revocado_en IS NULL AND vence_en > now()
               ORDER BY creado_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    DelegacionActiva(
                        id = it.getString(1),
                        creadoEn = it.getDouble(2).toLong(),
                        venceEn = it.getDouble(3).toLong(),
                    )
                }
            }
        }
    }

    fun revocar(yo: Auth, id: String) = Db.tx { c ->
        val uuid = runCatching { UUID.fromString(id.trim()) }.getOrElse { throw ErrorNegocio(400, "Id invalido.") }
        val n = c.prepareStatement(
            "UPDATE delegacion_bot SET revocado_en = now() WHERE id = ? AND dueno_id = ? AND revocado_en IS NULL"
        ).use { st ->
            st.setObject(1, uuid); st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "No hay una autorizacion activa con ese id.")
        Autz.auditar(c, yo.usuarioId, "delegacion.revocar", "bot", null)
        Unit
    }

    /**
     * El id del dueno del token si esta vivo (no vencido, no revocado), o null.
     * Busca por hash -como un token de sesion-: el indice lo hace barato y nunca
     * se guarda ni se compara el token en claro.
     */
    fun resolverDueno(token: String): UUID? {
        val t = token.trim()
        if (t.isEmpty()) return null
        return Db.query { c ->
            c.prepareStatement(
                """SELECT dueno_id FROM delegacion_bot
                   WHERE token_hash = ? AND revocado_en IS NULL AND vence_en > now()"""
            ).use { st ->
                st.setBytes(1, Cripto.hashToken(t))
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
            }
        }
    }
}
