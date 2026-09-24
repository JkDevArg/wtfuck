package com.wtfuck.server

import com.wtfuck.protocol.*
import java.util.UUID

/**
 * Modulo D: adjuntos.
 *
 * Tres pasos: reservar, subir, confirmar. Reservar primero es lo que permite
 * rechazar por permiso o cuota SIN haber transferido el archivo.
 *
 * Al confirmar se compara el tamano declarado con el tamano REAL en el almacen.
 * Sin esa comprobacion, declarar "1 KB" y subir 60 MB dejaria la cuota en
 * ridiculo.
 */
object Adjuntos {

    /** Cuota por usuario. Sin esto, cualquiera llena el disco del servidor. */
    private const val CUOTA_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB

    /**
     * Reserva un sitio en el almacen para un archivo que todavia no se subio.
     *
     * ## Dos duenos posibles, y exactamente uno
     *
     * Un adjunto cuelga **o** de una conversacion **o** de una historia. De ahi
     * sale su autorizacion, y son dos preguntas distintas: en un chat se mira
     * `participante`, en una historia se mira `historia_destino`. Por eso son
     * dos columnas y no una, y por eso hay un CHECK en la base -ver V30-: un
     * adjunto sin dueno no lo puede autorizar nadie, y con dos duenos gana el
     * camino mas permisivo.
     */
    fun reservar(yo: Auth, req: ReservarAdjuntoReq): AdjuntoReservado = Db.tx { c ->
        if (req.clase !in ClaseAdjunto.TODAS) throw ErrorNegocio(400, "Clase de adjunto desconocida.")

        val historiaId = req.historiaId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw ErrorNegocio(400, "Identificador de historia invalido.")
        }

        val convId = if (historiaId == null) {
            runCatching { UUID.fromString(req.conversacionId) }.getOrNull()
                ?: throw ErrorNegocio(400, "Identificador de conversacion invalido.")
        } else null

        if (historiaId != null) {
            // Para una historia la autorizacion es simple y estricta: solo
            // quien la publico le cuelga archivos. No hay roles ni permisos por
            // clase porque no hay grupo — una historia es de una persona.
            val mia = c.prepareStatement(
                """SELECT 1 FROM historia
                    WHERE id = ? AND autor_id = ?
                      AND retirada_en IS NULL AND expira_en > now()"""
            ).use { st ->
                st.setObject(1, historiaId); st.setObject(2, yo.usuarioId)
                st.executeQuery().use { it.next() }
            }
            if (!mia) throw ErrorNegocio(404, "Esa historia no existe.")
        } else {
            // Permiso por clase: quien tenga media.imagen denegado no sube
            // imagenes, aunque el boton siga en pantalla.
            Autz.exigir(c, yo.usuarioId, convId!!, ClaseAdjunto.permiso(req.clase))

            // El grupo puede tener la multimedia desactivada por configuracion.
            val permiteMedia = c.prepareStatement(
                "SELECT permitir_media FROM conversacion WHERE id = ?"
            ).use { st ->
                st.setObject(1, convId)
                st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: true
            }
            if (!permiteMedia && req.clase != ClaseAdjunto.STICKER) {
                throw ErrorNegocio(403, "Este grupo no permite enviar archivos.")
            }
        }

        val limite = ClaseAdjunto.limite(req.clase)
        if (req.bytes <= 0) throw ErrorNegocio(400, "Tamano invalido.")
        if (req.bytes > limite) {
            throw ErrorNegocio(413, "El limite para ${req.clase} es ${limite / 1024 / 1024} MB.")
        }

