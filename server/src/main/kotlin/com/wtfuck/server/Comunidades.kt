package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

/**
 * Comunidades (modulo AD).
 *
 * Un conjunto de grupos bajo un nombre, **mas un canal de anuncios**. Lo
 * segundo es lo unico que la hace una comunidad: sin el, agrupar chats es una
 * carpeta, y una carpeta se resuelve en el telefono sin que el servidor se
 * entere.
 *
 * Tres reglas que conviene tener presentes al leer esto:
 *
 *  1. **La pertenencia se deriva.** Sos de la comunidad si sos de alguno de
 *     sus grupos. No hay lista de miembros aparte; lo que se mantiene es la
 *     fila de `participante` del canal de anuncios.
 *  2. **Administrar la comunidad es ser admin de su canal de anuncios.** No se
 *     inventa un rol nuevo: el canal ya tiene roles, permisos y jerarquia.
 *  3. **Agregar un grupo respeta la privacidad de cada persona.** Quien tiene
 *     `comunidades: nadie` queda en su grupo y fuera de los anuncios. Ver
 *     `V37__comunidades.sql`.
 */
object Comunidades {

    private const val TOPE_GRUPOS = 50

    // ------------------------------------------------------------------
    //  Crear
    // ------------------------------------------------------------------

    fun crear(yo: Auth, req: CrearComunidadReq): ComunidadDetalle = Db.tx { c ->
        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre debe tener entre 1 y 64 caracteres.")
        }
        val descripcion = req.descripcion.trim().take(512)

        // El canal de anuncios es una conversacion de tipo 'canal', PRIVADA.
        //
        // Privada y no publica: los anuncios de una comunidad son para su
        // gente. Un canal publico guardaria el contenido en claro en el
        // servidor, y eso es una excepcion que se declara para lo que de
        // verdad es publico, no algo que se hereda sin querer.
        val anunciosId = c.prepareStatement(
            "INSERT INTO conversacion (tipo, creador_id, nombre) VALUES ('canal', ?, ?) RETURNING id"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, nombre)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        c.prepareStatement(
            "INSERT INTO canal (conversacion_id, publico, descripcion, comentarios, reacciones) " +
                "VALUES (?, false, ?, false, true)"
        ).use { st ->
            st.setObject(1, anunciosId); st.setString(2, descripcion)
            st.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO participante (conversacion_id, usuario_id, rol) VALUES (?, ?, 'admin')"
        ).use { st ->
            st.setObject(1, anunciosId); st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }

        val id = c.prepareStatement(
            """INSERT INTO comunidad (nombre, descripcion, creador_id, anuncios_id)
               VALUES (?, ?, ?, ?) RETURNING id"""
        ).use { st ->
            st.setString(1, nombre); st.setString(2, descripcion)
            st.setObject(3, yo.usuarioId); st.setObject(4, anunciosId)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        Autz.auditar(
            c, yo.usuarioId, "comunidad.crear", "comunidad", id,
            detalle = jsonDetalle("nombre", nombre),
        )

        val rechazados = sumarGrupos(c, yo, id, anunciosId, req.grupos)
        detalleDe(c, yo, id).let { it.copy(rechazados = rechazados) }
    }

    // ------------------------------------------------------------------
    //  Grupos
    // ------------------------------------------------------------------

    fun agregarGrupos(yo: Auth, comunidadId: UUID, req: AgregarGruposReq): ComunidadDetalle =
        Db.tx { c ->
            val anunciosId = exigirAdmin(c, yo, comunidadId)
            val rechazados = sumarGrupos(c, yo, comunidadId, anunciosId, req.grupos)
            detalleDe(c, yo, comunidadId).let { it.copy(rechazados = rechazados) }
        }

