package com.wtfuck.server

import com.wtfuck.protocol.*
import java.time.Duration
import java.util.UUID

/**
 * Modulo H: panel administrativo.
 *
 * Va pegado al modulo G y no antes: un panel sin denuncias no tiene nada que
 * mostrar, y se habria disenado a ciegas sobre datos inventados.
 *
 * ## Lo que el panel NO puede hacer
 *
 * No puede mostrar mensajes. Ni buscarlos, ni leerlos, ni exportarlos. Un panel
 * administrativo en una app normal se llena de eso, y aqui es imposible por
 * construccion: el servidor solo tiene bytes opacos. Lo unico legible que
 * existe es lo que un denunciante entrego, y eso vive dentro de su denuncia y
 * se borra al cerrarla.
 *
 * Tampoco puede ver la libreta de nadie, ni sus conversaciones, ni con quien
 * habla. Se puede contar cuantas tiene, no cuales.
 *
 * ## Los dos niveles
 *
 * Moderador (50) trabaja la cola: lee denuncias, advierte, silencia, expulsa.
 * Administrador (80) es el unico que suspende cuentas por mano propia y el
 * unico que puede nombrar staff. La linea esta ahi porque suspender una cuenta
 * y repartir poder son las dos cosas que no se pueden deshacer del todo.
 */
object Panel {

    /**
     * A partir de cuantas filas se deja de contar `mensaje_meta` de verdad.
     *
     * Por debajo el `count(*)` cuesta milisegundos y el numero exacto es
     * gratis; por encima es un recorrido secuencial de la tabla mas grande de
     * la plataforma cada vez que alguien abre el panel, y un tablero que tarda
     * es un tablero que no se mira.
     */
    private const val UMBRAL_CONTEO_EXACTO = 1_000_000L