        val usados = c.prepareStatement(
            "SELECT coalesce(bytes, 0) FROM uso_almacenamiento WHERE usuario_id = ?"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getLong(1) } } ?: 0L
        }
        if (usados + req.bytes > CUOTA_BYTES) {
            throw ErrorNegocio(413, "Te quedaste sin espacio. Libera archivos para seguir subiendo.")
        }

        Almacen.iniciar()
        val objeto = Almacen.nuevaClave(req.clase)

        val id = c.prepareStatement(
            """INSERT INTO adjunto (conversacion_id, historia_id, subido_por, clase, bytes,
                                    mime_declarado, nombre_declarado, ancho, alto, duracion_ms, objeto)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"""
        ).use { st ->
            st.setObject(1, convId)
            st.setObject(2, historiaId)
            st.setObject(3, yo.usuarioId)
            st.setString(4, req.clase)
            st.setLong(5, req.bytes)
            st.setString(6, req.mime.take(120).ifBlank { null })
            st.setString(7, req.nombre.take(200).ifBlank { null })
            st.setInt(8, req.ancho)
            st.setInt(9, req.alto)
            st.setInt(10, req.duracionMs)
            st.setString(11, objeto)
            st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }

        AdjuntoReservado(
            adjuntoId = id.toString(),
            urlSubida = Almacen.urlSubida(objeto),
            expiraEn = System.currentTimeMillis() + 15 * 60_000,
        )
    }

    /**
     * Motivo por el que un adjunto subido no vale, para limpiarlo AFUERA.
     *
     * Existe por un defecto que estaba y que no se veia: el rechazo borraba la
     * fila y **lanzaba dentro de la misma transaccion**, asi que `Db.tx` hacia
     * rollback y el DELETE se deshacia. Quedaba una fila apuntando a un objeto
     * que si se habia borrado del almacen -esa parte no es transaccional-, o
     * sea un adjunto que existe para la base, no existe en el disco, y del que
     * `leer` devolvia felizmente una URL de descarga.
     *
     * Pasaba con el tope de tamano desde el modulo D. Lo encontro la prueba de
     * la validacion de imagen, que comprueba que el rechazado **deja de
     * existir**: afirmar el 400 no alcanzaba, porque el 400 llegaba igual.
     *
     * La forma correcta es no mezclar: la transaccion decide, y la limpieza
     * -borrar el objeto y la fila- pasa despues, cada cosa en su sitio.
     */
    private data class Rechazo(val objeto: String, val codigo: Int, val motivo: String)

    /**
     * Confirma que la subida termino.
     *
     * Aqui se compara lo declarado con lo real. Si no coincide, se corrige el
     * registro con el tamano verdadero: la cuota tiene que medir lo que ocupa
     * de verdad, no lo que el cliente dijo.
     *
     * NO recibe el id del mensaje. El orden real es archivo primero y mensaje
     * despues -el sobre necesita el id del adjunto y su clave-, asi que aqui
     * ese mensaje todavia no existe. El enlace lo hace `Mensajes.registrar`.
     */
    fun confirmar(yo: Auth, adjuntoId: UUID): AdjuntoInfo {
        val rechazo = Db.tx { c ->
            val fila = c.prepareStatement(
                """SELECT objeto, subido_por, conversacion_id, clase, bytes
                   FROM adjunto WHERE id = ?"""
            ).use { st ->
                st.setObject(1, adjuntoId)
                st.executeQuery().use { rs ->
                    rs.primero {
                        Reserva(
                            it.getString(1), it.getObject(2, UUID::class.java),
                            it.getObject(3, UUID::class.java), it.getString(4), it.getLong(5),
                        )
                    }
                }
            } ?: throw ErrorNegocio(404, "Ese adjunto no existe.")

            if (fila.subidoPor != yo.usuarioId) {
                throw ErrorNegocio(403, "Ese adjunto no es tuyo.")
            }

            val real = Almacen.tamanoReal(fila.objeto)
                ?: throw ErrorNegocio(400, "El archivo no llego al almacen.")

            if (real > ClaseAdjunto.limite(fila.clase)) {
                // Se subio mas de lo permitido saltandose la reserva.
                return@tx Rechazo(fila.objeto, 413, "El archivo subido supera el limite permitido.")
            }

            /*
             * Modulo AC: en un canal PUBLICO el archivo va sin cifrar, y
             * entonces -y solo entonces- el servidor puede comprobar que es lo
             * que dice ser.
             *
             * El documento de cobertura declara que el brief pedia dos cosas
             * incompatibles: validar el tipo de archivo en el servidor Y
             * cifrado de extremo a extremo. Se resolvio partiendo por clase:
             * fotos de perfil validadas, adjuntos cifrados no. **Esta es la
             * tercera clase** y cae del lado validable, porque su contenido ya
             * es publico por definicion.
             *
             * De un adjunto cifrado los primeros bytes son ruido, asi que esto
             * no se puede -ni se debe- intentar ahi.
             */
            if (esDeCanalPublico(c, fila.conversacionId)) {
                val cabecera = Almacen.primerosBytes(fila.objeto, 12)
                if (cabecera == null || !pareceImagen(cabecera)) {
                    return@tx Rechazo(fila.objeto, 400, "El archivo no es una imagen valida.")
                }
            }

            c.prepareStatement(
                "UPDATE adjunto SET confirmado_en = now(), bytes = ? WHERE id = ?"
            ).use { st ->
                st.setLong(1, real)
                st.setObject(2, adjuntoId)
                st.executeUpdate()
            }
            null
        }

        if (rechazo != null) {
            // Afuera de la transaccion que decidio, para que el borrado quede
            // hecho de verdad. Un objeto que no paso la validacion y se queda
            // en el almacen es exactamente el "usar esto de almacen de
            // binarios" que la validacion evita.
            Almacen.borrar(rechazo.objeto)
            Db.tx { c ->
                c.prepareStatement("DELETE FROM adjunto WHERE id = ?").use {
                    it.setObject(1, adjuntoId); it.executeUpdate()
                }
            }
            throw ErrorNegocio(rechazo.codigo, rechazo.motivo)
        }

        return leer(yo, adjuntoId)
    }

    /** Si la conversacion es un canal publico, donde el contenido va en claro. */
    private fun esDeCanalPublico(c: java.sql.Connection, convId: UUID?): Boolean {
        if (convId == null) return false
        return c.prepareStatement(
            """SELECT 1 FROM canal k JOIN conversacion v ON v.id = k.conversacion_id
               WHERE k.conversacion_id = ? AND k.publico AND v.tipo = 'canal'"""
        ).use { st ->
            st.setObject(1, convId)
            st.executeQuery().use { it.next() }
        }
    }

    /**
     * La firma real del archivo, no el Content-Type que declaro el cliente.
     *
     * Misma comprobacion que `Repo.pareceImagen` para las fotos de perfil, y
     * por el mismo motivo: sin esto, el hueco de imagenes de un canal es un
     * sitio donde subir binarios cualesquiera con la cuota de alguien.
     */
    private fun pareceImagen(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val webp = String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP"
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte()
        return jpg || png || webp || gif
    }

    /** Devuelve una URL de descarga nueva. Las firmadas caducan a proposito. */
    /**
     * La URL firmada para bajar un adjunto, si corresponde.
     *
     * ## Las dos autorizaciones
     *
     * Un adjunto cuelga de una conversacion o de una historia, y cada dueno
     * responde a una pregunta distinta:
     *
     *  - **Conversacion**: ¿sos participante? Lo resuelve el motor de permisos.
     *  - **Historia**: ¿estabas en la audiencia cuando se publico? Lo resuelve
     *    `historia_destino`, que es la misma lista congelada que decide a quien
     *    se le cifro el sobre. Si no se mirara esto, el archivo de una historia
     *    seria publico para cualquiera que adivinara su id, y no serviria de
     *    nada haber cifrado el sobre.
     *
     * Quien la publico tambien puede bajarla: es suya, y la necesita para
     * volver a verla desde otro aparato.
     *
     * Y se respeta la caducidad. Una historia vencida deja de existir; su
     * archivo tambien. Sin esto quedaria una URL viva de algo que la pantalla
     * ya no muestra, que es la peor clase de sobra: invisible.
     */
    fun leer(yo: Auth, adjuntoId: UUID): AdjuntoInfo = Db.query { c ->
        val encontrado = c.prepareStatement(
            """SELECT conversacion_id, clase, bytes, coalesce(mime_declarado,''),
                      coalesce(nombre_declarado,''), ancho, alto, duracion_ms, objeto,
                      historia_id
               FROM adjunto WHERE id = ?"""
        ).use { st ->
            st.setObject(1, adjuntoId)
            st.executeQuery().use { rs ->
                rs.primero {
                    Detalle(
                        it.getObject(1, UUID::class.java), it.getString(2), it.getLong(3),
                        it.getString(4), it.getString(5), it.getInt(6), it.getInt(7),
                        it.getInt(8), it.getString(9),
                    ) to it.getObject(10, UUID::class.java)
                }
            }
        } ?: throw ErrorNegocio(404, "Ese adjunto no existe.")

        val (detalle, historiaId) = encontrado

        if (historiaId != null) {
            val puedo = c.prepareStatement(
                """SELECT 1 FROM historia h
                    WHERE h.id = ? AND h.retirada_en IS NULL AND h.expira_en > now()
                      AND (h.autor_id = ?
                           OR EXISTS (SELECT 1 FROM historia_destino d
                                       WHERE d.historia_id = h.id AND d.usuario_id = ?))"""
            ).use { st ->
                st.setObject(1, historiaId)
                st.setObject(2, yo.usuarioId)
                st.setObject(3, yo.usuarioId)
                st.executeQuery().use { it.next() }
            }
            // 404 y no 403: confirmar que el archivo existe ya dice algo de una
            // historia que no te tocaba ver.
            if (!puedo) throw ErrorNegocio(404, "Ese adjunto no existe.")
        } else {
            // Un canal PUBLICO se lee sin estar suscrito, y su imagen
            // tambien: es la MISMA regla que ya usa el muro. Si la imagen
            // exigiera pertenencia, el muro mostraria publicaciones con un
            // hueco donde deberia estar la foto, a quien todavia no siguio el
            // canal — o sea justo a quien esta decidiendo si seguirlo.
            if (!esDeCanalPublico(c, detalle.conversacionId)) {
                // Quien no esta en la conversacion no obtiene la URL. Sin este
                // chequeo bastaria con adivinar un id para descargar archivos
                // ajenos.
                Autz.exigir(c, yo.usuarioId, detalle.conversacionId!!, Permisos.MIEMBRO_VER)
            }
        }

        val fila = detalle

        AdjuntoInfo(
            adjuntoId = adjuntoId.toString(),
            clase = fila.clase,
            bytes = fila.bytes,
            mime = fila.mime,
            nombre = fila.nombre,
            ancho = fila.ancho,
            alto = fila.alto,
            duracionMs = fila.duracionMs,
            urlDescarga = Almacen.urlDescarga(fila.objeto),
            expiraEn = System.currentTimeMillis() + 60 * 60_000,
        )
    }

    fun uso(yo: Auth): UsoAlmacenamiento = Db.query { c ->
        c.prepareStatement(
            "SELECT coalesce(archivos,0), coalesce(bytes,0) FROM uso_almacenamiento WHERE usuario_id = ?"
        ).use { st ->
            st.setObject(1, yo.usuarioId)
            st.executeQuery().use { rs ->
                rs.primero { UsoAlmacenamiento(it.getInt(1), it.getLong(2), CUOTA_BYTES) }
            } ?: UsoAlmacenamiento(0, 0, CUOTA_BYTES)
        }
    }

    /**
     * Borra reservas abandonadas y sus objetos.
     *
     * Una subida interrumpida deja un objeto pagando disco sin que nadie lo
     * referencie. Se limpia a las 24 h.
     */
    fun barrerHuerfanos(): Int {
        val objetos = Db.query { c ->
            c.prepareStatement(
                """SELECT id, objeto FROM adjunto
                   WHERE confirmado_en IS NULL AND creado_en < now() - interval '24 hours'"""
            ).use { st ->
                st.executeQuery().use { rs ->
                    rs.mapear { it.getObject(1, UUID::class.java) to it.getString(2) }
                }
            }
        }
        objetos.forEach { (id, objeto) ->
            Almacen.borrar(objeto)
            Db.tx { c ->
                c.prepareStatement("DELETE FROM adjunto WHERE id = ?").use {
                    it.setObject(1, id); it.executeUpdate()
                }
            }
        }
        return objetos.size
    }

    private data class Reserva(
        val objeto: String,
        val subidoPor: UUID,
        // Nulo cuando el adjunto cuelga de una historia. Ver la migracion V30:
        // son dos duenos posibles y exactamente uno.
        val conversacionId: UUID?,
        val clase: String,
        val bytesDeclarados: Long,
    )

    private data class Detalle(
        // Nulo cuando el adjunto cuelga de una historia y no de un chat. Ver
        // la migracion V30: son dos duenos posibles y exactamente uno.
        val conversacionId: UUID?,
        val clase: String,
        val bytes: Long,
        val mime: String,
        val nombre: String,
        val ancho: Int,
        val alto: Int,
        val duracionMs: Int,
        val objeto: String,
    )
}
