package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

/**
 * Modulo C: acciones sobre mensajes.
 *
 * Todo lo de aqui trabaja con METADATOS. El contenido nunca pasa por estas
 * funciones: viaja en el sobre, que el servidor trata como bytes opacos.
 *
 * La razon de que estas acciones sean HTTP y no mensajes cifrados entre
 * clientes: el servidor tiene que poder comprobar que quien borra el mensaje
 * de otro tenga el permiso. Si la orden viajara cifrada, no podria.
 */
object Mensajes {

    /**
     * Registra el metadato de un mensaje recien enviado.
     *
     * El autor NO se lee de la peticion: sale de la sesion. Si se confiara en
     * el cuerpo, cualquiera podria registrar mensajes a nombre de otro y luego
     * "moderarlos".
     */
    fun registrar(yo: Auth, req: RegistrarMensajeReq): MensajeMeta = Db.tx { c ->
        val convId = uuid(req.conversacionId, "conversacion")
        val msgId = uuid(req.mensajeId, "mensaje")

        val respondeA = req.respondeA?.let { uuid(it, "respuesta") }

        // Un canal no se autoriza como un grupo, y esa es su razon de existir:
        // publicar y comentar son cosas distintas, con permisos distintos.
        if (Canales.esCanal(c, convId)) {
            val cfg = Canales.basico(c, convId)
            if (respondeA == null) {
                Autz.exigir(c, yo.usuarioId, convId, Permisos.CANAL_PUBLICAR)
            } else {
                // El interruptor del canal manda sobre el permiso del rol: es
                // lo que hace que apagar los comentarios surta efecto sin
                // tocarle los permisos a nadie.
                if (!cfg.comentarios) {
                    throw ErrorNegocio(403, "Este canal no admite comentarios.")
                }
                Autz.exigir(c, yo.usuarioId, convId, Permisos.CANAL_COMENTAR)
            }
        } else {
            Autz.exigir(c, yo.usuarioId, convId, Permisos.MSG_ENVIAR)
        }

        // La clase declarada. Poder escribir no alcanza para abrir una
        // encuesta: son permisos distintos porque una encuesta le pide algo a
        // todo el grupo, y un grupo grande con cualquiera abriendo encuestas es
        // el mismo ruido que el modo anuncio evita con los mensajes.
        //
        // Se valida contra una lista cerrada ANTES de usarla: un `clase`
        // inventado tiene que ser un 400 y no pasar de largo como si fuera
        // texto, o la declaracion no sirve de nada.
        if (req.clase !in ClaseContenido.VALIDAS) {
            throw ErrorNegocio(400, "Clase de contenido desconocida: ${req.clase}")
        }
        when (req.clase) {
            ClaseContenido.ENCUESTA ->
                Autz.exigir(c, yo.usuarioId, convId, Permisos.ENCUESTA_CREAR)
            ClaseContenido.EVENTO ->
                Autz.exigir(c, yo.usuarioId, convId, Permisos.EVENTO_CREAR)
            // Votar no pide permiso propio: si podes escribir en la
            // conversacion, podes contestar lo que se pregunto ahi. Un permiso
            // separado para votar solo serviria para dejar a alguien mirando
            // una encuesta que le habla a el.
            else -> Unit
        }
        if (respondeA != null && !existeEn(c, respondeA, convId)) {
            throw ErrorNegocio(400, "El mensaje al que respondes no esta en esta conversacion.")
        }

        val segundos = c.prepareStatement(
            "SELECT temporales_segundos FROM conversacion WHERE id = ?"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1) as? Int } }
        }

        c.prepareStatement(
            """INSERT INTO mensaje_meta (id, conversacion_id, autor_id, responde_a, reenviado_de, expira_en)
               VALUES (?, ?, ?, ?, ?, CASE WHEN ? > 0 THEN now() + make_interval(secs => ?) END)
               ON CONFLICT (id) DO NOTHING"""
        ).use { st ->
            st.setObject(1, msgId)
            st.setObject(2, convId)
            st.setObject(3, yo.usuarioId)
            st.setObject(4, respondeA)
            // `reenviadoDe` viene como USERNAME y se resuelve aqui. Si no
            // resuelve, se guarda NULL y el mensaje sale igual: la atribucion
            // de un reenvio es un adorno de la burbuja, y perder el "reenviado
            // de @fulano" es infinitamente mejor que rechazar el mensaje.
            st.setObject(5, req.reenviadoDe?.let { idDeUsername(c, it) })
            st.setInt(6, segundos ?: 0)
            st.setInt(7, segundos ?: 0)
            st.executeUpdate()
        }

        // Se ata el adjunto al mensaje. El WHERE exige que sea de quien envia y
        // de esta conversacion: sin eso, cualquiera podria colgar el archivo de
        // otra persona de un mensaje propio.
        req.adjuntoId?.let { crudo ->
            val adjId = uuid(crudo, "adjunto")
            c.prepareStatement(
                """UPDATE adjunto SET mensaje_id = ?
                   WHERE id = ? AND subido_por = ? AND conversacion_id = ?"""
            ).use { st ->
                st.setObject(1, msgId)
                st.setObject(2, adjId)
                st.setObject(3, yo.usuarioId)
                st.setObject(4, convId)
                st.executeUpdate()
            }
        }

        // Menciones: se resuelven contra participantes reales. Mencionar a
        // alguien que no esta en la conversacion no genera nada.
        if (req.menciones.isNotEmpty()) {
            c.prepareStatement(
                """INSERT INTO mencion (mensaje_id, usuario_id)
                   SELECT ?, u.id FROM usuario u
                     JOIN participante p ON p.usuario_id = u.id
                        AND p.conversacion_id = ? AND p.salido_en IS NULL
                   WHERE u.username = ANY(?)
                   ON CONFLICT DO NOTHING"""
            ).use { st ->
                st.setObject(1, msgId)
                st.setObject(2, convId)
                st.setArray(3, c.createArrayOf("text", req.menciones.map { it.lowercase() }.toTypedArray()))
                st.executeUpdate()
            }
        }

        leerMeta(c, msgId, yo.usuarioId) ?: throw ErrorNegocio(500, "No se pudo registrar el mensaje.")
    }

    /**
     * El id de una cuenta por su username. Null si no existe.
     *
     * No exige que la persona sea participante de la conversacion, y es a
     * proposito: un mensaje reenviado viene de OTRA conversacion, y quien lo
     * escribio no tiene por que estar en esta.
     */
    private fun idDeUsername(c: Connection, username: String): UUID? =
        c.prepareStatement("SELECT id FROM usuario WHERE username = ?").use { st ->
            st.setString(1, username.lowercase().trim())
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }

    /**
     * Retira un mensaje "para todos".
     *
     * Reglas: el autor siempre puede retirar lo suyo. Retirar lo de otro exige
     * `mensaje.borrar_ajeno` Y jerarquia superior a la del autor, para que un
     * moderador no borre lo que dijo un administrador.
     */
    fun retirar(yo: Auth, mensajeId: UUID): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val m = cabecera(c, mensajeId) ?: throw ErrorNegocio(404, "Ese mensaje no existe.")

        if (m.autorId == yo.usuarioId) {
            Autz.exigir(c, yo.usuarioId, m.conversacionId, Permisos.MSG_BORRAR_PROPIO)
        } else {
            Autz.exigirSobre(c, yo.usuarioId, m.autorId, m.conversacionId, Permisos.MSG_BORRAR_AJENO)
        }

        c.prepareStatement(
            "UPDATE mensaje_meta SET retirado_en = now(), retirado_por = ? WHERE id = ? AND retirado_en IS NULL"
        ).use { st -> st.setObject(1, yo.usuarioId); st.setObject(2, mensajeId); st.executeUpdate() }

        if (m.autorId != yo.usuarioId) {
            Autz.auditar(c, yo.usuarioId, "mensaje.borrar_ajeno", "mensaje", mensajeId, m.autorId)
        }

        avisarATodos(c, m.conversacionId, yo, "mensaje_retirado", mensajeId.toString())
    }

    /**
     * Marca un mensaje como editado.
     *
     * El texto nuevo viaja aparte, en un sobre. Aqui solo se comprueba el
     * permiso y se deja la marca de "editado", que es lo que el servidor puede
     * garantizar sin leer el contenido.
     */
    fun editar(yo: Auth, mensajeId: UUID): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val m = cabecera(c, mensajeId) ?: throw ErrorNegocio(404, "Ese mensaje no existe.")
        if (m.autorId != yo.usuarioId) throw ErrorNegocio(403, "Solo puedes editar tus propios mensajes.")
        if (m.retirado) throw ErrorNegocio(400, "No puedes editar un mensaje retirado.")
        Autz.exigir(c, yo.usuarioId, m.conversacionId, Permisos.MSG_EDITAR)

        c.prepareStatement("UPDATE mensaje_meta SET editado_en = now() WHERE id = ?").use { st ->
            st.setObject(1, mensajeId); st.executeUpdate()
        }
        avisarATodos(c, m.conversacionId, yo, "mensaje_editado", mensajeId.toString())
    }

    fun fijar(yo: Auth, mensajeId: UUID, fijar: Boolean): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val m = cabecera(c, mensajeId) ?: throw ErrorNegocio(404, "Ese mensaje no existe.")
        Autz.exigir(c, yo.usuarioId, m.conversacionId, Permisos.MSG_FIJAR)

        // Dos sentencias en vez de un CASE: Postgres no puede inferir el tipo
        // de la rama NULL de `fijado_por` y la consulta unica revienta con 500.
        if (fijar) {
            c.prepareStatement(
                "UPDATE mensaje_meta SET fijado_en = now(), fijado_por = ? WHERE id = ?"
            ).use { st -> st.setObject(1, yo.usuarioId); st.setObject(2, mensajeId); st.executeUpdate() }
        } else {
            c.prepareStatement(
                "UPDATE mensaje_meta SET fijado_en = NULL, fijado_por = NULL WHERE id = ?"
            ).use { st -> st.setObject(1, mensajeId); st.executeUpdate() }
        }
        Autz.auditar(c, yo.usuarioId, if (fijar) "mensaje.fijar" else "mensaje.desfijar",
            "mensaje", mensajeId, m.autorId)
        avisarATodos(c, m.conversacionId, yo, "mensaje_fijado", "$mensajeId:$fijar")
    }

    /**
     * Metadato de un mensaje suelto.
     *
     * Lo necesita el cliente para refrescar despues de un evento: el aviso de
     * "alguien reacciono" solo trae el id, no el recuento actualizado.
     */
    fun uno(yo: Auth, mensajeId: UUID): MensajeMeta = Db.query { c ->
        val m = cabecera(c, mensajeId) ?: throw ErrorNegocio(404, "Ese mensaje no existe.")
        Autz.exigir(c, yo.usuarioId, m.conversacionId, Permisos.MIEMBRO_VER)
        leerMeta(c, mensajeId, yo.usuarioId)!!
    }

    fun fijados(yo: Auth, convId: UUID): List<MensajeMeta> = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.MIEMBRO_VER)
        c.prepareStatement(
            """SELECT id FROM mensaje_meta
               WHERE conversacion_id = ? AND fijado_en IS NOT NULL AND retirado_en IS NULL
               ORDER BY fijado_en DESC"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }.mapNotNull { leerMeta(c, it, yo.usuarioId) }
    }

    fun reaccionar(yo: Auth, req: ReaccionReq): Pair<MensajeMeta, List<Pair<UUID, Bajada.Evento>>> = Db.tx { c ->
        val msgId = uuid(req.mensajeId, "mensaje")
        val m = cabecera(c, msgId) ?: throw ErrorNegocio(404, "Ese mensaje no existe.")
        Autz.exigir(c, yo.usuarioId, m.conversacionId, Permisos.MSG_REACCIONAR)

        // Un canal puede tener las reacciones apagadas. El permiso del rol no
        // alcanza: si el canal dice que no, es que no.
        if (Canales.esCanal(c, m.conversacionId) && !Canales.basico(c, m.conversacionId).reacciones) {
            throw ErrorNegocio(403, "Este canal no admite reacciones.")
        }

        val emoji = req.emoji.trim()
        if (emoji.isEmpty() || emoji.length > 16) throw ErrorNegocio(400, "Reaccion invalida.")

        if (req.poner) {
            // UNA reaccion por persona y por mensaje: la nueva REEMPLAZA a la
            // anterior en vez de ponerse al lado. Antes esto era
            // `ON CONFLICT DO NOTHING` sobre una llave que incluia el emoji, y
            // el resultado era que una sola persona podia dejar cinco marcas en
            // el mismo mensaje.
            //
            // El reemplazo se hace en la BASE -la llave primaria es
            // (mensaje_id, usuario_id) desde V25- y no borrando antes desde el
            // cliente: dos toques rapidos seguidos son dos peticiones, y con
            // un borrar-mas-insertar podian cruzarse y dejar el mensaje sin
            // ninguna reaccion.
            c.prepareStatement(
                """INSERT INTO reaccion (mensaje_id, usuario_id, emoji) VALUES (?, ?, ?)
                   ON CONFLICT (mensaje_id, usuario_id)
                   DO UPDATE SET emoji = EXCLUDED.emoji, creada_en = now()"""
            ).use { st ->
                st.setObject(1, msgId); st.setObject(2, yo.usuarioId); st.setString(3, emoji)
                st.executeUpdate()
            }
        } else {
            // Se filtra por emoji a proposito. Quitar es un interruptor sobre
            // LA MIA: si toco una reaccion ajena para quitarla, el DELETE no
            // encuentra nada y no pasa nada, que es lo correcto.
            c.prepareStatement(
                "DELETE FROM reaccion WHERE mensaje_id = ? AND usuario_id = ? AND emoji = ?"
            ).use { st ->
                st.setObject(1, msgId); st.setObject(2, yo.usuarioId); st.setString(3, emoji)
                st.executeUpdate()
            }
        }

        val meta = leerMeta(c, msgId, yo.usuarioId)!!
        meta to avisarATodos(c, m.conversacionId, yo, "mensaje_reaccion", "$msgId:$emoji:${req.poner}")
    }

    fun configurarTemporales(yo: Auth, convId: UUID, segundos: Int?) = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_EDITAR_INFO)
        if (segundos != null && segundos !in 60..7_776_000) {
            throw ErrorNegocio(400, "El tiempo debe estar entre 1 minuto y 90 dias.")
        }
        c.prepareStatement("UPDATE conversacion SET temporales_segundos = ? WHERE id = ?").use { st ->
            if (segundos == null) st.setNull(1, java.sql.Types.INTEGER) else st.setInt(1, segundos)
            st.setObject(2, convId)
            st.executeUpdate()
        }
        Autz.auditar(c, yo.usuarioId, "conversacion.temporales", "conversacion", convId, null,
            """{"segundos":${segundos ?: "null"}}""")
    }

    /** Borra los metadatos de mensajes vencidos. Lo llama un barrido periodico. */
    fun barrerVencidos(): Int = Db.tx { c ->
        c.createStatement().use {
            it.executeUpdate("DELETE FROM mensaje_meta WHERE expira_en IS NOT NULL AND expira_en < now()")
        }
    }

    // ============================================================
    //  Piezas
    // ============================================================

    private data class Cabecera(
        val conversacionId: UUID,
        val autorId: UUID,
        val retirado: Boolean,
    )

    private fun cabecera(c: Connection, mensajeId: UUID): Cabecera? =
        c.prepareStatement(
            "SELECT conversacion_id, autor_id, retirado_en IS NOT NULL FROM mensaje_meta WHERE id = ?"
        ).use { st ->
            st.setObject(1, mensajeId)
            st.executeQuery().use { rs ->
                rs.primero {
                    Cabecera(
                        it.getObject(1, UUID::class.java),
                        it.getObject(2, UUID::class.java),
                        it.getBoolean(3),
                    )
                }
            }
        }

    private fun existeEn(c: Connection, mensajeId: UUID, convId: UUID): Boolean =
        c.prepareStatement("SELECT 1 FROM mensaje_meta WHERE id = ? AND conversacion_id = ?").use { st ->
            st.setObject(1, mensajeId); st.setObject(2, convId)
            st.executeQuery().use { it.next() }
        }

    private fun leerMeta(c: Connection, mensajeId: UUID, yo: UUID): MensajeMeta? {
        val fila = c.prepareStatement(
            """SELECT m.id, m.conversacion_id, m.autor_id, u.username,
                      extract(epoch FROM m.creado_en) * 1000,
                      m.responde_a, (SELECT username FROM usuario WHERE id = m.reenviado_de),
                      coalesce(extract(epoch FROM m.editado_en) * 1000, 0),
                      coalesce(extract(epoch FROM m.retirado_en) * 1000, 0),
                      coalesce(extract(epoch FROM m.fijado_en) * 1000, 0),
                      coalesce(extract(epoch FROM m.expira_en) * 1000, 0)
               FROM mensaje_meta m JOIN usuario u ON u.id = m.autor_id
               WHERE m.id = ?"""
        ).use { st ->
            st.setObject(1, mensajeId)
            st.executeQuery().use { rs ->
                rs.primero {
                    MensajeMeta(
                        id = it.getObject(1, UUID::class.java).toString(),
                        conversacionId = it.getObject(2, UUID::class.java).toString(),
                        autorId = it.getObject(3, UUID::class.java).toString(),
                        autorUsername = it.getString(4),
                        creadoEn = it.getDouble(5).toLong(),
                        respondeA = it.getObject(6, UUID::class.java)?.toString(),
                        reenviadoDe = it.getString(7),
                        editadoEn = it.getDouble(8).toLong().takeIf { v -> v > 0 },
                        retiradoEn = it.getDouble(9).toLong().takeIf { v -> v > 0 },
                        fijadoEn = it.getDouble(10).toLong().takeIf { v -> v > 0 },
                        expiraEn = it.getDouble(11).toLong().takeIf { v -> v > 0 },
                    )
                }
            }
        } ?: return null

        return fila.copy(reacciones = reaccionesDe(c, mensajeId, yo))
    }

    /** Agrupadas y contadas aqui: la UI no deberia tener que sumarlas. */
    private fun reaccionesDe(c: Connection, mensajeId: UUID, yo: UUID): List<ReaccionAgrupada> =
        c.prepareStatement(
            """SELECT r.emoji, count(*)::int,
                      bool_or(r.usuario_id = ?),
                      (array_agg(u.username ORDER BY r.creada_en))[1:3]
               FROM reaccion r JOIN usuario u ON u.id = r.usuario_id
               WHERE r.mensaje_id = ?
               GROUP BY r.emoji
               ORDER BY count(*) DESC, r.emoji"""
        ).use { st ->
            st.setObject(1, yo); st.setObject(2, mensajeId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    // JDBC devuelve Object[], nunca String[]: castearlo directo
                    // compila pero revienta en runtime.
                    val quienes = (it.getArray(4).array as Array<*>)
                        .filterNotNull().map(Any::toString)
                    ReaccionAgrupada(it.getString(1), it.getInt(2), it.getBoolean(3), quienes)
                }
            }
        }

    /**
     * Avisa a todos los participantes (menos a quien actua, que ya lo sabe).
     *
     * Estas acciones van como evento del servidor y no como mensaje cifrado
     * justamente porque el servidor es quien las autorizo.
     */
    private fun avisarATodos(
        c: Connection,
        convId: UUID,
        yo: Auth,
        tipo: String,
        detalle: String,
    ): List<Pair<UUID, Bajada.Evento>> {
        val destinos = c.prepareStatement(
            """SELECT usuario_id FROM participante
               WHERE conversacion_id = ? AND salido_en IS NULL AND usuario_id <> ?"""
        ).use { st ->
            st.setObject(1, convId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        return Eventos.emitir(c, destinos, tipo, convId, yo.username, detalle)
    }

    private fun uuid(s: String, que: String): UUID =
        runCatching { UUID.fromString(s) }.getOrNull()
            ?: throw ErrorNegocio(400, "Identificador de $que invalido.")
}
