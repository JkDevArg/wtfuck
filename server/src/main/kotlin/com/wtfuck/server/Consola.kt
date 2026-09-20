package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * L.8 · La consola web de administracion.
 *
 * ## Que es y que no es
 *
 * Es el panel, en un navegador. Denuncias, cuentas, canales por aprobar,
 * limites, bitacora, grupos. **Ningun mensaje**: todo lo que toca son
 * metadatos que el servidor ya conoce porque los necesita para autorizar, y
 * eso es lo unico que se puede llevar a la web sin mentir.
 *
 * No es un cliente de mensajeria. Para descifrar haria falta ser un
 * dispositivo con claves de identidad propias, y llevar libsignal al navegador
 * -mas la pregunta seria de si un navegador es sitio para claves de largo
 * plazo- es un proyecto aparte. Queda declarado en vez de insinuado.
 *
 * ## El telefono sigue siendo la raiz de confianza
 *
 * No hay ingreso con usuario y contrasena desde el navegador, y no por pereza:
 * ingresar crea un DISPOSITIVO, y un navegador no tiene hardware que atestiguar
 * ni puede ser "una copia mas de tus mensajes". Quien ya esta dentro en su
 * telefono -con su contrasena y su segundo factor- emite un token de consola y
 * lo pega en el navegador.
 */
object Consola {

    /**
     * Cuanto vive un token de consola.
     *
     * Ocho horas: una jornada. Mas seria una sesion eterna con otro nombre en
     * un navegador que puede quedar abierto en una maquina compartida; menos
     * obligaria a volver al telefono en media tarde y la gente buscaria como
     * saltarselo.
     */
    val VIDA: Duration = Duration.ofHours(8)

    /** Tope de tokens vivos a la vez, por persona. */
    private const val MAX_VIVOS = 5

    /**
     * Emite un token para el navegador.
     *
     * Exige la contrasena otra vez aunque la sesion ya este abierta, por lo
     * mismo que vincular un dispositivo: un token de consola da acceso a
     * suspender cuentas, y una sesion robada del telefono no deberia poder
     * emitirlo.
     */
    fun emitir(yo: Auth, password: String, etiqueta: String?): TokenConsola = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.MODERADOR)
        // La contrasena se comprueba aqui y no en una ruta aparte: el mismo
        // patron que usa emitir un codigo de vinculacion, por el mismo motivo.
        val hash = c.prepareStatement("SELECT password_hash FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Usuario no encontrado.")
        if (!Cripto.verificarPassword(password, hash)) {
            throw ErrorNegocio(403, "La contrasena no coincide.")
        }

        val vivos = c.prepareStatement(
            """SELECT count(*) FROM token_consola
               WHERE usuario_id = ? AND revocado_en IS NULL AND expira_en > now()"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }
        if (vivos >= MAX_VIVOS) {
            throw ErrorNegocio(
                409,
                "Ya tienes $MAX_VIVOS consolas abiertas. Revoca alguna antes de abrir otra.",
            )
        }

        val token = Cripto.nuevoToken()
        val id = c.prepareStatement(
            """INSERT INTO token_consola
                 (usuario_id, token_hash, emitido_desde, etiqueta, expira_en)
               VALUES (?, ?, ?, ?, now() + make_interval(secs => ?))
               RETURNING id"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setBytes(2, Cripto.hashToken(token))
            st.setObject(3, yo.dispositivoId)
            st.setString(4, etiqueta?.trim()?.take(60)?.takeIf { it.isNotEmpty() })
            st.setDouble(5, VIDA.seconds.toDouble())
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        // Emitir un acceso administrativo no puede ser silencioso.
        Autz.auditar(c, yo.usuarioId, "consola.emitida", "token_consola", id)
        Seguridad.anotar(c, yo.usuarioId, "consola_emitida")

        TokenConsola(
            id = id.toString(),
            token = token,
            expiraEnSegundos = VIDA.seconds,
        )
    }

    /**
     * Resuelve un token de consola.
     *
     * Devuelve un [Auth] con el `dispositivoId` del aparato que lo emitio. Eso
     * no es un atajo: las rutas del panel auditan con `yo.dispositivoId`, y
     * apuntar al telefono que autorizo la consola es mas util para investigar
     * que un id inventado. Lo que NO devuelve es una sesion: un token de
     * consola no sirve para las rutas de mensajes porque esas piden
     * `autenticar()`, que solo mira `sesion`.
     */
    fun autenticar(token: String): Auth? = Db.tx { c ->
        val fila = c.prepareStatement(
            """SELECT t.id, t.usuario_id, u.username, t.emitido_desde
               FROM token_consola t JOIN usuario u ON u.id = t.usuario_id
               WHERE t.token_hash = ? AND t.revocado_en IS NULL AND t.expira_en > now()
                 AND u.desactivado_en IS NULL"""
        ).use { st ->
            st.setBytes(1, Cripto.hashToken(token))
            st.executeQuery().use { rs ->
                rs.primero {
                    Triple(
                        it.getObject(1, UUID::class.java),
                        it.getObject(2, UUID::class.java),
                        it.getString(3) to it.getObject(4, UUID::class.java),
                    )
                }
            }
        } ?: return@tx null

        c.prepareStatement("UPDATE token_consola SET ultimo_uso_en = now() WHERE id = ?")
            .use { st -> st.setObject(1, fila.first); st.executeUpdate() }

        Auth(
            usuarioId = fila.second,
            // Puede no haber aparato -se revoco el telefono que la emitio- y
            // en ese caso se usa el id del propio token: cualquier cosa es
            // mejor que un NPE en la ruta de auditoria.
            dispositivoId = fila.third.second ?: fila.first,
            username = fila.third.first,
            sesionId = fila.first,
        )
    }

    fun mias(yo: Auth): List<ConsolaAbierta> = Db.query { c ->
        c.prepareStatement(
            """SELECT t.id, coalesce(t.etiqueta, ''), coalesce(d.etiqueta, ''),
                      (EXTRACT(EPOCH FROM t.creado_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM t.expira_en) * 1000)::bigint,
                      coalesce((EXTRACT(EPOCH FROM t.ultimo_uso_en) * 1000)::bigint, 0)
               FROM token_consola t LEFT JOIN dispositivo d ON d.id = t.emitido_desde
               WHERE t.usuario_id = ? AND t.revocado_en IS NULL AND t.expira_en > now()
               ORDER BY t.creado_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    ConsolaAbierta(
                        id = it.getObject(1, UUID::class.java).toString(),
                        etiqueta = it.getString(2),
                        emitidaDesde = it.getString(3),
                        creadaEn = it.getLong(4),
                        expiraEn = it.getLong(5),
                        ultimoUso = it.getLong(6),
                    )
                }
            }
        }
    }

    fun revocar(yo: Auth, id: UUID) = Db.tx { c ->
        val n = c.prepareStatement(
            """UPDATE token_consola SET revocado_en = now()
               WHERE id = ? AND usuario_id = ? AND revocado_en IS NULL"""
        ).use { st ->
            st.setObject(1, id); st.setObject(2, yo.usuarioId); st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "Esa consola no existe o ya estaba cerrada.")
        Autz.auditar(c, yo.usuarioId, "consola.revocada", "token_consola", id)
    }

    /** Borra lo caducado. Lo llama la tarea horaria. */
    fun limpiar(c: Connection): Int =
        c.prepareStatement(
            "DELETE FROM token_consola WHERE expira_en < now() - interval '7 days'"
        ).use { it.executeUpdate() }
}
