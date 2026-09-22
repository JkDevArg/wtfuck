package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

/**
 * Modulo O · Historias.
 *
 * Contenido que se publica a una audiencia y caduca a las 24 horas. El
 * servidor guarda **el metadato y nada mas**: quien publico, cuando, de que
 * clase dijo que era, a quien iba y quien la vio. El contenido viaja cifrado
 * por el buzon de siempre.
 *
 * ## Que significa "todos" aqui, y que no
 *
 * Este es el punto que hay que decir en voz alta. En los cuatro niveles de
 * privacidad de esta plataforma, `todos` significa "cualquiera que pregunte".
 * **Para las historias no puede significar eso.**
 *
 * Una historia va cifrada contra dispositivos concretos. Publicar "para todos"
 * en el sentido literal seria cifrar contra los aparatos de cuarenta mil
 * personas, la inmensa mayoria de las cuales no sabe que existis. No es que sea
 * caro: es que no es lo que nadie quiere decir al elegir esa opcion.
 *
 * Asi que la audiencia sale siempre de un **conjunto acotado**: la gente con la
 * que ya hay una relacion en la plataforma —una conversacion directa abierta, o
 * alguien que te tiene guardado—. Sobre ese conjunto, el nivel decide:
 *
 *  - `todos`          → todo el conjunto.
 *  - `conocidos`      → solo quienes tienen una conversacion directa abierta.
 *  - `nadie`          → nadie.
 *  - `personalizado`  → el conjunto filtrado por la lista, con su modo.
 *
 * La diferencia entre los dos primeros es real y util: "cualquiera que me tenga
 * agendado" no es lo mismo que "con quien hablo".
 *
 * ## Por que la audiencia se congela
 *
 * Se resuelve al publicar y se guarda. Quien abra una conversacion conmigo
 * manana no vera lo de hoy, porque no habia con que cifrarselo. No es una
 * simplificacion que se arregla despues: es lo que significa cifrar contra
 * destinatarios concretos.
 */
object Historias {

    /** Ver una historia es leer un mensaje: cuenta como confirmacion. */
    private const val AJUSTE = "historias"

    // ------------------------------------------------------------------
    //  A quien le toca
    // ------------------------------------------------------------------

