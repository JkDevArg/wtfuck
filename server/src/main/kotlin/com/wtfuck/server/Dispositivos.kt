package com.wtfuck.server

import com.wtfuck.protocol.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * Modulo J: varios dispositivos por cuenta.
 *
 * ## Casi nada de esto es codigo nuevo de reparto
 *
 * El fan-out por dispositivo funciona desde el modulo E: la direccion de una
 * sesion de Signal siempre fue el dispositivo, `Claves.destinos` siempre
 * devolvio una lista, y `CopiaCifrada` existe porque con E2EE no hay un cuerpo
 * unico. Lo que faltaba era **dejar de prohibir** el segundo dispositivo.
 *
 * Lo verdaderamente nuevo son dos cosas: como entra un aparato, y que ve.
 *
 * ## La direccion de la vinculacion no es arbitraria
 *
 * El codigo lo genera **el dispositivo que ya tiene la cuenta**, y la persona
 * lo teclea en el nuevo. Al reves -que el aparato nuevo genere un codigo y
 * pida que se lo aprueben- es el patron exacto de la estafa de WhatsApp Web:
 * el atacante manda su codigo y convence a la victima de aprobarlo.
 *
 * Con esta direccion, para vincular hay que tener en la mano el dispositivo
 * que ya esta dentro. No hay nada que aprobar a distancia.
 */
object Dispositivos {

    /**
     * Cinco minutos. Es el tiempo de copiar ocho caracteres de una pantalla a
     * otra que estan las dos delante; no hace falta mas, y cada minuto extra
     * es un minuto en que el codigo sirve si alguien lo vio.
     */
    private val VIDA_CODIGO: Duration = Duration.ofMinutes(5)

    private const val MAX_INTENTOS = 5

    /**
     * Tope de dispositivos por cuenta.
     *
     * Ocho. No es una limitacion tecnica: es que cada dispositivo es una copia
     * mas de tus mensajes y un sitio mas donde te los pueden leer. Sin tope,
     * una cuenta comprometida acumula dispositivos y nadie lo nota; con tope,
     * llega un momento en que hay que revocar algo y mirar la lista.
     *
     * Si se sube, hay que subir tambien `Limitador.EMITIR_VINCULACION`: con
     * menos codigos por hora que dispositivos permitidos, el tope no se puede
     * alcanzar y el sintoma es un 429 sin explicacion.
     */
    const val MAX_DISPOSITIVOS = 8

    private val rnd = SecureRandom()

    /**
     * Alfabeto del codigo: sin los caracteres que se confunden al copiar a
     * mano (0/O, 1/I/L). Es el mismo criterio que los codigos de respaldo del
     * 2FA, y por el mismo motivo: esto se teclea leyendo de otra pantalla.
     */
    private const val ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LARGO = 8

    // ============================================================
    //  J.2 · Emitir el codigo
    // ============================================================

    /**
     * Genera un codigo de vinculacion. Solo el dispositivo PRINCIPAL.
     *
     * Exige la contrasena. Vincular un dispositivo da acceso durable a todo lo
     * que llegue desde ahora, asi que esta en la misma categoria que eliminar
     * la cuenta o apagar el 2FA: una sesion robada no deberia poder hacerlo en
     * silencio.
     */
    fun emitirCodigo(yo: Auth, password: String): CodigoVinculacion = Db.tx { c ->
        val fila = c.prepareStatement(
            """SELECT u.password_hash, d.principal, d.etiqueta
               FROM usuario u JOIN dispositivo d ON d.id = ?
               WHERE u.id = ?"""
        ).use { st ->
            st.setObject(1, yo.dispositivoId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.primero { Triple(it.getString(1), it.getBoolean(2), it.getString(3)) }
            }
        } ?: throw ErrorNegocio(404, "Cuenta no encontrada.")

        if (!Cripto.verificarPassword(password, fila.first)) {
            throw ErrorNegocio(403, "Contrasena incorrecta.")
        }
        if (!fila.second) {
            throw ErrorNegocio(
                403,
                "Solo el dispositivo principal puede autorizar otros. Hazlo desde el " +
                    "telefono con el que creaste la cuenta.",
            )
        }
        // El segundo factor tambien, si la cuenta lo tiene. Es coherente: es la
        // accion que mas acceso reparte.
        Identidad.exigirSegundoFactor(c, yo.usuarioId, null)

        val cuantos = activos(c, yo.usuarioId)
        if (cuantos >= MAX_DISPOSITIVOS) {
            throw ErrorNegocio(
                409,
                "Ya tienes $MAX_DISPOSITIVOS dispositivos. Revoca uno antes de agregar otro.",
            )
        }

        Limitador.exigir(
            yo.usuarioId, yo.usuarioId.toString(), "vincular", Limitador.EMITIR_VINCULACION,
        )

        // Los codigos vivos anteriores se queman: dos codigos validos a la vez
        // duplican la superficie sin ninguna ventaja.
        c.prepareStatement(
            "UPDATE codigo_vinculacion SET usado_en = now() WHERE usuario_id = ? AND usado_en IS NULL"
        ).use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }

