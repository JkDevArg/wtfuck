package com.wtfuck.server

import com.wtfuck.protocol.*
import java.sql.Connection
import java.util.UUID

class ErrorNegocio(val codigo: Int, val motivo: String) : Exception(motivo)

/**
 * Quien pregunta.
 *
 * Lleva `sesionId` desde el modulo I: sin el, la lista de sesiones no puede
 * marcar cual es la propia, y "cerrar las otras" no sabria cual dejar viva.
 */
data class Auth(
    val usuarioId: UUID,
    val dispositivoId: UUID,
    val username: String,
    val sesionId: UUID,
)

/**
 * Los datos de la conversacion misma, los que no dependen de quien pregunta.
 *
 * Era un `Triple` y dejo de caber al sumarle el temporizador. Con nombres se
 * lee `meta.temporalesSegundos` en vez de `meta.fourth`, que es el punto en que
 * una tupla deja de ayudar.
 */
private data class MetaConv(
    val tipo: String,
    val nombre: String?,
    val expiraEn: Long,
    val temporalesSegundos: Int?,
)

/** Rol y preferencias del usuario que consulta, dentro de una conversacion. */
private data class MiEstado(
    val rol: String,
    val jerarquia: Int,
    val silenciado: Long,
    val archivado: Boolean,
    val fijado: Boolean,
)

/** Un destino de entrega: dispositivo activo de un participante. */
data class Destino(val dispositivoId: UUID, val usuarioId: UUID)

/**
 * Resultado de una operacion de grupo.
 *
 * `avisos` son los eventos ya guardados en la base, listos para empujar a quien
 * este conectado. Se devuelven en vez de empujarse desde aqui porque el Repo no
 * conoce el Hub de sockets: esa es responsabilidad de la capa de rutas.
 */
data class ResultadoGrupo(
    val resumen: ConversacionResumen,
    val avisos: List<Pair<UUID, Bajada.Evento>>,
)

object Repo {

    private const val DIAS_SESION = 90L

    // ============================================================
    //  Registro y sesion
    // ============================================================

