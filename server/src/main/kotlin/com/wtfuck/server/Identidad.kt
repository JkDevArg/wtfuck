package com.wtfuck.server

import com.wtfuck.protocol.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Modulo I: identidad y cuenta.
 *
 * ## La decision central: el numero se guarda como hash y aun asi recupera
 *
 * Parece imposible -no se le puede mandar un SMS a un hash- y se resuelve
 * mirando *cuando* hace falta el numero: siempre esta delante en el momento de
 * usarlo. Al verificar lo escribe el usuario; al recuperar lo vuelve a
 * escribir. El servidor lo normaliza, lo hashea, compara con lo guardado, y
 * manda el codigo al numero que acaba de recibir. Nunca necesita tenerlo.
 *
 * ## El pepper es lo que hace que el hash sirva de algo
 *
 * Un telefono son nueve digitos utiles: mil millones de candidatos, que para un
 * hash rapido son minutos. Sin pepper, una fuga de la base entrega la agenda
 * entera de la plataforma. El hash se calcula con HMAC y una clave que vive en
 * `WTFUCK_PEPPER_TELEFONO`, **fuera de la base**: con la base sola no se puede
 * probar ningun candidato.
 *
 * Esa es tambien la razon por la que alcanza un hash rapido. Con Argon2 cada
 * hash llevaria su propia sal y no se podria BUSCAR por hash, y buscar es
 * justamente lo que hace falta para verificar unicidad y para descubrir.
 */
object Identidad {

    /** Vida del codigo. Diez minutos: suficiente para leer el SMS, poco para robarlo. */
    private val VIDA_CODIGO: Duration = Duration.ofMinutes(10)

    /** Espera entre dos pedidos del mismo codigo. */
    private val ESPERA_REENVIO: Duration = Duration.ofSeconds(60)

    /**
     * Intentos por codigo. Seis digitos son un millon de combinaciones, que a
     * fuerza bruta no es nada: sin este tope el vencimiento no protegeria.
     */
    private const val MAX_INTENTOS = 5

    /** Dias de gracia. El numero vive en el contrato: la pantalla tambien lo necesita. */
    const val DIAS_GRACIA = DIAS_GRACIA_ELIMINACION

    private const val CODIGOS_RESPALDO = 8

    private val rnd = SecureRandom()

    // ============================================================
    //  Hash del telefono
    // ============================================================

    /**
     * El pepper. Sin configurar, uno derivado y una advertencia ruidosa.
     *
     * No se cae si falta a proposito: en desarrollo hace falta que el servidor
     * arranque. Pero deja claro en la bitacora que los hashes de ese entorno no
     * valen nada, porque el pepper es deducible.
     */
    private val pepper: ByteArray by lazy {
        val v = System.getenv("WTFUCK_PEPPER_TELEFONO")?.takeIf { it.isNotBlank() }
        if (v == null) {
            org.slf4j.LoggerFactory.getLogger("Identidad").warn(
                "Sin WTFUCK_PEPPER_TELEFONO: los hashes de telefono de este entorno se " +
                    "pueden romper por fuerza bruta. Configurarlo antes de produccion.",
            )
            "wtfuck-pepper-de-desarrollo-no-usar-en-produccion".toByteArray()
        } else {
            v.toByteArray()
        }
    }