    fun resumen(yo: Auth): ResumenPanel = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId)

        val n = c.prepareStatement(
            """SELECT
                 (SELECT count(*) FROM denuncia WHERE estado = 'pendiente'),
                 (SELECT count(*) FROM denuncia WHERE estado = 'en_revision'),
                 (SELECT count(*) FROM denuncia
                   WHERE resuelta_en >= date_trunc('day', now())),
                 (SELECT count(*) FROM usuario
                   WHERE suspendido_en IS NOT NULL
                     AND (suspendido_hasta IS NULL OR suspendido_hasta > now())),
                 (SELECT count(*) FROM advertencia
                   WHERE revocada_en IS NULL AND (vence_en IS NULL OR vence_en > now())),
                 (SELECT count(*) FROM evento_seguridad
                   WHERE tipo = 'limite_excedido' AND creado_en >= date_trunc('day', now())),
                 (SELECT count(*) FROM canal WHERE estado = 'pendiente'),

                 -- Metricas de plataforma (§10). Van pegadas a las de
                 -- moderacion en la MISMA sentencia a proposito: son catorce
                 -- subconsultas independientes y Postgres las resuelve en un
                 -- viaje, mientras que catorce viajes de red desde el panel se
                 -- notan aunque cada consulta sea barata.

                 -- Total historico, sin descontar desactivadas: "registrados"
                 -- es cuanta gente entro alguna vez, no cuanta queda.
                 (SELECT count(*) FROM usuario),

                 -- Personas, no sesiones: quien tiene el telefono y el
                 -- portatil abiertos es un usuario activo, no dos. Y no se
                 -- filtra por revocada: alguien que uso la cuenta el martes y
                 -- cerro sesion el jueves estuvo activo igual, y descontarlo
                 -- haria que cerrar sesion pareciera abandonar la plataforma.
                 (SELECT count(DISTINCT d.usuario_id)
                    FROM sesion s JOIN dispositivo d ON d.id = s.dispositivo_id
                   WHERE s.ultimo_uso_en >= now() - interval '7 days'),

                 (SELECT count(*) FROM conversacion WHERE tipo = 'grupo'),
                 (SELECT count(*) FROM conversacion WHERE tipo = 'canal'),

                 -- Solo lo confirmado, igual que la vista `uso_almacenamiento`:
                 -- una reserva abandonada a mitad de subida no ocupa nada en el
                 -- almacen y contarla inflaria la factura que este numero
                 -- sirve para explicar.
                 --
                 -- Estas dos si se cuentan de verdad, al contrario que los
                 -- mensajes. Hay una fila por ARCHIVO, no por mensaje: son
                 -- ordenes de magnitud menos, y ademas `sum(bytes)` no tiene
                 -- estimacion honesta -`reltuples` estima filas, no lo que
                 -- suman-, de modo que aproximarlo seria exactamente inventar
                 -- el numero que este panel no se puede permitir inventar.
                 (SELECT count(*) FROM adjunto WHERE confirmado_en IS NOT NULL),
                 (SELECT coalesce(sum(bytes), 0) FROM adjunto WHERE confirmado_en IS NOT NULL)"""
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.primero {
                    listOf(
                        it.getLong(1), it.getLong(2), it.getLong(3),
                        it.getLong(4), it.getLong(5), it.getLong(6), it.getLong(7),
                        it.getLong(8), it.getLong(9), it.getLong(10), it.getLong(11),
                        it.getLong(12), it.getLong(13),
                    )
                }
            }
        } ?: List(13) { 0L }

        val (mensajes, mensajesAprox) = contarMensajes(c)

        // Por motivo, solo lo abierto: el reparto historico no ayuda a decidir
        // que revisar ahora.
        val porMotivo = c.prepareStatement(
            """SELECT motivo, count(*) FROM denuncia
               WHERE estado IN ('pendiente','en_revision')
               GROUP BY motivo ORDER BY count(*) DESC"""
        ).use { st ->
            st.executeQuery().use { rs -> rs.mapear { it.getString(1) to it.getInt(2) } }.toMap()
        }

        ResumenPanel(
            denunciasPendientes = n[0].toInt(),
            denunciasEnRevision = n[1].toInt(),
            denunciasResueltasHoy = n[2].toInt(),
            usuariosSuspendidos = n[3].toInt(),
            advertenciasVigentes = n[4].toInt(),
            limitesExcedidosHoy = n[5].toInt(),
            canalesPendientes = n[6].toInt(),
            porMotivo = porMotivo,

            usuariosRegistrados = n[7].toInt(),
            usuariosActivos7d = n[8].toInt(),
            gruposCreados = n[9].toInt(),
            canalesCreados = n[10].toInt(),
            almacenamientoArchivos = n[11].toInt(),
            almacenamientoBytes = n[12],
            mensajesEnviados = mensajes,
            mensajesAproximados = mensajesAprox,

            miNivel = Moderacion.nivel(c, yo.usuarioId),
        )
    }

    /**
     * Cuantos sobres pasaron por aqui, y si el numero es de verdad.
     *
     * Ojo con lo que cuenta: son filas de `mensaje_meta`, o sea **sobres, no
     * cartas**. El servidor no guarda el contenido -recibe bytes que no puede
     * abrir- asi que esto no es "mensajes que alguien pudo leer" ni se puede
     * convertir en eso. Se aclara aqui porque "mensajes enviados" en un panel
     * de administracion suena justo a lo contrario.
     *
     * ## Por que no siempre es un conteo
     *
     * `mensaje_meta` es la tabla que mas crece de toda la plataforma: una sola
     * persona activa le mete miles de filas al mes. Un `count(*)` ahi no tiene
     * atajo -Postgres no mantiene un contador y el indice no le sirve por la
     * visibilidad de MVCC-, asi que es un recorrido completo que con millones
     * de filas deja el panel colgado varios segundos cada vez que alguien lo
     * abre.
     *
     * Por encima de [UMBRAL_CONTEO_EXACTO] se usa la estimacion que el
     * planificador ya mantiene (`pg_class.reltuples`, que ANALYZE refresca):
     * cuesta una lectura de catalogo y se equivoca en un porcentaje pequeno.
     * A esa escala la diferencia entre 12.400.000 y 12.431.208 no cambia
     * ninguna decision; entre 0 y 12 millones, si. Pero el resumen viaja con
     * `mensajesAproximados` y la pantalla lo dice: un numero estimado que se
     * presenta como exacto es peor que no dar ninguno.
     *
     * `reltuples` vale -1 en una tabla que nunca paso por ANALYZE (una base
     * recien creada, por ejemplo). Ese caso cae solo del lado del conteo
     * exacto, que es lo correcto: si nadie analizo la tabla, es que es nueva.
     */
    private fun contarMensajes(c: java.sql.Connection): Pair<Long, Boolean> {
        val estimado = c.prepareStatement(
            "SELECT reltuples::bigint FROM pg_class WHERE oid = 'mensaje_meta'::regclass"
        ).use { st ->
            st.executeQuery().use { rs -> rs.primero { it.getLong(1) } }
        } ?: -1L

        if (estimado >= UMBRAL_CONTEO_EXACTO) return estimado to true

        val exacto = c.prepareStatement("SELECT count(*) FROM mensaje_meta").use { st ->
            st.executeQuery().use { rs -> rs.primero { it.getLong(1) } }
        } ?: 0L
        return exacto to false
    }

    /**
     * Buscar personas, o —con la caja vacia— ver los ultimos registros.
     *
     * Tres modos, segun lo que se escriba:
     *
     *  - **Vacio**: los ULTIMOS 10 que se registraron, del mas nuevo al mas
     *    viejo. Es lo primero que quiere ver quien administra —quien acaba de
     *    entrar— sin tener que adivinar un nombre. Es una vista acotada y fija,
     *    no un volcado: por eso no reabre el agujero que cerraba la regla de
     *    las dos letras.
     *  - **Una letra**: nada. Un prefijo de un caracter SI seria el listado
     *    completo de la plataforma disfrazado de busqueda, y eso es lo que no
     *    se quiere. La caja vacia da 10; una letra, cero; con dos ya se busca.
     *  - **Dos o mas**: busqueda por prefijo, ordenada por nombre.
     */
    fun usuarios(yo: Auth, consulta: String, limite: Int = 50): List<UsuarioPanel> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId)
        val q = consulta.trim().removePrefix("@").lowercase()
        // Exactamente un caracter: ni lista ni busca. Ver el porque arriba.
        if (q.length == 1) return@query emptyList()
        val buscando = q.length >= 2

        c.prepareStatement(
            """SELECT u.username,
                      (EXTRACT(EPOCH FROM u.creado_en) * 1000)::bigint,
                      u.staff_nivel,
                      u.suspendido_en IS NOT NULL
                        AND (u.suspendido_hasta IS NULL OR u.suspendido_hasta > now()),
                      (EXTRACT(EPOCH FROM u.suspendido_hasta) * 1000)::bigint,
                      u.suspendido_motivo,
                      (SELECT count(*) FROM advertencia a
                        WHERE a.usuario_id = u.id AND a.revocada_en IS NULL
                          AND (a.vence_en IS NULL OR a.vence_en > now())),
                      (SELECT count(*) FROM denuncia d WHERE d.objetivo_usuario_id = u.id),
                      (SELECT count(*) FROM denuncia d WHERE d.denunciante_id = u.id)
               FROM usuario u
               ${if (buscando) "WHERE u.username LIKE ? || '%'" else ""}
               ORDER BY ${if (buscando) "u.username" else "u.creado_en DESC"}
               LIMIT ?"""
        ).use { st ->
            // Los indices cambian con el modo: buscando, el LIKE es el 1 y el
            // limite el 2; en la vista de recientes no hay LIKE y el limite es
            // el 1. La vista de recientes va fija a 10, lo que pidio quien la usa.
            var i = 1
            if (buscando) st.setString(i++, q)
            st.setInt(i, if (buscando) limite.coerceIn(1, 100) else 10)
            st.executeQuery().use { rs ->
                rs.mapear {
                    val suspendido = it.getBoolean(4)
                    UsuarioPanel(
                        username = it.getString(1),
                        creadoEn = it.getLong(2),
                        staffNivel = it.getInt(3),
                        suspendido = suspendido,
                        suspendidoHasta = it.getLong(5).takeIf { v -> v > 0 && suspendido },
                        suspensionMotivo = it.getString(6)?.takeIf { suspendido },
                        advertenciasVigentes = it.getInt(7),
                        denunciasRecibidas = it.getInt(8),
                        denunciasHechas = it.getInt(9),
                    )
                }
            }
        }
    }

    /** Suspender a mano. Solo administrador: es lo que no se deshace del todo. */
    fun suspender(yo: Auth, username: String, req: SuspenderReq): UsuarioPanel {
        Db.tx { c ->
            Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
            val objetivo = Moderacion.idDeUsername(c, username)
            if (objetivo == yo.usuarioId) throw ErrorNegocio(400, "No puedes suspenderte a ti mismo.")
            if (Moderacion.nivel(c, objetivo) >= Moderacion.nivel(c, yo.usuarioId)) {
                throw ErrorNegocio(403, "No puedes suspender a alguien de tu mismo nivel o superior.")
            }
            if (req.motivo.isBlank()) {
                // Sin motivo no hay nada que explicarle al suspendido, y una
                // sancion que no se explica no corrige nada.
                throw ErrorNegocio(400, "Una suspension necesita un motivo.")
            }

            Moderacion.suspender(
                c, objetivo, yo.usuarioId, req.motivo,
                req.horas?.let { Duration.ofHours(it.toLong()) },
            )
            Autz.auditar(
                c, yo.usuarioId, "usuario.suspender", "usuario", objetivo,
                objetivoId = objetivo,
                detalle = """{"horas":${req.horas ?: "null"}}""",
            )
        }
        // Se lee DESPUES de confirmar: `uno` abre su propia conexion y una
        // conexion nueva no ve lo que otra transaccion no confirmo todavia.
        // Leerlo dentro devolvia el estado anterior a la suspension.
        return uno(yo, username)
    }

    fun restaurar(yo: Auth, username: String): UsuarioPanel {
        Db.tx { c ->
            Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
            val objetivo = Moderacion.idDeUsername(c, username)
            c.prepareStatement(
                """UPDATE usuario
                   SET suspendido_en = NULL, suspendido_hasta = NULL,
                       suspendido_motivo = NULL, suspendido_por = NULL
                   WHERE id = ?"""
            ).use { st -> st.setObject(1, objetivo); st.executeUpdate() }

            Seguridad.anotar(c, objetivo, "cuenta_restaurada")
            Autz.auditar(c, yo.usuarioId, "usuario.restaurar", "usuario", objetivo, objetivoId = objetivo)
        }
        return uno(yo, username)
    }

    /**
     * Nombrar o degradar staff.
     *
     * Solo administrador, y nunca a un nivel igual o mayor al propio. Sin esa
     * segunda regla, el primer administrador puede fabricar propietarios y el
     * nivel 100 deja de significar algo.
     */
    fun staff(yo: Auth, username: String, req: StaffReq): UsuarioPanel {
        Db.tx { c ->
            Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
            if (req.nivel !in listOf(
                    0, Moderacion.MODERADOR, Moderacion.ADMINISTRADOR, Moderacion.PROPIETARIO,
                )
            ) throw ErrorNegocio(400, "Nivel invalido. Es 0, 50, 80 o 100.")

            val mio = Moderacion.nivel(c, yo.usuarioId)
            if (req.nivel >= mio) throw ErrorNegocio(403, "No puedes dar un nivel igual o mayor al tuyo.")

            val objetivo = Moderacion.idDeUsername(c, username)
            if (objetivo == yo.usuarioId) throw ErrorNegocio(400, "No puedes cambiar tu propio nivel.")
            if (Moderacion.nivel(c, objetivo) >= mio) {
                throw ErrorNegocio(403, "No puedes tocar a alguien de tu mismo nivel o superior.")
            }

            c.prepareStatement("UPDATE usuario SET staff_nivel = ? WHERE id = ?").use { st ->
                st.setInt(1, req.nivel); st.setObject(2, objetivo); st.executeUpdate()
            }
            Seguridad.anotar(
                c, objetivo,
                if (req.nivel > 0) "staff_otorgado" else "staff_retirado",
                detalle = """{"nivel":${req.nivel}}""",
            )
            Autz.auditar(
                c, yo.usuarioId, "usuario.staff", "usuario", objetivo,
                objetivoId = objetivo, detalle = """{"nivel":${req.nivel}}""",
            )
        }
        return uno(yo, username)
    }

    /** Una persona concreta. Reusa la busqueda para no repetir la consulta. */
    fun uno(yo: Auth, username: String): UsuarioPanel {
        val u = username.trim().removePrefix("@").lowercase()
        return usuarios(yo, u, 5).firstOrNull { it.username.equals(u, true) }
            ?: throw ErrorNegocio(404, "No existe el usuario @$u.")
    }

    /**
     * Semilla del primer propietario.
     *
     * Hay un problema de arranque real: solo un administrador puede nombrar
     * staff, y al principio no hay ninguno. Se resuelve por variable de entorno
     * y una sola vez, al levantar: `WTFUCK_PROPIETARIO=joaquin`.
     *
     * No es una ruta a proposito. Una ruta de "hazme administrador" protegida
     * por un secreto es la clase de cosa que termina abierta en produccion.
     */
    fun sembrarPropietario(username: String?) {
        val u = username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        Db.tx { c ->
            val filas = c.prepareStatement(
                "UPDATE usuario SET staff_nivel = ? WHERE username = ? AND staff_nivel < ?"
            ).use { st ->
                st.setInt(1, Moderacion.PROPIETARIO)
                st.setString(2, u)
                st.setInt(3, Moderacion.PROPIETARIO)
                st.executeUpdate()
            }
            if (filas > 0) {
                Seguridad.anotar(
                    c, null, "staff_otorgado",
                    detalle = """{"username":"$u","nivel":100,"via":"WTFUCK_PROPIETARIO"}""",
                )
            }
        }
    }

    /** Ultimos eventos de seguridad de una cuenta. Solo staff. */
    fun eventosDe(yo: Auth, username: String, limite: Int = 50): List<EventoSeguridad> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId)
        val objetivo = Moderacion.idDeUsername(c, username)
        Autz.auditar(c, yo.usuarioId, "usuario.eventos", "usuario", objetivo, objetivoId = objetivo)
        Seguridad.mios(c, objetivo, limite)
    }

    // ==================================================================
    //  H.6 · Limites ajustables
    // ==================================================================

    /**
     * Exige **administrador**, no moderador.
     *
     * Un moderador decide sobre personas y contenidos; los limites de abuso
     * son infraestructura de toda la plataforma y relajarlos es una decision
     * de seguridad con consecuencias que no se ven en una denuncia. Por eso
     * esta un nivel mas arriba.
     */
    fun limites(yo: Auth): List<LimiteAjustable> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)

        val filas = c.prepareStatement(
            """SELECT l.clave, l.tope, l.ventana_s, coalesce(u.username, ''),
                      (EXTRACT(EPOCH FROM l.actualizado_en) * 1000)::bigint
               FROM limite_config l LEFT JOIN usuario u ON u.id = l.actualizado_por"""
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.mapear {
                    it.getString(1) to Triple(it.getInt(2), it.getInt(3), it.getString(4) to it.getLong(5))
                }
            }.toMap()
        }

        Limitador.AJUSTABLES.map { a ->
            val def = Limitador.porDefecto(a.clave) ?: a.leer()
            val fila = filas[a.clave]
            LimiteAjustable(
                clave = a.clave,
                etiqueta = a.etiqueta,
                detalle = a.detalle,
                tope = fila?.first ?: def.cuantas,
                ventanaSegundos = fila?.second ?: def.ventana.seconds.toInt(),
                topeDefecto = def.cuantas,
                ventanaDefectoSegundos = def.ventana.seconds.toInt(),
                esDefecto = fila == null,
                actualizadoPor = fila?.third?.first?.takeIf { it.isNotBlank() },
                actualizadoEn = fila?.third?.second ?: 0,
            )
        }
    }

    fun ajustarLimite(yo: Auth, clave: String, req: AjustarLimiteReq): LimiteAjustable {
        val r = ajustarEnBase(yo, clave, req)
        // La cache se invalida DESPUES del commit, no dentro de la
        // transaccion: invalidar antes deja que otro hilo refresque leyendo el
        // estado viejo y lo cachee, con lo que el cambio no surtiria efecto
        // durante los siguientes 30 segundos.
        Limitador.invalidarCache()
        return r
    }

    private fun ajustarEnBase(yo: Auth, clave: String, req: AjustarLimiteReq): LimiteAjustable = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        val a = Limitador.AJUSTABLES.firstOrNull { it.clave == clave }
            ?: throw ErrorNegocio(404, "Ese limite no existe.")

        // Los rangos los valida tambien la base con CHECKs. Aqui se validan
        // para poder decir QUE esta mal: un 400 con motivo sirve, un error de
        // restriccion de Postgres no.
        if (req.tope !in 1..1_000_000) {
            throw ErrorNegocio(400, "El tope tiene que estar entre 1 y 1000000.")
        }
        if (req.ventanaSegundos !in 1..86_400) {
            throw ErrorNegocio(400, "La ventana tiene que estar entre 1 segundo y 24 horas.")
        }

        c.prepareStatement(
            """INSERT INTO limite_config (clave, tope, ventana_s, actualizado_por, actualizado_en)
               VALUES (?, ?, ?, ?, now())
               ON CONFLICT (clave) DO UPDATE
                 SET tope = excluded.tope, ventana_s = excluded.ventana_s,
                     actualizado_por = excluded.actualizado_por, actualizado_en = now()"""
        ).use { st ->
            st.setString(1, clave)
            st.setInt(2, req.tope)
            st.setInt(3, req.ventanaSegundos)
            st.setObject(4, yo.usuarioId)
            st.executeUpdate()
        }

        // Relajar un limite es una decision de seguridad: queda en la bitacora
        // con nombre y fecha, como cualquier sancion.
        Autz.auditar(
            c, yo.usuarioId, "limite.ajustado", "limite", null,
            detalle = """{"clave":"$clave","tope":${req.tope},"ventana_s":${req.ventanaSegundos}}""",
        )

        val def = Limitador.porDefecto(clave) ?: a.leer()
        LimiteAjustable(
            clave = clave,
            etiqueta = a.etiqueta,
            detalle = a.detalle,
            tope = req.tope,
            ventanaSegundos = req.ventanaSegundos,
            topeDefecto = def.cuantas,
            ventanaDefectoSegundos = def.ventana.seconds.toInt(),
            esDefecto = false,
            actualizadoPor = yo.username,
            actualizadoEn = System.currentTimeMillis(),
        )
    }

    /** Vuelve al valor de fabrica borrando la fila. */
    fun restaurarLimite(yo: Auth, clave: String) {
        restaurarEnBase(yo, clave)
        Limitador.invalidarCache()
    }

    private fun restaurarEnBase(yo: Auth, clave: String) = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        if (Limitador.AJUSTABLES.none { it.clave == clave }) {
            throw ErrorNegocio(404, "Ese limite no existe.")
        }
        c.prepareStatement("DELETE FROM limite_config WHERE clave = ?").use { st ->
            st.setString(1, clave); st.executeUpdate()
        }
        Autz.auditar(
            c, yo.usuarioId, "limite.restaurado", "limite", null,
            detalle = """{"clave":"$clave"}""",
        )
    }

    // ==================================================================
    //  H.6 · La bitacora
    // ==================================================================

    /**
     * Quien hizo que.
     *
     * Existia desde el modulo A -`Autz.auditar` escribe en cada accion con
     * consecuencias- y no habia forma de LEERLA sin entrar a la base. Una
     * bitacora que nadie puede leer no disuade a nadie ni resuelve ninguna
     * discusion, que son sus dos unicas funciones.
     *
     * Exige **administrador**: la bitacora dice lo que hizo cada moderador, y
     * la vigilancia entre pares del mismo nivel es una forma rapida de que un
     * equipo deje de escribir cosas.
     */
    fun bitacora(yo: Auth, limite: Int, filtro: String?): List<LineaBitacora> = Db.query { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        val q = filtro?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        c.prepareStatement(
            """SELECT a.id, coalesce(ac.username, ''), a.accion, a.recurso_tipo, a.recurso_id,
                      ob.username, a.detalle::text,
                      (EXTRACT(EPOCH FROM a.creado_en) * 1000)::bigint
               FROM auditoria a
                 LEFT JOIN usuario ac ON ac.id = a.actor_id
                 LEFT JOIN usuario ob ON ob.id = a.objetivo_id
               WHERE (?::text IS NULL
                      OR lower(a.accion) LIKE '%' || ?::text || '%'
                      OR lower(coalesce(ac.username,'')) LIKE '%' || ?::text || '%'
                      OR lower(coalesce(ob.username,'')) LIKE '%' || ?::text || '%')
               ORDER BY a.id DESC
               LIMIT ?"""
        ).use { st ->
            st.setString(1, q); st.setString(2, q); st.setString(3, q); st.setString(4, q)
            st.setInt(5, limite.coerceIn(1, 300))
            st.executeQuery().use { rs ->
                rs.mapear {
                    LineaBitacora(
                        id = it.getObject(1, UUID::class.java).toString(),
                        actor = it.getString(2),
                        accion = it.getString(3),
                        recursoTipo = it.getString(4),
                        recursoId = it.getObject(5, UUID::class.java)?.toString(),
                        objetivo = it.getString(6),
                        detalle = it.getString(7),
                        creadoEn = it.getLong(8),
                    )
                }
            }
        }
    }


    // ==================================================================
    //  H.3 · Gobierno de grupos y canales
    // ==================================================================

    /**
     * Los grupos y canales de la plataforma, con lo que hace falta para
     * decidir: cuanta gente hay dentro, cuanto se habla y cuantas denuncias
     * acumula.
     *
     * Sin buscador esto seria la lista completa de la plataforma, que no cabe
     * en una pantalla y no ayuda a nada. Se exige texto por el mismo motivo
     * que en la busqueda de personas.
     */
    fun conversaciones(yo: Auth, q: String?, soloCerradas: Boolean): List<ConversacionPanel> =
        Db.query { c ->
            Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
            val texto = q?.trim()?.lowercase()?.takeIf { it.length >= 2 }
            c.prepareStatement(
                """SELECT v.id, v.tipo, coalesce(v.nombre, ''), coalesce(u.username, ''),
                          (SELECT count(*) FROM participante p
                            WHERE p.conversacion_id = v.id AND p.salido_en IS NULL),
                          (SELECT count(*) FROM mensaje_meta m
                            WHERE m.conversacion_id = v.id AND m.retirado_en IS NULL),
                          (SELECT count(*) FROM denuncia d
                            WHERE d.objetivo_conversacion_id = v.id),
                          (EXTRACT(EPOCH FROM v.creada_en) * 1000)::bigint,
                          v.cerrada_en IS NOT NULL, v.cierre_motivo,
                          cp.username
                   FROM conversacion v
                     LEFT JOIN usuario u  ON u.id = v.creador_id
                     LEFT JOIN usuario cp ON cp.id = v.cerrada_por
                   WHERE v.tipo IN ('grupo', 'canal')
                     AND (?::boolean = false OR v.cerrada_en IS NOT NULL)
                     AND (?::text IS NULL OR lower(coalesce(v.nombre,'')) LIKE '%' || ?::text || '%')
                   ORDER BY 7 DESC, 5 DESC
                   LIMIT 60"""
            ).use { st ->
                st.setBoolean(1, soloCerradas)
                st.setString(2, texto); st.setString(3, texto)
                st.executeQuery().use { rs ->
                    rs.mapear {
                        ConversacionPanel(
                            id = it.getObject(1, UUID::class.java).toString(),
                            tipo = it.getString(2),
                            nombre = it.getString(3).ifBlank {
                                if (it.getString(2) == "canal") "Canal" else "Grupo"
                            },
                            creador = it.getString(4),
                            miembros = it.getInt(5),
                            mensajes = it.getInt(6),
                            denuncias = it.getInt(7),
                            creadoEn = it.getLong(8),
                            cerrada = it.getBoolean(9),
                            cierreMotivo = it.getString(10),
                            cerradaPor = it.getString(11),
                        )
                    }
                }
            }
        }

    /**
     * Cierra una conversacion: nadie escribe mas.
     *
     * Lo que NO hace, y hay que decirlo en la pantalla: no borra lo que ya se
     * entrego. Esos mensajes estan cifrados en aparatos ajenos y el servidor no
     * los tiene ni podria leerlos.
     */
    fun cerrarConversacion(
        yo: Auth,
        convId: UUID,
        req: CerrarConversacionReq,
    ): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        val motivo = req.motivo.trim().take(500)
        if (motivo.isEmpty()) {
            throw ErrorNegocio(400, "Cerrar una conversacion necesita un motivo: sus miembros tienen que saber que paso.")
        }

        val tipo = c.prepareStatement(
            "SELECT tipo FROM conversacion WHERE id = ? AND cerrada_en IS NULL"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(409, "Esa conversacion no existe o ya estaba cerrada.")

        if (tipo == "directa") {
            // Una directa no se cierra desde el panel: eso seria decidir que
            // dos personas no pueden hablar entre si, y para eso existe la
            // suspension de una cuenta, que al menos tiene nombre y plazo.
            throw ErrorNegocio(400, "Una conversacion directa no se cierra: se suspende la cuenta.")
        }

        c.prepareStatement(
            """UPDATE conversacion
               SET cerrada_en = now(), cerrada_por = ?, cierre_motivo = ?
               WHERE id = ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setString(2, motivo); st.setObject(3, convId)
            st.executeUpdate()
        }

        Autz.auditar(
            c, yo.usuarioId, "conversacion.cerrada", "conversacion", convId,
            detalle = """{"tipo":"$tipo"}""",
        )

        val miembros = c.prepareStatement(
            "SELECT usuario_id FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        Eventos.emitir(c, miembros, "conversacion_cerrada", convId, yo.username, motivo)
    }

    fun reabrirConversacion(yo: Auth, convId: UUID): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, Moderacion.ADMINISTRADOR)
        val n = c.prepareStatement(
            """UPDATE conversacion
               SET cerrada_en = NULL, cerrada_por = NULL, cierre_motivo = NULL
               WHERE id = ? AND cerrada_en IS NOT NULL"""
        ).use { st -> st.setObject(1, convId); st.executeUpdate() }
        if (n == 0) throw ErrorNegocio(409, "Esa conversacion no estaba cerrada.")

        Autz.auditar(c, yo.usuarioId, "conversacion.reabierta", "conversacion", convId)

        val miembros = c.prepareStatement(
            "SELECT usuario_id FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        Eventos.emitir(c, miembros, "conversacion_reabierta", convId, yo.username, null)
    }

}
