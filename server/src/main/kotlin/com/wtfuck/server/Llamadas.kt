package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Modulo K: llamadas.
 *
 * ## Lo que este archivo NO hace, y es la parte importante
 *
 * No toca la señalizacion. El SDP y los candidatos ICE viajan **dentro de
 * sobres cifrados**, por el mismo camino que un mensaje, y el servidor no
 * puede leerlos. Ver la nota larga en `protocol/Llamadas.kt`: si el servidor
 * pudiera leer el SDP podria cambiar las huellas DTLS y escuchar la llamada,
 * con cada tramo perfectamente cifrado contra el.
 *
 * Lo que si hace este archivo son tres cosas que el servidor necesita saber de
 * todas formas:
 *
 *  1. **Autorizar.** Quien puede llamar a quien.
 *  2. **Llevar el estado y el historial.** Metadatos: quien, cuando, como
 *     termino. Ni un SDP en la base.
 *  3. **Repartir credenciales de TURN**, que es lo unico que no puede vivir en
 *     el cliente porque lleva un secreto.
 *
 * ## Una llamada vive en una conversacion
 *
 * No hay un sistema de permisos paralelo: una llamada 1:1 ocurre en la directa
 * y una de grupo en el grupo, asi que los bloqueos, las restricciones y el modo
 * anuncio se aplican **solos**. Quien no puede escribirte no puede hacerte
 * sonar el telefono.
 */
object Llamadas {

    /**
     * Cuanto suena antes de darse por no contestada.
     *
     * Cuarenta y cinco segundos. Es el tiempo de sacar el telefono del
     * bolsillo; mas alla, quien llama ya colgo o quien recibe no va a
     * contestar, y una llamada que suena dos minutos es una molestia.
     */
    val TIMBRE: Duration = Duration.ofSeconds(45)

    /**
     * Tope de personas en una llamada de grupo.
     *
     * Cuatro, y es una limitacion real que conviene declarar. Sin servidor de
     * medios la llamada es en MALLA: cada uno se conecta con cada otro, asi
     * que cada telefono sube su propio video N-1 veces. Con cinco personas,
     * cada uno sube cuatro copias, y con datos moviles eso no se sostiene.
     *
     * Subirlo exige un SFU -un servidor que recibe un flujo de cada uno y lo
     * reparte-, que es infraestructura aparte y no una linea de codigo. Es
     * preferible un tope declarado a una llamada de ocho que se cae sola.
     */
    const val MAX_EN_LLAMADA = 4

    // ============================================================
    //  Iniciar
    // ============================================================