        val codigo = nuevoCodigo()
        c.prepareStatement(
            """INSERT INTO codigo_vinculacion (usuario_id, emisor_id, codigo_hash, expira_en)
               VALUES (?, ?, ?, now() + make_interval(secs => ?))"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setObject(2, yo.dispositivoId)
            st.setBytes(3, Cripto.hashToken(codigo))
            st.setDouble(4, VIDA_CODIGO.seconds.toDouble())
            st.executeUpdate()
        }

        Seguridad.anotar(c, yo.usuarioId, "dispositivo_nuevo", detalle = """{"accion":"codigo_emitido"}""")
        Autz.auditar(c, yo.usuarioId, "dispositivo.codigo_emitido", "usuario", yo.usuarioId)

        CodigoVinculacion(
            codigo = codigo,
            expiraEnSegundos = VIDA_CODIGO.seconds.toInt(),
            emitidoPor = fila.third,
        )
    }

    /** Cuatro y cuatro, separados: `K7M2-9PQR` se copia mejor que `K7M29PQR`. */
    private fun nuevoCodigo(): String {
        val c = (1..LARGO).map { ABC[rnd.nextInt(ABC.length)] }.joinToString("")
        return c.substring(0, 4) + "-" + c.substring(4)
    }

    // ============================================================
    //  J.2 · Consumir el codigo
    // ============================================================

    /**
     * Vincula este aparato. **Sin sesion**: todavia no tiene ninguna.
     *
     * La regla de "un hardware, una cuenta" sigue en pie aqui: vincular no es
     * una puerta lateral para meter una segunda cuenta en el mismo telefono.
     * Lo garantiza el indice unico de la base, y se comprueba antes para poder
     * dar un mensaje legible en vez de un 500.
     */
    fun vincular(req: VincularReq, ip: String, agente: String?): Pair<VincularHechoResp, List<Pair<UUID, Bajada.Evento>>> =
        Db.tx { c ->
            val user = req.username.lowercase().trim()
            Limitador.exigir(null, ip, "vincular_ip", Limitador.CONSUMIR_VINCULACION)

            val usuarioId = c.prepareStatement(
                "SELECT id FROM usuario WHERE username = ? AND desactivado_en IS NULL"
            ).use { st ->
                st.setString(1, user)
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
            // Mismo mensaje que un codigo equivocado: si dijera "ese usuario no
            // existe", esta ruta serviria para averiguar que cuentas hay.
            } ?: throw ErrorNegocio(400, "El codigo no existe o ya vencio.")

            val vinculacion = canjear(c, usuarioId, req.codigo)

            val hwHash = Base64Util.dec(req.hardwareHash)
            val yaUsado = c.prepareStatement(
                """SELECT u.username FROM dispositivo d JOIN usuario u ON u.id = d.usuario_id
                   WHERE d.hardware_hash = ? AND d.revocado_en IS NULL"""
            ).use { st ->
                st.setBytes(1, hwHash)
                st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
            }
            if (yaUsado != null) {
                throw ErrorNegocio(
                    409,
                    if (yaUsado.equals(user, true)) {
                        "Este telefono ya esta vinculado a esta cuenta."
                    } else {
                        "Este telefono ya tiene una cuenta. Un hardware, una cuenta."
                    },
                )
            }

            if (activos(c, usuarioId) >= MAX_DISPOSITIVOS) {
                throw ErrorNegocio(409, "Esa cuenta ya tiene $MAX_DISPOSITIVOS dispositivos.")
            }

            val atestacion = Atestacion.evaluar(c, req.atestacion, Repo.nivelParaVincular(req.hardwareNivel))
            val nivel = atestacion.nivel
            val nuevoId = c.prepareStatement(
                """INSERT INTO dispositivo
                     (usuario_id, etiqueta, identidad_pub, hardware_hash, hardware_nivel,
                      principal, vinculado_por)
                   VALUES (?, ?, ?, ?, ?, false, ?) RETURNING id"""
            ).use { st ->
                st.setObject(1, usuarioId)
                st.setString(2, req.etiquetaDispositivo.take(64))
                st.setBytes(3, Base64Util.dec(req.identidadPub))
                st.setBytes(4, hwHash)
                st.setString(5, nivel)
                st.setObject(6, vinculacion)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
            Atestacion.anotar(c, nuevoId, atestacion)

            c.prepareStatement(
                "UPDATE codigo_vinculacion SET usado_por = ? WHERE usuario_id = ? AND usado_en IS NOT NULL AND usado_por IS NULL"
            ).use { st -> st.setObject(1, nuevoId); st.setObject(2, usuarioId); st.executeUpdate() }

            val token = Repo.emitirTokenPara(c, nuevoId, ip, agente)

            Seguridad.anotar(
                c, usuarioId, "dispositivo_nuevo", ip = ip, agente = agente,
                // Ver la nota de `Autz.detalleDe`: la etiqueta la escribe
                // quien vincula y no hay forma de interpolarla sin riesgo.
                detalle = Autz.detalleDe("etiqueta" to req.etiquetaDispositivo.take(40)),
            )
            Autz.auditar(c, usuarioId, "dispositivo.vinculado", "dispositivo", nuevoId)

            // Se avisa a los demas dispositivos. Un aparato nuevo en tu cuenta
            // que aparece sin que nadie te lo diga es exactamente lo que no
            // debe poder pasar en silencio.
            val avisos = Eventos.emitir(
                c, listOf(usuarioId), "dispositivo_vinculado", null, user,
                req.etiquetaDispositivo.take(64),
            ).filter { it.first != nuevoId }

            val total = activos(c, usuarioId)
            VincularHechoResp(
                token = token,
                usuarioId = usuarioId.toString(),
                dispositivoId = nuevoId.toString(),
                username = user,
                dispositivos = total,
            ) to avisos
        }

    /** Devuelve el dispositivo emisor si el codigo valia. Cuenta el intento si no. */
    private fun canjear(c: Connection, usuarioId: UUID, codigo: String): UUID {
        val fila = c.prepareStatement(
            """SELECT id, emisor_id, codigo_hash, intentos
               FROM codigo_vinculacion
               WHERE usuario_id = ? AND usado_en IS NULL AND expira_en > now()
               ORDER BY creado_en DESC LIMIT 1
               FOR UPDATE"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs ->
                rs.primero {
                    listOf(
                        it.getObject(1, UUID::class.java),
                        it.getObject(2, UUID::class.java),
                        it.getBytes(3),
                        it.getInt(4),
                    )
                }
            }
        } ?: throw ErrorNegocio(400, "El codigo no existe o ya vencio.")

        @Suppress("UNCHECKED_CAST")
        val id = fila[0] as UUID
        val emisor = fila[1] as UUID
        val hash = fila[2] as ByteArray
        val intentos = fila[3] as Int

        if (intentos >= MAX_INTENTOS) {
            c.prepareStatement("UPDATE codigo_vinculacion SET usado_en = now() WHERE id = ?")
                .use { st -> st.setObject(1, id); st.executeUpdate() }
            throw ErrorNegocio(429, "Demasiados intentos. Pide un codigo nuevo.")
        }

        // Se normaliza el guion y las minusculas: el codigo se muestra con
        // guion y la gente lo teclea como puede.
        val limpio = codigo.trim().uppercase().replace("-", "").replace(" ", "")
        val conGuion = if (limpio.length == LARGO) {
            limpio.substring(0, 4) + "-" + limpio.substring(4)
        } else {
            limpio
        }

        if (!MessageDigest.isEqual(Cripto.hashToken(conGuion), hash)) {
            c.prepareStatement("UPDATE codigo_vinculacion SET intentos = intentos + 1 WHERE id = ?")
                .use { st -> st.setObject(1, id); st.executeUpdate() }
            throw ErrorNegocio(400, "Ese codigo no coincide. Te quedan ${MAX_INTENTOS - intentos - 1} intentos.")
        }

        c.prepareStatement("UPDATE codigo_vinculacion SET usado_en = now() WHERE id = ?")
            .use { st -> st.setObject(1, id); st.executeUpdate() }
        return emisor
    }

    // ============================================================
    //  Listar y revocar
    // ============================================================

    fun listar(yo: Auth): List<DispositivoInfo> = Db.query { c ->
        c.prepareStatement(
            """SELECT d.id, d.etiqueta, d.principal, d.hardware_nivel,
                      (EXTRACT(EPOCH FROM d.registrado_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM d.ultimo_visto_en) * 1000)::bigint,
                      v.etiqueta,
                      d.registration_id <> 0,
                      d.push_token IS NOT NULL
               FROM dispositivo d
                 LEFT JOIN dispositivo v ON v.id = d.vinculado_por
               WHERE d.usuario_id = ? AND d.revocado_en IS NULL
               ORDER BY d.principal DESC, d.registrado_en"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    val id = it.getObject(1, UUID::class.java)
                    DispositivoInfo(
                        id = id.toString(),
                        etiqueta = it.getString(2),
                        principal = it.getBoolean(3),
                        esEste = id == yo.dispositivoId,
                        nivelHardware = it.getString(4),
                        registradoEn = it.getLong(5),
                        ultimoVistoEn = it.getLong(6).takeIf { v -> v > 0 },
                        vinculadoPor = it.getString(7),
                        // Sin claves publicadas un dispositivo NO puede recibir
                        // nada: los demas no tienen con que cifrarle. Verlo en
                        // la lista explica por que uno recien vinculado
                        // todavia no ve mensajes.
                        tieneClaves = it.getBoolean(8),
                        recibeAvisos = it.getBoolean(9),
                    )
                }
            }
        }
    }

    /**
     * Revoca un dispositivo. Mata sus sesiones y le avisa.
     *
     * El principal no se puede revocar a si mismo: dejaria la cuenta sin quien
     * autorice dispositivos nuevos, y no hay forma de recuperar eso. Para
     * cambiar de principal hay que promover otro primero.
     */
    fun revocar(yo: Auth, objetivoId: UUID): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val fila = c.prepareStatement(
            """SELECT principal, etiqueta FROM dispositivo
               WHERE id = ? AND usuario_id = ? AND revocado_en IS NULL"""
        ).use { st ->
            st.setObject(1, objetivoId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) to it.getString(2) } }
        } ?: throw ErrorNegocio(404, "Ese dispositivo no existe o ya estaba revocado.")

        if (fila.first) {
            throw ErrorNegocio(
                409,
                "No puedes revocar el dispositivo principal: quedaria sin quien autorice " +
                    "otros. Promueve otro a principal primero.",
            )
        }

        // El aviso se calcula ANTES de revocar: despues, el dispositivo ya no
        // aparece como activo y `Eventos.emitir` no le crearia la fila.
        val avisos = Eventos.emitir(
            c, listOf(yo.usuarioId), "dispositivo_revocado", null, yo.username, fila.second,
        )

        c.prepareStatement(
            "UPDATE dispositivo SET revocado_en = now(), revocado_por = ? WHERE id = ?"
        ).use { st -> st.setObject(1, yo.dispositivoId); st.setObject(2, objetivoId); st.executeUpdate() }

        c.prepareStatement(
            "UPDATE sesion SET revocado_en = now() WHERE dispositivo_id = ? AND revocado_en IS NULL"
        ).use { st -> st.setObject(1, objetivoId); st.executeUpdate() }

        // Las prekeys del dispositivo muerto se van con el: dejarlas permitiria
        // que alguien abriera una sesion contra un aparato que ya no existe y
        // los mensajes se perderian sin error.
        c.prepareStatement("DELETE FROM prekey_unica WHERE dispositivo_id = ?")
            .use { st -> st.setObject(1, objetivoId); st.executeUpdate() }

        Seguridad.anotar(
            c, yo.usuarioId, "dispositivo_revocado",
            detalle = Autz.detalleDe("etiqueta" to fila.second.take(40)),
        )
        Autz.auditar(c, yo.usuarioId, "dispositivo.revocado", "dispositivo", objetivoId)
        avisos
    }

    /**
     * Promueve un dispositivo a principal. Exige la contrasena.
     *
     * Las dos actualizaciones van en una transaccion y el indice unico parcial
     * de la base garantiza que no queden dos principales: si dos promociones
     * corrieran a la vez, una de las dos falla en vez de dejar el estado roto.
     */
    fun promover(yo: Auth, objetivoId: UUID, password: String) = Db.tx { c ->
        val hash = c.prepareStatement("SELECT password_hash FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Cuenta no encontrada.")
        if (!Cripto.verificarPassword(password, hash)) {
            throw ErrorNegocio(403, "Contrasena incorrecta.")
        }

        val nivel = c.prepareStatement(
            "SELECT hardware_nivel FROM dispositivo WHERE id = ? AND usuario_id = ? AND revocado_en IS NULL"
        ).use { st ->
            st.setObject(1, objetivoId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Ese dispositivo no existe.")
        // Lo garantiza tambien la base (V49), pero aqui se dice por que.
        if (nivel == NivelHardware.NAVEGADOR) {
            throw ErrorNegocio(409, "Un navegador no puede ser el aparato principal.")
        }

        c.prepareStatement(
            "UPDATE dispositivo SET principal = false WHERE usuario_id = ? AND principal"
        ).use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }
        c.prepareStatement("UPDATE dispositivo SET principal = true WHERE id = ?")
            .use { st -> st.setObject(1, objetivoId); st.executeUpdate() }

        Autz.auditar(c, yo.usuarioId, "dispositivo.promovido", "dispositivo", objetivoId)
        Unit
    }

    // ============================================================
    //  J.4 · Historial
    // ============================================================

    /**
     * Pide historial a los otros dispositivos de la misma persona.
     *
     * El servidor **no puede responder esto**: nunca tuvo el historial. Lo
     * unico que hace es repartir el aviso, porque es el que sabe quien esta
     * conectado. Quien responde manda los mensajes por el buzon normal,
     * cifrados, con `Carga.Historial`.
     */
    fun pedirHistorial(yo: Auth): Pair<EstadoHistorial, List<Pair<UUID, Bajada.Evento>>> = Db.tx { c ->
        val otros = c.prepareStatement(
            """SELECT count(*) FROM dispositivo
               WHERE usuario_id = ? AND revocado_en IS NULL AND id <> ?
                 AND registration_id <> 0"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setObject(2, yo.dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }

        val yaRecibido = c.prepareStatement(
            "SELECT coalesce(sum(cuantos), 0)::int FROM historial_enviado WHERE destino_id = ?"
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }

        val estado = EstadoHistorial(
            recibido = yaRecibido > 0,
            cuantos = yaRecibido,
            hayQuienResponda = otros > 0,
        )

        if (otros == 0) return@tx estado to emptyList()

        // El aviso va a los OTROS dispositivos, no a todos: pedirse historial a
        // uno mismo no tiene sentido y el emisor lo descartaria igual.
        val avisos = Eventos.emitir(
            c, listOf(yo.usuarioId), "historial_pedido", null, yo.username,
            yo.dispositivoId.toString(),
        ).filter { it.first != yo.dispositivoId }

        estado to avisos
    }

    /** El que reenvia anota que lo hizo, para no repetirlo en cada arranque. */
    fun anotarHistorialEnviado(yo: Auth, destinoId: UUID, cuantos: Int) = Db.tx { c ->
        val mismoDueno = c.prepareStatement(
            "SELECT 1 FROM dispositivo WHERE id = ? AND usuario_id = ? AND revocado_en IS NULL"
        ).use { st ->
            st.setObject(1, destinoId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { it.next() }
        }
        // Solo entre dispositivos de la MISMA cuenta. Sin esta comprobacion,
        // cualquiera podria marcar historial como enviado a un aparato ajeno.
        if (!mismoDueno) throw ErrorNegocio(403, "Ese dispositivo no es tuyo.")

        c.prepareStatement(
            """INSERT INTO historial_enviado (origen_id, destino_id, cuantos)
               VALUES (?, ?, ?)
               ON CONFLICT (origen_id, destino_id)
                 DO UPDATE SET cuantos = historial_enviado.cuantos + excluded.cuantos,
                               enviado_en = now()"""
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.setObject(2, destinoId)
            st.setInt(3, cuantos)
            st.executeUpdate()
        }
        Unit
    }

    // ============================================================
    //  Utilidades
    // ============================================================

    private fun activos(c: Connection, usuarioId: UUID): Int =
        c.prepareStatement(
            "SELECT count(*) FROM dispositivo WHERE usuario_id = ? AND revocado_en IS NULL"
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }

    /** Barre codigos vencidos. La llama la tarea periodica. */
    fun barrerCodigos(c: Connection): Int =
        c.prepareStatement("DELETE FROM codigo_vinculacion WHERE expira_en < now() - interval '1 day'")
            .use { it.executeUpdate() }
}