    /**
     * Agrega grupos y devuelve los que no se pudieron, con el motivo.
     *
     * **No falla entero.** Quien agrega cinco grupos y tiene permiso en cuatro
     * espera que entren los cuatro y que le digan cual no; un 403 que descarta
     * los cinco convierte un aviso en un reintento a ciegas.
     */
    private fun sumarGrupos(
        c: Connection,
        yo: Auth,
        comunidadId: UUID,
        anunciosId: UUID,
        grupos: List<String>,
    ): List<GrupoRechazado> {
        if (grupos.isEmpty()) return emptyList()
        val rechazados = mutableListOf<GrupoRechazado>()

        val yaTiene = c.prepareStatement(
            "SELECT count(*)::int FROM comunidad_grupo WHERE comunidad_id = ?"
        ).use { st ->
            st.setObject(1, comunidadId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }
        var cupo = TOPE_GRUPOS - yaTiene

        for (bruto in grupos.distinct()) {
            val convId = runCatching { UUID.fromString(bruto) }.getOrNull()
            if (convId == null) {
                rechazados += GrupoRechazado(bruto, "Identificador invalido.")
                continue
            }
            if (cupo <= 0) {
                rechazados += GrupoRechazado(bruto, "La comunidad llego al tope de $TOPE_GRUPOS grupos.")
                continue
            }

            val tipo = c.prepareStatement("SELECT tipo FROM conversacion WHERE id = ?").use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
            }
            if (tipo == null) {
                rechazados += GrupoRechazado(bruto, "Ese grupo no existe.")
                continue
            }
            if (tipo != "grupo") {
                // Un canal dentro de una comunidad no significa nada: ya
                // alcanza a su gente. Y una directa menos todavia.
                rechazados += GrupoRechazado(bruto, "Solo se pueden agregar grupos.")
                continue
            }

            // Hace falta poder administrar ESE grupo. Sin esto, cualquiera
            // metería el grupo de otro en su comunidad y le sumaría la gente a
            // un canal de anuncios ajeno.
            val puedo = runCatching {
                Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_EDITAR_INFO)
                true
            }.getOrDefault(false)
            if (!puedo) {
                rechazados += GrupoRechazado(bruto, "No administras ese grupo.")
                continue
            }

            val ocupado = c.prepareStatement(
                "SELECT comunidad_id FROM comunidad_grupo WHERE conversacion_id = ?"
            ).use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
            }
            if (ocupado != null) {
                rechazados += GrupoRechazado(
                    bruto,
                    if (ocupado == comunidadId) "Ya esta en esta comunidad."
                    else "Ya pertenece a otra comunidad.",
                )
                continue
            }

            c.prepareStatement(
                """INSERT INTO comunidad_grupo (comunidad_id, conversacion_id, agregado_por)
                   VALUES (?, ?, ?)"""
            ).use { st ->
                st.setObject(1, comunidadId); st.setObject(2, convId); st.setObject(3, yo.usuarioId)
                st.executeUpdate()
            }
            cupo--