    fun iniciar(yo: Auth, req: IniciarLlamadaReq): Pair<LlamadaCreada, List<Pair<UUID, Bajada.Evento>>> =
        Db.tx { c ->
            val conv = uuid(req.conversacionId)

            // Se exige el permiso de ENVIAR MENSAJE, no uno propio de llamadas.
            //
            // Es deliberado: si una llamada necesitara su propio permiso, un
            // silenciado o un expulsado podria llamar al grupo del que lo
            // echaron. Reusando `mensaje.enviar`, todas las sanciones que ya
            // existen se aplican a las llamadas sin escribir nada.
            Autz.exigir(c, yo.usuarioId, conv, Permisos.MSG_ENVIAR)

            Limitador.exigir(
                yo.usuarioId, yo.usuarioId.toString(), "llamar", Limitador.INICIAR_LLAMADA,
            )

            val tipo = c.prepareStatement("SELECT tipo FROM conversacion WHERE id = ?").use { st ->
                st.setObject(1, conv)
                st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
            } ?: throw ErrorNegocio(404, "Esa conversacion no existe.")

            if (tipo == "canal") {
                // Un canal es unidireccional y puede tener diez mil
                // suscriptores. "Llamar al canal" no significa nada.
                throw ErrorNegocio(409, "No se puede llamar a un canal.")
            }

            // Una llamada viva en la misma conversacion: se devuelve esa en vez
            // de crear otra. Sin esto, dos personas que se llaman a la vez
            // montan dos llamadas y ninguna funciona.
            vivaEn(c, conv)?.let { existente ->
                throw ErrorNegocio(409, "Ya hay una llamada en curso aqui.")
            }

            // Y una llamada viva MIA en otra conversacion: estoy ocupado.
            if (ocupado(c, yo.usuarioId)) {
                throw ErrorNegocio(409, "Ya estas en una llamada.")
            }

            val otros = participantesDe(c, conv, excepto = yo.usuarioId)
            if (otros.isEmpty()) throw ErrorNegocio(409, "No hay nadie mas en esta conversacion.")
            if (otros.size + 1 > MAX_EN_LLAMADA) {
                throw ErrorNegocio(
                    409,
                    "Las llamadas de grupo admiten hasta $MAX_EN_LLAMADA personas. " +
                        "Sin servidor de medios, cada telefono tendria que subir su video " +
                        "una vez por participante.",
                )
            }

            // K.6 y bloqueos. En una directa se comprueba el ajuste del otro;
            // en un grupo, pertenecer ya alcanza -si te dejan escribir, te
            // dejan llamar- y comprobar el ajuste de cada miembro convertiria
            // una llamada de grupo en una negociacion.
            if (tipo == "directa") {
                exigirPuedeLlamar(c, yo.usuarioId, otros.first())
            }

            // Quien esta ocupado no recibe el timbre, pero SI queda en la
            // llamada como 'rechazo' con motivo ocupado: es informacion que el
            // que llama necesita para no volver a intentar cinco veces.
            val ocupados = otros.filter { ocupado(c, it) }

            val llamadaId = c.prepareStatement(
                """INSERT INTO llamada
                     (conversacion_id, origen_id, origen_dispositivo, con_video, estado)
                   VALUES (?, ?, ?, ?, 'sonando') RETURNING id"""
            ).use { st ->
                st.setObject(1, conv)
                st.setObject(2, yo.usuarioId)
                st.setObject(3, yo.dispositivoId)
                st.setBoolean(4, req.conVideo)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }

            // El que llama entra ya como 'dentro': su lado de la llamada esta
            // levantado desde el momento en que llama.
            c.prepareStatement(
                """INSERT INTO llamada_participante (llamada_id, usuario_id, dispositivo_id, estado, unido_en)
                   VALUES (?, ?, ?, 'dentro', now())"""
            ).use { st ->
                st.setObject(1, llamadaId); st.setObject(2, yo.usuarioId); st.setObject(3, yo.dispositivoId)
                st.executeUpdate()
            }

            c.prepareStatement(
                """INSERT INTO llamada_participante (llamada_id, usuario_id, estado)
                   VALUES (?, ?, ?)"""
            ).use { st ->
                for (u in otros) {
                    st.setObject(1, llamadaId)
                    st.setObject(2, u)
                    st.setString(3, if (u in ocupados) "rechazo" else "sonando")
                    st.addBatch()
                }
                st.executeBatch()
            }

            val aSonar = otros - ocupados.toSet()
            val avisos = if (aSonar.isEmpty()) {
                emptyList()
            } else {
                Eventos.emitir(
                    c, aSonar, "llamada_entrante", conv, yo.username, llamadaId.toString(),
                )
            }

            LlamadaCreada(
                llamadaId = llamadaId.toString(),
                // Los destinos salen de la misma funcion que usan los mensajes:
                // todos los dispositivos de todos los participantes menos el
                // que llama. Incluye MIS otros aparatos, que es lo que permite
                // que la tablet deje de sonar cuando contesto en el telefono.
                destinos = Claves.destinos(yo, conv).destinos,
                turn = credencialesTurn(yo.username),
            ) to avisos
        }