    /**
     * HMAC-SHA256(pepper, numero en E.164).
     *
     * HMAC y no `sha256(pepper || numero)` porque lo segundo es vulnerable a
     * extension de longitud. Aqui no se explota facil, pero no hay motivo para
     * elegir la version fragil cuando la correcta cuesta lo mismo.
     *
     * **Recibe el numero YA NORMALIZADO.** Que la normalizacion quede fuera de
     * esta funcion es a proposito: asi hay un solo punto donde se normaliza
     * -`SmsFactory.exigirTelefono`- y es imposible hashear un numero sin
     * canonizar. Si cada sitio normalizara por su cuenta, el mismo telefono
     * escrito de dos maneras daria dos hashes y el descubrimiento fallaria en
     * silencio.
     */
    fun hashTelefono(e164: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper, "HmacSHA256"))
        return mac.doFinal(e164.toByteArray())
    }

    // ============================================================
    //  I.2 · Pedir un codigo
    // ============================================================

    fun pedirCodigo(
        sms: Sms,
        yo: Auth?,
        req: PedirCodigoReq,
        ip: String,
    ): CodigoPedido = Db.tx { c ->
        val e164 = SmsFactory.exigirTelefono(req.telefono)
        val hash = hashTelefono(e164)

        if (req.proposito !in listOf(
                PropositoCodigo.VERIFICAR_TELEFONO,
                PropositoCodigo.RECUPERAR_CUENTA,
                PropositoCodigo.ELIMINAR_CUENTA,
            )
        ) throw ErrorNegocio(400, "Proposito desconocido.")

        // Por destino primero, que es el limite que de verdad protege a una
        // persona, y por IP despues y holgado, que solo tiene que tolerar el
        // NAT de una institucion. El orden importa poco; la calibracion, mucho.
        //
        // La clave del destino es el HASH en base64, no el numero: el limitador
        // vive en memoria y no hay razon para tener telefonos dando vueltas en
        // un mapa de proceso.
        val claveDestino = java.util.Base64.getEncoder().encodeToString(hash)
        Limitador.exigir(yo?.usuarioId, claveDestino, "codigo_destino", Limitador.PEDIR_CODIGO_DESTINO)
        Limitador.exigir(yo?.usuarioId, ip, "codigo_ip", Limitador.PEDIR_CODIGO_IP)

        val usuarioId = when (req.proposito) {
            PropositoCodigo.VERIFICAR_TELEFONO -> {
                val a = yo ?: throw ErrorNegocio(401, "Hace falta sesion para verificar un numero.")
                // Que otro ya lo tenga se comprueba ANTES de mandar el codigo:
                // si no, la persona recibe un SMS, escribe el codigo, y recien
                // ahi se enteraria de que no se puede.
                val duenoActual = usuarioPorTelefono(c, hash)
                if (duenoActual != null && duenoActual != a.usuarioId) {
                    throw ErrorNegocio(409, "Ese numero ya esta verificado en otra cuenta.")
                }
                a.usuarioId
            }

            PropositoCodigo.RECUPERAR_CUENTA -> {
                val user = req.username?.trim()?.lowercase()
                    ?: throw ErrorNegocio(400, "Falta el usuario.")
                val id = c.prepareStatement(
                    "SELECT id FROM usuario WHERE username = ? AND telefono_hash = ?"
                ).use { st ->
                    st.setString(1, user); st.setBytes(2, hash)
                    st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
                }
                // Si no coincide NO se dice. Distinguir "ese usuario no existe"
                // de "ese numero no es el de esa cuenta" convierte esta ruta en
                // un oraculo para averiguar de quien es un telefono.
                if (id == null) {
                    Seguridad.anotar(
                        c, null, "ingreso_fallido", ip = ip,
                        detalle = """{"accion":"recuperar","username":"$user"}""",
                    )
                    return@tx CodigoPedido(
                        reintentarEnSegundos = ESPERA_REENVIO.seconds.toInt(),
                        expiraEnSegundos = VIDA_CODIGO.seconds.toInt(),
                    )
                }
                id
            }

            else -> {
                val a = yo ?: throw ErrorNegocio(401, "Hace falta sesion.")
                a.usuarioId
            }
        }

        // Un codigo pedido hace menos de un minuto no se reemplaza: se le dice
        // que espere. Si no, tocar "reenviar" invalidaria el codigo que la
        // persona ya tiene delante en el SMS.
        vivoReciente(c, hash, req.proposito)?.let { faltan ->
            throw ErrorNegocio(429, "Ya te enviamos un codigo. Espera $faltan segundos.")
        }

        // Los anteriores del mismo destino y proposito se queman: dos codigos
        // validos a la vez duplican la superficie por nada.
        c.prepareStatement(
            """UPDATE codigo_verificacion SET usado_en = now()
               WHERE destino_hash = ? AND proposito = ? AND usado_en IS NULL"""
        ).use { st -> st.setBytes(1, hash); st.setString(2, req.proposito); st.executeUpdate() }

        val codigo = nuevoCodigo()
        c.prepareStatement(
            """INSERT INTO codigo_verificacion
                 (usuario_id, proposito, destino_hash, codigo_hash, expira_en)
               VALUES (?, ?, ?, ?, now() + make_interval(secs => ?))"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setString(2, req.proposito)
            st.setBytes(3, hash)
            st.setBytes(4, Cripto.hashToken(codigo))
            st.setDouble(5, VIDA_CODIGO.seconds.toDouble())
            st.executeUpdate()
        }

        // El envio va DESPUES del insert y no lanza si falla: un fallo de la
        // pasarela no debe revertir la transaccion y borrar un codigo que tal
        // vez si llego.
        sms.enviarCodigo(e164, codigo, req.proposito)

        Seguridad.anotar(
            c, usuarioId, "ingreso", ip = ip,
            detalle = """{"accion":"codigo_enviado","proposito":"${req.proposito}"}""",
        )

        CodigoPedido(
            reintentarEnSegundos = ESPERA_REENVIO.seconds.toInt(),
            expiraEnSegundos = VIDA_CODIGO.seconds.toInt(),
            // Solo sin pasarela configurada. El nombre del campo grita lo que es.
            codigoDePrueba = if (sms.real) null else codigo,
        )
    }

    /** Segundos que faltan para poder pedir otro, o null si ya se puede. */
    private fun vivoReciente(c: Connection, hash: ByteArray, proposito: String): Long? =
        c.prepareStatement(
            """SELECT ceil(? - EXTRACT(EPOCH FROM (now() - creado_en)))::bigint
               FROM codigo_verificacion
               WHERE destino_hash = ? AND proposito = ? AND usado_en IS NULL
                 AND creado_en > now() - make_interval(secs => ?)
               ORDER BY creado_en DESC LIMIT 1"""
        ).use { st ->
            st.setDouble(1, ESPERA_REENVIO.seconds.toDouble())
            st.setBytes(2, hash)
            st.setString(3, proposito)
            st.setDouble(4, ESPERA_REENVIO.seconds.toDouble())
            st.executeQuery().use { rs -> rs.primero { it.getLong(1) } }
        }?.takeIf { it > 0 }

    /**
     * Seis digitos, con ceros a la izquierda si toca.
     *
     * `SecureRandom` y no `Random`: un codigo de recuperacion predecible es una
     * puerta abierta, y el coste de la version correcta es cero.
     */
    private fun nuevoCodigo(): String = "%06d".format(rnd.nextInt(1_000_000))

    /**
     * Canjea un codigo. Devuelve el usuario al que pertenece.
     *
     * Marca el uso DENTRO de la misma transaccion que la accion que lo consume,
     * asi que si la accion falla el codigo sigue valiendo. Y cuenta el intento
     * fallido antes de rechazar, que es lo que hace que el tope sirva.
     */
    private fun canjear(
        c: Connection,
        hashDestino: ByteArray,
        proposito: String,
        codigo: String,
    ): UUID? {
        val fila = c.prepareStatement(
            """SELECT id, usuario_id, codigo_hash, intentos
               FROM codigo_verificacion
               WHERE destino_hash = ? AND proposito = ? AND usado_en IS NULL
                 AND expira_en > now()
               ORDER BY creado_en DESC LIMIT 1
               FOR UPDATE"""
        ).use { st ->
            st.setBytes(1, hashDestino); st.setString(2, proposito)
            st.executeQuery().use { rs ->
                rs.primero {
                    Cuadruple(
                        it.getObject(1, UUID::class.java),
                        it.getObject(2, UUID::class.java),
                        it.getBytes(3),
                        it.getInt(4),
                    )
                }
            }
        } ?: throw ErrorNegocio(400, "El codigo no existe o ya vencio. Pide uno nuevo.")

        if (fila.d >= MAX_INTENTOS) {
            // Se quema para que no quede uno casi agotado dando vueltas.
            c.prepareStatement("UPDATE codigo_verificacion SET usado_en = now() WHERE id = ?")
                .use { st -> st.setObject(1, fila.a); st.executeUpdate() }
            throw ErrorNegocio(429, "Demasiados intentos. Pide un codigo nuevo.")
        }

        if (!MessageDigest.isEqual(Cripto.hashToken(codigo.trim()), fila.c)) {
            c.prepareStatement("UPDATE codigo_verificacion SET intentos = intentos + 1 WHERE id = ?")
                .use { st -> st.setObject(1, fila.a); st.executeUpdate() }
            throw ErrorNegocio(400, "El codigo no coincide. Te quedan ${MAX_INTENTOS - fila.d - 1} intentos.")
        }

        c.prepareStatement("UPDATE codigo_verificacion SET usado_en = now() WHERE id = ?")
            .use { st -> st.setObject(1, fila.a); st.executeUpdate() }
        return fila.b
    }

    private data class Cuadruple(val a: UUID, val b: UUID?, val c: ByteArray, val d: Int)

    // ============================================================
    //  I.1 · Verificar el telefono
    // ============================================================

    fun verificarTelefono(yo: Auth, req: VerificarTelefonoReq): TelefonoVerificado = Db.tx { c ->
        val e164 = SmsFactory.exigirTelefono(req.telefono)
        val hash = hashTelefono(e164)
        val pais = Telefonos.paisDe(e164)

        val dueno = canjear(c, hash, PropositoCodigo.VERIFICAR_TELEFONO, req.codigo)
        if (dueno != yo.usuarioId) {
            throw ErrorNegocio(403, "Ese codigo no es de esta cuenta.")
        }

        val filas = c.prepareStatement(
            """UPDATE usuario
               SET telefono_hash = ?, telefono_pais = ?, telefono_verificado_en = now()
               WHERE id = ?"""
        ).use { st ->
            st.setBytes(1, hash); st.setString(2, pais); st.setObject(3, yo.usuarioId)
            runCatching { st.executeUpdate() }.getOrElse {
                throw ErrorNegocio(409, "Ese numero ya esta verificado en otra cuenta.")
            }
        }
        if (filas == 0) throw ErrorNegocio(404, "Cuenta no encontrada.")

        Autz.auditar(
            c, yo.usuarioId, "cuenta.telefono_verificado", "usuario", yo.usuarioId,
            detalle = """{"pais":"$pais"}""",
        )
        TelefonoVerificado(pais, Telefonos.ofuscar(e164), System.currentTimeMillis())
    }

    /** Quitar el numero. El hecho de haberlo verificado queda en la auditoria. */
    fun quitarTelefono(yo: Auth) = Db.tx { c ->
        c.prepareStatement(
            """UPDATE usuario
               SET telefono_hash = NULL, telefono_pais = NULL, telefono_verificado_en = NULL
               WHERE id = ?"""
        ).use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }
        Autz.auditar(c, yo.usuarioId, "cuenta.telefono_quitado", "usuario", yo.usuarioId)
        Unit
    }

    // ============================================================
    //  I.3 · Recuperar la cuenta
    // ============================================================

    /**
     * Cambia la contrasena con un codigo recibido por SMS.
     *
     * **Cierra todas las sesiones.** Quien recupera una cuenta o se olvido la
     * contrasena, o se la robaron; en el segundo caso dejar las sesiones vivas
     * haria que el cambio no sirviera para nada.
     */
    fun recuperar(req: RecuperarReq, ip: String) = Db.tx { c ->
        val e164 = SmsFactory.exigirTelefono(req.telefono)
        if (req.passwordNueva.length < 10) {
            throw ErrorNegocio(400, "La contrasena nueva necesita al menos 10 caracteres.")
        }

        val hash = hashTelefono(e164)
        val user = req.username.trim().lowercase()

        val id = c.prepareStatement(
            "SELECT id FROM usuario WHERE username = ? AND telefono_hash = ?"
        ).use { st ->
            st.setString(1, user); st.setBytes(2, hash)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: throw ErrorNegocio(400, "El codigo no existe o ya vencio. Pide uno nuevo.")

        val dueno = canjear(c, hash, PropositoCodigo.RECUPERAR_CUENTA, req.codigo)
        if (dueno != id) throw ErrorNegocio(403, "Ese codigo no es de esta cuenta.")

        c.prepareStatement("UPDATE usuario SET password_hash = ? WHERE id = ?").use { st ->
            st.setString(1, Cripto.hashPassword(req.passwordNueva))
            st.setObject(2, id)
            st.executeUpdate()
        }

        val cerradas = revocarTodas(c, id, salvo = null, porQuien = id)

        Seguridad.anotar(
            c, id, "ingreso", ip = ip,
            detalle = """{"accion":"recuperada","sesiones_cerradas":$cerradas}""",
        )
        Autz.auditar(c, id, "cuenta.recuperada", "usuario", id)
        Unit
    }

    // ============================================================
    //  I.5 · Sesiones
    // ============================================================

    fun sesiones(yo: Auth): List<SesionActiva> = Db.query { c ->
        c.prepareStatement(
            """SELECT s.id,
                      (EXTRACT(EPOCH FROM s.emitido_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM s.ultimo_uso_en) * 1000)::bigint,
                      (EXTRACT(EPOCH FROM s.expira_en) * 1000)::bigint,
                      host(s.ip), s.agente
               FROM sesion s
                 JOIN dispositivo d ON d.id = s.dispositivo_id
               WHERE d.usuario_id = ? AND s.revocado_en IS NULL AND s.expira_en > now()
               ORDER BY coalesce(s.ultimo_uso_en, s.emitido_en) DESC"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    val id = it.getObject(1, UUID::class.java)
                    SesionActiva(
                        id = id.toString(),
                        esLaActual = id == yo.sesionId,
                        emitidoEn = it.getLong(2),
                        ultimoUsoEn = it.getLong(3).takeIf { v -> v > 0 },
                        expiraEn = it.getLong(4),
                        ip = it.getString(5),
                        agente = it.getString(6),
                    )
                }
            }
        }
    }

    /**
     * Cierra una sesion ajena.
     *
     * Devuelve los avisos a empujar: el dispositivo cerrado tiene que
     * enterarse. Sin eso seguiria mostrando la app como si nada hasta el
     * proximo intento de hablar con el servidor.
     */
    fun cerrarSesion(yo: Auth, sesionId: UUID): List<Pair<UUID, Bajada.Evento>> = Db.tx { c ->
        val dispositivo = c.prepareStatement(
            """SELECT s.dispositivo_id
               FROM sesion s JOIN dispositivo d ON d.id = s.dispositivo_id
               WHERE s.id = ? AND d.usuario_id = ? AND s.revocado_en IS NULL"""
        ).use { st ->
            st.setObject(1, sesionId); st.setObject(2, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        } ?: throw ErrorNegocio(404, "Esa sesion no existe o ya estaba cerrada.")

        revocar(c, sesionId, yo.usuarioId)
        Seguridad.anotar(c, yo.usuarioId, "sesion_cerrada", detalle = """{"remota":true}""")

        // Solo se avisa si se cerro OTRA sesion. Avisarle al propio dispositivo
        // que cerro su propia sesion es decirle lo que acaba de hacer.
        if (sesionId == yo.sesionId) emptyList()
        else Eventos.emitir(c, listOf(yo.usuarioId), "sesion_revocada", null, yo.username, null)
            .filter { it.first == dispositivo }
    }

    /** Cierra todas menos la actual. */
    fun cerrarOtras(yo: Auth): Int = Db.tx { c ->
        val n = revocarTodas(c, yo.usuarioId, salvo = yo.sesionId, porQuien = yo.usuarioId)
        Seguridad.anotar(c, yo.usuarioId, "sesion_cerrada", detalle = """{"otras":$n}""")
        n
    }

    private fun revocar(c: Connection, sesionId: UUID, porQuien: UUID) {
        c.prepareStatement(
            "UPDATE sesion SET revocado_en = now(), revocada_por = ? WHERE id = ? AND revocado_en IS NULL"
        ).use { st -> st.setObject(1, porQuien); st.setObject(2, sesionId); st.executeUpdate() }
    }

    private fun revocarTodas(c: Connection, usuarioId: UUID, salvo: UUID?, porQuien: UUID): Int =
        c.prepareStatement(
            """UPDATE sesion SET revocado_en = now(), revocada_por = ?
               WHERE dispositivo_id IN (SELECT id FROM dispositivo WHERE usuario_id = ?)
                 AND revocado_en IS NULL
                 AND (?::uuid IS NULL OR id <> ?::uuid)"""
        ).use { st ->
            st.setObject(1, porQuien)
            st.setObject(2, usuarioId)
            st.setObject(3, salvo)
            st.setObject(4, salvo)
            st.executeUpdate()
        }

    /** Cerrar la sesion propia. Existe para que `sesion_cerrada` tenga quien lo escriba. */
    fun salir(yo: Auth) = Db.tx { c ->
        revocar(c, yo.sesionId, yo.usuarioId)
        Seguridad.anotar(c, yo.usuarioId, "sesion_cerrada", detalle = """{"remota":false}""")
        Unit
    }

    // ============================================================
    //  I.6 · 2FA (TOTP)
    // ============================================================

    /**
     * Genera un secreto y lo guarda SIN activar.
     *
     * Guardarlo antes de confirmar es lo que permite que la app de
     * autenticacion y el servidor comparen el mismo secreto. Queda inerte
     * mientras `totp_activado_en` sea null: un secreto sin activar no cambia
     * como se ingresa.
     */
    fun iniciarTotp(yo: Auth): TotpIniciado = Db.tx { c ->
        val secreto = ByteArray(20).also { rnd.nextBytes(it) }
        c.prepareStatement(
            "UPDATE usuario SET totp_secreto = ?, totp_activado_en = NULL WHERE id = ?"
        ).use { st -> st.setBytes(1, secreto); st.setObject(2, yo.usuarioId); st.executeUpdate() }

        val b32 = Totp.aBase32(secreto)
        TotpIniciado(
            secretoBase32 = b32,
            uri = "otpauth://totp/wtfuck:${yo.username}?secret=$b32&issuer=wtfuck&digits=6&period=30",
        )
    }

    fun confirmarTotp(yo: Auth, req: TotpConfirmarReq): TotpActivado = Db.tx { c ->
        val secreto = c.prepareStatement("SELECT totp_secreto FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBytes(1) } }
        } ?: throw ErrorNegocio(409, "Primero hay que generar el secreto.")

        if (!Totp.valido(secreto, req.codigo)) {
            throw ErrorNegocio(400, "Ese codigo no coincide. Revisa la hora del telefono.")
        }

        c.prepareStatement("UPDATE usuario SET totp_activado_en = now() WHERE id = ?")
            .use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }

        // Los de respaldo se rehacen al activar: los de un intento anterior
        // abandonado no deben seguir valiendo.
        c.prepareStatement("DELETE FROM codigo_respaldo WHERE usuario_id = ?")
            .use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }

        val codigos = List(CODIGOS_RESPALDO) { nuevoRespaldo() }
        c.prepareStatement("INSERT INTO codigo_respaldo (usuario_id, codigo_hash) VALUES (?, ?)")
            .use { st ->
                codigos.forEach {
                    st.setObject(1, yo.usuarioId)
                    st.setBytes(2, Cripto.hashToken(it))
                    st.addBatch()
                }
                st.executeBatch()
            }

        Seguridad.anotar(c, yo.usuarioId, "ingreso", detalle = """{"accion":"totp_activado"}""")
        Autz.auditar(c, yo.usuarioId, "cuenta.totp_activado", "usuario", yo.usuarioId)
        TotpActivado(codigos)
    }

    /**
     * Apagar el 2FA exige la contrasena.
     *
     * Sin eso, una sesion robada podria quitar el segundo factor y quedarse con
     * la cuenta, que es exactamente lo que el segundo factor venia a impedir.
     */
    fun apagarTotp(yo: Auth, password: String) = Db.tx { c ->
        val hash = c.prepareStatement("SELECT password_hash FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Cuenta no encontrada.")
        if (!Cripto.verificarPassword(password, hash)) {
            throw ErrorNegocio(403, "Contrasena incorrecta.")
        }

        c.prepareStatement(
            "UPDATE usuario SET totp_secreto = NULL, totp_activado_en = NULL WHERE id = ?"
        ).use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }
        c.prepareStatement("DELETE FROM codigo_respaldo WHERE usuario_id = ?")
            .use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }

        Autz.auditar(c, yo.usuarioId, "cuenta.totp_apagado", "usuario", yo.usuarioId)
        Unit
    }

    /**
     * ¿Le hace falta segundo factor a esta cuenta, y el que dieron sirve?
     *
     * Acepta el TOTP o un codigo de respaldo, y el de respaldo se consume. Lo
     * usa el login.
     */
    fun exigirSegundoFactor(c: Connection, usuarioId: UUID, codigo: String?) {
        val fila = c.prepareStatement(
            "SELECT totp_secreto, totp_activado_en IS NOT NULL FROM usuario WHERE id = ?"
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBytes(1) to it.getBoolean(2) } }
        } ?: return

        val (secreto, activo) = fila
        if (!activo || secreto == null) return

        val dado = codigo?.trim()?.replace(" ", "")
        if (dado.isNullOrEmpty()) {
            throw ErrorNegocio(401, "Esta cuenta pide un codigo de verificacion en dos pasos.")
        }
        if (Totp.valido(secreto, dado)) return

        // Codigo de respaldo. Se consume con el UPDATE mismo: si se leyera
        // primero y se marcara despues, dos peticiones a la vez usarian el
        // mismo codigo.
        val usado = c.prepareStatement(
            """UPDATE codigo_respaldo SET usado_en = now()
               WHERE id = (
                 SELECT id FROM codigo_respaldo
                 WHERE usuario_id = ? AND usado_en IS NULL AND codigo_hash = ?
                 FOR UPDATE SKIP LOCKED LIMIT 1
               )"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setBytes(2, Cripto.hashToken(dado.uppercase()))
            st.executeUpdate()
        }
        if (usado == 0) throw ErrorNegocio(401, "El codigo de dos pasos no coincide.")
    }

    /** Letras y numeros sin los que se confunden: 0/O, 1/I/L. */
    private fun nuevoRespaldo(): String {
        val abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..10).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            .let { it.substring(0, 5) + "-" + it.substring(5) }
    }

    // ============================================================
    //  I.7 · Eliminar la cuenta
    // ============================================================

    fun pedirEliminacion(yo: Auth, req: EliminarCuentaReq): EliminacionPedida = Db.tx { c ->
        val hash = c.prepareStatement("SELECT password_hash FROM usuario WHERE id = ?").use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        } ?: throw ErrorNegocio(404, "Cuenta no encontrada.")

        // Contrasena y segundo factor: es la accion mas destructiva que existe
        // en el producto, y una sesion robada no deberia poder ejecutarla.
        if (!Cripto.verificarPassword(req.password, hash)) {
            throw ErrorNegocio(403, "Contrasena incorrecta.")
        }
        exigirSegundoFactor(c, yo.usuarioId, req.totp)

        c.prepareStatement("UPDATE usuario SET eliminacion_pedida_en = now() WHERE id = ?")
            .use { st -> st.setObject(1, yo.usuarioId); st.executeUpdate() }

        revocarTodas(c, yo.usuarioId, salvo = null, porQuien = yo.usuarioId)
        Autz.auditar(c, yo.usuarioId, "cuenta.eliminacion_pedida", "usuario", yo.usuarioId)

        val ahora = System.currentTimeMillis()
        EliminacionPedida(
            pedidaEn = ahora,
            seEjecutaEn = ahora + Duration.ofDays(DIAS_GRACIA.toLong()).toMillis(),
            diasDeGracia = DIAS_GRACIA,
            // Prometer un borrado total seria mentir. Se dice lo que no se puede
            // borrar, con nombre y apellido.
            advertencias = listOf(
                "Los mensajes que ya enviaste estan en los telefonos de las otras personas. " +
                    "El servidor no los tiene y nadie puede borrarlos de ahi.",
                "Si publicaste en un canal publico, esas publicaciones no se borran: " +
                    "son de la audiencia del canal, no de tu cuenta.",
                "Si te denunciaron y el caso esta abierto, la denuncia sigue su curso.",
                "Entrar de nuevo antes de $DIAS_GRACIA dias cancela la eliminacion.",
            ),
        )
    }

    /**
     * Cancela la eliminacion. Se llama al ingresar, no con un boton.
     *
     * Es deliberado: quien se arrepiente intenta entrar, no busca una pantalla
     * de "cancelar". Y hacerlo automatico evita el caso absurdo de una cuenta
     * que no deja ingresar para poder cancelar la eliminacion que impide
     * ingresar.
     */
    fun cancelarEliminacionSiHay(c: Connection, usuarioId: UUID): Boolean {
        val filas = c.prepareStatement(
            "UPDATE usuario SET eliminacion_pedida_en = NULL WHERE id = ? AND eliminacion_pedida_en IS NOT NULL"
        ).use { st -> st.setObject(1, usuarioId); st.executeUpdate() }
        if (filas > 0) {
            Autz.auditar(c, usuarioId, "cuenta.eliminacion_cancelada", "usuario", usuarioId)
        }
        return filas > 0
    }

    /**
     * Ejecuta las eliminaciones vencidas. La llama una tarea periodica.
     *
     * Borra la fila de `usuario` y deja que las llaves foraneas en cascada se
     * lleven el resto. Lo que sobrevive esta declarado en la migracion V13.
     */
    fun ejecutarEliminacionesVencidas(): Int = Db.tx { c ->
        c.prepareStatement(
            """DELETE FROM usuario
               WHERE eliminacion_pedida_en IS NOT NULL
                 AND eliminacion_pedida_en < now() - make_interval(days => ?)"""
        ).use { st -> st.setInt(1, DIAS_GRACIA); st.executeUpdate() }
    }

    // ============================================================
    //  Estado y ajustes
    // ============================================================

    fun estado(yo: Auth): EstadoCuenta = Db.query { c ->
        c.prepareStatement(
            """SELECT u.username,
                      (EXTRACT(EPOCH FROM u.creado_en) * 1000)::bigint,
                      coalesce(u.biografia, ''),
                      u.telefono_verificado_en IS NOT NULL,
                      u.telefono_pais,
                      u.descubrible,
                      u.totp_activado_en IS NOT NULL,
                      (SELECT count(*) FROM codigo_respaldo r
                        WHERE r.usuario_id = u.id AND r.usado_en IS NULL),
                      (EXTRACT(EPOCH FROM u.eliminacion_pedida_en) * 1000)::bigint
               FROM usuario u WHERE u.id = ?"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.primero {
                    val pais = it.getString(5)
                    val pedida = it.getLong(9).takeIf { v -> v > 0 }
                    val verificado = it.getBoolean(4)
                    EstadoCuenta(
                        username = it.getString(1),
                        creadoEn = it.getLong(2),
                        biografia = it.getString(3),
                        telefonoVerificado = verificado,
                        telefonoPais = pais,
                        descubrible = it.getBoolean(6),
                        totpActivado = it.getBoolean(7),
                        codigosRespaldoSinUsar = it.getInt(8),
                        eliminacionPedidaEn = pedida,
                        eliminacionSeEjecutaEn = pedida?.plus(
                            Duration.ofDays(DIAS_GRACIA.toLong()).toMillis()
                        ),
                        // Hoy recuperarse es exactamente "tener telefono verificado".
                        puedeRecuperarse = verificado,
                    )
                }
            }
        } ?: throw ErrorNegocio(404, "Cuenta no encontrada.")
    }

    fun ajustar(yo: Auth, req: AjustesCuentaReq): EstadoCuenta {
        Db.tx { c ->
            req.biografia?.let { bio ->
                c.prepareStatement("UPDATE usuario SET biografia = ? WHERE id = ?").use { st ->
                    st.setString(1, bio.trim().take(500).ifBlank { null })
                    st.setObject(2, yo.usuarioId)
                    st.executeUpdate()
                }
            }
            req.descubrible?.let { v ->
                c.prepareStatement("UPDATE usuario SET descubrible = ? WHERE id = ?").use { st ->
                    st.setBoolean(1, v); st.setObject(2, yo.usuarioId); st.executeUpdate()
                }
            }
        }
        return estado(yo)
    }

    // ============================================================
    //  I.4 · Contactos y descubrimiento
    // ============================================================

    fun contactos(yo: Auth): List<Contacto> = Db.query { c ->
        c.prepareStatement(
            """SELECT u.username, coalesce(u.nombre_mostrado, ''), k.alias, k.favorito,
                      coalesce(extract(epoch FROM u.avatar_actualizado) * 1000, 0)::bigint,
                      (EXTRACT(EPOCH FROM k.creado_en) * 1000)::bigint
               FROM contacto k JOIN usuario u ON u.id = k.contacto_id
               WHERE k.usuario_id = ?
               ORDER BY k.favorito DESC, coalesce(k.alias, u.nombre_mostrado, u.username::text)"""
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.mapear {
                    Contacto(
                        username = it.getString(1),
                        nombreMostrado = it.getString(2),
                        alias = it.getString(3),
                        favorito = it.getBoolean(4),
                        avatarVersion = it.getLong(5),
                        agregadoEn = it.getLong(6),
                    )
                }
            }
        }
    }

    fun guardarContacto(yo: Auth, req: GuardarContactoReq): List<Contacto> {
        Db.tx { c ->
            val otro = Moderacion.idDeUsername(c, req.username)
            if (otro == yo.usuarioId) throw ErrorNegocio(400, "No puedes agregarte a ti mismo.")
            // Un bloqueo se respeta aqui tambien: guardar a alguien que te
            // bloqueo no deberia poder usarse para sortear la privacidad de
            // "conocidos".
            if (Autz.hayBloqueo(c, yo.usuarioId, otro)) {
                throw ErrorNegocio(403, "No puedes agregar a esta persona.")
            }

            c.prepareStatement(
                """INSERT INTO contacto (usuario_id, contacto_id, alias, favorito)
                   VALUES (?, ?, ?, coalesce(?, false))
                   ON CONFLICT (usuario_id, contacto_id) DO UPDATE
                     SET alias = coalesce(excluded.alias, contacto.alias),
                         favorito = coalesce(?, contacto.favorito)"""
            ).use { st ->
                st.setObject(1, yo.usuarioId)
                st.setObject(2, otro)
                st.setString(3, req.alias?.trim()?.take(60)?.ifBlank { null })
                st.setObject(4, req.favorito)
                st.setObject(5, req.favorito)
                st.executeUpdate()
            }
        }
        return contactos(yo)
    }

    fun borrarContacto(yo: Auth, username: String): List<Contacto> {
        Db.tx { c ->
            val otro = Moderacion.idDeUsername(c, username)
            c.prepareStatement("DELETE FROM contacto WHERE usuario_id = ? AND contacto_id = ?")
                .use { st -> st.setObject(1, yo.usuarioId); st.setObject(2, otro); st.executeUpdate() }
        }
        return contactos(yo)
    }

    /**
     * Descubrir por numero de telefono.
     *
     * El servidor recibe numeros, los normaliza, los hashea con su pepper y
     * **no los guarda**. Ver la nota larga en `protocol/Identidad.kt` sobre por
     * que no son hashes lo que manda el cliente.
     *
     * Solo aparece quien acepto ser descubrible y tiene el numero verificado:
     * un numero sin verificar no prueba nada, y aparecer por el permitiria
     * hacerse pasar por alguien con solo escribir su telefono.
     */
    fun descubrir(yo: Auth, req: DescubrirReq, ip: String): List<Descubierto> {
        if (req.telefonos.isEmpty()) return emptyList()
        Limitador.exigir(yo.usuarioId, yo.usuarioId.toString(), "descubrir", Limitador.DESCUBRIR)

        // Se normaliza y se deduplica antes de contar contra el tope: el mismo
        // numero escrito de tres maneras es UNA entrada de la agenda, y no
        // deberia gastar tres lugares.
        val numeros = req.telefonos
            .asSequence()
            .mapNotNull { Telefonos.normalizar(it) }
            .distinct()
            .take(DescubrirReq.MAX_TELEFONOS)
            .toList()
        if (numeros.isEmpty()) return emptyList()

        val porHash = numeros.associateBy { java.util.Base64.getEncoder().encodeToString(hashTelefono(it)) }

        return Db.query { c ->
            c.prepareStatement(
                """SELECT u.telefono_hash, u.username, coalesce(u.nombre_mostrado, ''),
                          coalesce(extract(epoch FROM u.avatar_actualizado) * 1000, 0)::bigint,
                          EXISTS (SELECT 1 FROM contacto k
                                   WHERE k.usuario_id = ? AND k.contacto_id = u.id)
                   FROM usuario u
                   WHERE u.telefono_hash = ANY(?)
                     AND u.telefono_verificado_en IS NOT NULL
                     AND u.descubrible
                     AND u.desactivado_en IS NULL
                     AND u.eliminacion_pedida_en IS NULL
                     AND u.id <> ?
                     -- Un bloqueo en cualquier sentido oculta a la persona.
                     AND NOT EXISTS (
                       SELECT 1 FROM bloqueo b
                       WHERE (b.bloqueador_id = ? AND b.bloqueado_id = u.id)
                          OR (b.bloqueado_id = ? AND b.bloqueador_id = u.id)
                     )"""
            ).use { st ->
                st.setObject(1, yo.usuarioId)
                st.setArray(2, c.createArrayOf("bytea", numeros.map { hashTelefono(it) }.toTypedArray()))
                st.setObject(3, yo.usuarioId)
                st.setObject(4, yo.usuarioId)
                st.setObject(5, yo.usuarioId)
                st.executeQuery().use { rs ->
                    rs.mapear {
                        val h = java.util.Base64.getEncoder().encodeToString(it.getBytes(1))
                        Descubierto(
                            telefono = porHash[h] ?: "",
                            username = it.getString(2),
                            nombreMostrado = it.getString(3),
                            avatarVersion = it.getLong(4),
                            yaEsContacto = it.getBoolean(5),
                        )
                    }
                }
            }
        }
    }

    /** De quien es este numero, si es de alguien. */
    private fun usuarioPorTelefono(c: Connection, hash: ByteArray): UUID? =
        c.prepareStatement("SELECT id FROM usuario WHERE telefono_hash = ?").use { st ->
            st.setBytes(1, hash)
            st.executeQuery().use { rs -> rs.primero { it.getObject(1, UUID::class.java) } }
        }

    /** ¿Es mi contacto? Lo usa la resolucion de "conocidos". */
    fun esContacto(c: Connection, usuarioId: UUID, otro: UUID): Boolean =
        c.prepareStatement(
            "SELECT 1 FROM contacto WHERE usuario_id = ? AND contacto_id = ?"
        ).use { st ->
            st.setObject(1, usuarioId); st.setObject(2, otro)
            st.executeQuery().use { it.next() }
        }
}
