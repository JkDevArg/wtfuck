package com.wtfuck.server

import com.wtfuck.protocol.AlcanceInvitacion
import com.wtfuck.protocol.InvitacionResp
import com.wtfuck.protocol.MisInvitacionesWeb
import com.wtfuck.protocol.ModoRegistroResp
import com.wtfuck.protocol.NuevaInvitacionReq
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Invitaciones para registrarse.
 *
 * ## Por que existe
 *
 * El registro era abierto: quien tuviera el APK se creaba una cuenta. Para un
 * despliegue publico eso esta bien; para el de un equipo o un laboratorio no,
 * y la unica alternativa que habia era no repartir el APK — o sea, no tener
 * app.
 *
 * ## No cobra nada
 *
 * Una invitacion controla QUIEN puede registrarse, no si paga. El registro
 * sigue siendo gratis; lo que deja de ser es anonimo para el servidor, que
 * ahora sabe quien invito a cada quien.
 *
 * ## Abierto por defecto
 *
 * `WTFUCK_REGISTRO` vale `abierto` si no se dice otra cosa. Actualizar el
 * servidor no puede cerrarle el registro a quien no pidio cerrarlo: un cambio
 * que rompe un despliegue ajeno por venir activado de fabrica es peor que uno
 * que hay que encender a mano.
 */
object Invitaciones {

    /** Cuanto staff hace falta para repartir invitaciones. */
    private const val NIVEL_MINIMO = Moderacion.ADMINISTRADOR

    /**
     * El alfabeto de los codigos.
     *
     * Sin `0`, `O`, `1`, `I` ni `l`. Un codigo se dicta por telefono y se
     * copia a mano de una captura, y esas cinco son las que se confunden — un
     * codigo que no entra porque alguien leyo una O donde habia un cero es un
     * fallo de diseno, no de quien lo escribio.
     */
    private const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /**
     * 12 caracteres de 31 simbolos: unos 59 bits.
     *
     * No es una contrasena, pero si algo que se puede probar a ciegas contra
     * una ruta publica. **La defensa es el largo, no un limitador.** Este
     * comentario decia antes que adivinar uno exigia "mas intentos de los que
     * el limitador por IP deja hacer en varias vidas", y el registro no tenia
     * ningun limitador. Ahora lo tiene, y a proposito NO cuenta los codigos
     * equivocados: ver [exigirPuerta]. A diez mil intentos por segundo, 59
     * bits son mas de un millon de anos por codigo.
     */
    private const val LARGO = 12

    private val azar = SecureRandom()

    private fun generar(): String =
        (1..LARGO).map { ALFABETO[azar.nextInt(ALFABETO.length)] }.joinToString("")

    // ------------------------------------------------------------------
    //  El modo
    // ------------------------------------------------------------------

    /** Si este servidor exige invitacion para registrarse. */
    val exigeInvitacion: Boolean by lazy {
        System.getenv("WTFUCK_REGISTRO")?.trim()?.lowercase() == "invitacion"
    }

    /** El username que queda de propietario. Vacio si no se declaro. */
    private val propietario: String by lazy {
        System.getenv("WTFUCK_PROPIETARIO")?.trim()?.lowercase().orEmpty()
    }

    /**
     * El propietario entra sin invitacion. Es el unico que puede.
     *
     * ## El huevo y la gallina que esto resuelve
     *
     * Un servidor que arranca en modo invitacion no tiene ninguna cuenta, y
     * crear invitaciones exige ser administrador. Sin esta excepcion el
     * despliegue queda inservible: nadie puede entrar, nadie puede invitar, y
     * la unica salida es apagarlo, abrirlo, registrarse y volver a cerrarlo.
     *
     * Se encontro probandolo: con `WTFUCK_REGISTRO=invitacion` puesto desde el
     * primer arranque, hasta el propietario recibia
     * "Hace falta un codigo de invitacion".
     *
     * ## Por que no es un agujero
     *
     * Vale para UN username concreto, el que eligio quien desplego el
     * servidor, y solo mientras no exista. En cuanto esa cuenta se crea, el
     * segundo intento choca con la unicidad del username y falla — o sea que
     * la excepcion se cierra sola, sin ninguna marca que mantener.
     *
     * Y quien no sepa ese nombre no puede usarla; quien lo sepa, tampoco, si
     * la cuenta ya existe.
     */
    fun entraSinInvitacion(username: String): Boolean =
        propietario.isNotEmpty() && username.trim().lowercase() == propietario