            sumarMiembrosAAnuncios(c, yo.usuarioId, convId, anunciosId)
            Autz.auditar(
                c, yo.usuarioId, "comunidad.agregar_grupo", "comunidad", comunidadId,
                detalle = jsonDetalle("grupo", convId.toString()),
            )
        }
        return rechazados
    }

    /**
     * Mete en el canal de anuncios a la gente del grupo que lo acepte.
     *
     * ## El ajuste de privacidad, y por que este caso lo necesita
     *
     * `priv_grupos` gobierna que me agreguen a un grupo. Lo que no cubre es el
     * caso propio de una comunidad: alguien agrega a la comunidad **el grupo
     * en el que yo ya estaba**, y de golpe estoy en un canal de anuncios con
     * quinientos desconocidos sin que nadie me haya agregado a nada. Ese es el
     * hecho nuevo, y es el que `priv_comunidades` gobierna.
     *
     * Quien lo tenga en `nadie` **se queda en su grupo**: solo no entra a los
     * anuncios. Un ajuste de privacidad que te expulsa de algo no es un ajuste
     * de privacidad, es una sancion.
     */
    private fun sumarMiembrosAAnuncios(
        c: Connection,
        actor: UUID,
        convId: UUID,
        anunciosId: UUID,
    ) {
        val miembros = c.prepareStatement(
            "SELECT usuario_id FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        for (u in miembros) {
            if (u != actor && !aceptaComunidades(c, u, actor)) continue
            c.prepareStatement(
                """INSERT INTO participante (conversacion_id, usuario_id, rol)
                   VALUES (?, ?, 'miembro')
                   ON CONFLICT (conversacion_id, usuario_id)
                   DO UPDATE SET salido_en = NULL"""
            ).use { st ->
                st.setObject(1, anunciosId); st.setObject(2, u)
                st.executeUpdate()
            }
        }
    }

    /** Si `usuario` acepta que `actor` lo sume al canal de anuncios de una comunidad. */
    private fun aceptaComunidades(c: Connection, usuario: UUID, actor: UUID): Boolean {
        val nivel = c.prepareStatement(
            "SELECT priv_comunidades FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, usuario)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: Privacidad.TODOS

        return when (nivel) {
            Privacidad.NADIE -> false
            Privacidad.TODOS -> true
            // `conocidos` y `personalizado` se resuelven igual que el resto de
            // los ajustes: lo decide el dueno, no el observador. Ver la nota
            // larga de `Repo.quienesMeConocen`.
            else -> actor in Repo.quienesMeConocen(c, usuario)
        }
    }

    fun quitarGrupo(yo: Auth, comunidadId: UUID, convId: UUID) = Db.tx { c ->
        val anunciosId = exigirAdmin(c, yo, comunidadId)
        val borradas = c.prepareStatement(
            "DELETE FROM comunidad_grupo WHERE comunidad_id = ? AND conversacion_id = ?"
        ).use { st ->
            st.setObject(1, comunidadId); st.setObject(2, convId)
            st.executeUpdate()
        }
        if (borradas == 0) throw ErrorNegocio(404, "Ese grupo no esta en la comunidad.")

        // Se saca de los anuncios a quien se quede sin ningun grupo de la
        // comunidad. Quien siga en otro se queda: sacarlo seria quitarle algo
        // que sigue mereciendo.
        val candidatos = c.prepareStatement(
            "SELECT usuario_id FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        for (u in candidatos) sacarSiSeQuedoSinGrupos(c, comunidadId, anunciosId, u)

        Autz.auditar(
            c, yo.usuarioId, "comunidad.quitar_grupo", "comunidad", comunidadId,
            detalle = jsonDetalle("grupo", convId.toString()),
        )
    }

    /**
     * Saca del canal de anuncios a quien ya no este en ningun grupo.
     *
     * **Salvo si administra la comunidad.** Quien la administra puede no estar
     * en ninguno de sus grupos y eso es normal; echarlo de sus propios
     * anuncios por administrar y no participar seria absurdo.
     */
    private fun sacarSiSeQuedoSinGrupos(
        c: Connection,
        comunidadId: UUID,
        anunciosId: UUID,
        usuario: UUID,
    ) {
        val sigue = c.prepareStatement(
            """SELECT 1 FROM comunidad_grupo cg
                 JOIN participante p ON p.conversacion_id = cg.conversacion_id
               WHERE cg.comunidad_id = ? AND p.usuario_id = ? AND p.salido_en IS NULL
               LIMIT 1"""
        ).use { st ->
            st.setObject(1, comunidadId); st.setObject(2, usuario)
            st.executeQuery().use { it.next() }
        }
        if (sigue) return

        val esAdmin = c.prepareStatement(
            """SELECT 1 FROM participante
               WHERE conversacion_id = ? AND usuario_id = ? AND rol = 'admin'
                 AND salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, anunciosId); st.setObject(2, usuario)
            st.executeQuery().use { it.next() }
        }
        if (esAdmin) return

        c.prepareStatement(
            """UPDATE participante SET salido_en = now()
               WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, anunciosId); st.setObject(2, usuario)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    //  Los enganches: entrar y salir de un grupo
    // ------------------------------------------------------------------

    /**
     * Alguien entro a un grupo. Si ese grupo esta en una comunidad, entra
     * tambien a sus anuncios.
     *
     * Sin esto, la pertenencia derivada se rompe en cuanto alguien entra a un
     * grupo DESPUES de que el grupo entrara a la comunidad: estaria en la
     * comunidad y no en sus anuncios, que es precisamente la lista que el
     * modelo dice que se mantiene.
     *
     * Se llama desde donde se entra a un grupo, y se traga sus propios errores
     * a proposito: fallar al sumar a los anuncios no puede impedir entrar al
     * grupo, que es lo que la persona pidio.
     */
    fun alEntrarAGrupo(c: Connection, convId: UUID, usuario: UUID, actor: UUID) {
        runCatching {
            val fila = c.prepareStatement(
                """SELECT k.anuncios_id FROM comunidad_grupo cg
                     JOIN comunidad k ON k.id = cg.comunidad_id
                   WHERE cg.conversacion_id = ?"""
            ).use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
            } ?: return@runCatching
            if (usuario != actor && !aceptaComunidades(c, usuario, actor)) return@runCatching
            c.prepareStatement(
                """INSERT INTO participante (conversacion_id, usuario_id, rol)
                   VALUES (?, ?, 'miembro')
                   ON CONFLICT (conversacion_id, usuario_id) DO UPDATE SET salido_en = NULL"""
            ).use { st ->
                st.setObject(1, fila); st.setObject(2, usuario)
                st.executeUpdate()
            }
        }.onFailure { bitacoraComunidades.warn("No se pudo sumar a los anuncios: ${it.message}") }
    }

    /** Alguien salio de un grupo. Si era su ultimo grupo, sale de los anuncios. */
    fun alSalirDeGrupo(c: Connection, convId: UUID, usuario: UUID) {
        runCatching {
            val fila = c.prepareStatement(
                """SELECT cg.comunidad_id, k.anuncios_id FROM comunidad_grupo cg
                     JOIN comunidad k ON k.id = cg.comunidad_id
                   WHERE cg.conversacion_id = ?"""
            ).use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs ->
    rs.primero<Pair<UUID, UUID>> {
                        it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java)
                    }
                }
            } ?: return@runCatching
            sacarSiSeQuedoSinGrupos(c, fila.first, fila.second, usuario)
        }.onFailure { bitacoraComunidades.warn("No se pudo sacar de los anuncios: ${it.message}") }
    }

    // ------------------------------------------------------------------
    //  Leer
    // ------------------------------------------------------------------

    /**
     * Mis comunidades: aquellas donde estoy en algun grupo o en sus anuncios.
     *
     * Las dos condiciones, porque las dos clases de pertenencia son reales:
     * quien administra puede no estar en ningun grupo, y quien tiene
     * `comunidades: nadie` esta en los grupos y no en los anuncios. Con una
     * sola de las dos, uno de los dos desaparece de su propia lista.
     */
    fun mias(yo: Auth): ListaComunidades = Db.query { c ->
        val filas = c.prepareStatement(
            """SELECT k.id, k.nombre, k.descripcion, k.anuncios_id,
                      (SELECT count(*)::int FROM comunidad_grupo g WHERE g.comunidad_id = k.id),
                      coalesce((SELECT p.rol = 'admin' FROM participante p
                                 WHERE p.conversacion_id = k.anuncios_id
                                   AND p.usuario_id = ? AND p.salido_en IS NULL), false)
               FROM comunidad k
               WHERE EXISTS (SELECT 1 FROM participante pa
                              WHERE pa.conversacion_id = k.anuncios_id
                                AND pa.usuario_id = ? AND pa.salido_en IS NULL)
                  OR EXISTS (SELECT 1 FROM comunidad_grupo cg
                               JOIN participante pg ON pg.conversacion_id = cg.conversacion_id
                              WHERE cg.comunidad_id = k.id
                                AND pg.usuario_id = ? AND pg.salido_en IS NULL)
               ORDER BY k.creada_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setObject(2, yo.usuarioId); st.setObject(3, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    ComunidadResumen(
                        id = it.getObject(1, UUID::class.java).toString(),
                        nombre = it.getString(2),
                        descripcion = it.getString(3),
                        anunciosId = it.getObject(4, UUID::class.java).toString(),
                        grupos = it.getInt(5),
                        soyAdmin = it.getBoolean(6),
                    )
                }
            }
        }
        ListaComunidades(filas)
    }

    fun detalle(yo: Auth, comunidadId: UUID): ComunidadDetalle = Db.query { c ->
        detalleDe(c, yo, comunidadId)
    }

    private fun detalleDe(c: Connection, yo: Auth, comunidadId: UUID): ComunidadDetalle {
        val resumen = c.prepareStatement(
            """SELECT k.id, k.nombre, k.descripcion, k.anuncios_id,
                      (SELECT count(*)::int FROM comunidad_grupo g WHERE g.comunidad_id = k.id),
                      coalesce((SELECT p.rol = 'admin' FROM participante p
                                 WHERE p.conversacion_id = k.anuncios_id
                                   AND p.usuario_id = ? AND p.salido_en IS NULL), false)
               FROM comunidad k WHERE k.id = ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setObject(2, comunidadId)
            st.executeQuery().use { rs ->
                rs.primero {
                    ComunidadResumen(
                        id = it.getObject(1, UUID::class.java).toString(),
                        nombre = it.getString(2),
                        descripcion = it.getString(3),
                        anunciosId = it.getObject(4, UUID::class.java).toString(),
                        grupos = it.getInt(5),
                        soyAdmin = it.getBoolean(6),
                    )
                }
            }
        } ?: throw ErrorNegocio(404, "Esa comunidad no existe.")

        // Quien no pertenece no ve la lista de grupos. 404 y no 403: confirmar
        // que existe ya dice algo de una comunidad que no te toca.
        if (!pertenezco(c, yo.usuarioId, comunidadId, UUID.fromString(resumen.anunciosId))) {
            throw ErrorNegocio(404, "Esa comunidad no existe.")
        }

        val grupos = c.prepareStatement(
            """SELECT cv.id, coalesce(cv.nombre, ''),
                      (SELECT count(*)::int FROM participante p
                        WHERE p.conversacion_id = cv.id AND p.salido_en IS NULL),
                      EXISTS (SELECT 1 FROM participante pm
                               WHERE pm.conversacion_id = cv.id AND pm.usuario_id = ?
                                 AND pm.salido_en IS NULL)
               FROM comunidad_grupo cg JOIN conversacion cv ON cv.id = cg.conversacion_id
               WHERE cg.comunidad_id = ?
               ORDER BY cg.agregado_en"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setObject(2, comunidadId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    GrupoDeComunidad(
                        conversacionId = it.getObject(1, UUID::class.java).toString(),
                        nombre = it.getString(2),
                        miembros = it.getInt(3),
                        estoy = it.getBoolean(4),
                    )
                }
            }
        }
        return ComunidadDetalle(resumen, grupos)
    }

    fun editar(yo: Auth, comunidadId: UUID, req: EditarComunidadReq): ComunidadDetalle = Db.tx { c ->
        val anunciosId = exigirAdmin(c, yo, comunidadId)
        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre debe tener entre 1 y 64 caracteres.")
        }
        c.prepareStatement("UPDATE comunidad SET nombre = ?, descripcion = ? WHERE id = ?").use { st ->
            st.setString(1, nombre); st.setString(2, req.descripcion.trim().take(512))
            st.setObject(3, comunidadId)
            st.executeUpdate()
        }
        // El canal de anuncios lleva el mismo nombre: son la misma cosa para
        // quien lo ve en su lista de chats.
        c.prepareStatement("UPDATE conversacion SET nombre = ? WHERE id = ?").use { st ->
            st.setString(1, nombre); st.setObject(2, anunciosId)
            st.executeUpdate()
        }
        Autz.auditar(
            c, yo.usuarioId, "comunidad.editar", "comunidad", comunidadId,
            detalle = jsonDetalle("nombre", nombre),
        )
        detalleDe(c, yo, comunidadId)
    }

    // ------------------------------------------------------------------
    //  Ayudas
    // ------------------------------------------------------------------

    /** Administrar la comunidad ES ser admin de su canal de anuncios. */
    private fun exigirAdmin(c: Connection, yo: Auth, comunidadId: UUID): UUID {
        val anunciosId = c.prepareStatement("SELECT anuncios_id FROM comunidad WHERE id = ?").use { st ->
            st.setObject(1, comunidadId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: throw ErrorNegocio(404, "Esa comunidad no existe.")

        val esAdmin = c.prepareStatement(
            """SELECT 1 FROM participante
               WHERE conversacion_id = ? AND usuario_id = ? AND rol = 'admin'
                 AND salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, anunciosId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { it.next() }
        }
        if (!esAdmin) throw ErrorNegocio(403, "No administras esta comunidad.")
        return anunciosId
    }

    private fun pertenezco(c: Connection, usuario: UUID, comunidadId: UUID, anunciosId: UUID): Boolean =
        c.prepareStatement(
            """SELECT 1 WHERE EXISTS (
                   SELECT 1 FROM participante p
                    WHERE p.conversacion_id = ? AND p.usuario_id = ? AND p.salido_en IS NULL
               ) OR EXISTS (
                   SELECT 1 FROM comunidad_grupo cg
                     JOIN participante pg ON pg.conversacion_id = cg.conversacion_id
                    WHERE cg.comunidad_id = ? AND pg.usuario_id = ? AND pg.salido_en IS NULL
               )"""
        ).use { st ->
            st.setObject(1, anunciosId); st.setObject(2, usuario)
            st.setObject(3, comunidadId); st.setObject(4, usuario)
            st.executeQuery().use { it.next() }
        }
}

/**
 * Un detalle de auditoria en JSON, con el valor escapado.
 *
 * La columna `auditoria.detalle` es `jsonb`. Interpolar un nombre puesto por
 * una persona sin escapar -y los nombres de comunidad los pone una persona-
 * produce JSON invalido en cuanto alguien usa una comilla, y el INSERT falla
 * **despues** de haber hecho el trabajo real.
 */
private fun jsonDetalle(clave: String, valor: String): String =
    kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.JsonObject(
            mapOf(clave to kotlinx.serialization.json.JsonPrimitive(valor))
        ),
    )

private val bitacoraComunidades = org.slf4j.LoggerFactory.getLogger("comunidades")