    /**
     * K.6 · Quien puede llamarme.
     *
     * Por defecto 'conocidos' y no 'todos', al contrario que el resto de la
     * privacidad. Una llamada suena, interrumpe y despierta; un mensaje espera.
     */
    private fun exigirPuedeLlamar(c: Connection, yo: UUID, objetivo: UUID) {
        if (Autz.hayBloqueo(c, yo, objetivo)) {
            throw ErrorNegocio(403, "No puedes llamar a esta persona.")
        }
        val fila = c.prepareStatement(
            "SELECT priv_llamadas, username FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, objetivo)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) to it.getString(2) } }
        } ?: throw ErrorNegocio(404, "Esa persona no existe.")

        val permitido = when (fila.first) {
            "todos" -> true
            "nadie" -> false
            else -> Autz.meConoce(c, duenio = objetivo, otro = yo)
        }
        if (!permitido) {
            throw ErrorNegocio(
                403,
                if (fila.first == "nadie") "@${fila.second} no acepta llamadas."
                else "@${fila.second} solo acepta llamadas de sus contactos.",
            )
        }
    }

    // ============================================================
    //  Contestar, rechazar, terminar
    // ============================================================

    /**
     * Contesta. Devuelve los avisos para los DEMAS aparatos del que contesta.
     *
     * Ese aviso es lo que hace falta con multi-dispositivo: si contestas en el
     * telefono, la tablet tiene que dejar de sonar. Sin el, cada aparato
     * sonaria hasta que alguien lo silenciara a mano.
     */
    fun contestar(yo: Auth, llamadaId: UUID): Pair<LlamadaEnCurso, List<Pair<UUID, Bajada.Evento>>> =
        Db.tx { c ->
            val l = leerBasico(c, llamadaId)

            // El orden importa: PRIMERO si perteneces a la conversacion, y
            // recien despues el estado de la llamada.
            //
            // Sin esto, quien no tenia nada que ver con la conversacion
            // recibia 409 «Esta llamada no te esta sonando» -que confirma que
            // la llamada existe y sigue viva- o 409 «ya termino», mientras que
            // un id inventado daba 404. Esa diferencia es un oraculo: no da
            // acceso a nada, pero responde preguntas sobre un objeto ajeno, y
            // el paso 3 del §16 -«obtener el rol del usuario en el recurso»-
            // no llegaba a correr nunca.
            //
            // A quien SI es del grupo se le sigue contestando 409 con el
            // motivo: ahi el detalle es util y no revela nada que esa persona
            // no pudiera ver de todos modos.
            if (Autz.membresia(c, yo.usuarioId, l.conversacionId) == null) {
                throw ErrorNegocio(404, "Esa llamada no existe.")
            }

            if (l.estado == "terminada") throw ErrorNegocio(409, "Esa llamada ya termino.")

            val filas = c.prepareStatement(
                """UPDATE llamada_participante
                   SET estado = 'dentro', dispositivo_id = ?, unido_en = now()
                   WHERE llamada_id = ? AND usuario_id = ? AND estado = 'sonando'"""
            ).use { st ->
                st.setObject(1, yo.dispositivoId)
                st.setObject(2, llamadaId)
                st.setObject(3, yo.usuarioId)
                st.executeUpdate()
            }
            if (filas == 0) throw ErrorNegocio(409, "Esta llamada no te esta sonando.")

            // La llamada pasa a en_curso con el primero que contesta. El
            // `WHERE estado = 'sonando'` lo hace idempotente: el segundo que
            // contesta en una llamada de grupo no reescribe `contestada_en`, y
            // la duracion se sigue midiendo desde el primero.
            c.prepareStatement(
                """UPDATE llamada SET estado = 'en_curso', contestada_en = now()
                   WHERE id = ? AND estado = 'sonando'"""
            ).use { st -> st.setObject(1, llamadaId); st.executeUpdate() }

            // Solo a MIS otros aparatos: los demas participantes no necesitan
            // saber en que aparato conteste.
            val avisos = Eventos.emitir(
                c, listOf(yo.usuarioId), "llamada_contestada", l.conversacionId,
                yo.username, llamadaId.toString(),
            ).filter { it.first != yo.dispositivoId }

            LlamadaEnCurso(
                llamadaId = llamadaId.toString(),
                conversacionId = l.conversacionId.toString(),
                conVideo = l.conVideo,
                origen = l.origenUsername,
                estado = "en_curso",
                turn = credencialesTurn(yo.username),
                destinos = Claves.destinos(yo, l.conversacionId).destinos,
            ) to avisos
        }

