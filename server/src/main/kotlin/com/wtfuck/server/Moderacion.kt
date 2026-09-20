package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Modulo G: moderacion.
 *
 * ## El problema de fondo
 *
 * El servidor no puede moderar lo que no puede leer. Todo lo demas de este
 * archivo es consecuencia de eso.
 *
 * Una denuncia sobre un mensaje le llega al moderador como un uuid y unos
 * bytes opacos. No hay nada que revisar. Las salidas posibles eran tres:
 *
 *  1. No moderar contenido (lo que hace Signal). Honesto, y deja a la victima
 *     de acoso sin nada mas que bloquear.
 *  2. Descifrar en el servidor. Rompe el producto entero.
 *  3. Que el texto lo entregue quien denuncia. Su telefono ya lo descifro y es
 *     el unico que puede.
 *
 * Se eligio la 3, que es lo que hace WhatsApp. La regla que la vuelve
 * defendible es que el usuario lo SEPA antes de denunciar: entregar el texto es
 * parte de denunciar, no un efecto secundario. Ver `V12__moderacion.sql`.
 *
 * ## Dos ambitos de permiso
 *
 * Hasta el modulo F todos los permisos eran por conversacion. Una denuncia
 * sobre una persona no pertenece a ninguna, asi que no hay a quien pedirle
 * permiso: de ahi el nivel de plataforma. Los numeros son los mismos que la
 * jerarquia de roles (50/80/100) para no tener dos escalas que signifiquen lo
 * mismo, pero los ambitos no se mezclan nunca.
 */
object Moderacion {

    const val MODERADOR = 50
    const val ADMINISTRADOR = 80
    const val PROPIETARIO = 100

    /**
     * Cuantas advertencias vivas disparan la suspension automatica.
     *
     * Tres y no una: una advertencia es para corregir, y un sistema que sanciona
     * en la primera no advierte, castiga en dos pasos. Tres tampoco es
     * arbitrario respecto de lo demas: las advertencias caducan a los 90 dias,
     * asi que hacen falta tres en tres meses.
     */
    const val TOPE_ADVERTENCIAS = 3
    val VIDA_ADVERTENCIA: Duration = Duration.ofDays(90)

    /**
     * Cuanto dura la suspension que dispara la acumulacion.
     *
     * Temporal y no permanente a proposito: la acumulacion la decide un contador,
     * no una persona, y un contador no deberia poder expulsar a nadie para
     * siempre. Lo permanente lo firma un administrador.
     */
    val SUSPENSION_AUTOMATICA: Duration = Duration.ofHours(72)

    // ============================================================
    //  Nivel de plataforma
    // ============================================================