    /**
     * Lo que se le dice a la app ANTES de mostrar el formulario.
     *
     * Es publico a proposito y no filtra nada: que un servidor exija
     * invitacion se descubre igual intentando registrarse. Decirlo antes evita
     * que alguien rellene un formulario entero para que lo rechacen al final.
     */
    fun modo(): ModoRegistroResp =
        ModoRegistroResp(requiereInvitacion = exigeInvitacion, registroWeb = Correos.disponible)

    // ------------------------------------------------------------------
    //  La puerta, antes del limite de ritmo
    // ------------------------------------------------------------------

    /**
     * Rechaza a quien no trae una invitacion vigente, SIN gastarla.
     *
     * La llama la ruta del registro antes del limite por red, y el orden es la
     * razon de que exista. Al reves, cada intento sin invitacion gastaria cupo
     * de la red de donde viene, y detras de la salida a internet de un campus
     * esta todo el campus: alguien probando codigos inventados dejaria sin
     * poder registrarse a los que SI tienen uno. Con la puerta delante, el que
     * no tiene invitacion no toca el contador de nadie.
     *
     * Lo que se pierde es contar los codigos equivocados, y no hace falta: la
     * defensa contra adivinarlos es su largo ([LARGO]).
     *
     * Esto NO reemplaza a [canjear]. Entre esta lectura y la transaccion del
     * alta, otro puede gastar el ultimo uso; el canje atomico sigue siendo el
     * que decide. Por eso los mensajes son los mismos que los suyos: quien
     * llama no puede saber en cual de los dos sitios lo rechazaron.
     */
    fun exigirPuerta(username: String, codigo: String) {
        if (!exigeInvitacion || entraSinInvitacion(username)) return
        val limpio = normalizar(codigo)
        if (limpio.isEmpty()) {
            throw ErrorNegocio(400, "Hace falta un codigo de invitacion para registrarse.")
        }
        val vigente = Db.query { c ->
            c.prepareStatement(
                """SELECT 1 FROM invitacion_registro
                    WHERE codigo = ?
                      AND revocada_en IS NULL
                      AND (expira_en IS NULL OR expira_en > now())
                      AND usos < usos_max
                      AND alcance = 'general'"""
            ).use { st ->
                st.setString(1, limpio)
                st.executeQuery().use { it.next() }
            }
        }
        if (!vigente) throw ErrorNegocio(403, "El codigo de invitacion no es valido o ya se uso.")
    }

    // ------------------------------------------------------------------
    //  Canjear
    // ------------------------------------------------------------------