    /**
     * Termina la llamada -o solo mi parte, si es de grupo y quedan otros.
     *
     * La distincion importa: en una llamada de tres, que uno cuelgue no debe
     * cortar a los otros dos. La llamada se marca terminada solo cuando queda
     * menos de dos personas dentro, porque una llamada de uno no es una
     * llamada.
     */
    fun terminar(yo: Auth, llamadaId: UUID, motivo: String): List<Pair<UUID, Bajada.Evento>> =
        Db.tx { c ->
            if (motivo !in FinLlamada.TODOS) throw ErrorNegocio(400, "Motivo desconocido.")
            val l = leerBasico(c, llamadaId)

            val eraParticipante = c.prepareStatement(
                """UPDATE llamada_participante
                   SET estado = CASE WHEN estado = 'sonando' THEN 'rechazo' ELSE 'fuera' END,
                       salido_en = now()
                   WHERE llamada_id = ? AND usuario_id = ? AND salido_en IS NULL"""
            ).use { st ->
                st.setObject(1, llamadaId); st.setObject(2, yo.usuarioId); st.executeUpdate()
            }
            if (eraParticipante == 0) throw ErrorNegocio(404, "No estas en esa llamada.")

            val dentro = c.prepareStatement(
                "SELECT count(*) FROM llamada_participante WHERE llamada_id = ? AND estado = 'dentro'"
            ).use { st ->
                st.setObject(1, llamadaId)
                st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
            }

            // Una llamada de una sola persona no es una llamada.
            if (dentro >= 2 && l.estado == "en_curso") return@tx emptyList()

            // El motivo se afina aqui, no se toma tal cual del cliente: quien
            // cuelga un timbre que nadie contesto esta CANCELANDO, no
            // colgando, y esa diferencia es la que separa "no me contesto" de
            // "me colgo" en el historial.
            val motivoReal = when {
                l.estado == "sonando" && yo.usuarioId == l.origenId -> FinLlamada.CANCELADA
                l.estado == "sonando" -> FinLlamada.RECHAZADA
                else -> motivo
            }

            c.prepareStatement(
                """UPDATE llamada
                   SET estado = 'terminada', terminada_en = now(), fin_motivo = ?, fin_por = ?
                   WHERE id = ? AND estado <> 'terminada'"""
            ).use { st ->
                st.setString(1, motivoReal)
                st.setObject(2, yo.usuarioId)
                st.setObject(3, llamadaId)
                st.executeUpdate()
            }

            c.prepareStatement(
                """UPDATE llamada_participante
                   SET estado = CASE WHEN estado = 'sonando' THEN 'no_contesto' ELSE 'fuera' END,
                       salido_en = coalesce(salido_en, now())
                   WHERE llamada_id = ? AND estado IN ('sonando','dentro')"""
            ).use { st -> st.setObject(1, llamadaId); st.executeUpdate() }

            val todos = participantesDe(c, l.conversacionId, excepto = null)
            Eventos.emitir(
                c, todos, "llamada_terminada", l.conversacionId, yo.username,
                """{"llamada":"$llamadaId","motivo":"$motivoReal"}""",
            ).filter { it.first != yo.dispositivoId }
        }

