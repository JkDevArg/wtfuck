package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

/**
 * Canales (modulo F).
 *
 * Un canal es una conversacion con `tipo = 'canal'`. Reusa participante, roles,
 * permisos, mensajes, reacciones y adjuntos: nada de eso se duplica. Lo unico
 * propio es **quien puede hablar** y **como se lo encuentra**.
 *
 * Tres reglas que conviene tener presentes al leer esto:
 *
 *  1. Publicar exige `canal.publicar`. Un suscriptor no lo tiene, y por eso un
 *     canal es unidireccional sin necesidad de un "modo" aparte.
 *  2. Comentar exige `canal.comentar` Y que el canal tenga los comentarios
 *     encendidos. Lo segundo es lo que hace que apagarlos sirva de algo.
 *  3. Un canal publico guarda su contenido en el servidor, en claro. Ver la
 *     nota larga en `V10__canales.sql`; no es un descuido y la app lo dice.
 */
object Canales {

    /** Lo minimo del canal que otras partes necesitan consultar. */
    data class Basico(val publico: Boolean, val comentarios: Boolean, val reacciones: Boolean)

    // ------------------------------------------------------------------
    //  Crear y configurar
    // ------------------------------------------------------------------

    fun crear(yo: Auth, req: CrearCanalReq): ConfigCanal = Db.tx { c ->
        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre del canal debe tener entre 1 y 64 caracteres.")
        }
        val alias = normalizarAlias(req.alias)
        if (req.publico && alias == null) {
            throw ErrorNegocio(400, "Un canal publico necesita un alias para que se lo pueda encontrar.")
        }
        if (alias != null && aliasTomado(c, alias, null)) {
            throw ErrorNegocio(409, "Ese alias ya esta en uso.")
        }