    /**
     * Reserva un uso del codigo, o lanza.
     *
     * Se llama DENTRO de la transaccion que crea la cuenta. Si el registro
     * falla despues —un username ya cogido, por ejemplo— la reserva se
     * deshace con todo lo demas, y el codigo no se queda gastado por un alta
     * que nunca ocurrio.
     *
     * ## La carrera, y como se cierra
     *
     * El incremento va en el `WHERE` y no en Kotlin:
     *
     *     UPDATE ... SET usos = usos + 1 WHERE codigo = ? AND usos < usos_max
     *
     * Leer el contador, comprobarlo y escribirlo desde el servidor deja una
     * ventana entre la lectura y la escritura, y dos personas canjeando el
     * ultimo uso a la vez la encuentran. Asi la condicion y el incremento son
     * la misma operacion, y el `CHECK` de la tabla es la segunda red.
     *
     * @return el codigo normalizado, para anotar quien lo uso.
     */
    fun canjear(c: Connection, codigo: String, alcance: String = AlcanceInvitacion.GENERAL): String {
        val limpio = normalizar(codigo)
        if (limpio.isEmpty()) {
            throw ErrorNegocio(400, "Hace falta un codigo de invitacion para registrarse.")
        }

        val ok = c.prepareStatement(
            """UPDATE invitacion_registro
                  SET usos = usos + 1
                WHERE codigo = ?
                  AND revocada_en IS NULL
                  AND (expira_en IS NULL OR expira_en > now())
                  AND usos < usos_max
                  AND alcance = ?"""
        ).use { st ->
            st.setString(1, limpio)
            st.setString(2, alcance)
            st.executeUpdate() == 1
        }

        // Un solo mensaje para "no existe", "caducada", "revocada" y "agotada".
        //
        // Distinguirlos ayudaria a quien se equivoco de letra y tambien a
        // quien esta probando codigos: le diria cuales existen. Y quien tiene
        // una invitacion de verdad no necesita el matiz — la suya funciona.
        if (!ok) throw ErrorNegocio(403, "El codigo de invitacion no es valido o ya se uso.")
        return limpio
    }

    /** Se anota DESPUES de crear la cuenta, cuando ya hay un id que apuntar. */
    fun anotarUso(c: Connection, codigo: String, usuarioId: UUID) {
        c.prepareStatement(
            "INSERT INTO invitacion_uso (codigo, usuario_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        ).use { st ->
            st.setString(1, codigo)
            st.setObject(2, usuarioId)
            st.executeUpdate()
        }
    }

    /**
     * Mayusculas y sin espacios ni guiones.
     *
     * Quien copia un codigo de una captura o lo recibe por voz lo escribe como
     * puede. Rechazarlo por un espacio de mas seria un no gratuito.
     */
    private fun normalizar(codigo: String): String =
        codigo.trim().uppercase().filter { it in ALFABETO }

    // ------------------------------------------------------------------
    //  Repartir
    // ------------------------------------------------------------------

