package com.wtfuck.server

import com.wtfuck.protocol.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID

/**
 * Bots gestionados (ver protocol/Bots.kt y `V59__bots.sql`).
 *
 * El dueno CREA el bot (reserva el username, recibe un codigo de un solo uso) y
 * despues REGISTRA la cuenta del bot con ese codigo. El servidor no corre ni
 * descifra nada: el dueno hospeda el cliente del bot donde quiera.
 */
object Bots {

    private val rnd = SecureRandom()

    fun crear(yo: Auth, req: CrearBotReq): BotCreado = crearPara(yo.usuarioId, req, viaDelegacion = false)

    /** Igual que [crear] pero para el flujo delegado (@rootbot con un token). */
    fun crearDelegado(duenoId: UUID, req: CrearBotReq): BotCreado = crearPara(duenoId, req, viaDelegacion = true)

    private fun crearPara(usuarioId: UUID, req: CrearBotReq, viaDelegacion: Boolean): BotCreado = Db.tx { c ->
        val nombre = req.nombre.trim().lowercase().removePrefix("@")
        if (!Regex("^[a-z0-9_]{3,20}$").matches(nombre)) {
            throw ErrorNegocio(400, "El nombre del bot admite 3-20 caracteres: letras, numeros y guion bajo.")
        }
        val cuantos = c.prepareStatement("SELECT count(*) FROM bot WHERE dueno_id = ?").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }
        if (cuantos >= MAX_BOTS_POR_DUENO) {
            throw ErrorNegocio(409, "Llegaste al maximo de $MAX_BOTS_POR_DUENO bots.")
        }
        // El nombre tiene que estar libre tanto entre usuarios como entre bots:
        // un bot y una persona comparten el espacio de @usuario.
        val ocupado = c.prepareStatement(
            "SELECT 1 FROM usuario WHERE username = ? UNION SELECT 1 FROM bot WHERE username = ?"
        ).use { st ->
            st.setString(1, nombre); st.setString(2, nombre)
            st.executeQuery().use { it.next() }
        }
        if (ocupado) throw ErrorNegocio(409, "Ese nombre ya esta en uso.")

        val codigo = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24).also { rnd.nextBytes(it) })
        c.prepareStatement(
            """INSERT INTO bot (username, dueno_id, codigo_hash, publico, descripcion)
               VALUES (?, ?, ?, ?, ?)"""
        ).use { st ->
            st.setString(1, nombre)
            st.setObject(2, usuarioId)
            st.setBytes(3, Cripto.hashToken(codigo))
            st.setBoolean(4, req.publico)
            st.setString(5, req.descripcion.trim().take(200))
            st.executeUpdate()
        }
        Autz.auditar(c, usuarioId, if (viaDelegacion) "bot.crear.delegado" else "bot.crear", "bot", null)
        BotCreado(nombre, codigo)
    }

    fun mios(yo: Auth): List<BotResumen> = miosDe(yo.usuarioId)

    /** Los bots de un dueno por id (lo usan [mios] y el flujo delegado). */
    fun miosDe(usuarioId: UUID): List<BotResumen> = Db.query { c ->
        c.prepareStatement(
            """SELECT username, publico, descripcion, usuario_id IS NOT NULL,
                      extract(epoch FROM creado_en) * 1000
               FROM bot WHERE dueno_id = ? ORDER BY creado_en DESC"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    BotResumen(
                        username = it.getString(1),
                        publico = it.getBoolean(2),
                        descripcion = it.getString(3),
                        registrado = it.getBoolean(4),
                        creadoEn = it.getDouble(5).toLong(),
                    )
                }
            }
        }
    }

    /** Borra un bot del dueno. No borra la cuenta ya registrada (se revoca aparte). */
    fun borrar(yo: Auth, username: String) = Db.tx { c ->
        val n = username.trim().lowercase().removePrefix("@")
        val borradas = c.prepareStatement("DELETE FROM bot WHERE username = ? AND dueno_id = ?").use { st ->
            st.setString(1, n); st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }
        if (borradas == 0) throw ErrorNegocio(404, "No tenes un bot con ese nombre.")
        Autz.auditar(c, yo.usuarioId, "bot.borrar", "bot", null)
        Unit
    }

    /** Directorio de bots PUBLICOS ya registrados. `q` vacio devuelve todos. */
    fun directorio(q: String): List<BotEnDirectorio> = Db.query { c ->
        val filtro = "%${q.trim().lowercase()}%"
        c.prepareStatement(
            """SELECT username, descripcion FROM bot
               WHERE publico AND usuario_id IS NOT NULL
                 AND (? = '' OR username LIKE ? OR lower(descripcion) LIKE ?)
               ORDER BY username LIMIT 50"""
        ).use { st ->
            st.setString(1, q.trim())
            st.setString(2, filtro); st.setString(3, filtro)
            st.executeQuery().use { rs ->
                rs.mapear { BotEnDirectorio(it.getString(1), it.getString(2)) }
            }
        }
    }

    /**
     * Para el registro: valida el codigo del bot. Devuelve el id del DUENO si el
     * codigo es correcto y la cuenta del bot todavia no existe; si no, null.
     * Comparacion en tiempo constante, como las demas de credenciales.
     */
    fun validar(c: Connection, username: String, codigo: String): UUID? {
        val fila = c.prepareStatement(
            "SELECT dueno_id, codigo_hash FROM bot WHERE username = ? AND usuario_id IS NULL"
        ).use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                rs.primero { it.getObject(1, UUID::class.java) to it.getBytes(2) }
            }
        } ?: return null
        return if (MessageDigest.isEqual(Cripto.hashToken(codigo.trim()), fila.second)) fila.first else null
    }

    /** Marca la cuenta del bot como registrada, al crearla con el codigo. */
    fun marcarRegistrado(c: Connection, username: String, usuarioId: UUID) {
        c.prepareStatement("UPDATE bot SET usuario_id = ?, registrado_en = now() WHERE username = ?").use { st ->
            st.setObject(1, usuarioId); st.setString(2, username)
            st.executeUpdate()
        }
    }
}