        val id = c.prepareStatement(
            "INSERT INTO conversacion (tipo, creador_id, nombre) VALUES ('canal', ?, ?) RETURNING id"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, nombre)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        // Quien lo crea es propietario: puede publicar, configurar y nombrar
        // administradores. El rol ya trae todos los permisos de ambito canal.
        c.prepareStatement(
            """INSERT INTO participante (conversacion_id, usuario_id, rol, rol_id)
               VALUES (?, ?, 'admin', ?)"""
        ).use { st ->
            st.setObject(1, id)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, rolSistema(c, "propietario"))
            st.executeUpdate()
        }

        // F.7: nace PENDIENTE. Hasta que el dueno de la plataforma lo
        // apruebe no aparece en el directorio ni en la busqueda y nadie se
        // puede suscribir; quien lo creo si puede entrar y prepararlo.
        c.prepareStatement(
            """INSERT INTO canal (conversacion_id, alias, publico, descripcion,
                                  comentarios, reacciones, estado)
               VALUES (?, ?, ?, ?, ?, ?, 'pendiente')"""
        ).use { st ->
            st.setObject(1, id)
            st.setString(2, alias)
            st.setBoolean(3, req.publico)
            st.setString(4, req.descripcion.trim().take(1024))
            st.setBoolean(5, req.comentarios)
            st.setBoolean(6, req.reacciones)
            st.executeUpdate()
        }

        Autz.auditar(
            c, yo.usuarioId, "canal.crear", "conversacion", id,
            detalle = """{"alias":"${alias ?: ""}","publico":${req.publico}}""",
        )
        leer(c, id, yo.usuarioId)!!
    }

    fun configurar(yo: Auth, convId: UUID, req: ConfigCanalReq): ConfigCanal = Db.tx { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_EDITAR_INFO)
        exigirCanal(c, convId)

        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre del canal debe tener entre 1 y 64 caracteres.")
        }
        val alias = normalizarAlias(req.alias)
        if (req.publico && alias == null) {
            throw ErrorNegocio(400, "Un canal publico necesita un alias.")
        }
        if (alias != null && aliasTomado(c, alias, convId)) {
            throw ErrorNegocio(409, "Ese alias ya esta en uso.")
        }

        // Pasar de publico a privado NO vuelve secreto lo ya publicado: el
        // servidor lo tiene guardado y quien se suscribio lo leyo. Se dice
        // claro en vez de dar una falsa sensacion de que se puede deshacer.
        val eraPublico = basico(c, convId).publico
        if (eraPublico && !req.publico) {
            bitacoraCanales.info("El canal {} pasa a privado; lo ya publicado sigue guardado", convId)
        }

        c.prepareStatement("UPDATE conversacion SET nombre = ? WHERE id = ?").use { st ->
            st.setString(1, nombre); st.setObject(2, convId); st.executeUpdate()
        }
        c.prepareStatement(
            """UPDATE canal SET alias = ?, publico = ?, descripcion = ?,
                      comentarios = ?, reacciones = ? WHERE conversacion_id = ?"""
        ).use { st ->
            st.setString(1, alias)
            st.setBoolean(2, req.publico)
            st.setString(3, req.descripcion.trim().take(1024))
            st.setBoolean(4, req.comentarios)
            st.setBoolean(5, req.reacciones)
            st.setObject(6, convId)
            st.executeUpdate()
        }
        Autz.auditar(
            c, yo.usuarioId, "canal.configurar", "conversacion", convId,
            detalle = """{"publico":${req.publico},"comentarios":${req.comentarios}}""",
        )
        leer(c, convId, yo.usuarioId)!!
    }

    // ------------------------------------------------------------------
    //  Suscripcion
    // ------------------------------------------------------------------

    /**
     * Se suscribe al canal.
     *
     * Solo funciona con canales PUBLICOS. A un canal privado se entra por
     * invitacion, igual que a un grupo privado: reusa el modulo B y no hay
     * nada nuevo que escribir.
     */
    fun suscribir(yo: Auth, convId: UUID): ConfigCanal = Db.tx { c ->
        val b = basico(c, convId)
        if (!b.publico) {
            throw ErrorNegocio(403, "Ese canal es privado: hace falta una invitacion.")
        }
        // F.7. Se comprueba aqui ademas de filtrarlo en el directorio: el
        // filtro decide lo que se ve, y esto decide lo que se puede hacer.
        // Quien tenga el id de un canal pendiente -porque se lo pasaron-
        // tampoco entra.
        if (estadoDe(c, convId) != EstadoCanal.APROBADO) {
            throw ErrorNegocio(403, "Ese canal todavia no esta aprobado.")
        }
        c.prepareStatement(
            """INSERT INTO participante (conversacion_id, usuario_id, rol, rol_id)
               VALUES (?, ?, 'miembro', ?)
               ON CONFLICT (conversacion_id, usuario_id)
               DO UPDATE SET salido_en = NULL"""
        ).use { st ->
            st.setObject(1, convId)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, rolSistema(c, "suscriptor"))
            st.executeUpdate()
        }
        leer(c, convId, yo.usuarioId)!!
    }

    fun desuscribir(yo: Auth, convId: UUID) = Db.tx { c ->
        exigirCanal(c, convId)
        c.prepareStatement(
            """UPDATE participante SET salido_en = now()
               WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, convId); st.setObject(2, yo.usuarioId); st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    //  Publicar
    // ------------------------------------------------------------------

    /**
     * Guarda el contenido de una publicacion.
     *
     * El metadato del mensaje ya lo registro `Mensajes.registrar`, que es quien
     * comprueba `canal.publicar`. Aqui solo se guarda el cuerpo, y solo si el
     * canal es publico: en uno privado el cuerpo va cifrado y el servidor no
     * tiene nada que guardar.
     */
    fun guardarContenido(yo: Auth, convId: UUID, req: PublicarReq): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val b = basico(c, convId)
        if (!b.publico) {
            throw ErrorNegocio(409, "Un canal privado no guarda el contenido en el servidor.")
        }
        Autz.exigir(c, yo.usuarioId, convId, Permisos.CANAL_PUBLICAR)

        val msgId = uuid(req.mensajeId)
        val cuerpo = req.cuerpo.trim()
        if (cuerpo.isEmpty() || cuerpo.length > 8192) {
            throw ErrorNegocio(400, "La publicacion debe tener entre 1 y 8192 caracteres.")
        }
        // El mensaje tiene que existir y ser de esta conversacion y de quien
        // publica. Sin esto, cualquiera podria colgar texto de un mensaje ajeno.
        val valido = c.prepareStatement(
            "SELECT 1 FROM mensaje_meta WHERE id = ? AND conversacion_id = ? AND autor_id = ?"
        ).use { st ->
            st.setObject(1, msgId); st.setObject(2, convId); st.setObject(3, yo.usuarioId)
            st.executeQuery().use { it.next() }
        }
        if (!valido) throw ErrorNegocio(404, "Esa publicacion no existe en este canal.")

        c.prepareStatement(
            """INSERT INTO publicacion_contenido (mensaje_id, conversacion_id, cuerpo)
               VALUES (?, ?, ?) ON CONFLICT (mensaje_id) DO UPDATE SET cuerpo = EXCLUDED.cuerpo"""
        ).use { st ->
            st.setObject(1, msgId); st.setObject(2, convId); st.setString(3, cuerpo)
            st.executeUpdate()
        }

        // Un canal publico NO reparte sobres.
        //
        // Ahi esta la diferencia con un grupo, y es la que hace que un canal
        // escale: con diez mil suscriptores, un sobre por dispositivo serian
        // diez mil filas por publicacion. En vez de eso se emite UN aviso y
        // cada cliente se trae el historial cuando quiere. El contenido ya
        // esta guardado, asi que no hay nada que entregar.
        val suscriptores = c.prepareStatement(
            """SELECT usuario_id FROM participante
               WHERE conversacion_id = ? AND salido_en IS NULL AND usuario_id <> ?"""
        ).use { st ->
            st.setObject(1, convId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        Eventos.emitir(
            c, suscriptores, "canal_publicacion", convId, yo.username,
            detalle = cuerpo.take(140),
        )
    }

    /**
     * Historial del canal.
     *
     * Solo devuelve algo en canales publicos, porque solo ahi el servidor
     * tiene el contenido. En uno privado la respuesta es vacia y eso NO es un
     * error: es la consecuencia directa de que el servidor no pueda leer.
     */
    fun publicaciones(yo: Auth, convId: UUID, limite: Int, antesDe: String?): List<Publicacion> =
        Db.query { c ->
            // Un canal PUBLICO se lee sin estar suscrito, y eso no es una
            // concesion: es lo que significa publico. Sin esto, descubrir un
            // canal serviria para ver su nombre y nada mas, y habria que
            // seguirlo a ciegas para saber de que trata.
            if (!basico(c, convId).publico) {
                Autz.exigir(c, yo.usuarioId, convId, Permisos.GRUPO_VER_INFO)
            }
            val tope = limite.coerceIn(1, 100)
            val corte = antesDe?.let { runCatching { UUID.fromString(it) }.getOrNull() }

            val sql = StringBuilder(
                """SELECT p.mensaje_id, u.username, p.cuerpo,
                          extract(epoch FROM m.creado_en) * 1000,
                          m.editado_en IS NOT NULL, m.fijado_en IS NOT NULL,
                          (SELECT count(*) FROM mensaje_meta cm WHERE cm.responde_a = p.mensaje_id)
                   FROM publicacion_contenido p
                     JOIN mensaje_meta m ON m.id = p.mensaje_id
                     JOIN usuario u      ON u.id = m.autor_id
                   WHERE p.conversacion_id = ? AND m.retirado_en IS NULL"""
            )
            if (corte != null) sql.append(" AND p.mensaje_id < ?")
            sql.append(" ORDER BY p.mensaje_id DESC LIMIT ?")

            c.prepareStatement(sql.toString()).use { st ->
                var i = 1
                st.setObject(i++, convId)
                if (corte != null) st.setObject(i++, corte)
                st.setInt(i, tope)
                st.executeQuery().use { rs ->
                    rs.mapear {
                        val id = it.getObject(1, UUID::class.java)
                        Publicacion(
                            mensajeId = id.toString(),
                            autor = it.getString(2),
                            cuerpo = it.getString(3),
                            creadoEn = it.getDouble(4).toLong(),
                            editado = it.getBoolean(5),
                            fijado = it.getBoolean(6),
                            comentarios = it.getInt(7),
                        )
                    }
                }
            }
        }

    // ------------------------------------------------------------------
    //  Descubrir
    // ------------------------------------------------------------------

    /** Ficha de un canal por alias, SIN estar suscrito. Solo publicos. */
    fun porAlias(yo: Auth, alias: String): ConfigCanal = Db.query { c ->
        val limpio = normalizarAlias(alias) ?: throw ErrorNegocio(400, "Ese alias no es valido.")
        val id = c.prepareStatement(
            "SELECT conversacion_id FROM canal WHERE alias = ? AND publico AND estado = 'aprobado'"
        ).use { st ->
            st.setString(1, limpio)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        // Un canal pendiente responde lo mismo que uno inexistente: si diera
        // "existe pero no esta aprobado", el alias se volveria un oraculo para
        // enterarse de lo que hay en la cola del dueno.
        } ?: throw ErrorNegocio(404, "No existe un canal publico con ese alias.")
        leer(c, id, yo.usuarioId)!!
    }

    /**
     * Busca canales publicos.
     *
     * Busca en alias, nombre y descripcion. No hay ranking: se ordena por
     * suscriptores, que es la senal mas honesta que hay disponible sin
     * inventar una metrica de relevancia.
     */
    fun buscar(yo: Auth, consulta: String): List<CanalEnBusqueda> = Db.query { c ->
        val q = consulta.trim().lowercase().removePrefix("@")
        if (q.length < 2) return@query emptyList()
        c.prepareStatement(
            """SELECT k.conversacion_id, k.alias, coalesce(v.nombre,''), k.descripcion,
                      (SELECT count(*) FROM participante p
                        WHERE p.conversacion_id = k.conversacion_id AND p.salido_en IS NULL),
                      EXISTS (SELECT 1 FROM participante p2
                        WHERE p2.conversacion_id = k.conversacion_id
                          AND p2.usuario_id = ? AND p2.salido_en IS NULL)
               FROM canal k JOIN conversacion v ON v.id = k.conversacion_id
               WHERE k.publico AND k.estado = 'aprobado' AND (
                     k.alias LIKE ? OR lower(coalesce(v.nombre,'')) LIKE ?
                     OR lower(k.descripcion) LIKE ?)
               ORDER BY 5 DESC LIMIT 30"""
        ).use { st ->
            val patron = "%$q%"
            st.setObject(1, yo.usuarioId)
            st.setString(2, patron); st.setString(3, patron); st.setString(4, patron)
            st.executeQuery().use { rs ->
                rs.mapear {
                    CanalEnBusqueda(
                        conversacionId = it.getObject(1, UUID::class.java).toString(),
                        alias = it.getString(2),
                        nombre = it.getString(3),
                        descripcion = it.getString(4),
                        suscriptores = it.getInt(5),
                        suscrito = it.getBoolean(6),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Estadisticas
    // ------------------------------------------------------------------

    /**
     * Estadisticas del canal.
     *
     * Lo que NO hay: cuantos leyeron cada publicacion. Saberlo exigiria que
     * cada suscriptor reporte lectura por publicacion, y eso es a la vez un
     * problema de privacidad y de escala. Se cuenta lo que ya esta guardado
     * por otros motivos.
     */
    fun estadisticas(yo: Auth, convId: UUID): EstadisticasCanal = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, convId, Permisos.CANAL_ESTADISTICAS)
        c.prepareStatement(
            """SELECT
                 (SELECT count(*) FROM participante p
                   WHERE p.conversacion_id = ? AND p.salido_en IS NULL),
                 (SELECT count(*) FROM mensaje_meta m
                   WHERE m.conversacion_id = ? AND m.responde_a IS NULL AND m.retirado_en IS NULL),
                 (SELECT count(*) FROM reaccion r JOIN mensaje_meta m ON m.id = r.mensaje_id
                   WHERE m.conversacion_id = ?),
                 (SELECT count(*) FROM mensaje_meta m
                   WHERE m.conversacion_id = ? AND m.responde_a IS NOT NULL AND m.retirado_en IS NULL),
                 (SELECT count(*) FROM participante p
                   WHERE p.conversacion_id = ? AND p.salido_en IS NULL
                     AND p.unido_en > now() - interval '7 days')"""
        ).use { st ->
            (1..5).forEach { st.setObject(it, convId) }
            st.executeQuery().use { rs ->
                rs.primero {
                    EstadisticasCanal(
                        suscriptores = it.getInt(1),
                        publicaciones = it.getInt(2),
                        reacciones = it.getInt(3),
                        comentarios = it.getInt(4),
                        altasSemana = it.getInt(5),
                    )
                }
            } ?: EstadisticasCanal(0, 0, 0, 0, 0)
        }
    }

    // ------------------------------------------------------------------
    //  Consultas internas
    // ------------------------------------------------------------------

    /** Configuracion minima, para las comprobaciones de otros modulos. */
    fun basico(c: Connection, convId: UUID): Basico =
        c.prepareStatement(
            "SELECT publico, comentarios, reacciones FROM canal WHERE conversacion_id = ?"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs ->
                rs.primero { Basico(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3)) }
            }
        } ?: throw ErrorNegocio(404, "Ese canal no existe.")

    fun esCanal(c: Connection, convId: UUID): Boolean =
        c.prepareStatement("SELECT 1 FROM canal WHERE conversacion_id = ?").use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { it.next() }
        }

    /**
     * Un canal, por id.
     *
     * ## La comprobacion que faltaba
     *
     * La consulta de abajo -`leer(c, convId, usuarioId)`- filtra por
     * `conversacion_id` y nada mas, y eso es correcto para los CUATRO sitios
     * internos que la usan: crear, configurar, suscribirse y buscar por alias
     * llaman despues de haber autorizado, y en los cuatro quien pregunta o
     * esta suscrito o el canal ya se comprobo publico y aprobado.
     *
     * Esta funcion es la unica alcanzable desde la ruta, y no comprobaba nada.
     * Con el id -que es el id de la conversacion, o sea un valor que circula-
     * cualquiera leia el nombre, el alias, la descripcion, los contadores, el
     * estado y el **motivo de rechazo** de un canal privado o pendiente. Ese
     * ultimo campo es texto que escribio un moderador sobre el canal de otra
     * persona.
     *
     * Era una incoherencia dentro del propio modulo: `suscribir` exige publico
     * y aprobado, y `porAlias` esconde los pendientes diciendo por que -«el
     * alias se volveria un oraculo para enterarse de lo que hay en la cola»-.
     * Solo este camino se lo saltaba.
     *
     * La regla, en este orden:
     *
     *  1. Si estas suscrito, lees. Incluido el dueno de un canal pendiente o
     *     rechazado, que necesita ver su estado y por que se le rechazo.
     *  2. Si no, solo si es publico **y** aprobado.
     *  3. Si no, 404 y no 403: mismo motivo que en `porAlias`, un 403
     *     confirmaria que ese canal existe.
     */
    fun leer(yo: Auth, convId: UUID): ConfigCanal {
        val cfg = Db.query { c -> leer(c, convId, yo.usuarioId) }
            ?: throw ErrorNegocio(404, "Ese canal no existe.")
        val visible = cfg.suscrito || (cfg.publico && cfg.estado == EstadoCanal.APROBADO)
        if (!visible) throw ErrorNegocio(404, "Ese canal no existe.")
        return cfg
    }


    // ==================================================================
    //  F.7 · Aprobacion por el dueno de la plataforma
    // ==================================================================

    private fun estadoDe(c: Connection, convId: UUID): String =
        c.prepareStatement("SELECT estado FROM canal WHERE conversacion_id = ?").use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Ese canal no existe.")

    /**
     * El directorio: los canales aprobados, sin escribir nada.
     *
     * Reemplaza al buscador como forma normal de encontrar un canal. Un
     * buscador obliga a adivinar el nombre de algo que no sabes que existe; si
     * la unica puerta es esa, un canal nuevo es invisible salvo para quien ya
     * sabe que esta. Con aprobacion previa la lista completa es mostrable, asi
     * que se muestra.
     *
     * Ordena por suscriptores, que es la senal mas honesta disponible sin
     * inventar una metrica de relevancia.
     */
    fun directorio(yo: Auth, limite: Int): List<CanalEnBusqueda> = Db.query { c ->
        c.prepareStatement(
            """SELECT k.conversacion_id, k.alias, coalesce(v.nombre,''), k.descripcion,
                      (SELECT count(*) FROM participante p
                        WHERE p.conversacion_id = k.conversacion_id AND p.salido_en IS NULL),
                      EXISTS (SELECT 1 FROM participante p2
                               WHERE p2.conversacion_id = k.conversacion_id
                                 AND p2.usuario_id = ? AND p2.salido_en IS NULL)
               FROM canal k JOIN conversacion v ON v.id = k.conversacion_id
               WHERE k.publico AND k.estado = 'aprobado'
               ORDER BY 5 DESC, v.nombre
               LIMIT ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setInt(2, limite.coerceIn(1, 200))
            st.executeQuery().use { rs ->
                rs.mapear {
                    CanalEnBusqueda(
                        conversacionId = it.getObject(1, UUID::class.java).toString(),
                        alias = it.getString(2).orEmpty(),
                        nombre = it.getString(3),
                        descripcion = it.getString(4),
                        suscriptores = it.getInt(5),
                        suscrito = it.getBoolean(6),
                    )
                }
            }
        }
    }

    /**
     * Los canales que ve el dueno de la plataforma, por estado.
     *
     * `estado` vacio devuelve la cola -lo pendiente-, que es el caso normal.
     * Con `aprobado` devuelve lo que ya esta publicado, que es lo que hace
     * falta para poder RETIRAR algo despues: sin esta vista, aprobar seria una
     * puerta de un solo sentido y un canal que se descarrila un mes despues no
     * tendria como cerrarse sin entrar a la base a mano.
     */
    fun cola(yo: Auth, estado: String = EstadoCanal.PENDIENTE): List<CanalPendiente> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.PROPIETARIO)
        if (estado !in EstadoCanal.TODOS) throw ErrorNegocio(400, "Estado invalido.")
        c.prepareStatement(
            """SELECT k.conversacion_id, coalesce(v.nombre,''), k.alias, k.publico,
                      k.descripcion, coalesce(u.username,''),
                      (EXTRACT(EPOCH FROM k.creado_en) * 1000)::bigint,
                      (SELECT count(*) FROM mensaje_meta m
                        WHERE m.conversacion_id = k.conversacion_id
                          AND m.responde_a IS NULL AND m.retirado_en IS NULL),
                      k.estado,
                      (SELECT count(*) FROM participante p
                        WHERE p.conversacion_id = k.conversacion_id AND p.salido_en IS NULL),
                      k.motivo_rechazo
               FROM canal k
                 JOIN conversacion v ON v.id = k.conversacion_id
                 LEFT JOIN usuario u ON u.id = v.creador_id
               WHERE k.estado = ?
               ORDER BY k.creado_en"""
        ).use { st ->
            st.setString(1, estado)
            st.executeQuery().use { rs ->
                rs.mapear {
                    CanalPendiente(
                        conversacionId = it.getObject(1, UUID::class.java).toString(),
                        nombre = it.getString(2),
                        alias = it.getString(3),
                        publico = it.getBoolean(4),
                        descripcion = it.getString(5),
                        creador = it.getString(6),
                        creadoEn = it.getLong(7),
                        publicaciones = it.getInt(8),
                        estado = it.getString(9),
                        suscriptores = it.getInt(10),
                        motivoRechazo = it.getString(11),
                    )
                }
            }
        }
    }

    /**
     * Aprueba o rechaza un canal.
     *
     * Exige nivel **propietario** y no administrador. Es lo que se pidio: el
     * dueno de la plataforma decide que canales existen. Delegarlo despues es
     * cambiar esta constante, y esta aqui sola para que se vea.
     */
    fun revisar(yo: Auth, convId: UUID, req: RevisarCanalReq): List<Pair<UUID, Bajada.Evento>> =
        Db.tx { c ->
            Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.PROPIETARIO)
            exigirCanal(c, convId)

            val motivo = req.motivo.trim().take(500)
            if (!req.aprobado && motivo.isEmpty()) {
                throw ErrorNegocio(400, "Rechazar un canal necesita un motivo: quien lo creo tiene que saber que corregir.")
            }
            // La decision es REVERSIBLE en los dos sentidos, y tiene que
            // serlo: un canal aprobado que se descarrila se retira, y uno
            // rechazado que corrigio lo que se le pidio se aprueba. Lo unico
            // que se rechaza es el no-op, que solo puede venir de un doble
            // toque o de dos personas decidiendo a la vez.
            val antes = estadoDe(c, convId)
            val despues = if (req.aprobado) EstadoCanal.APROBADO else EstadoCanal.RECHAZADO
            if (antes == despues) {
                throw ErrorNegocio(409, "Ese canal ya esta en ese estado.")
            }

            c.prepareStatement(
                """UPDATE canal
                   SET estado = ?, revisado_por = ?, revisado_en = now(), motivo_rechazo = ?
                   WHERE conversacion_id = ?"""
            ).use { st ->
                st.setString(1, despues)
                st.setObject(2, yo.usuarioId)
                st.setString(3, if (req.aprobado) null else motivo)
                st.setObject(4, convId)
                st.executeUpdate()
            }

            val creador = c.prepareStatement(
                "SELECT creador_id FROM conversacion WHERE id = ?"
            ).use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
            }

            Autz.auditar(
                c, yo.usuarioId,
                if (req.aprobado) "canal.aprobar" else "canal.rechazar",
                "conversacion", convId,
                // `detalleDe` y no interpolacion: el motivo es texto libre.
                // El `.replace("\"", "")` que habia aqui paraba las comillas
                // -alguien ya habia visto el problema- pero no una barra
                // invertida final, y encima cambiaba el dato que se guardaba.
                detalle = Autz.detalleDe("motivo" to motivo),
            )

            // El aviso va a quien lo creo, no a los suscriptores: mientras
            // estuvo pendiente no podia tener ninguno.
            if (creador == null) emptyList()
            else Eventos.emitir(
                c, listOf(creador),
                if (req.aprobado) "canal_aprobado" else "canal_rechazado",
                convId, yo.username, motivo.ifEmpty { null },
            )
        }

    private fun leer(c: Connection, convId: UUID, usuarioId: UUID): ConfigCanal? =
        c.prepareStatement(
            """SELECT coalesce(v.nombre,''), k.alias, k.publico, k.descripcion,
                      k.comentarios, k.reacciones,
                      (SELECT count(*) FROM participante p
                        WHERE p.conversacion_id = k.conversacion_id AND p.salido_en IS NULL),
                      (SELECT count(*) FROM mensaje_meta m
                        WHERE m.conversacion_id = k.conversacion_id
                          AND m.responde_a IS NULL AND m.retirado_en IS NULL),
                      coalesce((SELECT r.clave FROM participante p JOIN rol r ON r.id = p.rol_id
                        WHERE p.conversacion_id = k.conversacion_id
                          AND p.usuario_id = ? AND p.salido_en IS NULL), ''),
                      k.estado, k.motivo_rechazo
               FROM canal k JOIN conversacion v ON v.id = k.conversacion_id
               WHERE k.conversacion_id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setObject(2, convId)
            st.executeQuery().use { rs ->
                rs.primero {
                    val miRol = it.getString(9)
                    val publico = it.getBoolean(3)
                    val suscrito = miRol.isNotEmpty()
                    ConfigCanal(
                        conversacionId = convId.toString(),
                        nombre = it.getString(1),
                        alias = it.getString(2),
                        publico = publico,
                        descripcion = it.getString(4),
                        comentarios = it.getBoolean(5),
                        reacciones = it.getBoolean(6),
                        suscriptores = it.getInt(7),
                        publicaciones = it.getInt(8),
                        suscrito = suscrito,
                        // Se resuelve con el motor de permisos y no mirando el
                        // rol a ojo: es la misma respuesta que dara el servidor
                        // cuando de verdad se intente publicar.
                        puedoPublicar = suscrito &&
                            Autz.puede(c, usuarioId, convId, Permisos.CANAL_PUBLICAR).permitido,
                        puedoGestionar = suscrito &&
                            Autz.puede(c, usuarioId, convId, Permisos.GRUPO_EDITAR_INFO).permitido,
                        miRol = miRol,
                        // Un canal publico NO va cifrado de extremo a extremo.
                        cifrado = !publico,
                        estado = it.getString(10),
                        motivoRechazo = it.getString(11),
                    )
                }
            }
        }

    private fun exigirCanal(c: Connection, convId: UUID) {
        if (!esCanal(c, convId)) throw ErrorNegocio(404, "Ese canal no existe.")
    }

    private fun normalizarAlias(crudo: String?): String? {
        val a = crudo?.trim()?.lowercase()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
        if (!Regex("^[a-z0-9_]{4,32}$").matches(a)) {
            throw ErrorNegocio(400, "El alias debe tener entre 4 y 32 caracteres: letras, numeros y _.")
        }
        return a
    }

    /**
     * Si el alias ya lo usa OTRO canal.
     *
     * Dos sentencias y no un `(? IS NULL OR ...)`: Postgres no puede inferir el
     * tipo de un parametro que solo aparece en un `IS NULL` y responde
     * "could not determine data type of parameter". Ya me mordio antes con un
     * CASE sobre uuid; la salida es la misma, no darle parametros ambiguos.
     */
    private fun aliasTomado(c: Connection, alias: String, excepto: UUID?): Boolean {
        val sql = if (excepto == null) {
            "SELECT 1 FROM canal WHERE alias = ?"
        } else {
            "SELECT 1 FROM canal WHERE alias = ? AND conversacion_id <> ?"
        }
        return c.prepareStatement(sql).use { st ->
            st.setString(1, alias)
            if (excepto != null) st.setObject(2, excepto)
            st.executeQuery().use { it.next() }
        }
    }

    private fun rolSistema(c: Connection, clave: String): UUID =
        c.prepareStatement("SELECT id FROM rol WHERE clave = ? AND es_sistema").use { st ->
            st.setString(1, clave)
            st.executeQuery().use { rs ->
                rs.primero { it.getObject(1, UUID::class.java) }
            }
        } ?: error("Falta el rol de sistema '$clave'")

    private fun uuid(s: String): UUID =
        runCatching { UUID.fromString(s) }.getOrElse { throw ErrorNegocio(400, "Identificador invalido.") }
}

private val bitacoraCanales = org.slf4j.LoggerFactory.getLogger("canales")