    fun registrar(r: RegistroReq, ip: String? = null, agente: String? = null): SesionResp = Db.tx { c ->
        val user = r.username.lowercase().trim()
        // La misma regla que usa el cliente para decidir si puede pintar un
        // `@` delante de lo que le mandaron. Dos copias del mismo patron se
        // separan sin que nadie se entere.
        if (!FORMA_USERNAME.matches(user)) {
            throw ErrorNegocio(400, "El usuario debe tener 3-24 caracteres: letras, numeros o guion bajo.")
        }
        if (user in USUARIOS_RESERVADOS) {
            throw ErrorNegocio(400, "Ese usuario no esta disponible.")
        }
        if (r.password.length < 8) {
            throw ErrorNegocio(400, "La contrasena debe tener al menos 8 caracteres.")
        }
        if (r.hardwareNivel !in setOf("STRONGBOX", "TEE", "SOFTWARE_DEV")) {
            throw ErrorNegocio(400, "Nivel de hardware desconocido.")
        }
        // El servidor decide, no el cliente: en release no se aceptan dispositivos
        // sin atestacion valida. Ver docs/04-DEVICE-BINDING.md
        if (r.hardwareNivel == "SOFTWARE_DEV" && !Config.permitirSoftwareDev) {
            throw ErrorNegocio(403, "Este dispositivo no puede acreditar hardware seguro.")
        }

        val hwHash = Base64Util.dec(r.hardwareHash)
        val identidad = Base64Util.dec(r.identidadPub)

        c.prepareStatement("SELECT 1 FROM usuario WHERE username = ?").use { st ->
            st.setString(1, user)
            st.executeQuery().use { if (it.next()) throw ErrorNegocio(409, "Ese usuario ya existe.") }
        }
        // Un hardware, una cuenta. El indice unico parcial tambien lo garantiza;
        // esta consulta solo permite dar un mensaje legible en vez de un 500.
        c.prepareStatement(
            "SELECT 1 FROM dispositivo WHERE hardware_hash = ? AND revocado_en IS NULL"
        ).use { st ->
            st.setBytes(1, hwHash)
            st.executeQuery().use {
                if (it.next()) throw ErrorNegocio(409, "Este dispositivo ya tiene una cuenta registrada.")
            }
        }

        // La invitacion se canjea DENTRO de esta transaccion y antes de crear
        // nada. Si el alta falla despues —un username ya cogido— la reserva se
        // deshace con el resto, y el codigo no queda gastado por una cuenta
        // que nunca existio.
        //
        // Se comprueba aqui y no en la ruta porque aqui es donde hay
        // transaccion: en la ruta seria una comprobacion suelta, y entre ella
        // y el INSERT cabe otro registro.
        val invitacion = if (Invitaciones.exigeInvitacion && !Invitaciones.entraSinInvitacion(user)) {
            Invitaciones.canjear(c, r.codigoInvitacion)
        } else {
            null
        }

        val usuarioId = c.prepareStatement(
            "INSERT INTO usuario (username, password_hash) VALUES (?, ?) RETURNING id"
        ).use { st ->
            st.setString(1, user)
            st.setString(2, Cripto.hashPassword(r.password))
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        // El dispositivo del registro es el PRINCIPAL: es el unico que puede
        // autorizar otros. Si cualquiera pudiera, robar un secundario
        // alcanzaria para vincular mas y la cuenta no se recuperaria nunca.
        val dispositivoId = c.prepareStatement(
            """INSERT INTO dispositivo
                 (usuario_id, etiqueta, identidad_pub, hardware_hash, hardware_nivel, principal)
               VALUES (?, ?, ?, ?, ?, true) RETURNING id"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setString(2, r.etiquetaDispositivo.take(64))
            st.setBytes(3, identidad)
            st.setBytes(4, hwHash)
            st.setString(5, r.hardwareNivel)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        // Quien entro con cada codigo. Despues del INSERT, que es cuando ya
        // hay un id al que apuntar.
        invitacion?.let { Invitaciones.anotarUso(c, it, usuarioId) }

        // El propietario queda con su nivel AL REGISTRARSE, no solo al
        // arrancar el servidor.
        //
        // `Panel.sembrarPropietario` corre en el arranque y promueve a la
        // cuenta si existe. En un servidor recien montado no existe todavia:
        // se registra despues, y se queda sin nivel hasta el siguiente
        // reinicio. En modo abierto eso es una molestia; en modo invitacion es
        // un bloqueo, porque repartir codigos exige ser administrador y sin
        // reiniciar no hay forma de empezar.
        //
        // Se encontro montandolo de cero: el propietario entraba y despues
        // recibia 404 al crear la primera invitacion.
        if (Invitaciones.entraSinInvitacion(user)) {
            c.prepareStatement(
                "UPDATE usuario SET staff_nivel = ? WHERE id = ? AND staff_nivel < ?"
            ).use { st ->
                st.setInt(1, Moderacion.PROPIETARIO)
                st.setObject(2, usuarioId)
                st.setInt(3, Moderacion.PROPIETARIO)
                st.executeUpdate()
            }
        }

        SesionResp(emitirToken(c, dispositivoId, ip, agente), usuarioId.toString(), dispositivoId.toString(), user)
    }

    fun login(r: SesionReq, ip: String? = null, agente: String? = null): SesionResp = Db.tx { c ->
        val user = r.username.lowercase().trim()
        val fila = c.prepareStatement(
            "SELECT id, password_hash FROM usuario WHERE username = ? AND desactivado_en IS NULL"
        ).use { st ->
            st.setString(1, user)
            st.executeQuery().use { rs ->
                rs.primero { it.getObject(1, UUID::class.java) to it.getString(2) }
            }
        } ?: throw ErrorNegocio(401, "Usuario o contrasena incorrectos.")

        if (!Cripto.verificarPassword(r.password, fila.second)) {
            throw ErrorNegocio(401, "Usuario o contrasena incorrectos.")
        }

        // El segundo factor se comprueba DESPUES de la contrasena y antes de
        // cualquier otra cosa. Antes de la contrasena seria un oraculo de
        // "esta cuenta tiene 2FA"; despues del vinculo de hardware daria dos
        // mensajes de error distintos para el mismo intento fallido.
        Identidad.exigirSegundoFactor(c, fila.first, r.totp)

        val hwHash = Base64Util.dec(r.hardwareHash)

        // Se busca EL dispositivo de ESTE hardware, no el primero de la cuenta.
        //
        // Antes del modulo J daba lo mismo: habia uno solo. Con varios, tomar
        // el primero y comparar su hardware habria hecho que ingresar desde el
        // segundo telefono fallara segun el orden de las filas, que es la clase
        // de fallo que parece aleatorio.
        val dispositivoId = c.prepareStatement(
            """SELECT id FROM dispositivo
               WHERE usuario_id = ? AND hardware_hash = ? AND revocado_en IS NULL"""
        ).use { st ->
            st.setObject(1, fila.first); st.setBytes(2, hwHash)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: run {
            // Se distingue el caso para poder decir algo util: si la cuenta
            // tiene dispositivos pero ninguno es este, lo que falta es
            // VINCULAR, y decirlo ahorra el soporte de "no me deja entrar".
            val tiene = c.prepareStatement(
                "SELECT count(*) FROM dispositivo WHERE usuario_id = ? AND revocado_en IS NULL"
            ).use { st ->
                st.setObject(1, fila.first)
                st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
            }
            throw ErrorNegocio(
                403,
                if (tiene > 0) {
                    "Este telefono no esta vinculado a la cuenta. Pide un codigo de " +
                        "vinculacion desde tu dispositivo principal."
                } else {
                    "La cuenta no tiene dispositivo activo."
                },
            )
        }

        // Entrar cancela una eliminacion pendiente. Es deliberado que no haya
        // un boton de "cancelar": quien se arrepiente intenta entrar, y asi se
        // evita el caso absurdo de una cuenta que no deja ingresar para poder
        // cancelar la eliminacion que impide ingresar.
        Identidad.cancelarEliminacionSiHay(c, fila.first)

        SesionResp(
            emitirToken(c, dispositivoId, ip, agente),
            fila.first.toString(),
            dispositivoId.toString(),
            user,
        )
    }

    private fun emitirToken(
        c: Connection,
        dispositivoId: UUID,
        ip: String? = null,
        agente: String? = null,
    ): String {
        val token = Cripto.nuevoToken()
        c.prepareStatement(
            """INSERT INTO sesion (dispositivo_id, token_hash, expira_en, ip, agente, ultimo_uso_en)
               VALUES (?, ?, now() + make_interval(days => ?), ?::inet, ?, now())"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.setBytes(2, Cripto.hashToken(token))
            st.setInt(3, DIAS_SESION.toInt())
            // De donde y con que se abrio. Sin esto, la gestion de sesiones es
            // una lista de identificadores que no permite decidir nada.
            //
            // Pasa por `ipValida` porque `remoteHost` puede traer un NOMBRE, y
            // 'localhost'::inet es un error de Postgres, no un null.
            st.setString(4, Seguridad.ipValida(ip))
            st.setString(5, agente?.take(200))
            st.executeUpdate()
        }
        return token
    }

    /**
     * Emite un token para un dispositivo concreto, dentro de una transaccion
     * que ya esta abierta.
     *
     * Existe para que `Dispositivos.vincular` pueda dar la sesion del aparato
     * nuevo sin duplicar la logica del token ni abrir una segunda conexion:
     * el dispositivo se crea y su sesion nace en la MISMA transaccion, asi que
     * no puede quedar un dispositivo sin sesion si algo falla en medio.
     */
    fun emitirTokenPara(c: Connection, dispositivoId: UUID, ip: String?, agente: String?): String =
        emitirToken(c, dispositivoId, ip, agente)

    /** Valida el nivel de hardware declarado. Lo usan el registro y la vinculacion. */
    fun nivelPermitido(nivel: String): String {
        if (nivel !in setOf("STRONGBOX", "TEE", "SOFTWARE_DEV")) {
            throw ErrorNegocio(400, "Nivel de hardware desconocido.")
        }
        if (nivel == "SOFTWARE_DEV" && !Config.permitirSoftwareDev) {
            throw ErrorNegocio(403, "Este dispositivo no tiene enclave seguro.")
        }
        return nivel
    }

    fun autenticar(token: String): Auth? = Db.query { c ->
        c.prepareStatement(
            """SELECT d.usuario_id, d.id, u.username, s.id
               FROM sesion s
                 JOIN dispositivo d ON d.id = s.dispositivo_id
                 JOIN usuario u     ON u.id = d.usuario_id
               WHERE s.token_hash = ? AND s.revocado_en IS NULL AND s.expira_en > now()
                 AND d.revocado_en IS NULL"""
        ).use { st ->
            st.setBytes(1, Cripto.hashToken(token))
            st.executeQuery().use { rs ->
                rs.primero {
                    Auth(
                        usuarioId = it.getObject(1, UUID::class.java),
                        dispositivoId = it.getObject(2, UUID::class.java),
                        username = it.getString(3),
                        sesionId = it.getObject(4, UUID::class.java),
                    )
                }
            }
        }
    }

    /**
     * Marca que esta sesion se uso.
     *
     * Va aparte de `autenticar` y sin transaccion: es un UPDATE por peticion y
     * no debe poder hacer fallar la peticion. Lo que gana es que la lista de
     * sesiones pueda distinguir una viva de una que quedo abierta hace meses,
     * que es justamente lo que hace falta para decidir cual cerrar.
     */
    fun tocarSesion(sesionId: UUID) {
        runCatching {
            Db.query { c ->
                c.prepareStatement("UPDATE sesion SET ultimo_uso_en = now() WHERE id = ?")
                    .use { st -> st.setObject(1, sesionId); st.executeUpdate() }
            }
        }
    }

    /**
     * Marca que esta persona estuvo activa ahora.
     *
     * No lanza: la presencia es un dato accesorio y un fallo al escribirla no
     * puede tumbar una conexion de socket que por lo demas funciona.
     */
    fun tocarPresencia(usuarioId: UUID) {
        runCatching {
            Db.tx { c ->
                c.prepareStatement("UPDATE usuario SET ultima_vez = now() WHERE id = ?")
                    .use { st -> st.setObject(1, usuarioId); st.executeUpdate() }
            }
        }
    }

    fun tocarDispositivo(dispositivoId: UUID) = Db.query { c ->
        c.prepareStatement("UPDATE dispositivo SET ultimo_visto_en = now() WHERE id = ?").use {
            it.setObject(1, dispositivoId); it.executeUpdate()
        }
    }

    // ============================================================
    //  Usuarios
    // ============================================================

    /**
     * Busca a alguien por su username.
     *
     * L.1: respeta `priv_busqueda`. Quien se oculto de la busqueda responde
     * como si no existiera -404 y no 403- porque un 403 seria un oraculo: "no
     * puedo decirte nada de esta persona" ya confirma que la persona esta.
     *
     * Ocultarse de la busqueda NO corta las conversaciones que ya existen: la
     * lista de participantes y los chats abiertos van por otro camino.
     */
    /**
     * Una pagina del directorio de Usuarios. Ver V44.
     *
     * Solo quien marco "aparecer en Usuarios", y de esos solo quien ademas:
     *
     *  - sigue activo: ni desactivado, ni suspendido, ni con la eliminacion
     *    pedida -quien pidio irse no quiere que lo encuentren mientras tanto-;
     *  - no me bloqueo ni lo bloquee yo;
     *  - me deja encontrarlo (`priv_busqueda`): si no, tocarlo en la lista
     *    abriria un perfil que responde 404, y la lista mentiria.
     *
     * Cada fila pasa por [leerPublico], asi que la foto, el nombre y la
     * biografia salen con las mismas reglas que en el perfil: estar en la
     * lista no es mostrar lo que la persona reservo para sus conocidos.
     *
     * Paginacion por clave (`desde` = el ultimo username visto) y no por
     * OFFSET: con OFFSET, alguien que entra o sale de la lista mientras se
     * recorre corre todo un lugar y se pierde o se repite una persona.
     */
    fun directorio(yo: Auth, consulta: String, desde: String): DirectorioResp = Db.query { c ->
        val q = consulta.trim().removePrefix("@").lowercase().take(32)
        // Un `%` o un `_` escritos a mano no son comodines: se escapan.
        val patron = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val conocidos = quienesMeConocen(c, yo.usuarioId)
        val listado = excepcionesQueMeIncluyen(c, yo.usuarioId)
        val comparte = comparteUltimaVez(c, yo.usuarioId)
        val pagina = 30
        val filas = c.prepareStatement(
            """SELECT $COLS_PUBLICO, u.priv_busqueda
               $FROM_PUBLICO
               WHERE u.priv_directorio
                 AND u.desactivado_en IS NULL
                 AND u.eliminacion_pedida_en IS NULL
                 AND NOT (u.suspendido_en IS NOT NULL
                          AND (u.suspendido_hasta IS NULL OR u.suspendido_hasta > now()))
                 AND u.id <> ?
                 AND u.username > ?
                 AND (? = '' OR u.username LIKE ? OR lower(coalesce(u.nombre_mostrado, '')) LIKE ?)
                 AND NOT EXISTS (
                       SELECT 1 FROM bloqueo b
                       WHERE (b.bloqueador_id = u.id AND b.bloqueado_id = ?)
                          OR (b.bloqueador_id = ? AND b.bloqueado_id = u.id))
               ORDER BY u.username
               LIMIT ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, desde.lowercase())
            st.setString(3, q)
            st.setString(4, patron)
            st.setString(5, patron)
            st.setObject(6, yo.usuarioId)
            st.setObject(7, yo.usuarioId)
            // Se piden de mas porque `priv_busqueda` se filtra despues, con
            // las excepciones ya resueltas para esta peticion.
            st.setInt(8, pagina * 2)
            st.executeQuery().use { rs ->
                val out = mutableListOf<Pair<String, UsuarioPublico?>>()
                while (rs.next()) {
                    val id = rs.getObject(1, UUID::class.java)
                    val username = rs.getString(2)
                    val conocido = id in conocidos
                    val visible = permiteCon(
                        nivel = rs.getString(25),
                        conocido = conocido,
                        modo = modoDe(rs.getString(14), "busqueda"),
                        enLaLista = (id to "busqueda") in listado,
                    )
                    out += username to if (!visible) null else leerPublico(
                        rs, conocido = conocido, esMio = false,
                        observadorComparte = comparte, listado = listado,
                    )
                }
                out
            }
        }
        val todos = filas.mapNotNull { it.second }
        val visibles = todos.take(pagina)
        // Donde sigue la proxima pagina:
        //  - si sobraron visibles, desde el ultimo MOSTRADO (no el ultimo
        //    leido: se saltarian los que sobraron);
        //  - si no sobraron pero la consulta trajo la tanda entera, desde el
        //    ultimo leido: lo descartado por privacidad tambien avanza;
        //  - si trajo menos, no hay mas.
        val siguiente = when {
            todos.size > pagina -> visibles.last().username
            filas.size >= pagina * 2 -> filas.last().first
            else -> null
        }
        DirectorioResp(visibles, siguiente)
    }

    fun buscar(yo: Auth, username: String): UsuarioPublico? = Db.query { c ->
        val u = publicoPorUsername(c, username, yo.usuarioId) ?: return@query null
        val id = runCatching { UUID.fromString(u.usuarioId) }.getOrNull() ?: return@query null
        if (!buscable(c, id, yo.usuarioId)) null else u
    }

    /**
     * Columnas del perfil publico.
     *
     * `d.id` y `d.identidad_pub` son del dispositivo PRINCIPAL, y desde el
     * modulo J eso es una simplificacion declarada: una persona puede tener
     * varios, cada uno con su propia identidad. Para cifrar NO se usan estas
     * columnas -se usa `Claves.destinos`, que devuelve todos-; aqui estan solo
     * para que la interfaz tenga algo que mostrar.
     *
     * Van por LEFT JOIN LATERAL con LIMIT 1, y las dos partes importan: el
     * LATERAL con limite evita que la misma persona aparezca una vez por
     * dispositivo, y el LEFT evita que desaparezca cuando no tiene ninguno
     * activo -alguien que revoco su unico aparato y aun no vinculo otro-.
     */
    private const val COLS_PUBLICO =
        """u.id, u.username, d.id, d.identidad_pub,
           coalesce(u.nombre_mostrado,''), coalesce(u.estado_texto,''),
           coalesce(extract(epoch FROM u.avatar_actualizado) * 1000, 0),
           coalesce(extract(epoch FROM u.portada_actualizada) * 1000, 0),
           u.priv_foto, u.priv_estado, u.priv_nombre, u.priv_ultima_vez,
           coalesce(extract(epoch FROM u.ultima_vez) * 1000, 0),
           u.priv_modo::text,
           coalesce(u.biografia,''), u.priv_biografia,
           e.nombre_comercial, e.categoria, e.descripcion, e.sitio_web,
           e.tamano, e.ubicacion, e.fundada_en, e.verificada_en"""

    /**
     * El FROM que va con [COLS_PUBLICO], en una constante y no copiado.
     *
     * Las dos consultas de perfil publico lo compartian por copia, y ahora hay
     * un LEFT JOIN mas que **tiene que** estar en las dos: una tercera consulta
     * que se olvidara del join no fallaria al compilar, fallaria al leer la
     * columna 17 y devolveria perfiles sin ficha sin decir por que.
     *
     * El join lleva `u.tipo_cuenta = 'empresa'` dentro y no en el WHERE: una
     * ficha de quien ya no es empresa no se muestra aunque la fila siga ahi.
     * Hoy `elegirTipo` la borra, pero esto no depende de que siga haciendolo.
     */
    /**
     * El join de la ficha, solo. Las consultas de [COLS_PUBLICO] no comparten
     * el FROM entero -la de participantes arranca de `participante`-, asi que
     * lo que se comparte es esta linea.
     *
     * Y hace falta compartirla: olvidarla no rompe la compilacion, rompe la
     * LECTURA de la columna 17 en tiempo de ejecucion. Paso de verdad al
     * anadir la ficha: las dos consultas de perfil la llevaban y la de
     * participantes no, asi que abrir cualquier conversacion devolvia 500 y
     * dos suites se caian con un `.some is not a function` sobre el cuerpo del
     * error. El compilador no puede ver esto; una constante si.
     *
     * La condicion `u.tipo_cuenta = 'empresa'` va DENTRO del join y no en el
     * WHERE: una ficha de quien ya no es empresa no se muestra aunque la fila
     * siga ahi, y un WHERE ademas borraria de la lista a todo el que no sea
     * empresa.
     */
    private const val JOIN_EMPRESA =
        """LEFT JOIN perfil_empresa e
                 ON e.usuario_id = u.id AND u.tipo_cuenta = 'empresa'"""

    private const val FROM_PUBLICO =
        """FROM usuario u LEFT JOIN LATERAL (
                   SELECT id, identidad_pub FROM dispositivo
                   WHERE usuario_id = u.id AND revocado_en IS NULL
                   ORDER BY principal DESC, registrado_en
                   LIMIT 1
                 ) d ON true
               $JOIN_EMPRESA"""

    /**
     * Arma el perfil publico YA FILTRADO por la privacidad del dueno.
     *
     * El filtro se aplica aqui, al construir la respuesta, no en el cliente: si
     * el observador no puede ver la foto, la version sale en 0 y la app ni
     * siquiera llega a conocer la URL que tendria que pedir.
     */
    private fun leerPublico(
        rs: java.sql.ResultSet,
        conocido: Boolean,
        esMio: Boolean,
        /**
         * Si QUIEN PREGUNTA comparte su propia ultima conexion.
         *
         * Aqui esta la reciprocidad, y se resuelve en el servidor: quien oculta
         * la suya no ve la de nadie. Tratarlo en el cliente lo dejaria a un
         * `if` de distancia de convertirse en un espejo de una sola direccion.
         */
        observadorComparte: Boolean = true,
        /**
         * Los pares (persona, ajuste) donde el observador esta listado. Ver
         * [excepcionesQueMeIncluyen]: se resuelve una vez por peticion.
         */
        listado: Set<Pair<UUID, String>> = emptySet(),
    ): UsuarioPublico {
        val duenio = rs.getObject(1, UUID::class.java)
        val privFoto = rs.getString(9)
        val privEstado = rs.getString(10)
        val privNombre = rs.getString(11)
        val privUltima = rs.getString(12)
        // La 14, no la 13: la 13 es la marca de tiempo de `ultima_vez`.
        // Leyendo la columna equivocada, `modoDe` no encontraba nada en un
        // numero y devolvia su defecto -"salvo"-, con lo que una lista BLANCA
        // se comportaba como una lista negra: exactamente al reves. Lo
        // destapo la prueba del modo `solo`.
        val modos = rs.getString(14)
        fun ve(nivel: String, ajuste: String) = permiteCon(
            nivel = nivel,
            conocido = conocido,
            modo = modoDe(modos, ajuste),
            enLaLista = (duenio to ajuste) in listado,
        )
        val veFoto = esMio || ve(privFoto, "foto")
        val veUltima = esMio || (observadorComparte && ve(privUltima, "ultima_vez"))
        val ultima = if (veUltima) rs.getDouble(13).toLong() else 0L
        return UsuarioPublico(
            usuarioId = rs.getObject(1, UUID::class.java).toString(),
            username = rs.getString(2),
            // Pueden venir vacios: el LEFT JOIN deja a la persona en la lista
            // aunque no tenga ningun dispositivo activo. Antes era un JOIN y el
            // caso no existia; ahora existe y un NPE aqui tumbaria la lista de
            // participantes entera por una sola persona sin aparato.
            dispositivoId = rs.getObject(3, UUID::class.java)?.toString().orEmpty(),
            identidadPub = rs.getBytes(4)?.let { Base64Util.enc(it) }.orEmpty(),
            // Con el nombre oculto se devuelve vacio y la app cae al
            // username, que es lo que ya hacia cuando alguien no habia puesto
            // nombre: no hace falta un caso nuevo en la interfaz.
            nombreMostrado = if (esMio || ve(privNombre, "nombre")) rs.getString(5) else "",
            estadoTexto = if (esMio || ve(privEstado, "estado")) rs.getString(6) else "",
            avatarVersion = if (veFoto) rs.getDouble(7).toLong() else 0L,
            portadaVersion = if (veFoto) rs.getDouble(8).toLong() else 0L,
            ultimaVez = ultima,
            // "En linea" se deduce de la ultima vez y del Hub, no de una
            // columna aparte: una columna booleana se queda en true para
            // siempre en cuanto un proceso muera sin limpiarla.
            enLinea = veUltima && ultima > 0 &&
                Hub.conectado(rs.getObject(1, UUID::class.java)),
            // La biografia tiene su propio ajuste y no el del estado. Son dos
            // cosas: el estado es una frase que cambia cada semana, la
            // biografia dice quien sos y suele llevar donde trabajas.
            biografia = if (esMio || ve(rs.getString(16), "biografia")) rs.getString(15) else "",
            // La ficha NO pasa por `ve`, y es deliberado: declararse empresa es
            // una declaracion hacia afuera. Un ajuste para esconderla seria
            // pedir un modo publico y apagarlo; quien no la quiera publica
            // vuelve a cuenta personal, y entonces la ficha se borra.
            empresa = fichaDe(rs),
        )
    }

    /**
     * La ficha de empresa de una fila de [COLS_PUBLICO], o null si no hay.
     *
     * El LEFT JOIN deja las ocho columnas en NULL cuando no hay ficha, y
     * `nombre_comercial` es NOT NULL en la tabla: por eso ese es el que decide
     * si hay ficha. Preguntarlo por el tipo de cuenta seria leer una columna
     * que esta consulta no trae.
     */
    private fun fichaDe(rs: java.sql.ResultSet): FichaEmpresa? {
        val nombre = rs.getString(17) ?: return null
        return FichaEmpresa(
            nombreComercial = nombre,
            categoria = rs.getString(18) ?: CategoriaEmpresa.OTRA,
            descripcion = rs.getString(19).orEmpty(),
            sitioWeb = rs.getString(20).orEmpty(),
            tamano = rs.getString(21).orEmpty(),
            ubicacion = rs.getString(22).orEmpty(),
            // `getInt` de un NULL devuelve 0, que es justo lo que significa
            // "sin ano" en el contrato: no hace falta distinguirlo.
            fundadaEn = rs.getInt(23),
            // El distintivo es "hay fecha de verificacion", no una bandera
            // aparte: asi no puede existir una ficha verificada sin saber
            // cuando ni por quien.
            verificada = rs.getObject(24) != null,
        )
    }

    /**
     * Si quien pregunta comparte su propia ultima conexion.
     *
     * Es la mitad que hace reciproco el ajuste: `nadie` en el propio perfil
     * significa tambien "no veo la de nadie". Se consulta una vez por peticion
     * y no por fila.
     */
    fun comparteUltimaVez(c: Connection, observador: UUID?): Boolean {
        if (observador == null) return true
        return c.prepareStatement("SELECT priv_ultima_vez FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, observador)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } != Privacidad.NADIE
    }

    /**
     * Si esa persona admite que la encuentren por su username. L.1.
     *
     * Devuelve el veredicto ya resuelto para este observador, incluido el caso
     * `conocidos`. Se usa en la busqueda, y **no** en abrir una conversacion
     * que ya existe: ocultarse de la busqueda no es bloquear a quien ya te
     * habla.
     */
    fun buscable(c: Connection, objetivo: UUID, observador: UUID?): Boolean {
        if (observador == null || observador == objetivo) return true
        val fila = c.prepareStatement(
            "SELECT priv_busqueda, priv_modo::text FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, objetivo)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) to it.getString(2) } }
        } ?: (Privacidad.TODOS to null)
        return permiteCon(
            nivel = fila.first,
            conocido = objetivo in quienesMeConocen(c, observador),
            modo = modoDe(fila.second, "busqueda"),
            enLaLista = (objetivo to "busqueda") in excepcionesQueMeIncluyen(c, observador),
        )
    }

    /** La regla de privacidad, en una linea. */
    private fun permite(nivel: String, conocido: Boolean): Boolean = when (nivel) {
        Privacidad.TODOS -> true
        Privacidad.CONOCIDOS -> conocido
        // `personalizado` sin contexto resuelve NO. Quien llama a esta version
        // no tiene la lista, y en una decision de privacidad la respuesta
        // segura ante la falta de datos es no mostrar el dato.
        else -> false
    }

    /**
     * L.1 · La regla completa, con el nivel `personalizado`.
     *
     * `enLaLista` dice si el observador figura en las excepciones de ESE ajuste
     * de ESA persona. El modo decide como leerlo:
     *
     *  - `salvo` (lista negra): lo ven todos menos los de la lista.
     *  - `solo`  (lista blanca): lo ven solo los de la lista.
     *
     * Con la lista vacia y modo `solo` no lo ve nadie, y ese es el defecto
     * seguro a proposito: si un fallo dejara las excepciones sin leer, el
     * resultado es ocultar el dato, nunca mostrarselo a todos.
     */
    private fun permiteCon(
        nivel: String,
        conocido: Boolean,
        modo: String,
        enLaLista: Boolean,
    ): Boolean = when (nivel) {
        Privacidad.TODOS -> true
        Privacidad.CONOCIDOS -> conocido
        Privacidad.PERSONALIZADO ->
            if (modo == Privacidad.MODO_SOLO) enLaLista else !enLaLista
        else -> false
    }

    /**
     * Los pares (persona, ajuste) en cuyas excepciones aparece el observador.
     *
     * UNA consulta por peticion, no una por fila: se pregunta al reves -"donde
     * estoy yo listado"- en vez de "quien tiene excepciones", que obligaria a
     * consultar por cada persona de la lista de participantes.
     */
    fun excepcionesQueMeIncluyen(c: Connection, observador: UUID?): Set<Pair<UUID, String>> {
        if (observador == null) return emptySet()
        return c.prepareStatement(
            "SELECT usuario_id, ajuste FROM privacidad_excepcion WHERE otro_id = ?"
        ).use { st ->
            st.setObject(1, observador)
            st.executeQuery().use { rs ->
                rs.mapear { it.getObject(1, UUID::class.java) to it.getString(2) }
            }.toSet()
        }
    }

    /** El modo de un ajuste, leido del jsonb. `salvo` si no hay nada escrito. */
    /**
     * El modo (`solo` o `salvo`) de un ajuste personalizado.
     *
     * Deja de ser privado porque las historias lo necesitan y resuelven la
     * privacidad AL REVES que el resto: aqui no se pregunta "puede este
     * observador ver lo mio" sino "a quienes les toca lo mio", y esa
     * direccion no cabe en `buscable`.
     */
    fun modoDe(json: String?, ajuste: String): String {
        if (json.isNullOrBlank()) return Privacidad.MODO_SALVO
        // Se lee con una expresion y no con un parser: el jsonb tiene como
        // maximo siete claves con valores de dos palabras, y meter una
        // dependencia de JSON en el camino de cada perfil no se paga.
        val m = Regex("\"" + ajuste + "\"\\s*:\\s*\"(salvo|solo)\"").find(json)
        return m?.groupValues?.get(1) ?: Privacidad.MODO_SALVO
    }

    /**
     * Quienes consideran conocido a `usuarioId`.
     *
     * El nombre dice la direccion a proposito: `conocidosDe(x)` se leia como
     * "los conocidos de x" y devolvia lo contrario. Ese nombre equivocado es
     * como nacio el salto de privacidad que se describe abajo.
     *
     * Ojo con la DIRECCION, que es donde estaba el error.
     *
     * Un ajuste de privacidad es del DUENO: "solo los conocidos ven mi foto"
     * significa *los que YO considero conocidos*. La version anterior de esta
     * funcion devolvia a quienes el observador consideraba conocidos, y
     * preguntaba si el dueno estaba ahi. Mientras "conocido" fue simetrico
     * -compartir una conversacion directa- las dos preguntas daban lo mismo y
     * el error era invisible.
     *
     * Con la libreta del modulo I dejo de ser simetrico, y la version vieja se
     * volvio un **salto de la privacidad**: bastaba con agregar a alguien a mi
     * propia libreta para pasar su filtro de "solo conocidos". Eso es
     * exactamente lo contrario de lo que el ajuste promete.
     *
     * Devolver el conjunto en esta direccion permite arreglarlo sin cambiar los
     * sitios que la usan: todos preguntan "¿esta el dueno en el conjunto del
     * observador?", y con esta consulta eso ya significa "¿el dueno considera
     * conocido al observador?".
     *
     * Se resuelve en una sola consulta y se reusa para toda la lista:
     * preguntarlo por fila seria una consulta por participante.
     */
    /**
     * Con quien hay ya una relacion en esta plataforma.
     *
     * Deja de ser privado porque las historias lo usan como **conjunto
     * acotado** del que sale su audiencia: no se puede cifrar una historia
     * contra cuarenta mil desconocidos, asi que `todos` significa ahi "todo
     * este conjunto" y no "toda la plataforma". Ver `Historias`.
     */
    fun quienesMeConocen(c: Connection, usuarioId: UUID): Set<UUID> =
        c.prepareStatement(
            // Dos formas de no ser un desconocido, en union:
            //   - compartimos una conversacion directa (simetrico);
            //   - esa persona me tiene en SU libreta (no simetrico).
            """SELECT p2.usuario_id
               FROM participante p1
                 JOIN conversacion cv ON cv.id = p1.conversacion_id AND cv.tipo = 'directa'
                 JOIN participante p2 ON p2.conversacion_id = p1.conversacion_id
               WHERE p1.usuario_id = ? AND p1.salido_en IS NULL AND p2.salido_en IS NULL
                 AND p2.usuario_id <> ?
               UNION
               SELECT k.usuario_id FROM contacto k WHERE k.contacto_id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId); st.setObject(2, usuarioId); st.setObject(3, usuarioId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) }.toSet() }
        }

    // ============================================================
    //  Privacidad
    // ============================================================

    fun privacidad(usuarioId: UUID): Privacidad = Db.query { c -> privacidadDe(c, usuarioId) }

    /**
     * Los ajustes de privacidad de una persona.
     *
     * Se construye con argumentos CON NOMBRE y no por posicion. No es estilo:
     * al agregar `historias` en medio de `Privacidad`, la version posicional
     * siguio compilando en otros sitios y aqui empezo a pasar un `Boolean`
     * donde iba un `String`. Con nombres, un campo nuevo da un error que
     * senala exactamente lo que falta en vez de correr los demas un lugar.
     */
    private fun privacidadDe(c: Connection, usuarioId: UUID): Privacidad =
        c.prepareStatement(
            """SELECT priv_foto, priv_estado, priv_escribe, priv_grupos, priv_llamadas,
                      priv_ultima_vez, priv_nombre, priv_busqueda, priv_lectura,
                      priv_escribiendo, priv_historias,
                      priv_biografia, priv_videollamadas, priv_grabando, priv_solicitudes,
                      priv_comunidades, priv_directorio
               FROM usuario WHERE id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs ->
                rs.primero {
                    Privacidad(
                        foto = it.getString(1),
                        estado = it.getString(2),
                        escribe = it.getString(3),
                        grupos = it.getString(4),
                        llamadas = it.getString(5),
                        ultimaVez = it.getString(6),
                        nombre = it.getString(7),
                        busqueda = it.getString(8),
                        lectura = it.getBoolean(9),
                        escribiendo = it.getBoolean(10),
                        historias = it.getString(11),
                        biografia = it.getString(12),
                        videollamadas = it.getString(13),
                        grabando = it.getBoolean(14),
                        solicitudes = it.getBoolean(15),
                        comunidades = it.getString(16),
                        directorio = it.getBoolean(17),
                    )
                }
            } ?: Privacidad()
        }

    fun guardarPrivacidad(yo: Auth, p: Privacidad): Privacidad = Db.tx { c ->
        if (p.foto !in Privacidad.NIVELES || p.estado !in Privacidad.NIVELES ||
            p.grupos !in Privacidad.NIVELES || p.escribe !in Privacidad.NIVELES_ESCRIBE ||
            p.llamadas !in Privacidad.NIVELES || p.ultimaVez !in Privacidad.NIVELES ||
            p.nombre !in Privacidad.NIVELES || p.busqueda !in Privacidad.NIVELES ||
            p.historias !in Privacidad.NIVELES ||
            p.biografia !in Privacidad.NIVELES || p.videollamadas !in Privacidad.NIVELES ||
            p.comunidades !in Privacidad.NIVELES
        ) {
            throw ErrorNegocio(400, "Nivel de privacidad invalido.")
        }
        c.prepareStatement(
            """UPDATE usuario SET priv_foto = ?, priv_estado = ?, priv_escribe = ?,
                                  priv_grupos = ?, priv_llamadas = ?, priv_ultima_vez = ?,
                                  priv_nombre = ?, priv_busqueda = ?, priv_lectura = ?,
                                  priv_escribiendo = ?, priv_historias = ?,
                                  priv_biografia = ?, priv_videollamadas = ?,
                                  priv_grabando = ?, priv_solicitudes = ?,
                                  priv_comunidades = ?, priv_directorio = ?
               WHERE id = ?"""
        ).use { st ->
            st.setString(1, p.foto); st.setString(2, p.estado)
            st.setString(3, p.escribe); st.setString(4, p.grupos)
            st.setString(5, p.llamadas); st.setString(6, p.ultimaVez)
            st.setString(7, p.nombre); st.setString(8, p.busqueda)
            st.setBoolean(9, p.lectura)
            st.setBoolean(10, p.escribiendo)
            st.setString(11, p.historias)
            st.setString(12, p.biografia)
            st.setString(13, p.videollamadas)
            st.setBoolean(14, p.grabando)
            st.setBoolean(15, p.solicitudes)
            st.setString(16, p.comunidades)
            st.setBoolean(17, p.directorio)
            st.setObject(18, yo.usuarioId)
            st.executeUpdate()
        }
        p
    }

    /** Lanza si `objetivo` no acepta esta accion viniendo de `yo`. */
    private fun exigirPermiso(c: Connection, yo: UUID, objetivo: UsuarioPublico, campo: String) {
        val objetivoId = UUID.fromString(objetivo.usuarioId)
        if (objetivoId == yo) return
        val priv = privacidadDe(c, objetivoId)
        val nivel = if (campo == "escribe") priv.escribe else priv.grupos
        if (!permite(nivel, objetivoId in quienesMeConocen(c, yo))) {
            throw ErrorNegocio(
                403,
                if (campo == "escribe") "@" + objetivo.username + " no acepta mensajes de desconocidos."
                else "@" + objetivo.username + " no permite que lo agreguen a grupos.",
            )
        }
    }

    /** `observador` es quien pregunta. null = uso interno, sin filtrar. */
    private fun publicoPorUsername(
        c: Connection,
        username: String,
        observador: UUID? = null,
    ): UsuarioPublico? =
        c.prepareStatement(
            """SELECT $COLS_PUBLICO
               $FROM_PUBLICO
               WHERE u.username = ? AND u.desactivado_en IS NULL"""
        ).use { st ->
            st.setString(1, username.lowercase().trim())
            st.executeQuery().use { rs ->
                rs.primero { fila ->
                    val id = fila.getObject(1, UUID::class.java)
                    val propio = observador == null || observador == id
                    leerPublico(
                        fila,
                        conocido = observador != null && id in quienesMeConocen(c, observador),
                        esMio = propio,
                        observadorComparte = comparteUltimaVez(c, observador),
                        listado = excepcionesQueMeIncluyen(c, observador),
                    )
                }
            }
        }

    fun porId(usuarioId: UUID): UsuarioPublico? = Db.query { c ->
        c.prepareStatement(
            """SELECT $COLS_PUBLICO
               $FROM_PUBLICO
               WHERE u.id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            // Perfil propio: se devuelve completo, sin filtrar.
            st.executeQuery().use { rs -> rs.primero { leerPublico(it, conocido = true, esMio = true) } }
        }
    }

    // ============================================================
    //  Perfil
    // ============================================================

    fun guardarPerfil(yo: Auth, r: PerfilReq) = Db.tx { c ->
        // El nombre y el estado los ve otra gente. Ver `exigirNoSuspendido`.
        Autz.exigirNoSuspendido(c, yo.usuarioId)
        val nombre = r.nombreMostrado.trim().take(48)
        val estado = r.estadoTexto.trim().take(140)
        c.prepareStatement("UPDATE usuario SET nombre_mostrado = ?, estado_texto = ? WHERE id = ?").use { st ->
            st.setString(1, nombre.ifEmpty { null })
            st.setString(2, estado.ifEmpty { null })
            st.setObject(3, yo.usuarioId)
            st.executeUpdate()
        }
    }

    /** `campo` es 'avatar' o 'portada'. Nunca viene del cliente sin validar. */
    fun guardarImagen(yo: Auth, campo: String, bytes: ByteArray) {
        val (col, colFecha, max) = when (campo) {
            "avatar" -> Triple("avatar", "avatar_actualizado", 524_288)
            "portada" -> Triple("portada", "portada_actualizada", 1_048_576)
            else -> throw ErrorNegocio(400, "Campo de imagen invalido.")
        }
        if (bytes.isEmpty()) throw ErrorNegocio(400, "Imagen vacia.")
        if (bytes.size > max) throw ErrorNegocio(413, "La imagen supera el limite permitido.")
        if (!pareceImagen(bytes)) throw ErrorNegocio(400, "El archivo no es una imagen valida.")

        Db.tx { c ->
            // Una imagen es lo mas visible de un perfil: un logo ajeno vale
            // mas que cualquier texto para hacerse pasar por alguien.
            Autz.exigirNoSuspendido(c, yo.usuarioId)
            c.prepareStatement("UPDATE usuario SET $col = ?, $colFecha = now() WHERE id = ?").use { st ->
                st.setBytes(1, bytes)
                st.setObject(2, yo.usuarioId)
                st.executeUpdate()
            }
        }
    }

    /**
     * No basta con confiar en el Content-Type: se revisa la firma real del
     * archivo. Evita que alguien use el perfil como almacen de binarios.
     */
    private fun pareceImagen(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val webp = String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP"
        return jpg || png || webp
    }

    /**
     * Devuelve la imagen SOLO si la privacidad del dueno lo permite.
     *
     * Este chequeo es el que cuenta de verdad: sin el bastaria con adivinar la
     * URL para saltarse el ajuste, por mucho que la app oculte la foto.
     */
    fun imagen(yo: Auth, username: String, campo: String): ByteArray? = Db.query { c ->
        val col = if (campo == "portada") "portada" else "avatar"
        val fila = c.prepareStatement(
            "SELECT id, priv_foto, $col FROM usuario WHERE username = ?"
        ).use { st ->
            st.setString(1, username.lowercase().trim())
            st.executeQuery().use { rs ->
                rs.primero { Triple(it.getObject(1, UUID::class.java), it.getString(2), it.getBytes(3)) }
            }
        } ?: return@query null

        val (duenoId, priv, bytes) = fila
        if (duenoId == yo.usuarioId) return@query bytes
        if (!permite(priv, duenoId in quienesMeConocen(c, yo.usuarioId))) {
            throw ErrorNegocio(403, "No puedes ver esta foto.")
        }
        bytes
    }

    // ============================================================
    //  Conversaciones
    // ============================================================

    /**
     * @param duracionMs `0` = para siempre. Ver `DuracionChat`.
     *
     * ## Un chat temporal es una conversacion APARTE
     *
     * No se convierte la que ya existe, y no es un detalle: convertirla
     * pondria fecha de borrado a un historial que nadie acepto perder. Con una
     * conversacion nueva, la de siempre sigue donde estaba y la temporal
     * empieza vacia, que es lo que alguien espera al abrir una.
     *
     * Por eso su `clave_directa` lleva un identificador propio: la clave de
     * una directa normal es unica por pareja —es lo que hace que abrirla dos
     * veces devuelva la misma— y una temporal tiene que poder convivir con
     * ella, e incluso con otra temporal.
     */
    fun crearDirecta(
        yo: Auth,
        usernameDestino: String,
        duracionMs: Long = 0,
    ): ConversacionResumen = Db.tx { c ->
        if (!DuracionChat.valida(duracionMs)) {
            throw ErrorNegocio(400, "Esa duracion no esta permitida.")
        }
        val otro = publicoPorUsername(c, usernameDestino)
            ?: throw ErrorNegocio(404, "No existe el usuario @${usernameDestino.lowercase().trim()}.")
        if (otro.usuarioId == yo.usuarioId.toString()) {
            throw ErrorNegocio(400, "No puedes abrir una conversacion contigo mismo.")
        }
        // Un bloqueo en cualquier sentido cierra la puerta antes que nada. Se
        // comprueba aqui ademas de en Autz: si solo se comprobara al enviar, el
        // bloqueado igual podria abrir el chat y aparecer en la lista del otro.
        if (Autz.hayBloqueo(c, yo.usuarioId, UUID.fromString(otro.usuarioId))) {
            throw ErrorNegocio(403, "No puedes iniciar una conversacion con esta persona.")
        }

        // "Quien me puede escribir". Si ya existe la conversacion, ambos son
        // conocidos y esto pasa solo; el filtro muerde en el primer contacto.
        //
        // Cuando NO deja pasar, todavia queda una salida: si esa persona acepta
        // solicitudes, la conversacion nace **marcada** en vez de no nacer. Ver
        // `esSolicitud` y la migracion V33.
        val puedeEscribirDirecto = runCatching {
            exigirPermiso(c, yo.usuarioId, otro, "escribe")
        }.isSuccess

        val otroId = UUID.fromString(otro.usuarioId)
        if (!puedeEscribirDirecto && !aceptaSolicitudes(c, otroId)) {
            // Sin solicitudes, el portazo de siempre. Se repite la llamada para
            // que el mensaje de error salga de un solo sitio.
            exigirPermiso(c, yo.usuarioId, otro, "escribe")
        }
        val esSolicitud = !puedeEscribirDirecto

        val a = yo.usuarioId.toString()
        val b = otro.usuarioId
        val clave = if (a < b) "$a:$b" else "$b:$a"

        // Una temporal NO reusa: cada una es nueva y tiene su propio plazo.
        val temporal = duracionMs > 0
        val claveFinal = if (temporal) "$clave:t:${UUID.randomUUID()}" else clave

        val existente = if (temporal) null else c.prepareStatement(
            "SELECT id FROM conversacion WHERE clave_directa = ?"
        ).use { st ->
            st.setString(1, clave)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }
        val convId = existente ?: run {
            val id = c.prepareStatement(
                """INSERT INTO conversacion (tipo, creador_id, clave_directa, solicitud_de, expira_en)
                   VALUES ('directa', ?, ?, ?, ?) RETURNING id"""
            ).use { st ->
                st.setObject(1, yo.usuarioId)
                st.setString(2, claveFinal)
                // NULL = conversacion normal. Con valor, es una solicitud y
                // quien la recibe decide.
                if (esSolicitud) st.setObject(3, yo.usuarioId) else st.setNull(3, java.sql.Types.OTHER)
                ponerVencimiento(st, 4, duracionMs)
                st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
            agregarParticipantes(c, id, listOf(yo.usuarioId, UUID.fromString(otro.usuarioId)), "miembro")
            id
        }

        ConversacionResumen(
            convId.toString(), "directa", otro.username, listOf(otro),
            esSolicitud = esSolicitud,
            expiraEn = if (temporal) System.currentTimeMillis() + duracionMs else 0,
        )
    }

    /**
     * Pone la fecha de vencimiento, o NULL.
     *
     * Se calcula en el SERVIDOR y no en el cliente, a proposito. El plazo lo
     * elige quien crea el chat, pero la fecha tiene que salir de un solo reloj:
     * con dos telefonos mal puestos en hora, el mismo chat venceria en momentos
     * distintos en cada uno — y el que lo tuviera adelantado lo borraria
     * mientras el otro sigue escribiendo.
     */
    private fun ponerVencimiento(st: java.sql.PreparedStatement, indice: Int, duracionMs: Long) {
        if (duracionMs > 0) {
            st.setTimestamp(
                indice,
                java.sql.Timestamp(System.currentTimeMillis() + duracionMs),
            )
        } else {
            st.setNull(indice, java.sql.Types.TIMESTAMP)
        }
    }

    /**
     * Borra las conversaciones temporales que ya vencieron.
     *
     * ## Que se borra de verdad
     *
     * La fila de `conversacion`, y con ella —en cascada— participantes,
     * metadatos de mensajes y sobres pendientes. Del servidor no queda nada.
     *
     * Del contenido, aqui nunca hubo nada que borrar: el historial vive solo
     * en los telefonos. Por eso esto NO es lo que hace desaparecer el chat —
     * eso lo hace cada cliente con su copia— sino lo que impide que el
     * servidor siga sabiendo que esa conversacion existio.
     *
     * ## Y por que se avisa antes de borrar
     *
     * Porque los avisos se emiten contra las filas de participantes, y despues
     * del DELETE ya no hay a quien avisar. Se junta la lista primero.
     */
    fun borrarConversacionesVencidas(): Pair<Int, List<Pair<UUID, Bajada.Evento>>> = Db.tx { c ->
        val vencidas = c.prepareStatement(
            "SELECT id FROM conversacion WHERE expira_en IS NOT NULL AND expira_en < now()"
        ).use { st ->
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        if (vencidas.isEmpty()) return@tx 0 to emptyList()

        // Los participantes se leen ANTES de borrar, porque el DELETE se los
        // lleva; los avisos se emiten DESPUES, y sin `conversacionId`.
        //
        // El orden costo una prueba en rojo. `evento_pendiente.conversacion_id`
        // apunta a `conversacion` con ON DELETE CASCADE, asi que emitir antes
        // creaba los avisos y el borrado los barria en la misma transaccion:
        // quien estaba conectado se enteraba igual —el empujon lleva el objeto
        // en memoria— y quien estaba apagado no se enteraba nunca.
        //
        // Con el id en el detalle en vez de en la columna, el aviso sobrevive
        // al borrado y espera al telefono que estaba apagado.
        val gentePorConv = vencidas.associateWith { id ->
            c.prepareStatement(
                "SELECT usuario_id FROM participante WHERE conversacion_id = ? AND salido_en IS NULL"
            ).use { st ->
                st.setObject(1, id)
                st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
            }
        }

        val arr = c.createArrayOf("uuid", vencidas.toTypedArray())
        c.prepareStatement("DELETE FROM conversacion WHERE id = ANY(?)")
            .use { st -> st.setArray(1, arr); st.executeUpdate() }

        val avisos = gentePorConv.entries.flatMap { (id, gente) ->
            Eventos.emitir(c, gente, "conversacion_vencida", null, "", """{"conversacion":"$id"}""")
        }

        vencidas.size to avisos
    }

    /** Si esta persona acepta solicitudes de quien no puede escribirle. */
    private fun aceptaSolicitudes(c: Connection, usuarioId: UUID): Boolean =
        c.prepareStatement("SELECT priv_solicitudes FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } }
        } ?: true

    /**
     * Acepta o rechaza una solicitud de mensaje.
     *
     * ## Quien decide, y quien no
     *
     * Solo **quien la recibio**. Quien la mando no puede aceptarse a si mismo
     * —seria saltarse el ajuste entero con una peticion mas—, y por eso la
     * comprobacion no es "soy participante" sino "soy participante **y no soy
     * quien la pidio**".
     *
     * ## Rechazar borra la conversacion
     *
     * Y no la deja marcada como rechazada. Una lista de solicitudes rechazadas
     * es una lista de gente a la que dijiste que no, que no le sirve a nadie y
     * que el otro lado podria sondear. Al borrarse, quien la mando ve lo mismo
     * que antes de mandarla: nada.
     *
     * Lo que NO hace rechazar es bloquear. Son dos decisiones distintas y
     * juntarlas convertiria un "ahora no" en un portazo permanente.
     */
    fun decidirSolicitud(yo: Auth, conversacionId: UUID, aceptar: Boolean) = Db.tx { c ->
        val quienPidio = c.prepareStatement(
            """SELECT cv.solicitud_de
               FROM conversacion cv
                 JOIN participante p ON p.conversacion_id = cv.id
                                    AND p.usuario_id = ? AND p.salido_en IS NULL
               WHERE cv.id = ? AND cv.solicitud_de IS NOT NULL"""
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setObject(2, conversacionId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        // 404 y no 403: quien no participa no deberia enterarse de que esa
        // conversacion existe probando la ruta.
        } ?: throw ErrorNegocio(404, "Esa solicitud no existe.")

        if (quienPidio == yo.usuarioId) {
            throw ErrorNegocio(403, "La solicitud la acepta quien la recibio.")
        }

        if (aceptar) {
            c.prepareStatement("UPDATE conversacion SET solicitud_de = NULL WHERE id = ?").use { st ->
                st.setObject(1, conversacionId); st.executeUpdate()
            }
        } else {
            // Se borra la conversacion entera. `mensaje_meta`, `participante` y
            // los sobres pendientes cuelgan de ella con ON DELETE CASCADE.
            c.prepareStatement("DELETE FROM conversacion WHERE id = ?").use { st ->
                st.setObject(1, conversacionId); st.executeUpdate()
            }
        }
    }

    fun crearGrupo(yo: Auth, req: GrupoReq): ResultadoGrupo = Db.tx { c ->
        if (!DuracionChat.valida(req.duracionMs)) {
            throw ErrorNegocio(400, "Esa duracion no esta permitida.")
        }
        val nombre = req.nombre.trim()
        if (nombre.isEmpty() || nombre.length > 64) {
            throw ErrorNegocio(400, "El nombre del grupo debe tener entre 1 y 64 caracteres.")
        }
        val miembros = req.usernames.map { u ->
            publicoPorUsername(c, u) ?: throw ErrorNegocio(404, "No existe el usuario @${u.lowercase().trim()}.")
        }.distinctBy { it.usuarioId }.filter { it.usuarioId != yo.usuarioId.toString() }

        // "Quien me puede agregar a grupos", verificado por cada invitado.
        miembros.forEach { exigirPermiso(c, yo.usuarioId, it, "grupos") }

        val id = c.prepareStatement(
            """INSERT INTO conversacion (tipo, creador_id, nombre, expira_en)
               VALUES ('grupo', ?, ?, ?) RETURNING id"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, nombre)
            ponerVencimiento(st, 3, req.duracionMs)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        agregarParticipantes(c, id, listOf(yo.usuarioId), "admin")
        agregarParticipantes(c, id, miembros.map { UUID.fromString(it.usuarioId) }, "miembro")

        val avisos = Eventos.emitir(
            c, miembros.map { UUID.fromString(it.usuarioId) },
            "agregado_grupo", id, yo.username,
        )
        ResultadoGrupo(
            ConversacionResumen(
                id.toString(), "grupo", nombre, miembros,
                expiraEn = if (req.duracionMs > 0) {
                    System.currentTimeMillis() + req.duracionMs
                } else 0,
            ),
            avisos,
        )
    }

    fun agregarMiembros(yo: Auth, conversacionId: UUID, usernames: List<String>): ResultadoGrupo = Db.tx { c ->
        exigirRol(c, conversacionId, yo.usuarioId, "admin")
        val nuevos = usernames.map { u ->
            publicoPorUsername(c, u) ?: throw ErrorNegocio(404, "No existe el usuario @${u.lowercase().trim()}.")
        }
        nuevos.forEach { exigirPermiso(c, yo.usuarioId, it, "grupos") }

        agregarParticipantes(c, conversacionId, nuevos.map { UUID.fromString(it.usuarioId) }, "miembro")

        val nombre = c.prepareStatement("SELECT coalesce(nombre,'Grupo') FROM conversacion WHERE id = ?")
            .use { st ->
                st.setObject(1, conversacionId)
                st.executeQuery().use { rs -> rs.primero { it.getString(1) } } ?: "Grupo"
            }
        val avisos = Eventos.emitir(
            c, nuevos.map { UUID.fromString(it.usuarioId) },
            "agregado_grupo", conversacionId, yo.username,
        )
        val r = resumen(c, conversacionId, yo.usuarioId)
            ?: throw ErrorNegocio(404, "Conversacion no encontrada.")
        ResultadoGrupo(r, avisos)
    }

    fun salir(yo: Auth, conversacionId: UUID) = Db.tx { c ->
        c.prepareStatement(
            "UPDATE participante SET salido_en = now() WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, conversacionId)
            st.setObject(2, yo.usuarioId)
            if (st.executeUpdate() == 0) throw ErrorNegocio(404, "No perteneces a esa conversacion.")
        }
        // Si era su ultimo grupo de la comunidad, sale tambien de los anuncios.
        Comunidades.alSalirDeGrupo(c, conversacionId, yo.usuarioId)
    }

    private fun exigirRol(c: Connection, conv: UUID, usuario: UUID, rol: String) {
        val actual = c.prepareStatement(
            "SELECT rol FROM participante WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, usuario)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(403, "No perteneces a esa conversacion.")
        if (actual != rol) throw ErrorNegocio(403, "Necesitas ser administrador del grupo.")
    }

    /** Traduce una clave de rol de sistema a su id. Cacheado por proceso. */
    private val rolesSistema = java.util.concurrent.ConcurrentHashMap<String, UUID>()

    private fun rolSistema(c: Connection, clave: String): UUID =
        rolesSistema.getOrPut(clave) {
            c.prepareStatement("SELECT id FROM rol WHERE es_sistema AND clave = ?").use { st ->
                st.setString(1, clave)
                st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
                    ?: error("Falta el rol de sistema ")
            }
        }

    /**
     * Modulo AD: si el grupo esta en una comunidad, quien entra al grupo entra
     * tambien a sus anuncios.
     *
     * El enganche va **aqui y no en cada sitio que agrega gente**: este es el
     * paso por el que todos acaban entrando. Repetirlo en cada llamador es
     * garantizar que alguien agregue un camino nuevo y se olvide.
     */
    private fun agregarParticipantes(
        c: Connection,
        conv: UUID,
        usuarios: List<UUID>,
        rol: String,
        actor: UUID? = null,
    ) {
        if (usuarios.isEmpty()) return
        val rolId = rolSistema(c, if (rol == "admin") "propietario" else "miembro")
        c.prepareStatement(
            """INSERT INTO participante (conversacion_id, usuario_id, rol, rol_id) VALUES (?, ?, ?, ?)
               ON CONFLICT (conversacion_id, usuario_id)
               DO UPDATE SET salido_en = NULL, rol_id = EXCLUDED.rol_id"""
        ).use { st ->
            usuarios.forEach {
                st.setObject(1, conv); st.setObject(2, it); st.setString(3, rol)
                st.setObject(4, rolId); st.addBatch()
            }
            st.executeBatch()
        }
        usuarios.forEach { Comunidades.alEntrarAGrupo(c, conv, it, actor ?: it) }
    }

    /**
     * La "Nota para mi" de quien pregunta: la crea la primera vez y despues
     * devuelve siempre la misma. Ver V45.
     *
     * Con el rol `miembro` y nada mas, como en una directa: no hay a quien
     * agregar ni a quien administrar, y ese rol no tiene permisos para
     * agregar gente, invitar ni tocar roles. Si alguna vez salio de ella,
     * vuelve a entrar: `agregarParticipantes` limpia `salido_en`.
     */
    fun notaParaMi(yo: Auth): ConversacionResumen = Db.tx { c ->
        val clave = "notas:${yo.usuarioId}"
        val existente = c.prepareStatement(
            "SELECT id FROM conversacion WHERE clave_directa = ?"
        ).use { st ->
            st.setString(1, clave)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }
        val id = existente ?: c.prepareStatement(
            // ON CONFLICT por la carrera de dos aparatos pidiendola a la vez:
            // el segundo no falla, se queda con la del primero.
            """INSERT INTO conversacion (tipo, creador_id, clave_directa)
               VALUES ('$TIPO_NOTAS', ?, ?)
               ON CONFLICT (clave_directa) DO UPDATE SET clave_directa = EXCLUDED.clave_directa
               RETURNING id"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setString(2, clave)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        agregarParticipantes(c, id, listOf(yo.usuarioId), "miembro")
        resumen(c, id, yo.usuarioId) ?: throw ErrorNegocio(500, "No se pudo abrir la nota.")
    }

    fun listar(yo: Auth): List<ConversacionResumen> = Db.query { c ->
        val ids = c.prepareStatement(
            "SELECT conversacion_id FROM participante WHERE usuario_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
        ids.mapNotNull { resumen(c, it, yo.usuarioId) }
    }

    private fun resumen(c: Connection, conv: UUID, yo: UUID): ConversacionResumen? {
        val meta = c.prepareStatement(
            """SELECT tipo, nombre,
                      coalesce(extract(epoch FROM expira_en) * 1000, 0),
                      temporales_segundos
                 FROM conversacion WHERE id = ?"""
        ).use { st ->
            st.setObject(1, conv)
            st.executeQuery().use { rs ->
                rs.primero {
                    MetaConv(
                        tipo = it.getString(1),
                        nombre = it.getString(2),
                        expiraEn = it.getDouble(3).toLong(),
                        // `getInt` devuelve 0 para NULL, y 0 no es un valor
                        // valido de este campo: hay que preguntar por el NULL
                        // aparte o "permanentes" se convertiria en "cero
                        // segundos".
                        temporalesSegundos = it.getObject(4) as? Int,
                    )
                }
            }
        } ?: return null

        // Rol y preferencias personales, en una sola consulta.
        val mio = c.prepareStatement(
            """SELECT r.clave, r.jerarquia,
                      CASE WHEN p.silenciado_hasta = 'infinity'::timestamptz THEN -1
                           ELSE coalesce(extract(epoch FROM p.silenciado_hasta) * 1000, 0) END,
                      p.archivado, p.fijado
               FROM participante p JOIN rol r ON r.id = p.rol_id
               WHERE p.conversacion_id = ? AND p.usuario_id = ? AND p.salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, yo)
            st.executeQuery().use { rs ->
                rs.primero {
                    MiEstado(it.getString(1), it.getInt(2), it.getDouble(3).toLong(), it.getBoolean(4), it.getBoolean(5))
                }
            }
        } ?: MiEstado("miembro", 10, 0L, false, false)

        val otros = c.prepareStatement(
            """SELECT $COLS_PUBLICO
               FROM participante p
                 JOIN usuario u     ON u.id = p.usuario_id
                 LEFT JOIN LATERAL (
                   SELECT id, identidad_pub FROM dispositivo
                   WHERE usuario_id = u.id AND revocado_en IS NULL
                   ORDER BY principal DESC, registrado_en
                   LIMIT 1
                 ) d ON true
                 $JOIN_EMPRESA
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL AND p.usuario_id <> ?"""
        ).use { st ->
            st.setObject(1, conv); st.setObject(2, yo)
            val conocidos = quienesMeConocen(c, yo)
            // Una sola consulta para toda la lista: la reciprocidad depende de
            // QUIEN PREGUNTA, no de cada participante.
            val comparte = comparteUltimaVez(c, yo)
            val listado = excepcionesQueMeIncluyen(c, yo)
            st.executeQuery().use { rs ->
                rs.mapear { fila ->
                    val id = fila.getObject(1, UUID::class.java)
                    leerPublico(
                        fila,
                        conocido = id in conocidos,
                        esMio = false,
                        observadorComparte = comparte,
                        listado = listado,
                    )
                }
            }
        }
        // Un CANAL se nombra como un grupo: tiene nombre propio.
        //
        // Antes caia en la rama de las directas -"el username del otro"- y un
        // canal recien creado, donde todavia no hay nadie mas, aparecia en la
        // lista de chats como "(sin participantes)". El nombre del canal estaba
        // ahi al lado, sin usar.
        val nombre = when (meta.tipo) {
            "grupo" -> meta.nombre ?: "Grupo"
            "canal" -> meta.nombre ?: "Canal"
            TIPO_NOTAS -> "Nota para mí"
            else -> otros.firstOrNull()?.username ?: "(sin participantes)"
        }
        return ConversacionResumen(
            id = conv.toString(),
            tipo = meta.tipo,
            // Viaja en cada listado, no solo al crear: un aparato que se
            // sincroniza por primera vez tiene que enterarse igual de que ese
            // chat vence, y es el unico sitio por el que puede llegarle.
            expiraEn = meta.expiraEn,
            // Lo mismo, y por un motivo mas fuerte: quien recibe necesita el
            // temporizador para BORRAR sus copias. Ver
            // `ConversacionResumen.temporalesSegundos`.
            temporalesSegundos = meta.temporalesSegundos,
            nombre = nombre,
            participantes = otros,
            miRol = mio.rol,
            miJerarquia = mio.jerarquia,
            silenciadoHasta = mio.silenciado.takeIf { it != 0L },
            archivado = mio.archivado,
            fijado = mio.fijado,
        )
    }

    // ============================================================
    //  El buzon
    // ============================================================

    /** Dispositivos activos de los participantes, excluyendo el del remitente. */
    fun destinos(conversacionId: UUID, exceptoDispositivo: UUID): List<Destino> = Db.query { c ->
        c.prepareStatement(
            """SELECT d.id, d.usuario_id
               FROM participante p
                 JOIN dispositivo d ON d.usuario_id = p.usuario_id AND d.revocado_en IS NULL
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL AND d.id <> ?"""
        ).use { st ->
            st.setObject(1, conversacionId); st.setObject(2, exceptoDispositivo)
            st.executeQuery().use { rs ->
                rs.mapear { Destino(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java)) }
            }
        }
    }

    fun perteneceA(conversacionId: UUID, usuarioId: UUID): Boolean = Db.query { c ->
        c.prepareStatement(
            "SELECT 1 FROM participante WHERE conversacion_id = ? AND usuario_id = ? AND salido_en IS NULL"
        ).use { st ->
            st.setObject(1, conversacionId); st.setObject(2, usuarioId)
            st.executeQuery().use { it.next() }
        }
    }

    /** Una copia ya cifrada, lista para enrutar. */
    data class Copia(val destino: Destino, val cuerpo: ByteArray, val tipo: Int)

    /**
     * Enruta una copia ya cifrada a cada buzon.
     *
     * Antes esto era fan-out de verdad: llegaba un cuerpo y se copiaba a N
     * filas. Con E2EE ya no se puede, y el cambio es de fondo: cada copia esta
     * cifrada para un dispositivo distinto y el servidor no tiene con que
     * producir las otras. Lo unico que hace es poner cada cuerpo en el buzon
     * que le corresponde.
     *
     * `ON CONFLICT DO NOTHING` sobre el id hace el envio idempotente: si el
     * cliente reintenta tras una desconexion, no se duplica el mensaje.
     */
    fun encolar(
        sobreId: UUID,
        origen: UUID,
        /**
         * La conversacion, o `null` si el sobre no pertenece a ninguna.
         *
         * Lo segundo pasa con las historias: se publican a una audiencia que
         * puede incluir gente sin chat abierto. Ver V29.
         */
        conversacionId: UUID?,
        copias: List<Copia>,
        /**
         * Hora en que la persona escribio el mensaje, no la de ahora.
         *
         * Es el dato que se muestra al entregar. Con `msg off` un mensaje puede
         * salir horas despues de escrito, y usar la hora del servidor lo
         * mostraria mal y lo ordenaria mal en la conversacion.
         */
        creadoEn: Long,
    ): List<Pair<Destino, UUID>> {
        if (copias.isEmpty()) return emptyList()
        // El id del sobre es unico por destino: se deriva del id que genero el
        // cliente mas el indice del destinatario. Asi el reintento del cliente
        // choca con ON CONFLICT y no duplica el mensaje.
        //
        // El indice sale del ORDEN de las copias que manda el cliente, y el
        // cliente las manda en el mismo orden en que el servidor le dio los
        // destinos. Si ese orden cambiara, un reintento generaria ids nuevos y
        // el mensaje se duplicaria: por eso `Claves.destinos` ordena por
        // username y no deja el orden al azar del planificador.
        val pares = copias.mapIndexed { i, cp -> cp to derivar(sobreId, i) }
        Db.tx { c ->
            c.prepareStatement(
                """INSERT INTO sobre_pendiente
                       (id, destino_dispositivo, origen_dispositivo, conversacion_id,
                        cuerpo, creado_en_origen, tipo_cifrado, mensaje_id)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING"""
            ).use { st ->
                pares.forEach { (cp, id) ->
                    st.setObject(1, id)
                    st.setObject(2, cp.destino.dispositivoId)
                    st.setObject(3, origen)
                    st.setObject(4, conversacionId)
                    st.setBytes(5, cp.cuerpo)
                    st.setLong(6, creadoEn)
                    st.setInt(7, cp.tipo)
                    // El id original, igual para todos los destinos. Ver V26.
                    st.setObject(8, sobreId)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        return pares.map { (cp, id) -> cp.destino to id }
    }

    private fun derivar(base: UUID, i: Int): UUID =
        if (i == 0) base else UUID(base.mostSignificantBits, base.leastSignificantBits + i)

    /** A quien menciona un mensaje, ya resuelto. Ver `Bajada.Entrega.mencionado`. */
    fun mencionados(mensajeId: UUID): Set<UUID> = Db.query { c ->
        c.prepareStatement("SELECT usuario_id FROM mencion WHERE mensaje_id = ?").use { st ->
            st.setObject(1, mensajeId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) }.toSet() }
        }
    }

    fun pendientes(dispositivoId: UUID): List<Bajada.Entrega> = Db.query { c ->
        c.prepareStatement(
            """SELECT s.id, s.conversacion_id, u.id, u.username,
                      s.creado_en_origen, s.cuerpo, s.origen_dispositivo, s.tipo_cifrado,
                      coalesce(s.mensaje_id, s.id),
                      EXISTS (SELECT 1 FROM mencion m
                              WHERE m.mensaje_id = coalesce(s.mensaje_id, s.id)
                                AND m.usuario_id = dd.usuario_id)
               FROM sobre_pendiente s
                 JOIN dispositivo d  ON d.id = s.origen_dispositivo
                 JOIN usuario u      ON u.id = d.usuario_id
                 JOIN dispositivo dd ON dd.id = s.destino_dispositivo
               WHERE s.destino_dispositivo = ?
               ORDER BY s.id"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    Bajada.Entrega(
                        sobreId = it.getObject(1, UUID::class.java).toString(),
                        mensajeId = it.getObject(9, UUID::class.java).toString(),
                        // Puede venir NULL: una historia no pertenece a
                        // ninguna conversacion. Ver V29. Vacio y no "null":
                        // el cliente ya decide por el tipo de carga, y una
                        // cadena vacia es lo que su modelo entiende como
                        // "esto no va a ningun chat".
                        conversacionId =
                            it.getObject(2, UUID::class.java)?.toString().orEmpty(),
                        origenUsuarioId = it.getObject(3, UUID::class.java).toString(),
                        origenUsername = it.getString(4),
                        origenDispositivo = it.getObject(7, UUID::class.java).toString(),
                        creadoEn = it.getLong(5),
                        cuerpo = Base64Util.enc(it.getBytes(6)),
                        tipo = it.getInt(8),
                        mencionado = it.getBoolean(10),
                    )
                }
            }
        }
    }

    /**
     * Confirma y BORRA. Devuelve los dispositivos de origen para avisarles
     * "entregado" — es lo que pinta la palomita doble en el remitente.
     */
    /** Si el sobre sigue sin acusar. Ver `Hub.vigilarAcuse`. */
    fun sobreSigue(id: UUID): Boolean = Db.tx { c ->
        c.prepareStatement("SELECT 1 FROM sobre_pendiente WHERE id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { it.next() }
        }
    }

    fun acusar(dispositivoId: UUID, sobreIds: List<String>): List<Pair<UUID, String>> {
        if (sobreIds.isEmpty()) return emptyList()
        val ids = sobreIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (ids.isEmpty()) return emptyList()
        return Db.tx { c ->
            val arr = c.createArrayOf("uuid", ids.toTypedArray())
            c.prepareStatement(
                // Se devuelve el id del MENSAJE y no el de la fila: el
                // emisor solo conoce el suyo, y con el id derivado el
                // "entregado" no encontraba ninguna fila que marcar.
                """DELETE FROM sobre_pendiente
                   WHERE destino_dispositivo = ? AND id = ANY(?)
                   RETURNING origen_dispositivo, coalesce(mensaje_id, id)"""
            ).use { st ->
                st.setObject(1, dispositivoId)
                st.setArray(2, arr)
                st.executeQuery().use { rs ->
                    rs.mapear { it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java).toString() }
                }
            }
        }
    }

    // ============================================================
    //  Modulo N: token de push por dispositivo
    // ============================================================

    data class DestinoPush(val token: String, val proveedor: String)

    /**
     * El token de un aparato, si tiene y si todavia vale la pena intentarlo.
     *
     * Se filtran los que fallaron muchas veces seguidas: un token de una app
     * desinstalada falla para siempre, y sin el filtro el servidor gasta una
     * peticion HTTP a un tercero por cada mensaje que le llegue a esa cuenta.
     */
    fun tokenPush(dispositivoId: UUID): DestinoPush? = Db.query { c ->
        c.prepareStatement(
            """SELECT push_token, push_proveedor FROM dispositivo
               WHERE id = ? AND push_token IS NOT NULL AND revocado_en IS NULL
                 AND push_fallos < 10"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.primero { DestinoPush(it.getString(1), it.getString(2)) }
            }
        }
    }

    fun guardarTokenPush(dispositivoId: UUID, token: String, proveedor: String) = Db.tx { c ->
        c.prepareStatement(
            """UPDATE dispositivo
               SET push_token = ?, push_proveedor = ?, push_en = now(), push_fallos = 0
               WHERE id = ?"""
        ).use { st ->
            st.setString(1, token)
            st.setString(2, proveedor)
            st.setObject(3, dispositivoId)
            st.executeUpdate()
        }

        // El MISMO token en otro dispositivo se limpia. Pasa de verdad: si se
        // reinstala la app, Android puede devolver el token anterior, y dos
        // filas con el mismo token harian que un aviso despertara al aparato
        // equivocado -o que se mandaran dos por lo mismo-.
        //
        // ## Lo que esto concede, revisado y aceptado
        //
        // Gana el ULTIMO en registrar. Quien conozca el token de otro aparato
        // puede registrarlo en el suyo y dejar al primero sin avisos: una
        // denegacion, no una lectura -el aviso no lleva contenido y para
        // recibir los mensajes sigue haciendo falta la sesion y las claves-.
        //
        // Se acepta porque la alternativa es peor. Que gane el PRIMERO
        // significa que un token que quedo registrado en un aparato perdido
        // bloquea para siempre al mismo token en el aparato nuevo, y eso pasa
        // solo -Android reutiliza tokens al reinstalar- mientras que lo otro
        // exige conocer un valor que no se publica en ninguna respuesta.
        //
        // El control real esta aguas arriba: el token de push es un secreto
        // del aparato y no aparece en ninguna ruta de esta API. `recibeAvisos`
        // dice si HAY token, nunca cual.
        c.prepareStatement(
            "UPDATE dispositivo SET push_token = NULL, push_proveedor = NULL WHERE push_token = ? AND id <> ?"
        ).use { st ->
            st.setString(1, token)
            st.setObject(2, dispositivoId)
            st.executeUpdate()
        }
    }

    fun borrarTokenPush(dispositivoId: UUID) = Db.tx { c ->
        c.prepareStatement(
            "UPDATE dispositivo SET push_token = NULL, push_proveedor = NULL, push_fallos = 0 WHERE id = ?"
        ).use { st -> st.setObject(1, dispositivoId); st.executeUpdate() }
    }

    fun pushOk(dispositivoId: UUID) = Db.tx { c ->
        c.prepareStatement("UPDATE dispositivo SET push_fallos = 0 WHERE id = ?").use { st ->
            st.setObject(1, dispositivoId); st.executeUpdate()
        }
    }

    fun pushFallo(dispositivoId: UUID) = Db.tx { c ->
        c.prepareStatement("UPDATE dispositivo SET push_fallos = push_fallos + 1 WHERE id = ?").use { st ->
            st.setObject(1, dispositivoId); st.executeUpdate()
        }
    }

    /** Cuantos fallos seguidos lleva. Solo para las pruebas y el panel. */
    fun pushFallos(dispositivoId: UUID): Int = Db.query { c ->
        c.prepareStatement("SELECT push_fallos FROM dispositivo WHERE id = ?").use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } } ?: 0
        }
    }

    fun tienePush(dispositivoId: UUID): Boolean = Db.query { c ->
        c.prepareStatement(
            "SELECT push_token IS NOT NULL FROM dispositivo WHERE id = ?"
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: false
        }
    }

    fun barrerExpirados(): Int = Db.tx { c ->
        c.createStatement().use { it.executeUpdate("DELETE FROM sobre_pendiente WHERE expira_en < now()") }
    }

    /**
     * Los registros con datos personales tienen fecha de caducidad.
     *
     * ## Por que
     *
     * `evento_seguridad` guarda usuario, **IP** y **agente** de cada ingreso,
     * cambio de clave y limite excedido. `auditoria` guarda quien hizo que a
     * quien. Las dos crecian para siempre: en diez dias de un entorno de
     * desarrollo con dos usuarios de prueba juntaron 96.000 filas.
     *
     * Multiplicar eso por una institucion de decenas de miles de cuentas da un
     * archivo permanente de direcciones IP y horarios de conexion de todo el
     * mundo — que es exactamente la clase de dato que la Ley 29733 obliga a
     * conservar solo mientras haga falta para su finalidad.
     *
     * Y hay una razon menos legal y mas directa: **lo que no esta guardado no
     * se puede filtrar**. Un servidor comprometido entrega lo que tiene.
     *
     * ## Por que dos ventanas distintas
     *
     * No es lo mismo. `evento_seguridad` existe para que alguien reconozca un
     * acceso raro en "sesiones recientes" y para detectar abuso en curso: a
     * los tres meses ya no sirve para ninguna de las dos cosas. `auditoria` es
     * el rastro de moderacion —quien expulso a quien, quien cambio un rol— y
     * ahi un ano es defendible, porque una decision se puede discutir mucho
     * despues.
     *
     * Las dos salen del entorno: quien despliegue esto puede tener otra
     * obligacion legal, y cambiarla no deberia exigir recompilar.
     */
    fun barrerRegistros(): Pair<Int, Int> = Db.tx { c ->
        val seguridad = System.getenv("WTFUCK_RETENCION_SEGURIDAD_DIAS")?.toIntOrNull() ?: 90
        val auditoria = System.getenv("WTFUCK_RETENCION_AUDITORIA_DIAS")?.toIntOrNull() ?: 365

        // Cero o menos apaga el barrido en vez de borrarlo todo.
        //
        // Importa que sea asi y no al reves: `0` se lee como "sin retencion",
        // y con la otra interpretacion una variable mal puesta vaciaria el
        // registro de seguridad entero sin que nadie lo pidiera.
        val a = if (seguridad > 0) {
            c.prepareStatement(
                "DELETE FROM evento_seguridad WHERE creado_en < now() - make_interval(days => ?)"
            ).use { st -> st.setInt(1, seguridad); st.executeUpdate() }
        } else 0

        val b = if (auditoria > 0) {
            c.prepareStatement(
                "DELETE FROM auditoria WHERE creado_en < now() - make_interval(days => ?)"
            ).use { st -> st.setInt(1, auditoria); st.executeUpdate() }
        } else 0

        a to b
    }

    /**
     * L.1 · Anota que alguien leyo esos mensajes y devuelve a quien avisar.
     *
     * ## Las dos reglas que lo hacen reciproco
     *
     *  1. Si QUIEN LEE tiene las confirmaciones apagadas, no se anota nada y no
     *     se avisa a nadie. No es solo "no lo muestres": el dato no se guarda.
     *  2. Si QUIEN ESCRIBIO las tiene apagadas, tampoco se le avisa. Sin esto,
     *     apagarlas solo te dejaria de mostrar las de los demas mientras los
     *     demas siguen viendo las tuyas, que es al reves de lo que la persona
     *     pidio.
     *
     * Devuelve, por dispositivo del remitente, los ids que hay que marcar.
     */
    fun anotarLectura(
        yo: Auth,
        conversacionId: UUID,
        mensajeIds: List<String>,
    ): Map<UUID, List<String>> = Db.tx { c ->
        if (mensajeIds.isEmpty()) return@tx emptyMap()
        val comparto = c.prepareStatement("SELECT priv_lectura FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: true
        }
        if (!comparto) return@tx emptyMap()

        // Solo mensajes de ESTA conversacion, y solo ajenos: marcar como leido
        // el propio no significa nada y seria una via para inflar la tabla.
        val ids = mensajeIds.take(200).mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (ids.isEmpty()) return@tx emptyMap()

        val avisar = mutableMapOf<UUID, MutableList<String>>()
        c.prepareStatement(
            """SELECT m.id, d.id
               FROM mensaje_meta m
                 JOIN usuario u ON u.id = m.autor_id
                 JOIN dispositivo d ON d.usuario_id = m.autor_id AND d.revocado_en IS NULL
               WHERE m.conversacion_id = ? AND m.autor_id <> ? AND u.priv_lectura
                 AND m.id = ANY (?)"""
        ).use { st ->
            st.setObject(1, conversacionId)
            st.setObject(2, yo.usuarioId)
            st.setArray(3, c.createArrayOf("uuid", ids.toTypedArray()))
            st.executeQuery().use { rs ->
                rs.mapear { it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) }
            }
        }.forEach { (mensaje, dispositivo) ->
            avisar.getOrPut(dispositivo) { mutableListOf() }.add(mensaje.toString())
        }

        // La fila de lectura se guarda igual para los mensajes de quien tiene
        // las confirmaciones apagadas? No: si no se le va a avisar, guardarlo
        // seria acumular un dato que nadie va a usar.
        c.prepareStatement(
            """INSERT INTO lectura (mensaje_id, usuario_id)
               SELECT m.id, ?
               FROM mensaje_meta m JOIN usuario u ON u.id = m.autor_id
               WHERE m.conversacion_id = ? AND m.autor_id <> ? AND u.priv_lectura
                 AND m.id = ANY (?)
               ON CONFLICT DO NOTHING"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.setObject(2, conversacionId)
            st.setObject(3, yo.usuarioId)
            st.setArray(4, c.createArrayOf("uuid", ids.toTypedArray()))
            st.executeUpdate()
        }

        avisar.mapValues { it.value.toList() }
    }

    /**
     * Cuales de MIS mensajes de esa conversacion ya fueron leidos.
     *
     * Hace falta porque el aviso de lectura va por el socket y se pierde si el
     * remitente no estaba conectado. Sin esto, cerrar la app justo cuando el
     * otro lee dejaria ese mensaje en "entregado" para siempre.
     */
    fun mensajesLeidos(yo: Auth, conversacionId: UUID, limite: Int = 200): List<String> =
        Db.query { c ->
            c.prepareStatement(
                """SELECT DISTINCT l.mensaje_id
                   FROM lectura l JOIN mensaje_meta m ON m.id = l.mensaje_id
                   WHERE m.conversacion_id = ? AND m.autor_id = ?
                   ORDER BY 1 DESC LIMIT ?"""
            ).use { st ->
                st.setObject(1, conversacionId)
                st.setObject(2, yo.usuarioId)
                st.setInt(3, limite.coerceIn(1, 500))
                st.executeQuery().use { rs ->
                    rs.mapear { it.getObject(1, UUID::class.java).toString() }
                }
            }
        }


    /**
     * L.1 · Los dispositivos a los que reenviar un "estoy escribiendo".
     *
     * Una sola consulta indexada por rafaga: el cliente manda esto como maximo
     * una vez cada cuatro segundos por conversacion. Y NO escribe nada: la
     * senal se reenvia y se olvida.
     *
     * Excluye los aparatos de quien escribe -verse a uno mismo escribiendo no
     * es informacion- y a quien ya no pertenece a la conversacion.
     */
    /**
     * A que aparatos se les reenvia "esta escribiendo" o "esta grabando".
     *
     * ## El ajuste se comprueba AQUI, y antes no se comprobaba en ningun lado
     *
     * `priv_escribiendo` lo miraba solo el cliente: si no queria avisar, no
     * mandaba el mensaje. Eso deja la privacidad de una persona en manos del
     * programa que tenga instalado, y el §16 del brief lo prohibe con todas las
     * letras: *"nunca confiar unicamente en permisos enviados por el cliente"*.
     * Un cliente modificado —o simplemente viejo— seguia anunciando.
     *
     * Ahora el servidor no reenvia lo que su duena apago, venga de donde venga.
     * El cliente sigue sin mandarlo, que es lo correcto por ancho de banda,
     * pero ya no es lo unico que lo impide.
     *
     * `grabando` es un ajuste aparte porque es otra cosa: teclear dice que hay
     * algo en camino; grabar dice ademas que hay un microfono abierto ahora
     * mismo.
     */
    fun destinosDeEscritura(
        yo: Auth,
        conversacionId: UUID,
        grabando: Boolean = false,
    ): List<UUID> = Db.query { c ->
        val avisa = c.prepareStatement(
            "SELECT priv_escribiendo, priv_grabando FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.primero { if (grabando) it.getBoolean(2) else it.getBoolean(1) }
            } ?: true
        }
        if (!avisa) return@query emptyList()

        c.prepareStatement(
            """SELECT d.id
               FROM participante p
                 JOIN dispositivo d ON d.usuario_id = p.usuario_id AND d.revocado_en IS NULL
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL AND p.usuario_id <> ?
                 AND EXISTS (
                   SELECT 1 FROM participante mio
                   WHERE mio.conversacion_id = p.conversacion_id
                     AND mio.usuario_id = ? AND mio.salido_en IS NULL
                 )"""
        ).use { st ->
            st.setObject(1, conversacionId)
            st.setObject(2, yo.usuarioId)
            st.setObject(3, yo.usuarioId)
            st.executeQuery().use { rs -> rs.mapear { it.getObject(1, UUID::class.java) } }
        }
    }


    // ==================================================================
    //  L.1 · Excepciones de privacidad (el nivel `personalizado`)
    // ==================================================================

    fun excepciones(yo: Auth): List<ExcepcionesPrivacidad> = Db.query { c ->
        val modos = c.prepareStatement("SELECT priv_modo::text FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        }
        val porAjuste = c.prepareStatement(
            """SELECT e.ajuste, u.username
               FROM privacidad_excepcion e JOIN usuario u ON u.id = e.otro_id
               WHERE e.usuario_id = ? ORDER BY e.ajuste, u.username"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear { it.getString(1) to it.getString(2) }
            }
        }.groupBy({ it.first }, { it.second })

        // Se devuelven TODOS los ajustes personalizables, incluso los vacios:
        // la pantalla necesita saber que existen para poder ofrecerlos, y una
        // lista que aparece solo cuando ya tiene contenido es una lista que
        // nadie descubre.
        Privacidad.PERSONALIZABLES.map { ajuste ->
            ExcepcionesPrivacidad(
                ajuste = ajuste,
                modo = modoDe(modos, ajuste),
                usernames = porAjuste[ajuste].orEmpty(),
            )
        }
    }

    /**
     * Reemplaza la lista de un ajuste. No es incremental a proposito: el
     * cliente manda la lista entera y el servidor la deja igual. Un API de
     * "agregar" y "quitar" invita a que dos pantallas abiertas a la vez dejen
     * la lista a medias.
     */
    fun guardarExcepciones(yo: Auth, req: ExcepcionesPrivacidad) = Db.tx { c ->
        if (req.ajuste !in Privacidad.PERSONALIZABLES) {
            throw ErrorNegocio(400, "Ese ajuste no admite excepciones.")
        }
        if (req.modo !in listOf(Privacidad.MODO_SALVO, Privacidad.MODO_SOLO)) {
            throw ErrorNegocio(400, "Modo invalido.")
        }
        // Un tope: una lista de excepciones con miles de nombres no es una
        // excepcion, es otra cosa, y cada peticion de perfil la tendria que
        // cruzar.
        if (req.usernames.size > 200) {
            throw ErrorNegocio(400, "Maximo 200 personas por ajuste.")
        }

        c.prepareStatement(
            """UPDATE usuario
               SET priv_modo = jsonb_set(coalesce(priv_modo, '{}'::jsonb), ARRAY[?], to_jsonb(?::text), true)
               WHERE id = ?"""
        ).use { st ->
            st.setString(1, req.ajuste); st.setString(2, req.modo)
            st.setObject(3, yo.usuarioId); st.executeUpdate()
        }

        c.prepareStatement(
            "DELETE FROM privacidad_excepcion WHERE usuario_id = ? AND ajuste = ?"
        ).use { st ->
            st.setObject(1, yo.usuarioId); st.setString(2, req.ajuste); st.executeUpdate()
        }

        // Los usernames que no existen se ignoran en silencio y NO son un
        // error: la lista se edita a mano y un 400 por un nombre mal escrito
        // haria perder las otras veinte entradas correctas.
        req.usernames.map { it.lowercase().trim() }.distinct().take(200).forEach { u ->
            c.prepareStatement(
                """INSERT INTO privacidad_excepcion (usuario_id, ajuste, otro_id)
                   SELECT ?, ?, id FROM usuario WHERE username = ? AND id <> ?
                   ON CONFLICT DO NOTHING"""
            ).use { st ->
                st.setObject(1, yo.usuarioId); st.setString(2, req.ajuste)
                st.setString(3, u); st.setObject(4, yo.usuarioId)
                st.executeUpdate()
            }
        }
    }

}
