package com.wtfuck.server

import com.wtfuck.protocol.*
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID

/**
 * Modulo B: grupos avanzados.
 *
 * Cada operacion sigue el mismo guion: autenticar (ya viene resuelto), exigir
 * el permiso con [Autz], ejecutar, auditar. Ninguna funcion de aqui decide por
 * su cuenta quien puede hacer que.
 */
object Grupos {

    private val rnd = SecureRandom()

    // ============================================================
    //  Configuracion
    // ============================================================

    fun config(yo: Auth, convId: UUID): ConfigGrupo = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_VER_INFO)
        leerConfig(c, convId)
    }

    private fun leerConfig(c: Connection, convId: UUID): ConfigGrupo =
        c.prepareStatement(
            """SELECT coalesce(nombre,''), coalesce(descripcion,''), publico, solo_admins,
                      historial_visible, aprobar_ingreso, permitir_media, permitir_enlaces,
                      coalesce(alias::text,'')
               FROM conversacion WHERE id = ?"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs ->
                rs.primero {
                    ConfigGrupo(
                        nombre = it.getString(1),
                        descripcion = it.getString(2),
                        publico = it.getBoolean(3),
                        soloAdmins = it.getBoolean(4),
                        historialVisible = it.getBoolean(5),
                        aprobarIngreso = it.getBoolean(6),
                        permitirMedia = it.getBoolean(7),
                        permitirEnlaces = it.getBoolean(8),
                        alias = it.getString(9),
                    )
                }
            } ?: throw ErrorNegocio(404, "Conversacion no encontrada.")
        }

    fun guardarConfig(yo: Auth, convId: UUID, cfg: ConfigGrupo): ConfigGrupo = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_EDITAR_INFO)

        val nombre = cfg.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre debe tener entre 1 y 64 caracteres.")
        }
        if (cfg.descripcion.length > 500) throw ErrorNegocio(400, "La descripcion no puede pasar de 500 caracteres.")

        val alias = cfg.alias.trim().lowercase().removePrefix("@").ifEmpty { null }
        if (alias != null) {
            if (!Regex("^[a-z0-9_-]{4,32}$").matches(alias)) {
                throw ErrorNegocio(400, "El alias admite 4-32 caracteres: letras, numeros, guion y guion bajo.")
            }
            val ocupado = c.prepareStatement(
                "SELECT 1 FROM conversacion WHERE alias = ? AND id <> ?"
            ).use { st ->
                st.setString(1, alias); st.setObject(2, convId)
                st.executeQuery().use { it.next() }
            }
            if (ocupado) throw ErrorNegocio(409, "Ese alias ya esta en uso.")
        }

        c.prepareStatement(
            """UPDATE conversacion SET nombre = ?, descripcion = ?, publico = ?, solo_admins = ?,
                   historial_visible = ?, aprobar_ingreso = ?, permitir_media = ?,
                   permitir_enlaces = ?, alias = ?
               WHERE id = ?"""
        ).use { st ->
            st.setString(1, nombre)
            st.setString(2, cfg.descripcion.trim().ifEmpty { null })
            st.setBoolean(3, cfg.publico)
            st.setBoolean(4, cfg.soloAdmins)
            st.setBoolean(5, cfg.historialVisible)
            st.setBoolean(6, cfg.aprobarIngreso)
            st.setBoolean(7, cfg.permitirMedia)
            st.setBoolean(8, cfg.permitirEnlaces)
            st.setString(9, alias)
            st.setObject(10, convId)
            st.executeUpdate()
        }

        Autz.auditar(c, yo.usuarioId, "grupo.editar_info", "conversacion", convId)
        leerConfig(c, convId)
    }

    // ============================================================
    //  Miembros
    // ============================================================

    fun miembros(yo: Auth, convId: UUID): List<MiembroDetalle> = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.MIEMBRO_VER)

        c.prepareStatement(
            """SELECT u.id, u.username, d.id, d.identidad_pub,
                      coalesce(u.nombre_mostrado,''), coalesce(u.estado_texto,''),
                      coalesce(extract(epoch FROM u.avatar_actualizado) * 1000, 0),
                      r.clave, r.nombre, r.jerarquia,
                      (SELECT tipo FROM restriccion x
                        WHERE x.conversacion_id = p.conversacion_id AND x.usuario_id = u.id
                          AND x.levantada_en IS NULL AND (x.hasta IS NULL OR x.hasta > now())
                        LIMIT 1),
                      (SELECT extract(epoch FROM hasta) * 1000 FROM restriccion x
                        WHERE x.conversacion_id = p.conversacion_id AND x.usuario_id = u.id
                          AND x.levantada_en IS NULL AND (x.hasta IS NULL OR x.hasta > now())
                        LIMIT 1)
               FROM participante p
                 JOIN usuario u     ON u.id = p.usuario_id
                 JOIN dispositivo d ON d.usuario_id = u.id AND d.revocado_en IS NULL
                 JOIN rol r         ON r.id = p.rol_id
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL
               ORDER BY r.jerarquia DESC, u.username"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    MiembroDetalle(
                        usuario = UsuarioPublico(
                            usuarioId = it.getObject(1, UUID::class.java).toString(),
                            username = it.getString(2),
                            dispositivoId = it.getObject(3, UUID::class.java).toString(),
                            identidadPub = Base64Util.enc(it.getBytes(4)),
                            nombreMostrado = it.getString(5),
                            estadoTexto = it.getString(6),
                            avatarVersion = it.getDouble(7).toLong(),
                        ),
                        rolClave = it.getString(8),
                        rolNombre = it.getString(9),
                        jerarquia = it.getInt(10),
                        restriccion = it.getString(11),
                        restringidoHasta = it.getDouble(12).toLong().takeIf { v -> v > 0 },
                    )
                }
            }
        }
    }

    fun cambiarRol(yo: Auth, convId: UUID, objetivoId: UUID, rolClave: String): List<Pair<UUID, Bajada.Evento>> =
        Db.tx { c ->
            Autz.exigirSobre(c, yo.usuarioId, objetivoId, convId, Permisos.GRUPO_ADMIN_ROLES)

            val rol = buscarRol(c, convId, rolClave)
                ?: throw ErrorNegocio(404, "No existe el rol $rolClave.")

            // No se puede promover a alguien por encima o al nivel de uno mismo:
            // seria una forma indirecta de saltarse la jerarquia.
            val miJerarquia = Autz.membresia(c, yo.usuarioId, convId)!!.jerarquia
            if (rol.second >= miJerarquia) {
                throw ErrorNegocio(403, "No puedes asignar un rol igual o superior al tuyo.")
            }

            c.prepareStatement(
                "UPDATE participante SET rol_id = ? WHERE conversacion_id = ? AND usuario_id = ?"
            ).use { st ->
                st.setObject(1, rol.first); st.setObject(2, convId); st.setObject(3, objetivoId)
                st.executeUpdate()
            }

            Autz.auditar(c, yo.usuarioId, "grupo.rol_cambiado", "conversacion", convId, objetivoId,
                """{"rol":"$rolClave"}""")
            Eventos.emitir(c, listOf(objetivoId), "rol_cambiado", convId, yo.username, rolClave)
        }

    fun expulsar(yo: Auth, convId: UUID, objetivoId: UUID, req: ExpulsarReq): List<Pair<UUID, Bajada.Evento>> =
        Db.tx { c ->
            Autz.exigirSobre(c, yo.usuarioId, objetivoId, convId, Permisos.MIEMBRO_EXPULSAR)

            c.prepareStatement(
                "UPDATE participante SET salido_en = now() WHERE conversacion_id = ? AND usuario_id = ?"
            ).use { st -> st.setObject(1, convId); st.setObject(2, objetivoId); st.executeUpdate() }

            // Vetado = no puede volver ni con un enlace valido.
            if (req.vetar) {
                c.prepareStatement(
                    """INSERT INTO restriccion (conversacion_id, usuario_id, tipo, motivo, aplicada_por)
                       VALUES (?, ?, 'vetado', ?, ?)"""
                ).use { st ->
                    st.setObject(1, convId); st.setObject(2, objetivoId)
                    st.setString(3, req.motivo.ifBlank { null }); st.setObject(4, yo.usuarioId)
                    st.executeUpdate()
                }
            }

            Autz.auditar(c, yo.usuarioId, if (req.vetar) "miembro.vetar" else "miembro.expulsar",
                "conversacion", convId, objetivoId, """{"motivo":"${req.motivo.replace("\"", "")}"}""")
            Eventos.emitir(c, listOf(objetivoId), "expulsado", convId, yo.username, req.motivo)
        }

    fun silenciar(yo: Auth, convId: UUID, objetivoId: UUID, req: SilenciarReq): List<Pair<UUID, Bajada.Evento>> =
        Db.tx { c ->
            Autz.exigirSobre(c, yo.usuarioId, objetivoId, convId, Permisos.MIEMBRO_SILENCIAR)

            // Se levanta cualquier silencio previo antes de aplicar el nuevo:
            // si no, quedarian dos vivos y ganaria el mas viejo.
            c.prepareStatement(
                """UPDATE restriccion SET levantada_en = now()
                   WHERE conversacion_id = ? AND usuario_id = ? AND tipo = 'silenciado'
                     AND levantada_en IS NULL"""
            ).use { st -> st.setObject(1, convId); st.setObject(2, objetivoId); st.executeUpdate() }

            if (req.minutos > 0 || req.minutos == 0) {
                c.prepareStatement(
                    """INSERT INTO restriccion (conversacion_id, usuario_id, tipo, motivo, hasta, aplicada_por)
                       VALUES (?, ?, 'silenciado', ?, CASE WHEN ? > 0 THEN now() + make_interval(mins => ?) END, ?)"""
                ).use { st ->
                    st.setObject(1, convId); st.setObject(2, objetivoId)
                    st.setString(3, req.motivo.ifBlank { null })
                    st.setInt(4, req.minutos); st.setInt(5, req.minutos)
                    st.setObject(6, yo.usuarioId)
                    st.executeUpdate()
                }
            }

            Autz.auditar(c, yo.usuarioId, "miembro.silenciar", "conversacion", convId, objetivoId,
                """{"minutos":${req.minutos}}""")
            Eventos.emitir(c, listOf(objetivoId), "silenciado", convId, yo.username, req.minutos.toString())
        }

    fun quitarSilencio(yo: Auth, convId: UUID, objetivoId: UUID) = Db.tx { c ->
        Autz.exigirSobre(c, yo.usuarioId, objetivoId, convId, Permisos.MIEMBRO_SILENCIAR)
        c.prepareStatement(
            """UPDATE restriccion SET levantada_en = now()
               WHERE conversacion_id = ? AND usuario_id = ? AND tipo = 'silenciado' AND levantada_en IS NULL"""
        ).use { st -> st.setObject(1, convId); st.setObject(2, objetivoId); st.executeUpdate() }
        Autz.auditar(c, yo.usuarioId, "miembro.quitar_silencio", "conversacion", convId, objetivoId)
    }

    // ============================================================
    //  Roles personalizados
    // ============================================================

    fun roles(yo: Auth, convId: UUID): List<RolDetalle> = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.MIEMBRO_VER)
        c.prepareStatement(
            """SELECT r.id, r.clave, r.nombre, r.jerarquia, r.es_sistema
               FROM rol r
               WHERE r.es_sistema OR r.conversacion_id = ?
               ORDER BY r.jerarquia DESC"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    FilaRol(
                        it.getObject(1, UUID::class.java), it.getString(2),
                        it.getString(3), it.getInt(4), it.getBoolean(5),
                    )
                }
            }
        }.map { (id, clave, nombre, jerarquia, sistema) ->
            RolDetalle(id.toString(), clave, nombre, jerarquia, sistema, permisosDe(c, id).toList())
        }
    }

    fun crearRol(yo: Auth, convId: UUID, req: CrearRolReq): RolDetalle = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_ADMIN_ROLES)

        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 32) {
            throw ErrorNegocio(400, "El nombre del rol debe tener entre 1 y 32 caracteres.")
        }
        // Por encima de 79 viven los roles de sistema; no se pueden imitar.
        if (req.jerarquia !in 1..79) {
            throw ErrorNegocio(400, "La jerarquia de un rol propio va de 1 a 79.")
        }
        val miJerarquia = Autz.membresia(c, yo.usuarioId, convId)!!.jerarquia
        if (req.jerarquia >= miJerarquia) {
            throw ErrorNegocio(403, "No puedes crear un rol igual o superior al tuyo.")
        }

        // No se puede regalar un permiso que uno mismo no tiene.
        val mios = Autz.membresia(c, yo.usuarioId, convId)!!.permisos
        val deMas = req.permisos.filter { it !in mios }
        if (deMas.isNotEmpty()) {
            throw ErrorNegocio(403, "No puedes otorgar permisos que tu no tienes: ${deMas.joinToString()}")
        }

        val clave = nombre.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            .ifEmpty { "rol_${System.currentTimeMillis() % 100000}" }

        val id = c.prepareStatement(
            """INSERT INTO rol (conversacion_id, clave, nombre, jerarquia, es_sistema)
               VALUES (?, ?, ?, ?, false) RETURNING id"""
        ).use { st ->
            st.setObject(1, convId); st.setString(2, clave)
            st.setString(3, nombre); st.setInt(4, req.jerarquia)
            try {
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            } catch (e: org.postgresql.util.PSQLException) {
                throw ErrorNegocio(409, "Ya existe un rol con ese nombre en este grupo.")
            }
        }

        if (req.permisos.isNotEmpty()) {
            c.prepareStatement("INSERT INTO rol_permiso (rol_id, permiso) VALUES (?, ?)").use { st ->
                req.permisos.distinct().forEach { st.setObject(1, id); st.setString(2, it); st.addBatch() }
                st.executeBatch()
            }
        }

        Autz.auditar(c, yo.usuarioId, "grupo.rol_creado", "conversacion", convId, null, """{"rol":"$clave"}""")
        RolDetalle(id.toString(), clave, nombre, req.jerarquia, false, req.permisos)
    }

    fun eliminarRol(yo: Auth, convId: UUID, rolId: UUID) = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_ADMIN_ROLES)

        val esSistema = c.prepareStatement("SELECT es_sistema FROM rol WHERE id = ?").use { st ->
            st.setObject(1, rolId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } }
        } ?: throw ErrorNegocio(404, "Rol no encontrado.")
        if (esSistema) throw ErrorNegocio(400, "Los roles de sistema no se pueden eliminar.")

        // Quien tuviera ese rol vuelve a miembro; si no, quedaria sin permisos.
        c.prepareStatement(
            """UPDATE participante
               SET rol_id = (SELECT id FROM rol WHERE es_sistema AND clave = 'miembro')
               WHERE rol_id = ?"""
        ).use { st -> st.setObject(1, rolId); st.executeUpdate() }

        c.prepareStatement("DELETE FROM rol WHERE id = ? AND conversacion_id = ?").use { st ->
            st.setObject(1, rolId); st.setObject(2, convId); st.executeUpdate()
        }
        Autz.auditar(c, yo.usuarioId, "grupo.rol_eliminado", "conversacion", convId)
    }

    private fun buscarRol(c: Connection, convId: UUID, clave: String): Pair<UUID, Int>? =
        c.prepareStatement(
            """SELECT id, jerarquia FROM rol
               WHERE clave = ? AND (es_sistema OR conversacion_id = ?)
               ORDER BY es_sistema LIMIT 1"""
        ).use { st ->
            st.setString(1, clave); st.setObject(2, convId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) to it.getInt(2) } }
        }

    private fun permisosDe(c: Connection, rolId: UUID): Set<String> =
        c.prepareStatement("SELECT permiso FROM rol_permiso WHERE rol_id = ?").use { st ->
            st.setObject(1, rolId)
            st.executeQuery().use { rs -> rs.mapear { it.getString(1) }.toSet() }
        }

    // ============================================================
    //  Invitaciones
    // ============================================================

    fun crearInvitacion(yo: Auth, convId: UUID, req: CrearInvitacionReq): Invitacion = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.INVITACION_CREAR)

        // Codigo aleatorio y opaco: NO se deriva del id del grupo, porque
        // entonces conocer un enlace permitiria adivinar los demas.
        val codigo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(12).also { rnd.nextBytes(it) })

        c.prepareStatement(
            """INSERT INTO invitacion (conversacion_id, codigo, creada_por, expira_en, usos_max)
               VALUES (?, ?, ?, CASE WHEN ? > 0 THEN now() + make_interval(hours => ?) END,
                       CASE WHEN ? > 0 THEN ? END)
               RETURNING extract(epoch FROM creada_en) * 1000,
                         extract(epoch FROM expira_en) * 1000"""
        ).use { st ->
            st.setObject(1, convId); st.setString(2, codigo); st.setObject(3, yo.usuarioId)
            st.setInt(4, req.horas); st.setInt(5, req.horas)
            st.setInt(6, req.usosMax); st.setInt(7, req.usosMax)
            st.executeQuery().use { rs ->
                rs.next()
                Autz.auditar(c, yo.usuarioId, "invitacion.crear", "conversacion", convId)
                Invitacion(
                    codigo = codigo,
                    conversacionId = convId.toString(),
                    creadaEn = rs.getDouble(1).toLong(),
                    expiraEn = rs.getDouble(2).toLong().takeIf { it > 0 },
                    usosMax = req.usosMax.takeIf { it > 0 },
                    usos = 0,
                    revocada = false,
                )
            }
        }
    }

    fun revocarInvitacion(yo: Auth, convId: UUID, codigo: String) = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.INVITACION_REVOCAR)
        val n = c.prepareStatement(
            "UPDATE invitacion SET revocada_en = now() WHERE codigo = ? AND conversacion_id = ? AND revocada_en IS NULL"
        ).use { st -> st.setString(1, codigo); st.setObject(2, convId); st.executeUpdate() }
        if (n == 0) throw ErrorNegocio(404, "Ese enlace no existe o ya fue revocado.")
        Autz.auditar(c, yo.usuarioId, "invitacion.revocar", "conversacion", convId)
    }

    fun vistaPrevia(yo: Auth, codigo: String): VistaPreviaInvitacion = Db.query { c ->
        val inv = resolverInvitacion(c, codigo)
        val cfg = leerConfig(c, inv)
        val miembros = c.prepareStatement(
            "SELECT count(*) FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
        ).use { st -> st.setObject(1, inv); st.executeQuery().use { it.next(); it.getInt(1) } }

        VistaPreviaInvitacion(
            conversacionId = inv.toString(),
            nombre = cfg.nombre,
            descripcion = cfg.descripcion,
            miembros = miembros,
            requiereAprobacion = cfg.aprobarIngreso,
            yaEsMiembro = Autz.membresia(c, yo.usuarioId, inv) != null,
        )
    }

    fun usarInvitacion(yo: Auth, codigo: String): Pair<ResultadoIngreso, List<Pair<UUID, Bajada.Evento>>> =
        Db.tx { c ->
            val convId = resolverInvitacion(c, codigo)

            if (Autz.membresia(c, yo.usuarioId, convId) != null) {
                return@tx ResultadoIngreso("ingresado", convId.toString()) to emptyList()
            }

            // Un veto pesa mas que un enlace valido.
            val vetado = c.prepareStatement(
                """SELECT 1 FROM restriccion
                   WHERE conversacion_id = ? AND usuario_id = ? AND tipo = 'vetado'
                     AND levantada_en IS NULL LIMIT 1"""
            ).use { st ->
                st.setObject(1, convId); st.setObject(2, yo.usuarioId)
                st.executeQuery().use { it.next() }
            }
            if (vetado) throw ErrorNegocio(403, "No puedes entrar a este grupo.")

            val cfg = leerConfig(c, convId)

            if (cfg.aprobarIngreso) {
                c.prepareStatement(
                    """INSERT INTO solicitud_ingreso (conversacion_id, usuario_id) VALUES (?, ?)
                       ON CONFLICT (conversacion_id, usuario_id)
                       DO UPDATE SET estado = 'pendiente', creada_en = now(), resuelta_en = NULL"""
                ).use { st -> st.setObject(1, convId); st.setObject(2, yo.usuarioId); st.executeUpdate() }

                val admins = administradores(c, convId)
                val avisos = Eventos.emitir(c, admins, "solicitud_nueva", convId, yo.username)
                return@tx ResultadoIngreso("solicitud_enviada", convId.toString()) to avisos
            }

            unir(c, convId, yo.usuarioId)
            consumirUso(c, codigo)
            Autz.auditar(c, yo.usuarioId, "grupo.ingreso_por_enlace", "conversacion", convId)
            ResultadoIngreso("ingresado", convId.toString()) to emptyList()
        }

    /** Valida vigencia, usos y revocacion. Lanza si el enlace no sirve. */
    private fun resolverInvitacion(c: Connection, codigo: String): UUID =
        c.prepareStatement(
            """SELECT conversacion_id FROM invitacion
               WHERE codigo = ? AND revocada_en IS NULL
                 AND (expira_en IS NULL OR expira_en > now())
                 AND (usos_max IS NULL OR usos < usos_max)"""
        ).use { st ->
            st.setString(1, codigo)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: throw ErrorNegocio(404, "Ese enlace no es valido, vencio o alcanzo su limite de usos.")

    private fun consumirUso(c: Connection, codigo: String) {
        c.prepareStatement("UPDATE invitacion SET usos = usos + 1 WHERE codigo = ?").use { st ->
            st.setString(1, codigo); st.executeUpdate()
        }
    }

    // ============================================================
    //  Solicitudes de ingreso
    // ============================================================

    fun solicitudes(yo: Auth, convId: UUID): List<Solicitud> = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.MIEMBRO_APROBAR)
        c.prepareStatement(
            """SELECT u.id, u.username, d.id, d.identidad_pub, coalesce(u.nombre_mostrado,''),
                      coalesce(s.mensaje,''), extract(epoch FROM s.creada_en) * 1000
               FROM solicitud_ingreso s
                 JOIN usuario u     ON u.id = s.usuario_id
                 JOIN dispositivo d ON d.usuario_id = u.id AND d.revocado_en IS NULL
               WHERE s.conversacion_id = ? AND s.estado = 'pendiente'
               ORDER BY s.creada_en"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    Solicitud(
                        usuario = UsuarioPublico(
                            usuarioId = it.getObject(1, UUID::class.java).toString(),
                            username = it.getString(2),
                            dispositivoId = it.getObject(3, UUID::class.java).toString(),
                            identidadPub = Base64Util.enc(it.getBytes(4)),
                            nombreMostrado = it.getString(5),
                        ),
                        mensaje = it.getString(6),
                        creadaEn = it.getDouble(7).toLong(),
                    )
                }
            }
        }
    }

    fun resolverSolicitud(
        yo: Auth,
        convId: UUID,
        objetivoId: UUID,
        aprobar: Boolean,
    ): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.MIEMBRO_APROBAR)

        val n = c.prepareStatement(
            """UPDATE solicitud_ingreso SET estado = ?, resuelta_en = now(), resuelta_por = ?
               WHERE conversacion_id = ? AND usuario_id = ? AND estado = 'pendiente'"""
        ).use { st ->
            st.setString(1, if (aprobar) "aprobada" else "rechazada")
            st.setObject(2, yo.usuarioId); st.setObject(3, convId); st.setObject(4, objetivoId)
            st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "No hay una solicitud pendiente de esa persona.")

        if (aprobar) unir(c, convId, objetivoId)

        Autz.auditar(c, yo.usuarioId, if (aprobar) "solicitud.aprobar" else "solicitud.rechazar",
            "conversacion", convId, objetivoId)
        Eventos.emitir(
            c, listOf(objetivoId),
            if (aprobar) "solicitud_aprobada" else "solicitud_rechazada",
            convId, yo.username,
        )
    }

    private fun unir(c: Connection, convId: UUID, usuarioId: UUID) {
        c.prepareStatement(
            """INSERT INTO participante (conversacion_id, usuario_id, rol, rol_id)
               VALUES (?, ?, 'miembro', (SELECT id FROM rol WHERE es_sistema AND clave = 'miembro'))
               ON CONFLICT (conversacion_id, usuario_id) DO UPDATE SET salido_en = NULL"""
        ).use { st -> st.setObject(1, convId); st.setObject(2, usuarioId); st.executeUpdate() }
    }

    private fun administradores(c: Connection, convId: UUID): List<UUID> =
        c.prepareStatement(
            """SELECT p.usuario_id FROM participante p JOIN rol r ON r.id = p.rol_id
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL AND r.jerarquia >= 50"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }

    // ============================================================
    //  Preferencias personales
    // ============================================================

    fun guardarPreferencias(yo: Auth, convId: UUID, p: PreferenciasChat): EstadoChat = Db.tx { c ->
        if (Autz.membresia(c, yo.usuarioId, convId) == null) {
            throw ErrorNegocio(404, "No perteneces a esta conversacion.")
        }

        p.silenciarMinutos?.let { min ->
            c.prepareStatement(
                """UPDATE participante SET silenciado_hasta =
                       CASE WHEN ? = 0 THEN NULL
                            WHEN ? < 0 THEN 'infinity'::timestamptz
                            ELSE now() + make_interval(mins => ?) END
                   WHERE conversacion_id = ? AND usuario_id = ?"""
            ).use { st ->
                st.setInt(1, min); st.setInt(2, min); st.setInt(3, maxOf(min, 0))
                st.setObject(4, convId); st.setObject(5, yo.usuarioId)
                st.executeUpdate()
            }
        }
        p.archivado?.let { v ->
            c.prepareStatement("UPDATE participante SET archivado = ? WHERE conversacion_id = ? AND usuario_id = ?")
                .use { st -> st.setBoolean(1, v); st.setObject(2, convId); st.setObject(3, yo.usuarioId); st.executeUpdate() }
        }
        p.fijado?.let { v ->
            c.prepareStatement("UPDATE participante SET fijado = ? WHERE conversacion_id = ? AND usuario_id = ?")
                .use { st -> st.setBoolean(1, v); st.setObject(2, convId); st.setObject(3, yo.usuarioId); st.executeUpdate() }
        }

        c.prepareStatement(
            """SELECT CASE WHEN silenciado_hasta = 'infinity'::timestamptz THEN -1
                           ELSE coalesce(extract(epoch FROM silenciado_hasta) * 1000, 0) END,
                      archivado, fijado
               FROM participante WHERE conversacion_id = ? AND usuario_id = ?"""
        ).use { st ->
            st.setObject(1, convId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.next()
                EstadoChat(
                    conversacionId = convId.toString(),
                    silenciadoHasta = rs.getDouble(1).toLong().takeIf { it != 0L },
                    archivado = rs.getBoolean(2),
                    fijado = rs.getBoolean(3),
                )
            }
        }
    }
}

/** Solo para leer filas de rol sin crear un tipo publico. Los componentN los
 *  genera `data class`: declararlos a mano da "conflicting overloads". */
private data class FilaRol(
    val id: UUID,
    val clave: String,
    val nombre: String,
    val jerarquia: Int,
    val esSistema: Boolean,
)