    /**
     * Marca como sin respuesta las llamadas que suenan desde hace demasiado.
     *
     * Existe porque el timbre no puede depender del cliente: si el que llama
     * cierra la app, nadie manda el "cancelada" y la llamada quedaria sonando
     * para siempre en la base y el otro telefono sonando de verdad.
     */
    fun cerrarTimbresVencidos(): Int = Db.tx { c ->
        val vencidas = c.prepareStatement(
            """SELECT id FROM llamada
               WHERE estado = 'sonando'
                 AND iniciada_en < now() - make_interval(secs => ?)"""
        ).use { st ->
            st.setDouble(1, TIMBRE.seconds.toDouble())
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        if (vencidas.isEmpty()) return@tx 0

        val arr = c.createArrayOf("uuid", vencidas.toTypedArray())
        c.prepareStatement(
            """UPDATE llamada
               SET estado = 'terminada', terminada_en = now(), fin_motivo = 'sin_respuesta'
               WHERE id = ANY(?)"""
        ).use { st -> st.setArray(1, arr); st.executeUpdate() }
        c.prepareStatement(
            """UPDATE llamada_participante
               SET estado = CASE WHEN estado = 'sonando' THEN 'no_contesto' ELSE 'fuera' END,
                   salido_en = coalesce(salido_en, now())
               WHERE llamada_id = ANY(?) AND estado IN ('sonando','dentro')"""
        ).use { st -> st.setArray(1, arr); st.executeUpdate() }
        vencidas.size
    }

    // ============================================================
    //  Estado e historial
    // ============================================================

    /** La llamada viva de esta persona, si hay alguna. La usa el arranque. */
    fun enCurso(yo: Auth): LlamadaEnCurso? = Db.query { c ->
        val id = c.prepareStatement(
            """SELECT l.id FROM llamada l
                 JOIN llamada_participante p ON p.llamada_id = l.id AND p.usuario_id = ?
               WHERE l.estado <> 'terminada' AND p.estado IN ('sonando','dentro')
               ORDER BY l.iniciada_en DESC LIMIT 1"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: return@query null

        val l = leerBasico(c, id)
        LlamadaEnCurso(
            llamadaId = id.toString(),
            conversacionId = l.conversacionId.toString(),
            conVideo = l.conVideo,
            origen = l.origenUsername,
            estado = l.estado,
            turn = credencialesTurn(yo.username),
            destinos = Claves.destinos(yo, l.conversacionId).destinos,
        )
    }

    fun historial(yo: Auth, limite: Int): List<LlamadaEnHistorial> = Db.query { c ->
        c.prepareStatement(
            """SELECT l.id, l.conversacion_id, coalesce(cv.nombre, ''), l.con_video,
                      l.origen_id = ?,
                      (EXTRACT(EPOCH FROM l.iniciada_en) * 1000)::bigint,
                      coalesce(EXTRACT(EPOCH FROM (l.terminada_en - l.contestada_en)), 0)::int,
                      l.estado, l.fin_motivo,
                      p.estado,
                      (SELECT string_agg(u.username::text, ',' ORDER BY u.username)
                         FROM llamada_participante pp JOIN usuario u ON u.id = pp.usuario_id
                        WHERE pp.llamada_id = l.id AND pp.usuario_id <> ?)
               FROM llamada l
                 JOIN llamada_participante p ON p.llamada_id = l.id AND p.usuario_id = ?
                 LEFT JOIN conversacion cv ON cv.id = l.conversacion_id
               ORDER BY l.iniciada_en DESC
               LIMIT ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, yo.usuarioId)
            st.setInt(4, limite.coerceIn(1, 200))
            st.executeQuery().use { rs ->
                rs.mapear {
                    val fueMia = it.getBoolean(5)
                    val miEstado = it.getString(10)
                    val otros = it.getString(11)?.split(",")?.filter { u -> u.isNotBlank() }
                        ?: emptyList()
                    LlamadaEnHistorial(
                        id = it.getObject(1, UUID::class.java).toString(),
                        conversacionId = it.getObject(2, UUID::class.java).toString(),
                        // Una directa NO tiene nombre de conversacion -esa
                        // columna es solo de los grupos-, asi que ahi el titulo
                        // es con quien se hablo. Sin este arreglo el historial
                        // mostraba una lista de "Llamada" sin nombre, que es
                        // exactamente el dato que se va a buscar.
                        titulo = it.getString(3).ifBlank { otros.firstOrNull().orEmpty() },
                        conVideo = it.getBoolean(4),
                        fueMia = fueMia,
                        iniciadaEn = it.getLong(6),
                        duracion = it.getInt(7).coerceAtLeast(0),
                        estado = it.getString(8),
                        finMotivo = it.getString(9),
                        // Perdida = no era mia Y no la conteste. Es el unico
                        // caso que la interfaz pinta distinto, porque es el
                        // unico que reclama una accion.
                        perdida = !fueMia && miEstado in listOf("no_contesto", "sonando"),
                        participantes = otros,
                    )
                }
            }
        }
    }

