package com.wtfuck.server

import com.wtfuck.protocol.*
import java.util.Base64
import java.util.UUID

/**
 * Reparto de claves publicas (modulo E).
 *
 * Todo lo que pasa por aqui es material PUBLICO. Las privadas no salen del
 * telefono, asi que este archivo puede leerse entero sin encontrar nada con lo
 * que descifrar un mensaje. Eso no es un detalle: es la propiedad que hace que
 * el resto del servidor pueda hacer su trabajo sin poder leer.
 *
 * El limite honesto, escrito donde se ve: el servidor elige QUE clave entrega.
 * Uno malicioso podria entregar la suya y ponerse en medio. Contra eso no hay
 * criptografia que alcance: hay que comparar la huella fuera del canal. De ahi
 * salen [huella] y el registro de cambios de identidad.
 */
object Claves {

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun deB64(s: String): ByteArray = Base64.getDecoder().decode(s)

    // ------------------------------------------------------------------
    //  Publicar
    // ------------------------------------------------------------------

    /**
     * Publica el juego de claves de este dispositivo.
     *
     * Las unicas se AGREGAN; la firmada y la kyber se reemplazan. Reponer
     * prekeys no puede borrar las que otra persona ya se llevo y todavia no
     * uso: si se borraran, esa persona abriria una sesion contra una clave que
     * ya no existe y el mensaje seria indescifrable.
     */
    fun publicar(yo: Auth, req: PublicarClavesReq) = Db.tx { c ->
        if (req.registrationId <= 0) {
            throw ErrorNegocio(400, "El identificador de registro no es valido.")
        }

        val identidad = deB64(req.identidad)

        // Si la identidad cambio, se anota. Quien ya hablaba con esta persona
        // tiene que poder enterarse; ver la nota de arriba sobre el hombre en
        // el medio.
        //
        // `registration_id` es lo que distingue ESTABLECER una identidad de
        // CAMBIARLA, y esa distincion no es un detalle. Al registrarse, la app
        // manda en `identidad_pub` la clave atestada del Keystore, que sirve
        // para probar que el telefono tiene enclave seguro. La identidad de
        // libsignal es otra clave completamente distinta y llega despues, en
        // esta misma ruta, sobreescribiendo esa columna.
        //
        // Comparar a ciegas hacia que la PRIMERA publicacion de cada
        // dispositivo pareciera un cambio de identidad. Un aviso de seguridad
        // que salta siempre es un aviso que nadie lee, asi que el falso
        // positivo no era cosmetico: vaciaba de sentido la advertencia de
        // verdad. `registration_id` es NULL hasta la primera publicacion, y
        // eso responde exactamente la pregunta correcta.
        //
        // El centinela es 0 y no NULL: la columna se creo `NOT NULL DEFAULT 0`
        // (V9). Sirve igual porque esta misma funcion rechaza cualquier
        // registrationId <= 0, asi que un 0 guardado solo puede significar que
        // nadie publico todavia.
        val previo = c.prepareStatement(
            "SELECT identidad_pub, registration_id FROM dispositivo WHERE id = ?"
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getBytes(1) to it.getInt(2) } }
        }
        val anterior = previo?.first
        val yaPublico = (previo?.second ?: 0) != 0

        if (yaPublico && anterior != null && !anterior.contentEquals(identidad)) {
            bitacoraClaves.info("La identidad de @{} cambio", yo.username)
            c.prepareStatement(
                "INSERT INTO cambio_identidad (usuario_id, dispositivo_id) VALUES (?, ?)"
            ).use { st ->
                st.setObject(1, yo.usuarioId); st.setObject(2, yo.dispositivoId); st.executeUpdate()
            }
            // Dos registros del mismo hecho, para dos preguntas distintas:
            // `cambio_identidad` responde "¿le aviso a quien habla con el?" y
            // lo consulta el protocolo; esto responde "¿cambio alguien mi clave
            // sin que yo lo sepa?" y lo consulta el dueno de la cuenta.
            Seguridad.anotar(c, yo.usuarioId, "clave_identidad_cambiada")
        }

        c.prepareStatement(
            "UPDATE dispositivo SET identidad_pub = ?, registration_id = ? WHERE id = ?"
        ).use { st ->
            st.setBytes(1, identidad)
            st.setInt(2, req.registrationId)
            st.setObject(3, yo.dispositivoId)
            st.executeUpdate()
        }

        // Una sola firmada vigente y una sola kyber vigente por dispositivo:
        // se borran las anteriores. A diferencia de las unicas, estas se rotan
        // y quien tenga una vieja puede pedir el paquete otra vez.
        c.prepareStatement("DELETE FROM prekey_firmada WHERE dispositivo_id = ?").use {
            it.setObject(1, yo.dispositivoId); it.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO prekey_firmada (dispositivo_id, key_id, publica, firma) VALUES (?, ?, ?, ?)"
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.setInt(2, req.firmada.keyId)
            st.setBytes(3, deB64(req.firmada.publica))
            st.setBytes(4, deB64(req.firmada.firma))
            st.executeUpdate()
        }

        c.prepareStatement("DELETE FROM prekey_kyber WHERE dispositivo_id = ?").use {
            it.setObject(1, yo.dispositivoId); it.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO prekey_kyber (dispositivo_id, key_id, publica, firma) VALUES (?, ?, ?, ?)"
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.setInt(2, req.kyber.keyId)
            st.setBytes(3, deB64(req.kyber.publica))
            st.setBytes(4, deB64(req.kyber.firma))
            st.executeUpdate()
        }

        if (req.unicas.isNotEmpty()) {
            c.prepareStatement(
                """INSERT INTO prekey_unica (dispositivo_id, key_id, publica)
                   VALUES (?, ?, ?) ON CONFLICT (dispositivo_id, key_id) DO NOTHING"""
            ).use { st ->
                req.unicas.forEach { k ->
                    st.setObject(1, yo.dispositivoId)
                    st.setInt(2, k.keyId)
                    st.setBytes(3, deB64(k.publica))
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    fun estado(yo: Auth): EstadoClaves = Db.query { c ->
        val n = c.prepareStatement(
            "SELECT count(*) FROM prekey_unica WHERE dispositivo_id = ?"
        ).use { st ->
            st.setObject(1, yo.dispositivoId)
            st.executeQuery().use { rs -> rs.primero { it.getInt(1) } ?: 0 }
        }
        EstadoClaves(unicasDisponibles = n)
    }

    // ------------------------------------------------------------------
    //  Entregar
    // ------------------------------------------------------------------

    /**
     * Entrega el paquete de un dispositivo y consume una prekey de un solo uso.
     *
     * Se exige compartir conversacion: sin eso, cualquiera podria cosechar las
     * prekeys de cualquiera y agotarlas, que es una negacion de servicio barata
     * -quien se queda sin prekeys unicas pierde la garantia hacia adelante en
     * las sesiones nuevas.
     */
    fun paquete(yo: Auth, dispositivoId: UUID): PaqueteClaves = Db.tx { c ->
        val info = c.prepareStatement(
            """SELECT d.usuario_id, u.username, d.registration_id, d.identidad_pub
               FROM dispositivo d JOIN usuario u ON u.id = d.usuario_id
               WHERE d.id = ? AND d.revocado_en IS NULL"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.primero {
                    InfoDispositivo(
                        it.getObject(1, UUID::class.java), it.getString(2), it.getInt(3), it.getBytes(4),
                    )
                }
            }
        } ?: throw ErrorNegocio(404, "Ese dispositivo no existe.")

        if (info.usuarioId != yo.usuarioId && !compartenConversacion(c, yo.usuarioId, info.usuarioId)) {
            throw ErrorNegocio(403, "No compartes ninguna conversacion con esa persona.")
        }
        if (info.registrationId <= 0) {
            throw ErrorNegocio(409, "Ese dispositivo todavia no publico sus claves.")
        }

        val firmada = leerFirmada(c, "prekey_firmada", dispositivoId)
            ?: throw ErrorNegocio(409, "Ese dispositivo todavia no publico sus claves.")
        val kyber = leerFirmada(c, "prekey_kyber", dispositivoId)
            ?: throw ErrorNegocio(409, "Ese dispositivo todavia no publico su clave post-cuantica.")

        // Se toma la primera libre y se borra: una prekey de un solo uso
        // consumida no vuelve a servir. Ver la nota en V9 sobre por que se
        // borra en vez de marcarse.
        val unica = c.prepareStatement(
            """DELETE FROM prekey_unica
               WHERE (dispositivo_id, key_id) IN (
                   SELECT dispositivo_id, key_id FROM prekey_unica
                   WHERE dispositivo_id = ? ORDER BY key_id LIMIT 1
                   FOR UPDATE SKIP LOCKED
               )
               RETURNING key_id, publica"""
        ).use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.primero { ClavePublica(it.getInt(1), b64(it.getBytes(2))) }
            }
        }
        if (unica == null) {
            // No es un error: las unicas se agotan. La sesion se abre igual,
            // con algo menos de garantia, y eso es mejor que no poder escribir.
            bitacoraClaves.info("Sin prekeys unicas para el dispositivo {}", dispositivoId)
        }

        PaqueteClaves(
            usuarioId = info.usuarioId.toString(),
            username = info.username,
            dispositivoId = dispositivoId.toString(),
            registrationId = info.registrationId,
            identidad = b64(info.identidad),
            firmada = firmada,
            kyber = kyber,
            unica = unica,
        )
    }

    /**
     * Dispositivos a los que hay que entregar copia de un mensaje.
     *
     * Con E2EE el remitente necesita esta lista ANTES de cifrar, porque hay un
     * cuerpo por dispositivo. Se excluye el propio: el mensaje ya esta en este
     * telefono.
     */
    fun destinos(yo: Auth, conversacionId: UUID): DestinosConversacion = Db.query { c ->
        Autz.exigir(c, yo.usuarioId, conversacionId, Permisos.MIEMBRO_VER)
        val lista = c.prepareStatement(
            """SELECT d.usuario_id, u.username, d.id, d.registration_id, d.identidad_pub,
                      coalesce(d.etiqueta, '')
               FROM participante p
                 JOIN dispositivo d ON d.usuario_id = p.usuario_id AND d.revocado_en IS NULL
                 JOIN usuario u     ON u.id = d.usuario_id
               WHERE p.conversacion_id = ? AND p.salido_en IS NULL AND d.id <> ?
               ORDER BY u.username, d.principal DESC, d.etiqueta"""
        ).use { st ->
            st.setObject(1, conversacionId)
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
        DestinosConversacion(conversacionId.toString(), lista)
    }

    /** Si la identidad de alguien cambio despues de `desde`. */
    fun cambioIdentidad(usuarioId: UUID, desde: Long): Boolean = Db.query { c ->
        c.prepareStatement(
            """SELECT 1 FROM cambio_identidad
               WHERE usuario_id = ? AND ocurrio_en > to_timestamp(? / 1000.0) LIMIT 1"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.setLong(2, desde)
            st.executeQuery().use { it.next() }
        }
    }

    // ------------------------------------------------------------------
    //  Internos
    // ------------------------------------------------------------------

    private data class InfoDispositivo(
        val usuarioId: UUID,
        val username: String,
        val registrationId: Int,
        val identidad: ByteArray,
    )

    private fun leerFirmada(
        c: java.sql.Connection,
        tabla: String,
        dispositivoId: UUID,
    ): ClaveFirmada? =
        // El nombre de tabla se interpola porque es una constante del codigo,
        // no entrada de nadie: las dos llamadas estan en este archivo.
        c.prepareStatement("SELECT key_id, publica, firma FROM $tabla WHERE dispositivo_id = ?").use { st ->
            st.setObject(1, dispositivoId)
            st.executeQuery().use { rs ->
                rs.primero { ClaveFirmada(it.getInt(1), b64(it.getBytes(2)), b64(it.getBytes(3))) }
            }
        }

    private fun compartenConversacion(c: java.sql.Connection, a: UUID, b: UUID): Boolean =
        c.prepareStatement(
            """SELECT 1 FROM participante pa
                 JOIN participante pb ON pb.conversacion_id = pa.conversacion_id
               WHERE pa.usuario_id = ? AND pa.salido_en IS NULL
                 AND pb.usuario_id = ? AND pb.salido_en IS NULL
               LIMIT 1"""
        ).use { st ->
            st.setObject(1, a); st.setObject(2, b)
            st.executeQuery().use { it.next() }
        }
}

private val bitacoraClaves = org.slf4j.LoggerFactory.getLogger("claves")