    fun crear(yo: Auth, req: NuevaInvitacionReq): InvitacionResp = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, NIVEL_MINIMO)

        val usos = req.usos.coerceIn(1, 500)
        val alcance = req.alcance.takeIf { it == AlcanceInvitacion.WEB } ?: AlcanceInvitacion.GENERAL
        val expira = req.diasValida
            .takeIf { it > 0 }
            ?.let { Instant.now().plusSeconds(it.toLong() * 86_400) }

        // Se reintenta por si sale un codigo repetido. Con 59 bits no va a
        // pasar, y el bucle cuesta tres lineas: el dia que el alfabeto o el
        // largo cambien, esto sigue siendo correcto sin que nadie lo revise.
        repeat(5) {
            val codigo = generar()
            val puesto = c.prepareStatement(
                """INSERT INTO invitacion_registro (codigo, creada_por, expira_en, usos_max, nota, alcance)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (codigo) DO NOTHING"""
            ).use { st ->
                st.setString(1, codigo)
                st.setObject(2, yo.usuarioId)
                if (expira == null) st.setNull(3, java.sql.Types.TIMESTAMP)
                else st.setObject(3, java.sql.Timestamp.from(expira))
                st.setInt(4, usos)
                st.setString(5, req.nota.take(120))
                st.setString(6, alcance)
                st.executeUpdate() == 1
            }
            if (puesto) {
                // El codigo va en `detalle` y no en `recursoId`: ese campo es un UUID
                // y un codigo no lo es. Forzarlo seria inventarse un id.
                Autz.auditar(
                    c, yo.usuarioId, "invitacion.creada", "invitacion_registro",
                    recursoId = null, detalle = codigo,
                )
                return@tx InvitacionResp(
                    codigo = codigo,
                    creadaEn = Instant.now().toEpochMilli(),
                    expiraEn = expira?.toEpochMilli() ?: 0,
                    usos = 0,
                    usosMax = usos,
                    revocada = false,
                    nota = req.nota.take(120),
                    alcance = alcance,
                )
            }
        }
        throw ErrorNegocio(500, "No se pudo generar un codigo. Intenta de nuevo.")
    }

    fun listar(yo: Auth): List<InvitacionResp> = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, NIVEL_MINIMO)
        c.prepareStatement(
            """SELECT codigo, creada_en, expira_en, usos, usos_max, revocada_en, nota, alcance
                 FROM invitacion_registro
                ORDER BY creada_en DESC
                LIMIT 200"""
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.mapear {
                    InvitacionResp(
                        codigo = it.getString(1),
                        creadaEn = it.getTimestamp(2).time,
                        expiraEn = it.getTimestamp(3)?.time ?: 0,
                        usos = it.getInt(4),
                        usosMax = it.getInt(5),
                        revocada = it.getTimestamp(6) != null,
                        nota = it.getString(7).orEmpty(),
                        alcance = it.getString(8),
                    )
                }
            }
        }
    }

    /**
     * Revocar no borra.
     *
     * Borrar la fila se llevaria por delante, en cascada, el rastro de quien
     * entro con ese codigo — que es exactamente lo que hace falta conservar
     * cuando se revoca algo. Se marca y se queda.
     */
    fun revocar(yo: Auth, codigo: String) = Db.tx { c ->
        Moderacion.exigirStaff(c, yo.usuarioId, NIVEL_MINIMO)
        val n = c.prepareStatement(
            "UPDATE invitacion_registro SET revocada_en = now() WHERE codigo = ? AND revocada_en IS NULL"
        ).use { st ->
            st.setString(1, normalizar(codigo))
            st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "No se encontro.")
        Autz.auditar(
            c, yo.usuarioId, "invitacion.revocada", "invitacion_registro",
            recursoId = null, detalle = normalizar(codigo),
        )
    }

    // ------------------------------------------------------------------
    //  W5 · Invitaciones WEB
    // ------------------------------------------------------------------
    //
    // El registro desde el navegador pide, ademas del correo verificado, una
    // invitacion de alcance `web`. Las crea el staff (desde el panel, con
    // `alcance = web`) o cualquier usuario, con cupo: es la forma de que alguien
    // con Android le abra la puerta a un amigo con iPhone.
    //
    // ## Por que con cupo y de un solo uso
    //
    // La invitacion es lo que pone el COSTO contra las cuentas automaticas: un
    // correo se consigue gratis, una invitacion no. De un solo uso y con cupo,
    // quien le pasa codigos a un bot le pasa pocos, y queda escrito quien
    // invito a quien (`invitacion_uso` + `creada_por`): si aparecen bots, se
    // corta por la raiz.

    /** Vigentes a la vez por usuario. */
    const val CUPO_WEB_VIGENTES = 5

    /** Creadas por usuario en 30 dias, vigentes o no. */
    const val CUPO_WEB_MES = 20

    private const val DIAS_WEB = 7L

    /** La puerta del registro web. No gasta: se gasta al registrarse. */
    fun exigirPuertaWeb(c: Connection, codigo: String) {
        val limpio = normalizar(codigo)
        if (limpio.isEmpty()) {
            throw ErrorNegocio(400, "Para crear una cuenta desde la web hace falta una invitacion.")
        }
        val vigente = c.prepareStatement(
            """SELECT 1 FROM invitacion_registro
                WHERE codigo = ? AND alcance = 'web'
                  AND revocada_en IS NULL
                  AND (expira_en IS NULL OR expira_en > now())
                  AND usos < usos_max"""
        ).use { st ->
            st.setString(1, limpio)
            st.executeQuery().use { it.next() }
        }
        if (!vigente) throw ErrorNegocio(403, "El codigo de invitacion no es valido o ya se uso.")
    }

    fun crearWeb(yo: Auth): InvitacionResp = Db.tx { c ->
        // Una cuenta que nacio en la web tambien invita: es justo el caso de un
        // grupo de amigos con iPhone. Lo que pone el limite es el cupo.
        val (vigentes, delMes) = contarWeb(c, yo.usuarioId)
        if (vigentes >= CUPO_WEB_VIGENTES) {
            throw ErrorNegocio(
                429,
                "Ya tienes $CUPO_WEB_VIGENTES invitaciones sin usar. Revoca una o espera a que la usen.",
            )
        }
        if (delMes >= CUPO_WEB_MES) {
            throw ErrorNegocio(429, "Llegaste al tope de $CUPO_WEB_MES invitaciones en 30 dias.")
        }
        val expira = Instant.now().plusSeconds(DIAS_WEB * 86_400)
        repeat(5) {
            val codigo = generar()
            val puesto = c.prepareStatement(
                """INSERT INTO invitacion_registro (codigo, creada_por, expira_en, usos_max, nota, alcance)
                   VALUES (?, ?, ?, 1, '', 'web')
                   ON CONFLICT (codigo) DO NOTHING"""
            ).use { st ->
                st.setString(1, codigo)
                st.setObject(2, yo.usuarioId)
                st.setObject(3, java.sql.Timestamp.from(expira))
                st.executeUpdate() == 1
            }
            if (puesto) {
                Autz.auditar(
                    c, yo.usuarioId, "invitacion_web.creada", "invitacion_registro",
                    recursoId = null, detalle = codigo,
                )
                return@tx InvitacionResp(
                    codigo = codigo,
                    creadaEn = Instant.now().toEpochMilli(),
                    expiraEn = expira.toEpochMilli(),
                    usos = 0,
                    usosMax = 1,
                    revocada = false,
                    nota = "",
                    alcance = AlcanceInvitacion.WEB,
                )
            }
        }
        throw ErrorNegocio(500, "No se pudo generar un codigo. Intenta de nuevo.")
    }

    /** Mis invitaciones web de los ultimos 30 dias, y cuantas me quedan. */
    fun misWeb(yo: Auth): MisInvitacionesWeb = Db.query { c ->
        val lista = c.prepareStatement(
            """SELECT codigo, creada_en, expira_en, usos, usos_max, revocada_en
                 FROM invitacion_registro
                WHERE creada_por = ? AND alcance = 'web'
                  AND creada_en > now() - interval '30 days'
                ORDER BY creada_en DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    InvitacionResp(
                        codigo = it.getString(1),
                        creadaEn = it.getTimestamp(2).time,
                        expiraEn = it.getTimestamp(3)?.time ?: 0,
                        usos = it.getInt(4),
                        usosMax = it.getInt(5),
                        revocada = it.getTimestamp(6) != null,
                        nota = "",
                        alcance = AlcanceInvitacion.WEB,
                    )
                }
            }
        }
        val (vigentes, delMes) = contarWeb(c, yo.usuarioId)
        MisInvitacionesWeb(
            invitaciones = lista,
            disponibles = minOf(CUPO_WEB_VIGENTES - vigentes, CUPO_WEB_MES - delMes).coerceAtLeast(0),
        )
    }

    /** Solo las mias. Una ajena da 404, igual que una que no existe. */
    fun revocarWeb(yo: Auth, codigo: String) = Db.tx { c ->
        val n = c.prepareStatement(
            """UPDATE invitacion_registro SET revocada_en = now()
                WHERE codigo = ? AND creada_por = ? AND alcance = 'web' AND revocada_en IS NULL"""
        ).use { st ->
            st.setString(1, normalizar(codigo))
            st.setObject(2, yo.usuarioId)
            st.executeUpdate()
        }
        if (n == 0) throw ErrorNegocio(404, "No se encontro.")
    }

    private fun contarWeb(c: Connection, usuarioId: UUID): Pair<Int, Int> =
        c.prepareStatement(
            """SELECT count(*) FILTER (WHERE revocada_en IS NULL AND usos < usos_max
                                         AND (expira_en IS NULL OR expira_en > now())),
                      count(*)
                 FROM invitacion_registro
                WHERE creada_por = ? AND alcance = 'web'
                  AND creada_en > now() - interval '30 days'"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) }
        }
}