    // ============================================================
    //  K.4 · TURN
    // ============================================================

    /**
     * Credenciales temporales, esquema REST de coturn.
     *
     * El usuario es `<vencimiento unix>:<algo>` y la clave es
     * `base64(HMAC-SHA1(secreto, usuario))`. El TURN valida la firma sin
     * consultar ninguna base y sin que le hagamos falta: el secreto compartido
     * es lo unico que necesita.
     *
     * Sin `WTFUCK_TURN_URL` configurada devuelve `hay = false` en vez de
     * lanzar, y eso es a proposito: **una llamada sin TURN no es un error**. En
     * la misma red funciona perfectamente porque ICE encuentra una ruta
     * directa. Lo que no se puede es pretender que funcionara siempre.
     */
    fun credencialesTurn(username: String): ConfigTurn {
        val urls = System.getenv("WTFUCK_TURN_URL")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val secreto = System.getenv("WTFUCK_TURN_SECRETO")?.takeIf { it.isNotBlank() }

        if (urls.isEmpty() || secreto == null) return ConfigTurn(hay = false)

        val vida = System.getenv("WTFUCK_TURN_VIDA_S")?.toIntOrNull() ?: 12 * 3600
        val vence = System.currentTimeMillis() / 1000 + vida
        // El username va en el usuario del TURN para poder atribuir el consumo
        // de ancho de banda si algo se desmadra. No es un secreto.
        val usuario = "$vence:$username"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secreto.toByteArray(), "HmacSHA1"))
        val clave = Base64.getEncoder().encodeToString(mac.doFinal(usuario.toByteArray()))

        return ConfigTurn(
            urls = urls,
            usuario = usuario,
            clave = clave,
            expiraEnSegundos = vida,
            hay = true,
        )
    }

    // ============================================================
    //  Piezas
    // ============================================================

    private data class Basico(
        val conversacionId: UUID,
        val origenId: UUID,
        val origenUsername: String,
        val conVideo: Boolean,
        val estado: String,
    )

    private fun leerBasico(c: Connection, llamadaId: UUID): Basico =
        c.prepareStatement(
            """SELECT l.conversacion_id, l.origen_id, u.username, l.con_video, l.estado
               FROM llamada l JOIN usuario u ON u.id = l.origen_id
               WHERE l.id = ?"""
        ).use { st ->
            st.setObject(1, llamadaId)
            st.executeQuery().use { rs ->
                rs.primero {
                    Basico(
                        conversacionId = it.getObject(1, UUID::class.java),
                        origenId = it.getObject(2, UUID::class.java),
                        origenUsername = it.getString(3),
                        conVideo = it.getBoolean(4),
                        estado = it.getString(5),
                    )
                }
            }
        } ?: throw ErrorNegocio(404, "Esa llamada no existe.")

    private fun vivaEn(c: Connection, conv: UUID): UUID? =
        c.prepareStatement(
            "SELECT id FROM llamada WHERE conversacion_id = ? AND estado <> 'terminada' LIMIT 1"
        ).use { st ->
            st.setObject(1, conv)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }

    private fun ocupado(c: Connection, usuarioId: UUID): Boolean =
        c.prepareStatement(
            """SELECT 1 FROM llamada l
                 JOIN llamada_participante p ON p.llamada_id = l.id AND p.usuario_id = ?
               WHERE l.estado <> 'terminada' AND p.estado = 'dentro' LIMIT 1"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { it.next() }
        }

    private fun participantesDe(c: Connection, conv: UUID, excepto: UUID?): List<UUID> =
        c.prepareStatement(
            """SELECT usuario_id FROM participante
               WHERE conversacion_id = ? AND salido_en IS NULL
                 AND (?::uuid IS NULL OR usuario_id <> ?::uuid)"""
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, excepto); st.setObject(3, excepto)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }

    private fun uuid(s: String): UUID =
        runCatching { UUID.fromString(s) }
            .getOrElse { throw ErrorNegocio(400, "Identificador de conversacion invalido.") }
}