    /**
     * Los dispositivos a los que hay que cifrarle una historia mia.
     *
     * Se pregunta **antes** de publicar: el cliente necesita la lista para
     * armar un sobre por aparato. Misma forma que los destinos de una
     * conversacion, y a proposito.
     */
    fun destinos(yo: Auth): DestinosHistoria = Db.query { c ->
        val gente = audienciaDe(c, yo.usuarioId)
        if (gente.isEmpty()) return@query DestinosHistoria()

        val lista = c.prepareStatement(
            """SELECT d.usuario_id, u.username, d.id, d.registration_id, d.identidad_pub,
                      coalesce(d.etiqueta, '')
               FROM dispositivo d
                 JOIN usuario u ON u.id = d.usuario_id
               WHERE d.usuario_id = ANY (?) AND d.revocado_en IS NULL AND d.id <> ?
               ORDER BY u.username, d.principal DESC, d.etiqueta"""
        ).use { st ->
            st.setArray(1, c.createArrayOf("uuid", gente.toTypedArray()))
            st.setObject(2, yo.dispositivoId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    DestinoDispositivo(
                        usuarioId = it.getObject(1, UUID::class.java).toString(),
                        username = it.getString(2),
                        dispositivoId = it.getObject(3, UUID::class.java).toString(),
                        registrationId = it.getInt(4),
                        identidad = b64(it.getBytes(5)),
                        etiqueta = it.getString(6),
                    )
                }
            }
        }
        DestinosHistoria(lista)
    }

    /**
     * Quien puede ver las historias de esta persona, ahora mismo.
     *
     * Devuelve ids de usuario, no de dispositivo: un aparato nuevo de alguien
     * que ya esta en la audiencia entra solo, y uno de alguien que no esta, no.
     */
    private fun audienciaDe(c: Connection, autorId: UUID): List<UUID> {
        val nivel = c.prepareStatement(
            "SELECT priv_historias, priv_modo::text FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, autorId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) to it.getString(2) } }
        } ?: (Privacidad.CONOCIDOS to null)

        if (nivel.first == Privacidad.NADIE) return emptyList()

        // El conjunto acotado. Ver la nota de la clase.
        val pool = Repo.quienesMeConocen(c, autorId)
        if (pool.isEmpty()) return emptyList()

        return when (nivel.first) {
            Privacidad.TODOS -> pool.toList()

            // La mitad estricta: solo con quien hay conversacion abierta.
            Privacidad.CONOCIDOS -> conConversacionDirecta(c, autorId, pool)

            Privacidad.PERSONALIZADO -> {
                val lista = excepcionesMias(c, autorId)
                val soloLista = Repo.modoDe(nivel.second, AJUSTE) == Privacidad.MODO_SOLO
                pool.filter { if (soloLista) it in lista else it !in lista }
            }

            // Un nivel que no se reconoce resuelve NO, como en todo el resto de
            // las decisiones de privacidad de este proyecto.
            else -> emptyList()
        }
    }

    private fun conConversacionDirecta(c: Connection, autorId: UUID, pool: Set<UUID>): List<UUID> =
        c.prepareStatement(
            """SELECT DISTINCT p2.usuario_id
               FROM participante p1
                 JOIN conversacion cv ON cv.id = p1.conversacion_id AND cv.tipo = 'directa'
                 JOIN participante p2 ON p2.conversacion_id = p1.conversacion_id
               WHERE p1.usuario_id = ? AND p1.salido_en IS NULL AND p2.salido_en IS NULL
                 AND p2.usuario_id <> ?"""
        ).use { st ->
            st.setObject(1, autorId); st.setObject(2, autorId)
            st.executeQuery().use { rs ->
                rs.mapear { it.getObject(1, UUID::class.java) }.filter { it in pool }
            }
        }

    /** Mis excepciones para este ajuste: a quien nombre en la lista. */
    private fun excepcionesMias(c: Connection, autorId: UUID): Set<UUID> =
        c.prepareStatement(
            "SELECT otro_id FROM privacidad_excepcion WHERE usuario_id = ? AND ajuste = ?"
        ).use { st ->
            st.setObject(1, autorId); st.setString(2, AJUSTE)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) }.toSet() }
        }

    // ------------------------------------------------------------------
    //  Publicar
    // ------------------------------------------------------------------

    /**
     * Registra una historia y congela su audiencia.
     *
     * El id lo trae el cliente —como en los mensajes— para que los sobres
     * puedan apuntar a ella y para que reintentar no publique dos veces.
     */
    fun publicar(yo: Auth, req: PublicarHistoriaReq): HistoriaMia = Db.tx { c ->
        if (req.clase !in ClaseHistoria.TODAS) {
            throw ErrorNegocio(400, "Esa clase de historia no existe.")
        }
        val id = runCatching { UUID.fromString(req.historiaId) }.getOrNull()
            ?: throw ErrorNegocio(400, "Identificador de historia invalido.")

        // Una historia la ve la audiencia entera y no pertenece a ninguna
        // conversacion, asi que no pasa por `Autz.puede`. Sin esta linea,
        // publicar seria la forma mas facil de saltarse una suspension.
        Autz.exigirNoSuspendido(c, yo.usuarioId)

        // Reintentar no duplica: si ya existe y es mia, se devuelve la que hay.
        yaMia(c, id, yo.usuarioId)?.let { return@tx it }

        val gente = audienciaDe(c, yo.usuarioId)

        c.prepareStatement(
            """INSERT INTO historia (id, autor_id, clase, expira_en)
               VALUES (?, ?, ?, now() + make_interval(hours => ?))"""
        ).use { st ->
            st.setObject(1, id)
            st.setObject(2, yo.usuarioId)
            st.setString(3, req.clase)
            st.setInt(4, HORAS_DE_VIDA_HISTORIA)
            st.executeUpdate()
        }

        if (gente.isNotEmpty()) {
            c.prepareStatement(
                "INSERT INTO historia_destino (historia_id, usuario_id) SELECT ?, unnest(?::uuid[])"
            ).use { st ->
                st.setObject(1, id)
                st.setArray(2, c.createArrayOf("uuid", gente.toTypedArray()))
                st.executeUpdate()
            }
        }

        Autz.auditar(
            c, yo.usuarioId, "historia.publicar", "historia", id,
            detalle = """{"clase":"${req.clase}","destinatarios":${gente.size}}""",
        )

        yaMia(c, id, yo.usuarioId)!!
    }

    private fun yaMia(c: Connection, id: UUID, autorId: UUID): HistoriaMia? =
        c.prepareStatement(
            """SELECT h.clase,
                      (EXTRACT(EPOCH FROM h.creada_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM h.expira_en) * 1000)::bigint,
                      (SELECT count(*) FROM historia_destino d WHERE d.historia_id = h.id),
                      (SELECT count(*) FROM historia_vista v WHERE v.historia_id = h.id)
               FROM historia h
               WHERE h.id = ? AND h.autor_id = ? AND h.retirada_en IS NULL"""
        ).use { st ->
            st.setObject(1, id); st.setObject(2, autorId)
            st.executeQuery().use { rs ->
                rs.primero {
                    HistoriaMia(
                        historiaId = id.toString(),
                        clase = it.getString(1),
                        creadaEn = it.getLong(2),
                        expiraEn = it.getLong(3),
                        destinatarios = it.getInt(4),
                        vistas = it.getInt(5),
                    )
                }
            }
        }

    // ------------------------------------------------------------------
    //  Los sobres
    // ------------------------------------------------------------------

    /**
     * Encola los sobres cifrados de una historia.
     *
     * ## Por que no van por `/v1/mensajes`
     *
     * Una historia no pertenece a ninguna conversacion. Meterla por la ruta de
     * mensajes obligaria a inventar un chat con cada persona de la audiencia
     * —incluida la que te tiene agendado y nunca te escribio— o sea a crear
     * conversaciones vacias en la pantalla de otra gente solo para que el buzon
     * tenga donde apoyarse.
     *
     * ## Los destinos los decide el servidor
     *
     * Misma regla que en los mensajes, y aqui es mas importante: sin la
     * comprobacion contra la audiencia congelada, esta ruta seria una forma de
     * meterle bytes en el buzon a cualquiera saltandose las conversaciones **y
     * los bloqueos**. Una copia dirigida a un aparato que no esta en la
     * audiencia se descarta en silencio.
     *
     * Devuelve los dispositivos que el cliente NO cubrio, igual que los
     * mensajes: no se inventa nada, se le dice cuales faltan.
     */
    fun encolarSobres(yo: Auth, historiaId: UUID, req: SobresHistoriaReq): List<String> {
        val permitidos = Db.query { c ->
            val mia = c.prepareStatement(
                """SELECT 1 FROM historia
                    WHERE id = ? AND autor_id = ?
                      AND retirada_en IS NULL AND expira_en > now()"""
            ).use { st ->
                st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
                st.executeQuery().use { it.next() }
            }
            if (!mia) throw ErrorNegocio(404, "Esa historia no existe.")

            c.prepareStatement(
                """SELECT d.id, d.usuario_id
                   FROM historia_destino t
                     JOIN dispositivo d ON d.usuario_id = t.usuario_id
                                       AND d.revocado_en IS NULL
                   WHERE t.historia_id = ? AND d.id <> ?"""
            ).use { st ->
                st.setObject(1, historiaId); st.setObject(2, yo.dispositivoId)
                st.executeQuery().use { rs ->
                    rs.mapear {
                        Destino(
                            dispositivoId = it.getObject(1, UUID::class.java),
                            usuarioId = it.getObject(2, UUID::class.java),
                        )
                    }
                }
            }
        }.associateBy { it.dispositivoId }

        val copias = mutableListOf<Repo.Copia>()
        for (cp in req.copias) {
            val cuerpo = runCatching { Base64Util.dec(cp.cuerpo) }.getOrNull()
            if (cuerpo == null || cuerpo.isEmpty() || cuerpo.size > 65536) {
                throw ErrorNegocio(400, "Cuerpo invalido o demasiado grande.")
            }
            for (crudo in cp.destinos) {
                val id = runCatching { UUID.fromString(crudo) }.getOrNull() ?: continue
                val destino = permitidos[id] ?: continue
                copias += Repo.Copia(destino, cuerpo, cp.tipo)
            }
        }

        val ahora = System.currentTimeMillis()
        // `conversacionId = null`: ver V29. Es el primer sobre de este proyecto
        // que no vive dentro de una conversacion.
        val encolados = Repo.encolar(historiaId, yo.dispositivoId, null, copias, ahora)

        encolados.forEachIndexed { i, (destino, sobreIdDerivado) ->
            val cp = copias[i]
            Hub.empujar(
                destino.dispositivoId,
                Bajada.Entrega(
                    sobreId = sobreIdDerivado.toString(),
                    mensajeId = historiaId.toString(),
                    // Vacio a proposito: no va a ningun chat.
                    conversacionId = "",
                    origenUsuarioId = yo.usuarioId.toString(),
                    origenUsername = yo.username,
                    origenDispositivo = yo.dispositivoId.toString(),
                    creadoEn = ahora,
                    cuerpo = Base64Util.enc(cp.cuerpo),
                    tipo = cp.tipo,
                ),
            )
        }

        val cubiertos = copias.map { it.destino.dispositivoId }.toSet()
        val sinCopia = permitidos.keys.filter { it !in cubiertos }

        // Cerrar el reparto: quien no recibio NINGUNA copia deja de tener la
        // historia anunciada.
        //
        // ## Por que esto hace falta
        //
        // El metadato se registra antes que los sobres —el servidor lo exige
        // para poder validar los destinos—, asi que si el cifrado falla para
        // alguien, esa persona se queda con una historia que **aparece en su
        // lista y no se puede abrir**. Eso ya estaba escrito como la intencion
        // ("lo honesto es que su historia no exista para el en vez de que
        // exista y no abra") pero no lo hacia nadie: el servidor decia quien
        // habia quedado fuera y el cliente lo apuntaba en un log.
        //
        // ## Por usuario, no por aparato
        //
        // Quien tiene dos telefonos y recibio copia en uno **sigue viendola**:
        // la lista es por persona, y esa persona si puede abrirla. Solo se cae
        // de la audiencia quien no recibio copia en ninguno.
        //
        // Se calcula sobre las copias de ESTA peticion y no consultando la
        // cola, porque un sobre se borra al entregarse: mirar `sobre_pendiente`
        // daria por no cubierto a quien ya lo recogio.
        var retirados = 0
        if (req.ultimoLote) {
            val conCopia = copias.map { it.destino.usuarioId }.toSet()
            val fuera = permitidos.values.map { it.usuarioId }.toSet() - conCopia
            if (fuera.isNotEmpty()) {
                retirados = Db.tx { c ->
                    c.prepareStatement(
                        "DELETE FROM historia_destino WHERE historia_id = ? AND usuario_id = ANY (?)"
                    ).use { st ->
                        st.setObject(1, historiaId)
                        st.setArray(2, c.createArrayOf("uuid", fuera.map { it.toString() }.toTypedArray()))
                        st.executeUpdate()
                    }
                }
            }
        }
        if (retirados > 0) {
            bitacoraHistorias.info(
                "Historia {}: {} destinos retirados por quedar sin sobre", historiaId, retirados)
        }

        return sinCopia.map { it.toString() }
    }

    // ------------------------------------------------------------------
    //  Leer
    // ------------------------------------------------------------------

    /**
     * Las historias vivas dirigidas a mi.
     *
     * `expira_en > now()` en la consulta y no un barrendero: una historia
     * vencida deja de existir para quien pregunta en el instante exacto en que
     * vence, sin depender de que un proceso haya pasado.
     */
    fun paraMi(yo: Auth): HistoriasParaMi = Db.query { c ->
        val lista = c.prepareStatement(
            """SELECT h.id, u.username, h.clase,
                      (EXTRACT(EPOCH FROM h.creada_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM h.expira_en) * 1000)::bigint,
                      EXISTS (SELECT 1 FROM historia_vista v
                               WHERE v.historia_id = h.id AND v.usuario_id = ?)
               FROM historia h
                 JOIN historia_destino d ON d.historia_id = h.id
                 JOIN usuario u          ON u.id = h.autor_id
               WHERE d.usuario_id = ?
                 AND h.retirada_en IS NULL
                 AND h.expira_en > now()
                 -- Un bloqueo tapa las historias en los dos sentidos: ni las
                 -- suyas me llegan ni las mias le llegan. Filtrar solo al
                 -- publicar dejaria vivas las de antes del bloqueo.
                 AND NOT EXISTS (
                     SELECT 1 FROM bloqueo b
                      WHERE (b.bloqueador_id = ? AND b.bloqueado_id = h.autor_id)
                         OR (b.bloqueador_id = h.autor_id AND b.bloqueado_id = ?)
                 )
               ORDER BY u.username, h.creada_en"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, yo.usuarioId)
            st.setObject(4, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    HistoriaInfo(
                        historiaId = it.getObject(1, UUID::class.java).toString(),
                        autorUsername = it.getString(2),
                        clase = it.getString(3),
                        creadaEn = it.getLong(4),
                        expiraEn = it.getLong(5),
                        vista = it.getBoolean(6),
                    )
                }
            }
        }
        HistoriasParaMi(lista)
    }

    /** Las mias que siguen vivas, con cuantos la vieron. */
    fun mias(yo: Auth): MisHistorias = Db.query { c ->
        val cuento = registraLecturas(c, yo.usuarioId)
        val lista = c.prepareStatement(
            """SELECT h.id, h.clase,
                      (EXTRACT(EPOCH FROM h.creada_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM h.expira_en) * 1000)::bigint,
                      (SELECT count(*) FROM historia_destino d WHERE d.historia_id = h.id),
                      (SELECT count(*) FROM historia_vista v WHERE v.historia_id = h.id)
               FROM historia h
               WHERE h.autor_id = ? AND h.retirada_en IS NULL AND h.expira_en > now()
               ORDER BY h.creada_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    HistoriaMia(
                        historiaId = it.getObject(1, UUID::class.java).toString(),
                        clase = it.getString(2),
                        creadaEn = it.getLong(3),
                        expiraEn = it.getLong(4),
                        destinatarios = it.getInt(5),
                        // `null` y no 0 cuando no se puede saber: 0 diria
                        // "nadie la vio", que es otra afirmacion y es falsa.
                        vistas = if (cuento) it.getInt(6) else null,
                    )
                }
            }
        }
        MisHistorias(lista)
    }

    // ------------------------------------------------------------------
    //  Vistas
    // ------------------------------------------------------------------

    /**
     * Marca que vi una historia.
     *
     * ## Por que respeta el ajuste de confirmaciones de lectura
     *
     * Ver una historia es exactamente lo mismo que leer un mensaje: la otra
     * persona se entera de que estuviste. Tener un interruptor para una cosa y
     * no para la otra convertiria las historias en la puerta de atras del
     * ajuste que alguien apago a proposito.
     *
     * Quien lo tiene apagado no registra la vista —la fila no se crea, no hay
     * nada que esconder despues— y por la misma reciprocidad tampoco ve quien
     * vio las suyas.
     */
    fun marcarVista(yo: Auth, historiaId: UUID) = Db.tx { c ->
        // Solo se puede marcar lo que me llego de verdad. Sin esto, alguien
        // podria confirmar la vista de una historia que no le tocaba y el autor
        // veria en su lista a alguien a quien no le publico.
        val meLlego = c.prepareStatement(
            """SELECT 1 FROM historia h
                 JOIN historia_destino d ON d.historia_id = h.id
                WHERE h.id = ? AND d.usuario_id = ?
                  AND h.retirada_en IS NULL AND h.expira_en > now()"""
        ).use { st ->
            st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { it.next() }
        }
        if (!meLlego) throw ErrorNegocio(404, "Esa historia no existe.")

        if (!registraLecturas(c, yo.usuarioId)) return@tx Unit

        c.prepareStatement(
            """INSERT INTO historia_vista (historia_id, usuario_id) VALUES (?, ?)
               ON CONFLICT (historia_id, usuario_id) DO NOTHING"""
        ).use { st ->
            st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }
        Unit
    }

    /** Quien vio una historia mia. */
    fun vistas(yo: Auth, historiaId: UUID): VistasDeHistoria = Db.query { c ->
        val esMia = c.prepareStatement(
            "SELECT 1 FROM historia WHERE id = ? AND autor_id = ?"
        ).use { st ->
            st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { it.next() }
        }
        if (!esMia) throw ErrorNegocio(404, "Esa historia no existe.")

        // Reciprocidad: quien no manda confirmaciones tampoco las recibe. Se
        // DICE por que la lista viene vacia, en vez de devolver un vacio que se
        // lee como "no le intereso a nadie".
        if (!registraLecturas(c, yo.usuarioId)) {
            return@query VistasDeHistoria(motivo = MotivoSinVistas.SIN_LECTURA)
        }

        val lista = c.prepareStatement(
            """SELECT u.username, coalesce(u.nombre_mostrado, ''),
                      (EXTRACT(EPOCH FROM v.vista_en) * 1000)::bigint
               FROM historia_vista v JOIN usuario u ON u.id = v.usuario_id
               WHERE v.historia_id = ?
               ORDER BY v.vista_en DESC"""
        ).use { st ->
            st.setObject(1, historiaId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    VistaDeHistoria(
                        username = it.getString(1),
                        nombreMostrado = it.getString(2),
                        vistaEn = it.getLong(3),
                    )
                }
            }
        }
        VistasDeHistoria(vistas = lista)
    }

    private fun registraLecturas(c: Connection, usuarioId: UUID): Boolean =
        c.prepareStatement("SELECT priv_lectura FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: true
        }

    // ------------------------------------------------------------------
    //  Retirar
    // ------------------------------------------------------------------

    /**
     * Quitarla antes de que caduque.
     *
     * Se marca en vez de borrar para que las vistas ya registradas sigan
     * teniendo a que apuntar, y porque el borrado fisico de lo caducado es otro
     * problema y va sin prisa.
     */
    fun retirar(yo: Auth, historiaId: UUID) = Db.tx { c ->
        val n = c.prepareStatement(
            """UPDATE historia SET retirada_en = now()
               WHERE id = ? AND autor_id = ? AND retirada_en IS NULL"""
        ).use { st ->
            st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "Esa historia no existe o ya se habia quitado.")
        Autz.auditar(c, yo.usuarioId, "historia.retirar", "historia", historiaId)
    }

    /**
     * Borra de verdad lo que ya no le sirve a nadie.
     *
     * Se llama de vez en cuando, no en cada peticion: lo caducado ya es
     * invisible para las consultas, asi que esto es higiene y no correccion.
     */
    fun limpiar(): Int = Db.tx { c ->
        c.prepareStatement(
            "DELETE FROM historia WHERE expira_en < now() - make_interval(days => 7)"
        ).use { it.executeUpdate() }
    }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
}

private val bitacoraHistorias = org.slf4j.LoggerFactory.getLogger("historias")