    fun nivel(c: Connection, usuarioId: UUID): Int =
        c.prepareStatement("SELECT staff_nivel FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }

    fun exigirStaff(c: Connection, usuarioId: UUID, minimo: Int = MODERADOR) {
        val n = nivel(c, usuarioId)
        if (n < minimo) {
            // 404 y no 403 a proposito: quien no es staff no deberia poder
            // deducir que existe una cola de moderacion probando la ruta.
            throw ErrorNegocio(404, "No se encontro.")
        }
    }

    /**
     * Sancionar a alguien exige estar estrictamente por encima.
     *
     * Es la misma regla que en los grupos y por el mismo motivo: sin ella, dos
     * moderadores pueden suspenderse mutuamente, y el primero en apretar gana.
     */
    private fun exigirPorEncima(c: Connection, actorId: UUID, objetivoId: UUID) {
        if (actorId == objetivoId) throw ErrorNegocio(400, "No puedes sancionarte a ti mismo.")
        val mio = nivel(c, actorId)
        val suyo = nivel(c, objetivoId)
        if (suyo >= mio) {
            throw ErrorNegocio(403, "No puedes sancionar a alguien de tu mismo nivel o superior.")
        }
    }

    // ============================================================
    //  G.1 · Denunciar
    // ============================================================

    fun denunciar(yo: Auth, req: DenunciaReq): Pair<DenunciaCreada, List<Pair<UUID, Bajada.Evento>>> =
        Db.tx { c ->
            if (req.tipo !in listOf(
                    TipoDenuncia.USUARIO, TipoDenuncia.MENSAJE,
                    TipoDenuncia.GRUPO, TipoDenuncia.CANAL,
                )
            ) throw ErrorNegocio(400, "Tipo de denuncia desconocido.")

            if (req.motivo !in MotivoDenuncia.TODOS) throw ErrorNegocio(400, "Motivo desconocido.")

            // El cupo diario protege la COLA, no al denunciado: sin el, una
            // persona abre cincuenta denuncias en una tarde y nadie revisa
            // ninguna. Va en la base porque un reinicio no debe perdonarlo.
            Cupos.exigir(c, yo.usuarioId, "denuncia", Cupos.DENUNCIAS_POR_DIA, Duration.ofDays(1))

            var objUsuario: UUID? = null
            var objConversacion: UUID? = null
            var objMensaje: UUID? = null

            when (req.tipo) {
                TipoDenuncia.USUARIO -> {
                    objUsuario = idDeUsername(c, req.objetivoUsuario)
                    if (objUsuario == yo.usuarioId) {
                        throw ErrorNegocio(400, "No puedes denunciarte a ti mismo.")
                    }
                }

                TipoDenuncia.MENSAJE -> {
                    val m = uuid(req.objetivoMensaje, "mensaje")
                    // Se exige pertenecer a la conversacion del mensaje. Sin
                    // esto, cualquiera con un uuid podria denunciar mensajes de
                    // conversaciones ajenas, y de paso confirmar que existen.
                    val fila = c.prepareStatement(
                        """SELECT m.conversacion_id, m.autor_id
                           FROM mensaje_meta m
                             JOIN participante p ON p.conversacion_id = m.conversacion_id
                                                AND p.usuario_id = ? AND p.salido_en IS NULL
                           WHERE m.id = ?"""
                    ).use { st ->
                        st.setObject(1, yo.usuarioId); st.setObject(2, m)
                        st.executeQuery().use { rs ->
                            rs.primero {
                                it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java)
                            }
                        }
                    } ?: throw ErrorNegocio(404, "Ese mensaje no existe.")

                    if (fila.second == yo.usuarioId) {
                        throw ErrorNegocio(400, "Ese mensaje es tuyo.")
                    }
                    objMensaje = m
                    objConversacion = fila.first
                    objUsuario = fila.second
                }

                TipoDenuncia.GRUPO, TipoDenuncia.CANAL -> {
                    objConversacion = uuid(req.objetivoConversacion, "conversacion")
                    val existe = c.prepareStatement(
                        "SELECT 1 FROM conversacion WHERE id = ? AND tipo <> 'directa'"
                    ).use { st ->
                        st.setObject(1, objConversacion); st.executeQuery().use { it.next() }
                    }
                    if (!existe) throw ErrorNegocio(404, "Esa conversacion no existe.")
                }
            }

            val id = c.prepareStatement(
                """INSERT INTO denuncia
                     (denunciante_id, tipo, objetivo_usuario_id, objetivo_conversacion_id,
                      objetivo_mensaje_id, motivo, detalle)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   RETURNING id"""
            ).use { st ->
                st.setObject(1, yo.usuarioId)
                st.setString(2, req.tipo)
                st.setObject(3, objUsuario)
                st.setObject(4, objConversacion)
                st.setObject(5, objMensaje)
                st.setString(6, req.motivo)
                st.setString(7, req.detalle.trim().take(2000).ifBlank { null })
                runCatching { st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) } }
                    .getOrElse {
                        // Lo atrapa el indice unico. Repetir la denuncia no es un
                        // error del usuario: ya la mando.
                        throw ErrorNegocio(409, "Ya denunciaste esto. Esta en revision.")
                    }
            }

            guardarEvidencia(c, id, req.evidencia)

            Seguridad.anotar(
                c, yo.usuarioId, "denuncia_creada",
                detalle = """{"tipo":"${req.tipo}","motivo":"${req.motivo}"}""",
            )
            Autz.auditar(
                c, yo.usuarioId, "denuncia.crear", "denuncia", id,
                objetivoId = objUsuario, detalle = """{"motivo":"${req.motivo}"}""",
            )

            DenunciaCreada(id.toString(), "pendiente") to emptyList()
        }

    /**
     * Guarda el contexto en claro que entrego el denunciante.
     *
     * Se recorta a 20 mensajes. El limite no es de rendimiento: una denuncia
     * con la conversacion entera deja de ser una prueba y se vuelve una
     * filtracion, y un moderador con mil mensajes delante no lee ninguno.
     */
    private fun guardarEvidencia(c: Connection, denunciaId: UUID, evidencia: List<Evidencia>) {
        if (evidencia.isEmpty()) return
        c.prepareStatement(
            """INSERT INTO denuncia_evidencia
                 (denuncia_id, orden, autor_username, enviado_en, contenido)
               VALUES (?, ?, ?, ?, ?)"""
        ).use { st ->
            evidencia.take(DenunciaReq.MAX_EVIDENCIA).forEachIndexed { i, e ->
                st.setObject(1, denunciaId)
                st.setInt(2, i)
                st.setString(3, e.autor.lowercase().trim())
                st.setObject(4, Timestamp(e.enviadoEn))
                st.setString(5, e.contenido.take(8192))
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    // ============================================================
    //  G.2 · La cola de revision
    // ============================================================

    /**
     * La cola de revision, de lo mas viejo a lo mas nuevo.
     *
     * ## Por que hay cursor y no numero de pagina
     *
     * `LIMIT ? OFFSET ?` sobre una cola VIVA se salta filas y repite otras: en
     * cuanto alguien resuelve una denuncia de la pagina 1, la que estaba
     * primera de la pagina 2 pasa a la 1 y nunca se ve. En una cola de
     * moderacion eso significa una denuncia que nadie mira jamas.
     *
     * El cursor es el **id de la ultima fila recibida**, y la fecha la resuelve
     * el servidor. No es un detalle de comodidad: la primera version mandaba
     * `(creadaEn, id)` desde el cliente y REPETIA FILAS.
     *
     * El motivo es que `creada_en` es `timestamptz` -microsegundos- mientras
     * que `creadaEn` viaja como milisegundos truncados. Una fila creada en
     * `…123456µs` se entrega como `…123ms`, y al devolverla como corte,
     * `(creada_en, id) > (…123000µs, id)` sigue siendo cierto PARA ESA MISMA
     * FILA. Con paginas de tres sobre la cola de desarrollo aparecieron 163
     * filas donde habia 139. Un cursor truncado no es un cursor.
     *
     * El orden es `(creada_en, id)` y no solo la fecha: dos denuncias del mismo
     * microsegundo son posibles -una rafaga sobre el mismo mensaje- y con la
     * fecha sola una de las dos se perderia. El id desempata y ordena estable.
     *
     * Un id que no existe corta la cola en vez de empezarla de nuevo: devolver
     * el principio ante un cursor que no se entiende es como se construye un
     * bucle infinito en el cliente. Las denuncias no se borran -pasan a
     * `resuelta`-, asi que un cursor valido no se evapora.
     */
    fun cola(
        yo: Auth,
        estado: String?,
        limite: Int,
        despuesId: UUID? = null,
    ): List<DenunciaEnCola> = Db.query { c ->
        exigirStaff(c, yo.usuarioId)

        val filtro = when (estado) {
            "pendiente", "en_revision", "resuelta", "descartada" -> "d.estado = ?"
            else -> "d.estado IN ('pendiente','en_revision')"
        }

        // La fecha sale de la propia fila, con toda su precision. Si el id no
        // existe la subconsulta da NULL, la comparacion da NULL y no vuelve
        // nada: la cola se termina, que es lo correcto ante un cursor invalido.
        val corte =
            if (despuesId != null) {
                "(d.creada_en, d.id) > ((SELECT creada_en FROM denuncia WHERE id = ?), ?)"
            } else {
                "true"
            }

        c.prepareStatement(
            """SELECT d.id, d.tipo, d.motivo, coalesce(d.detalle,''), d.estado,
                      den.username, obj.username, d.objetivo_conversacion_id,
                      cv.nombre, d.objetivo_mensaje_id,
                      (EXTRACT(EPOCH FROM d.creada_en) * 1000)::bigint,
                      rev.username,
                      -- Cuantas denuncias acumula el denunciado y cuantas
                      -- advertencias vivas tiene. Va en la LISTA y no en el
                      -- detalle porque es lo que separa un caso de un patron.
                      (SELECT count(*) FROM denuncia o
                        WHERE o.objetivo_usuario_id = d.objetivo_usuario_id
                          AND o.estado IN ('pendiente','en_revision')),
                      (SELECT count(*) FROM advertencia a
                        WHERE a.usuario_id = d.objetivo_usuario_id
                          AND a.revocada_en IS NULL
                          AND (a.vence_en IS NULL OR a.vence_en > now())),
                      (SELECT count(*) FROM denuncia_evidencia e WHERE e.denuncia_id = d.id)
               FROM denuncia d
                 JOIN usuario den ON den.id = d.denunciante_id
                 LEFT JOIN usuario obj ON obj.id = d.objetivo_usuario_id
                 LEFT JOIN usuario rev ON rev.id = d.revisor_id
                 LEFT JOIN conversacion cv ON cv.id = d.objetivo_conversacion_id
               WHERE $filtro AND $corte
               ORDER BY d.creada_en, d.id
               LIMIT ?"""
        ).use { st ->
            var i = 1
            if (filtro.contains("?")) st.setString(i++, estado)
            if (despuesId != null) {
                // El mismo id dos veces: una para resolver su fecha exacta y
                // otra para desempatar entre filas de la misma fecha.
                st.setObject(i++, despuesId)
                st.setObject(i++, despuesId)
            }
            st.setInt(i, limite.coerceIn(1, 200))
            st.executeQuery().use { rs -> rs.mapear { fila(it) } }
        }
    }

    private fun fila(rs: java.sql.ResultSet) = DenunciaEnCola(
        id = rs.getObject(1, UUID::class.java).toString(),
        tipo = rs.getString(2),
        motivo = rs.getString(3),
        detalle = rs.getString(4),
        estado = rs.getString(5),
        denunciante = rs.getString(6),
        objetivoUsuario = rs.getString(7),
        objetivoConversacion = rs.getObject(8, UUID::class.java)?.toString(),
        objetivoTitulo = rs.getString(9),
        objetivoMensaje = rs.getObject(10, UUID::class.java)?.toString(),
        creadaEn = rs.getLong(11),
        revisor = rs.getString(12),
        denunciasDelObjetivo = rs.getInt(13),
        advertenciasDelObjetivo = rs.getInt(14),
        evidencias = rs.getInt(15),
    )

    fun detalle(yo: Auth, denunciaId: UUID): DenunciaDetalle = Db.query { c ->
        exigirStaff(c, yo.usuarioId)

        val cabecera = c.prepareStatement(
            """SELECT d.id, d.tipo, d.motivo, coalesce(d.detalle,''), d.estado,
                      den.username, obj.username, d.objetivo_conversacion_id,
                      cv.nombre, d.objetivo_mensaje_id,
                      (EXTRACT(EPOCH FROM d.creada_en) * 1000)::bigint,
                      rev.username,
                      (SELECT count(*) FROM denuncia o
                        WHERE o.objetivo_usuario_id = d.objetivo_usuario_id
                          AND o.estado IN ('pendiente','en_revision')),
                      (SELECT count(*) FROM advertencia a
                        WHERE a.usuario_id = d.objetivo_usuario_id
                          AND a.revocada_en IS NULL
                          AND (a.vence_en IS NULL OR a.vence_en > now())),
                      (SELECT count(*) FROM denuncia_evidencia e WHERE e.denuncia_id = d.id)
               FROM denuncia d
                 JOIN usuario den ON den.id = d.denunciante_id
                 LEFT JOIN usuario obj ON obj.id = d.objetivo_usuario_id
                 LEFT JOIN usuario rev ON rev.id = d.revisor_id
                 LEFT JOIN conversacion cv ON cv.id = d.objetivo_conversacion_id
               WHERE d.id = ?"""
        ).use { st ->
            st.setObject(1, denunciaId)
            st.executeQuery().use { rs -> rs.primero { fila(it) } }
        } ?: throw ErrorNegocio(404, "Esa denuncia no existe.")

        val evidencia = c.prepareStatement(
            """SELECT autor_username, (EXTRACT(EPOCH FROM enviado_en) * 1000)::bigint, contenido
               FROM denuncia_evidencia WHERE denuncia_id = ? ORDER BY orden"""
        ).use { st ->
            st.setObject(1, denunciaId)
            st.executeQuery().use { rs ->
                rs.mapear { Evidencia(it.getString(1), it.getLong(2), it.getString(3)) }
            }
        }

        // Abrir una denuncia se audita. Que un moderador lea el contenido
        // entregado es exactamente el tipo de acceso que tiene que quedar
        // anotado: el poder de leer se controla registrando quien leyo.
        Autz.auditar(c, yo.usuarioId, "denuncia.leer", "denuncia", denunciaId)

        DenunciaDetalle(cabecera, evidencia)
    }

    /**
     * Tomar una denuncia: la reclama para un revisor.
     *
     * Existe para que dos moderadores no trabajen la misma denuncia sin saberlo.
     * El `WHERE estado = 'pendiente'` es lo que lo hace seguro: el que llega
     * segundo no actualiza ninguna fila y se lo dice.
     */
    fun tomar(yo: Auth, denunciaId: UUID): DenunciaEnCola {
        Db.tx { c ->
            exigirStaff(c, yo.usuarioId)
            val filas = c.prepareStatement(
                """UPDATE denuncia
                   SET estado = 'en_revision', revisor_id = ?, tomada_en = now()
                   WHERE id = ? AND estado = 'pendiente'"""
            ).use { st ->
                st.setObject(1, yo.usuarioId); st.setObject(2, denunciaId); st.executeUpdate()
            }
            if (filas == 0) throw ErrorNegocio(409, "Otra persona ya esta revisando esta denuncia.")
            Autz.auditar(c, yo.usuarioId, "denuncia.tomar", "denuncia", denunciaId)
        }
        return detalle(yo, denunciaId).cabecera
    }

    // ============================================================
    //  G.3 · Resolver: advertir, restringir, suspender
    // ============================================================

    fun resolver(
        yo: Auth,
        denunciaId: UUID,
        req: ResolverReq,
    ): Pair<ResolucionHecha, List<Pair<UUID, Bajada.Evento>>> = Db.tx { c ->
        exigirStaff(c, yo.usuarioId)
        if (req.accion !in AccionModeracion.TODAS) throw ErrorNegocio(400, "Accion desconocida.")

        val d = c.prepareStatement(
            """SELECT tipo, motivo, objetivo_usuario_id, objetivo_conversacion_id, estado
               FROM denuncia WHERE id = ? FOR UPDATE"""
        ).use { st ->
            st.setObject(1, denunciaId)
            st.executeQuery().use { rs ->
                rs.primero {
                    Denunciada(
                        tipo = it.getString(1),
                        motivo = it.getString(2),
                        objetivoUsuario = it.getObject(3, UUID::class.java),
                        objetivoConversacion = it.getObject(4, UUID::class.java),
                        estado = it.getString(5),
                    )
                }
            }
        } ?: throw ErrorNegocio(404, "Esa denuncia no existe.")

        if (d.estado in listOf("resuelta", "descartada")) {
            throw ErrorNegocio(409, "Esa denuncia ya estaba cerrada.")
        }

        val avisos = mutableListOf<Pair<UUID, Bajada.Evento>>()
        var advertenciasVigentes = 0
        var escalada: String? = null

        if (req.accion !in listOf(AccionModeracion.DESCARTAR, AccionModeracion.SIN_ACCION)) {
            val objetivo = d.objetivoUsuario
                ?: throw ErrorNegocio(400, "Esta denuncia no apunta a una persona: no hay a quien sancionar.")
            exigirPorEncima(c, yo.usuarioId, objetivo)

            when (req.accion) {
                AccionModeracion.ADVERTIR -> {
                    advertir(c, objetivo, yo, d, req.nota, denunciaId)
                    advertenciasVigentes = advertenciasVigentes(c, objetivo)
                    // La escalada la decide el contador, no el moderador.
                    if (advertenciasVigentes >= TOPE_ADVERTENCIAS) {
                        suspender(
                            c, objetivo, yo.usuarioId,
                            "Acumulo $advertenciasVigentes advertencias.",
                            SUSPENSION_AUTOMATICA,
                        )
                        escalada = "suspension_${SUSPENSION_AUTOMATICA.toHours()}h"
                    }
                    avisos += avisar(c, objetivo, "advertencia", yo.username, req.nota)
                }

                AccionModeracion.SILENCIAR, AccionModeracion.EXPULSAR -> {
                    val conv = d.objetivoConversacion ?: throw ErrorNegocio(
                        400,
                        "Silenciar y expulsar son por conversacion, y esta denuncia no apunta a ninguna.",
                    )
                    restringir(
                        c, conv, objetivo, yo.usuarioId,
                        if (req.accion == AccionModeracion.SILENCIAR) "silenciado" else "expulsado",
                        req.nota, req.horas,
                    )
                    advertenciasVigentes = advertenciasVigentes(c, objetivo)
                    avisos += avisar(c, objetivo, "sancion", yo.username, req.accion, conv)
                }

                AccionModeracion.SUSPENDER -> {
                    suspender(
                        c, objetivo, yo.usuarioId,
                        req.nota.ifBlank { MotivoDenuncia.etiqueta(d.motivo) },
                        req.horas?.let { Duration.ofHours(it.toLong()) },
                    )
                    advertenciasVigentes = advertenciasVigentes(c, objetivo)
                    avisos += avisar(c, objetivo, "sancion", yo.username, "suspension")
                }
            }
        }

        val estadoFinal = if (req.accion == AccionModeracion.DESCARTAR) "descartada" else "resuelta"
        c.prepareStatement(
            """UPDATE denuncia
               SET estado = ?, revisor_id = ?, resuelta_en = now(), resolucion = ?
               WHERE id = ?"""
        ).use { st ->
            st.setString(1, estadoFinal)
            st.setObject(2, yo.usuarioId)
            st.setString(3, "${req.accion}${if (req.nota.isBlank()) "" else ": ${req.nota.take(500)}"}")
            st.setObject(4, denunciaId)
            st.executeUpdate()
        }

        // La evidencia se borra al cerrar. Existia para decidir, no para
        // archivar: guardarla despues seria acumular texto en claro sin ningun
        // motivo que la justifique.
        purgarEvidencia(c, denunciaId)

        Autz.auditar(
            c, yo.usuarioId, "denuncia.resolver", "denuncia", denunciaId,
            objetivoId = d.objetivoUsuario,
            detalle = """{"accion":"${req.accion}","escalada":${escalada?.let { "\"$it\"" } ?: "null"}}""",
        )

        ResolucionHecha(
            denunciaId = denunciaId.toString(),
            accion = req.accion,
            advertenciasVigentes = advertenciasVigentes,
            escaladaAutomatica = escalada,
        ) to avisos
    }

    private data class Denunciada(
        val tipo: String,
        val motivo: String,
        val objetivoUsuario: UUID?,
        val objetivoConversacion: UUID?,
        val estado: String,
    )

    private fun purgarEvidencia(c: Connection, denunciaId: UUID) {
        c.prepareStatement("DELETE FROM denuncia_evidencia WHERE denuncia_id = ?").use { st ->
            st.setObject(1, denunciaId); st.executeUpdate()
        }
    }

    // ============================================================
    //  G.4 · Advertencias acumulativas
    // ============================================================

    private fun advertir(
        c: Connection,
        objetivo: UUID,
        yo: Auth,
        d: Denunciada,
        nota: String,
        denunciaId: UUID,
    ) {
        c.prepareStatement(
            """INSERT INTO advertencia
                 (usuario_id, emisor_id, denuncia_id, conversacion_id, motivo, detalle, vence_en)
               VALUES (?, ?, ?, ?, ?, ?, now() + (? || ' days')::interval)"""
        ).use { st ->
            st.setObject(1, objetivo)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, denunciaId)
            st.setObject(4, d.objetivoConversacion)
            st.setString(5, d.motivo)
            st.setString(6, nota.trim().take(1000).ifBlank { null })
            st.setString(7, VIDA_ADVERTENCIA.toDays().toString())
            st.executeUpdate()
        }
        Seguridad.anotar(c, objetivo, "advertencia_recibida", detalle = """{"motivo":"${d.motivo}"}""")
    }

    /** Vivas = ni revocadas ni vencidas. Es la cuenta que dispara la escalada. */
    fun advertenciasVigentes(c: Connection, usuarioId: UUID): Int =
        c.prepareStatement(
            """SELECT count(*) FROM advertencia
               WHERE usuario_id = ? AND revocada_en IS NULL
                 AND (vence_en IS NULL OR vence_en > now())"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }

    // ============================================================
    //  Sanciones
    // ============================================================

    private fun restringir(
        c: Connection,
        conversacionId: UUID,
        objetivo: UUID,
        actor: UUID,
        tipo: String,
        motivo: String,
        horas: Int?,
    ) {
        c.prepareStatement(
            """INSERT INTO restriccion (conversacion_id, usuario_id, tipo, motivo, hasta, aplicada_por)
               VALUES (?, ?, ?, ?, ?, ?)"""
        ).use { st ->
            st.setObject(1, conversacionId)
            st.setObject(2, objetivo)
            st.setString(3, tipo)
            st.setString(4, motivo.trim().take(500).ifBlank { null })
            st.setObject(5, horas?.let { Timestamp.from(Instant.now().plus(Duration.ofHours(it.toLong()))) })
            st.setObject(6, actor)
            st.executeUpdate()
        }
        // Expulsar por moderacion tambien lo saca de la conversacion: dejar la
        // restriccion sin sacarlo lo deja viendo un grupo donde no puede nada.
        if (tipo == "expulsado") {
            c.prepareStatement(
                """UPDATE participante SET salido_en = now()
                   WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"""
            ).use { st ->
                st.setObject(1, conversacionId); st.setObject(2, objetivo); st.executeUpdate()
            }
        }
        Seguridad.anotar(c, objetivo, "restriccion_aplicada", detalle = """{"tipo":"$tipo"}""")
    }

    /**
     * Suspende la cuenta entera.
     *
     * Una suspension NO corta la sesion, y eso es deliberado: si se cortara, la
     * persona veria la pantalla de ingreso sin ninguna explicacion y volveria a
     * intentar. Lo que hace es cortar todo lo que produce contenido (via
     * `Autz.puede`) y dejarla entrar a ver POR QUE, que es lo unico util que
     * puede hacer en ese momento.
     */
    fun suspender(c: Connection, objetivo: UUID, actor: UUID?, motivo: String, duracion: Duration?) {
        c.prepareStatement(
            """UPDATE usuario
               SET suspendido_en = now(),
                   suspendido_hasta = ?,
                   suspendido_motivo = ?,
                   suspendido_por = ?
               WHERE id = ?"""
        ).use { st ->
            st.setObject(1, duracion?.let { Timestamp.from(Instant.now().plus(it)) })
            st.setString(2, motivo.trim().take(500))
            st.setObject(3, actor)
            st.setObject(4, objetivo)
            st.executeUpdate()
        }
        Seguridad.anotar(
            c, objetivo, "cuenta_suspendida",
            detalle = """{"horas":${duracion?.toHours() ?: "null"}}""",
        )
    }

    // ============================================================
    //  Lo que ve el sancionado
    // ============================================================

    /**
     * Mi propio estado de moderacion.
     *
     * Existe porque una sancion que no se explica no corrige nada: el usuario
     * solo ve que dejo de poder escribir. Esta ruta es la unica que un
     * suspendido puede usar de verdad.
     */
    fun miEstado(yo: Auth): MiEstadoModeracion = Db.query { c ->
        val advertencias = c.prepareStatement(
            """SELECT id, motivo, coalesce(detalle,''),
                      (EXTRACT(EPOCH FROM creada_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM vence_en) * 1000)::bigint,
                      reconocida_en IS NOT NULL
               FROM advertencia
               WHERE usuario_id = ? AND revocada_en IS NULL
                 AND (vence_en IS NULL OR vence_en > now())
               ORDER BY creada_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    MiAdvertencia(
                        id = it.getObject(1, UUID::class.java).toString(),
                        motivo = it.getString(2),
                        detalle = it.getString(3),
                        creadaEn = it.getLong(4),
                        venceEn = it.getLong(5).takeIf { v -> v > 0 },
                        reconocida = it.getBoolean(6),
                    )
                }
            }
        }

        val susp = c.prepareStatement(
            """SELECT suspendido_en IS NOT NULL
                        AND (suspendido_hasta IS NULL OR suspendido_hasta > now()),
                      (EXTRACT(EPOCH FROM suspendido_hasta) * 1000)::bigint,
                      suspendido_motivo
               FROM usuario WHERE id = ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.primero { Triple(it.getBoolean(1), it.getLong(2), it.getString(3)) }
            }
        } ?: Triple(false, 0L, null)

        MiEstadoModeracion(
            advertenciasVigentes = advertencias.size,
            advertencias = advertencias,
            suspendido = susp.first,
            suspendidoHasta = susp.second.takeIf { it > 0 && susp.first },
            suspensionMotivo = susp.third?.takeIf { susp.first },
            topeAdvertencias = TOPE_ADVERTENCIAS,
        )
    }

    /** Marcar una advertencia como vista. No la borra: la deja constar como leida. */
    fun reconocer(yo: Auth, advertenciaId: UUID) = Db.tx { c ->
        c.prepareStatement(
            """UPDATE advertencia SET reconocida_en = now()
               WHERE id = ? AND usuario_id = ? AND reconocida_en IS NULL"""
        ).use { st ->
            st.setObject(1, advertenciaId); st.setObject(2, yo.usuarioId); st.executeUpdate()
        }
        Unit
    }

    // ============================================================
    //  Avisos al sancionado
    // ============================================================

    /**
     * Le avisa a la persona. Sin esto, una advertencia es una fila en una tabla
     * que el interesado nunca ve, y el punto de advertir es dar la oportunidad
     * de corregir.
     */
    private fun avisar(
        c: Connection,
        objetivo: UUID,
        tipo: String,
        actor: String,
        detalle: String,
        conversacionId: UUID? = null,
    ): List<Pair<UUID, Bajada.Evento>> =
        Eventos.emitir(c, listOf(objetivo), tipo, conversacionId, actor, detalle.take(500))

    // ============================================================
    //  Utilidades
    // ============================================================

    fun idDeUsername(c: Connection, username: String?): UUID {
        val u = username?.trim()?.removePrefix("@")?.lowercase()
            ?: throw ErrorNegocio(400, "Falta el usuario.")
        return c.prepareStatement("SELECT id FROM usuario WHERE username = ?").use { st ->
            st.setString(1, u)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: throw ErrorNegocio(404, "No existe el usuario @$u.")
    }

    private fun uuid(s: String?, que: String): UUID =
        runCatching { UUID.fromString(s) }
            .getOrElse { throw ErrorNegocio(400, "Identificador de $que invalido.") }
}
