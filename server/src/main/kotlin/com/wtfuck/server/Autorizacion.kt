package com.wtfuck.server

import java.sql.Connection
import java.util.UUID

/**
 * El motor de permisos.
 *
 * Toda accion sobre una conversacion pasa por aqui. La regla que sostiene el
 * modulo es simple y no admite excepciones:
 *
 *   El cliente nunca decide. Los permisos NO viajan en el token; se resuelven
 *   en cada peticion contra la base.
 *
 * Esa decision es la que hace que degradar a un administrador surta efecto de
 * inmediato en vez de esperar a que venza su sesion.
 */
object Permisos {

    // --- catalogo, en constantes para que el compilador atrape los typos ----
    const val MSG_ENVIAR = "mensaje.enviar"
    const val MSG_RESPONDER = "mensaje.responder"
    const val MSG_REACCIONAR = "mensaje.reaccionar"
    const val MSG_EDITAR = "mensaje.editar"
    const val MSG_BORRAR_PROPIO = "mensaje.borrar_propio"
    const val MSG_BORRAR_AJENO = "mensaje.borrar_ajeno"
    const val MSG_FIJAR = "mensaje.fijar"
    const val MSG_REENVIAR = "mensaje.reenviar"
    const val MIEMBRO_VER = "miembro.ver"
    const val MIEMBRO_INVITAR = "miembro.invitar"
    const val MIEMBRO_APROBAR = "miembro.aprobar"
    const val MIEMBRO_EXPULSAR = "miembro.expulsar"
    const val MIEMBRO_SILENCIAR = "miembro.silenciar"
    const val GRUPO_VER_INFO = "grupo.ver_info"
    const val GRUPO_EDITAR_INFO = "grupo.editar_info"
    const val GRUPO_CAMBIAR_FOTO = "grupo.cambiar_foto"
    const val GRUPO_CAMBIAR_NOMBRE = "grupo.cambiar_nombre"
    const val GRUPO_ADMIN_ROLES = "grupo.administrar_roles"
    const val GRUPO_ELIMINAR = "grupo.eliminar"
    const val INVITACION_CREAR = "invitacion.crear"
    const val INVITACION_REVOCAR = "invitacion.revocar"

    // --- modulo F: canales ---
    // Publicar es lo que separa a un canal de un grupo: quien no lo tiene
    // puede leer, y eso convierte al canal en unidireccional sin necesidad de
    // un "modo" aparte.
    const val CANAL_PUBLICAR = "canal.publicar"
    const val CANAL_COMENTAR = "canal.comentar"
    const val CANAL_ESTADISTICAS = "canal.estadisticas"

    // --- modulo M: contenido con estructura ---
    // Estos dos estaban en el catalogo desde V4 sin nada detras. Ahora se
    // exigen de verdad, contra la clase que el cliente declara en el metadato.
    // El limite de esa garantia esta dicho en `ClaseContenido`.
    const val ENCUESTA_CREAR = "encuesta.crear"
    const val EVENTO_CREAR = "evento.crear"
}

/** Por que se denego. Sirve para dar un mensaje util y para las pruebas. */
enum class Denegacion {
    SIN_SESION,
    CUENTA_SUSPENDIDA,
    BLOQUEADO,
    NO_PERTENECE,
    EXPULSADO,
    SILENCIADO,
    /** H.3: la plataforma cerro esta conversacion. Se lee, no se escribe. */
    CONVERSACION_CERRADA,
    PERMISO_DENEGADO_EXPLICITO,
    SIN_PERMISO,
    JERARQUIA_INSUFICIENTE,
}

data class Veredicto(val permitido: Boolean, val motivo: Denegacion? = null) {
    companion object {
        val SI = Veredicto(true)
        fun no(m: Denegacion) = Veredicto(false, m)
    }
}

/** Lo que el usuario es dentro de una conversacion concreta. */
data class Membresia(
    val rolClave: String,
    val jerarquia: Int,
    val permisos: Set<String>,
)

object Autz {

    // ============================================================
    //  Entrada principal
    // ============================================================

    /**
     * Resuelve un permiso siguiendo el orden de docs/05-PLAN-COMPLETO.md.
     *
     * El orden importa: lo mas restrictivo primero. Un bloqueo se comprueba
     * antes que los roles porque ningun rol debe poder saltarselo.
     */
    /** Si la plataforma cerro esta conversacion. H.3. */
    fun cerrada(c: Connection, conversacionId: UUID): Boolean =
        c.prepareStatement("SELECT cerrada_en IS NOT NULL FROM conversacion WHERE id = ?").use { st ->
            st.setObject(1, conversacionId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: false
        }

    fun puede(
        c: Connection,
        usuarioId: UUID,
        conversacionId: UUID,
        permiso: String,
    ): Veredicto {
        if (suspendido(c, usuarioId)) return Veredicto.no(Denegacion.CUENTA_SUSPENDIDA)

        // En una conversacion directa, un bloqueo en cualquier sentido la cierra.
        bloqueoEnDirecta(c, usuarioId, conversacionId)?.let { return it }

        // H.3 · Conversacion cerrada por la plataforma.
        //
        // Va ANTES de la pertenencia y del rol a proposito: un cierre lo
        // decide la plataforma y no lo levanta ningun permiso de grupo. Si se
        // evaluara despues del rol, un administrador del grupo podria seguir
        // publicando en el grupo que la plataforma acaba de cerrar, que es
        // exactamente lo que el cierre existe para impedir.
        //
        // Y solo frena lo que PRODUCE contenido: leer sigue permitido. El
        // historial ya esta en los telefonos; bloquear la lectura no lo
        // borraria y solo volveria la app inutil para consultar lo que ya se
        // dijo.
        if (permiso !in SOLO_LECTURA && cerrada(c, conversacionId)) {
            return Veredicto.no(Denegacion.CONVERSACION_CERRADA)
        }

        val m = membresia(c, usuarioId, conversacionId)
            ?: return Veredicto.no(Denegacion.NO_PERTENECE)

        when (restriccionActiva(c, usuarioId, conversacionId)) {
            "expulsado", "vetado" -> return Veredicto.no(Denegacion.EXPULSADO)
            // Silenciado bloquea lo que produce contenido, no lo que solo lee.
            "silenciado" -> if (permiso !in SOLO_LECTURA) {
                return Veredicto.no(Denegacion.SILENCIADO)
            }
        }

        // Modo anuncio: solo mandos escriben.
        //
        // Se evalua aqui y NO quitando el permiso a cada miembro: si se hiciera
        // asi, apagar el modo dejaria a todos sin permiso y habria que recordar
        // la configuracion anterior de cada persona.
        if (permiso in PRODUCE_CONTENIDO && soloAdmins(c, conversacionId) && m.jerarquia < 50) {
            return Veredicto.no(Denegacion.SIN_PERMISO)
        }

        // Excepcion individual: un DENEGAR explicito gana sobre el rol.
        override(c, usuarioId, conversacionId, permiso)?.let { permitido ->
            return if (permitido) Veredicto.SI
            else Veredicto.no(Denegacion.PERMISO_DENEGADO_EXPLICITO)
        }

        return if (permiso in m.permisos) Veredicto.SI
        else Veredicto.no(Denegacion.SIN_PERMISO)
    }

    /** Version que lanza. Es la que usan las rutas. */
    fun exigir(c: Connection, usuarioId: UUID, conversacionId: UUID, permiso: String) {
        val v = puede(c, usuarioId, conversacionId, permiso)
        if (!v.permitido) throw ErrorNegocio(codigoDe(v.motivo), mensajeDe(v.motivo))
    }

    /**
     * Para acciones sobre OTRA persona: expulsar, silenciar, cambiar su rol.
     *
     * Ademas del permiso, exige jerarquia estrictamente mayor. Es lo que impide
     * que un moderador expulse a un administrador, o que dos administradores se
     * expulsen mutuamente.
     */
    fun exigirSobre(
        c: Connection,
        actorId: UUID,
        objetivoId: UUID,
        conversacionId: UUID,
        permiso: String,
    ) {
        exigir(c, actorId, conversacionId, permiso)

        val actor = membresia(c, actorId, conversacionId)
            ?: throw ErrorNegocio(403, mensajeDe(Denegacion.NO_PERTENECE))
        val objetivo = membresia(c, objetivoId, conversacionId)
            ?: throw ErrorNegocio(404, "Esa persona no esta en la conversacion.")

        if (actor.jerarquia <= objetivo.jerarquia) {
            throw ErrorNegocio(403, mensajeDe(Denegacion.JERARQUIA_INSUFICIENTE))
        }
    }

    // ============================================================
    //  Piezas
    // ============================================================

    /** Permisos que un silencio NO bloquea: seguir leyendo no molesta a nadie. */
    private val SOLO_LECTURA = setOf(Permisos.MIEMBRO_VER, Permisos.GRUPO_VER_INFO)

    /**
     * Lo que el MODO ANUNCIO corta. Reaccionar no entra: el modo anuncio regula
     * quien habla, y una reaccion no interrumpe a nadie.
     *
     * Ojo con la diferencia: SILENCIAR a alguien es una sancion de moderacion y
     * si le corta las reacciones, porque un silenciado llenando de emojis sigue
     * siendo ruido. Son dos cosas distintas a proposito.
     */
    private val PRODUCE_CONTENIDO = setOf(
        Permisos.MSG_ENVIAR, Permisos.MSG_RESPONDER, Permisos.MSG_REENVIAR,
        "media.imagen", "media.video", "media.audio", "media.documento",
        "media.nota_voz", "media.sticker_gif", "encuesta.crear", "evento.crear",
    )

    private fun soloAdmins(c: Connection, conversacionId: UUID): Boolean =
        c.prepareStatement("SELECT solo_admins FROM conversacion WHERE id = ?").use { st ->
            st.setObject(1, conversacionId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: false
        }

    fun membresia(c: Connection, usuarioId: UUID, conversacionId: UUID): Membresia? {
        val rol = c.prepareStatement(
            """SELECT r.clave, r.jerarquia, r.id
               FROM participante p JOIN rol r ON r.id = p.rol_id
               WHERE p.conversacion_id = ? AND p.usuario_id = ? AND p.salido_en IS NULL"""
        ).use { st ->
            st.setObject(1, conversacionId); st.setObject(2, usuarioId)
            st.executeQuery().use { rs ->
                rs.primero { Triple(it.getString(1), it.getInt(2), it.getObject(3, UUID::class.java)) }
            }
        } ?: return null

        val permisos = c.prepareStatement(
            "SELECT permiso FROM rol_permiso WHERE rol_id = ?"
        ).use { st ->
            st.setObject(1, rol.third)
            st.executeQuery().use { rs -> rs.mapear { it.getString(1) }.toSet() }
        }

        return Membresia(rol.first, rol.second, permisos)
    }

    /**
     * Una suspension con vencimiento tiene que vencer.
     *
     * Sin la condicion de `suspendido_hasta`, la columna seria decorativa y
     * toda suspension temporal seria permanente en la practica. No se limpia
     * la fila al vencer a proposito: que la suspension siga anotada es lo que
     * permite ver el historial de alguien con tres suspensiones cumplidas.
     */
    fun suspendido(c: Connection, usuarioId: UUID): Boolean =
        c.prepareStatement(
            """SELECT suspendido_en IS NOT NULL
                      AND (suspendido_hasta IS NULL OR suspendido_hasta > now())
               FROM usuario WHERE id = ?"""
        ).use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } } ?: false
        }

    /**
     * Lanza si esta cuenta esta suspendida. Para todo lo que produce contenido.
     *
     * ## Por que hace falta aparte de `puede`
     *
     * `puede` es autorizacion **de conversacion**: pregunta que puede hacer
     * alguien DENTRO de un grupo o un chat, y ahi la suspension ya se miraba.
     * Lo que se quedaba fuera era todo lo que no tiene conversacion: el nombre,
     * el estado, la biografia, la foto, la portada, el tipo de cuenta, la ficha
     * de empresa y las historias.
     *
     * Es decir, **justo lo que otros ven**. Una cuenta suspendida por
     * suplantar a una institucion podia seguir editando el perfil con el que
     * suplantaba: cambiarse el nombre a "Banco Nacional" y la biografia a
     * "Entidad financiera regulada" mientras cumplia la sancion. Lo encontro
     * una auditoria de solo lectura y estaba medido antes de arreglarse.
     *
     * ## Lo que NO hace
     *
     * No corta la sesion. Eso es deliberado y no se toca: una suspension deja
     * entrar a ver POR QUE, porque una sancion que no se explica no corrige
     * nada. Lo que corta es producir, no leer.
     *
     * 403 y no 404: aqui no hay nada que esconder —la persona sabe que esta
     * sancionada, se lo dijimos— y un 404 la dejaria pensando que la ruta se
     * rompio.
     */
    fun exigirNoSuspendido(c: Connection, usuarioId: UUID) {
        if (suspendido(c, usuarioId)) {
            throw ErrorNegocio(
                403,
                "Tu cuenta esta suspendida: no puedes cambiar tu perfil ni publicar " +
                    "mientras dure la sancion.",
            )
        }
    }

    /**
     * Devuelve null si no aplica. En una conversacion directa, un bloqueo en
     * CUALQUIER sentido la cierra: ni el que bloquea ni el bloqueado escriben.
     */
    private fun bloqueoEnDirecta(c: Connection, usuarioId: UUID, conversacionId: UUID): Veredicto? {
        val hay = c.prepareStatement(
            """SELECT 1
               FROM conversacion cv
                 JOIN participante otro ON otro.conversacion_id = cv.id
                                       AND otro.usuario_id <> ?
                                       AND otro.salido_en IS NULL
                 JOIN bloqueo b ON (b.bloqueador_id = ? AND b.bloqueado_id = otro.usuario_id)
                                OR (b.bloqueado_id = ? AND b.bloqueador_id = otro.usuario_id)
               WHERE cv.id = ? AND cv.tipo = 'directa'
               LIMIT 1"""
        ).use { st ->
            st.setObject(1, usuarioId); st.setObject(2, usuarioId)
            st.setObject(3, usuarioId); st.setObject(4, conversacionId)
            st.executeQuery().use { it.next() }
        }
        return if (hay) Veredicto.no(Denegacion.BLOQUEADO) else null
    }

    /** Restriccion viva: una con vencimiento pasado ya no cuenta. */
    private fun restriccionActiva(c: Connection, usuarioId: UUID, conversacionId: UUID): String? =
        c.prepareStatement(
            """SELECT tipo FROM restriccion
               WHERE conversacion_id = ? AND usuario_id = ?
                 AND levantada_en IS NULL
                 AND (hasta IS NULL OR hasta > now())
               ORDER BY CASE tipo WHEN 'vetado' THEN 0 WHEN 'expulsado' THEN 1 ELSE 2 END
               LIMIT 1"""
        ).use { st ->
            st.setObject(1, conversacionId); st.setObject(2, usuarioId)
            st.executeQuery().use { rs -> rs.primero { it.getString(1) } }
        }

    private fun override(c: Connection, usuarioId: UUID, conversacionId: UUID, permiso: String): Boolean? =
        c.prepareStatement(
            """SELECT permitido FROM permiso_override
               WHERE conversacion_id = ? AND usuario_id = ? AND permiso = ?"""
        ).use { st ->
            st.setObject(1, conversacionId); st.setObject(2, usuarioId); st.setString(3, permiso)
            st.executeQuery().use { rs -> rs.primero { it.getBoolean(1) } }
        }

    // ============================================================
    //  Bloqueos entre personas
    // ============================================================

    fun bloquear(c: Connection, bloqueador: UUID, bloqueado: UUID) {
        if (bloqueador == bloqueado) throw ErrorNegocio(400, "No puedes bloquearte a ti mismo.")
        c.prepareStatement(
            """INSERT INTO bloqueo (bloqueador_id, bloqueado_id) VALUES (?, ?)
               ON CONFLICT DO NOTHING"""
        ).use { st -> st.setObject(1, bloqueador); st.setObject(2, bloqueado); st.executeUpdate() }
    }

    fun desbloquear(c: Connection, bloqueador: UUID, bloqueado: UUID) {
        c.prepareStatement(
            "DELETE FROM bloqueo WHERE bloqueador_id = ? AND bloqueado_id = ?"
        ).use { st -> st.setObject(1, bloqueador); st.setObject(2, bloqueado); st.executeUpdate() }
    }

    /**
     * ¿`duenio` considera conocido a `otro`?
     *
     * Es la misma pregunta que resuelve `quienesMeConocen` en Repo, pero para
     * UNA pareja en vez de un conjunto. Existe porque los ajustes de privacidad
     * de una lista se resuelven de una sola consulta para toda la lista,
     * mientras una llamada 1:1 pregunta por una sola persona y traer el
     * conjunto completo seria trabajo de mas.
     *
     * Ojo con la DIRECCION, que es donde ya hubo un fallo: un ajuste de
     * privacidad es del DUENO. "Solo mis conocidos pueden llamarme" significa
     * *los que YO considero conocidos*, no los que el otro considera. Preguntar
     * al reves permitiria saltarse el filtro agregando a la victima a la propia
     * libreta.
     */
    fun meConoce(c: Connection, duenio: UUID, otro: UUID): Boolean =
        c.prepareStatement(
            """SELECT 1 FROM participante p1
                 JOIN conversacion cv ON cv.id = p1.conversacion_id AND cv.tipo = 'directa'
                 JOIN participante p2 ON p2.conversacion_id = p1.conversacion_id
               WHERE p1.usuario_id = ? AND p2.usuario_id = ?
                 AND p1.salido_en IS NULL AND p2.salido_en IS NULL
               UNION
               SELECT 1 FROM contacto WHERE usuario_id = ? AND contacto_id = ?
               LIMIT 1"""
        ).use { st ->
            st.setObject(1, duenio); st.setObject(2, otro)
            st.setObject(3, duenio); st.setObject(4, otro)
            st.executeQuery().use { it.next() }
        }

    /** ¿Hay bloqueo en cualquier sentido entre estas dos personas? */
    fun hayBloqueo(c: Connection, a: UUID, b: UUID): Boolean =
        c.prepareStatement(
            """SELECT 1 FROM bloqueo
               WHERE (bloqueador_id = ? AND bloqueado_id = ?)
                  OR (bloqueador_id = ? AND bloqueado_id = ?) LIMIT 1"""
        ).use { st ->
            st.setObject(1, a); st.setObject(2, b); st.setObject(3, b); st.setObject(4, a)
            st.executeQuery().use { it.next() }
        }

    // ============================================================
    //  Auditoria
    // ============================================================

    /**
     * Deja rastro de una accion administrativa.
     *
     * Se llama DENTRO de la misma transaccion que la accion: si la accion se
     * revierte, el registro tambien. Un audit log que sobrevive a un rollback
     * miente.
     *
     * ## Por que hay un SAVEPOINT, y por que se reintenta sin detalle
     *
     * El `detalle` entra como `?::jsonb`. Si no es JSON valido, Postgres falla
     * la sentencia, y **una sentencia fallida aborta la transaccion entera**:
     * el `commit()` posterior se vuelve un ROLLBACK silencioso. La accion
     * devuelve 200 y no paso nada. Este proyecto ya perdio una tarde con eso
     * en `Seguridad.anotar` —ver su nota— y aqui el riesgo estaba latente:
     * todos los `detalle` de hoy interpolan valores de listas cerradas, asi
     * que funciona **por accidente de orden**. El dia que alguien escriba
     * `{"nombre":"${'$'}{req.nombre}"}`, una comilla en un nombre tumba la
     * operacion sin dejar rastro de por que.
     *
     * Con el savepoint, un detalle malformado no se lleva la accion por
     * delante. Y como un audit log sin fila es peor que uno sin adorno, se
     * **reintenta sin el detalle**: la fila queda siempre, y lo unico que se
     * pierde es el JSON que venia mal.
     *
     * Lo que NO se hace es tragarse el fallo en silencio: queda en el log del
     * servidor con la accion concreta, porque un detalle que se pierde en
     * produccion es un defecto que alguien tiene que arreglar.
     */
    fun auditar(
        c: Connection,
        actorId: UUID?,
        accion: String,
        recursoTipo: String,
        recursoId: UUID?,
        objetivoId: UUID? = null,
        detalle: String? = null,
    ) {
        fun insertar(conDetalle: String?) {
            c.prepareStatement(
                """INSERT INTO auditoria (actor_id, accion, recurso_tipo, recurso_id, objetivo_id, detalle)
                   VALUES (?, ?, ?, ?, ?, ?::jsonb)"""
            ).use { st ->
                st.setObject(1, actorId)
                st.setString(2, accion)
                st.setString(3, recursoTipo)
                st.setObject(4, recursoId)
                st.setObject(5, objetivoId)
                st.setString(6, conDetalle)
                st.executeUpdate()
            }
        }

        // Sin transaccion, un savepoint no aplica ni hace falta: un fallo solo
        // afecta a esta sentencia.
        val enTransaccion = runCatching { !c.autoCommit }.getOrDefault(false)
        val punto = if (enTransaccion) runCatching { c.setSavepoint("auditar") }.getOrNull() else null

        try {
            insertar(detalle)
            punto?.let { runCatching { c.releaseSavepoint(it) } }
        } catch (e: Exception) {
            if (punto != null) runCatching { c.rollback(punto) }
            bitacoraAuditoria.warn(
                "Detalle invalido al auditar {}: {}. Se registra sin detalle.", accion, e.message,
            )
            // El rastro importa mas que el adorno. Si esto tambien falla, se
            // deja subir: quedarse sin fila de auditoria en una accion
            // administrativa si es motivo para que la operacion no cuente.
            if (punto != null) runCatching { c.rollback(punto) }
            insertar(null)
        }
    }

    /**
     * Construye el `detalle` de una auditoria sin poder romperlo.
     *
     * Interpolar a mano —`"""{"x":"${'$'}v"}"""`— funciona hasta que `v` trae
     * una comilla, y entonces revienta el cast a `jsonb` en el peor momento
     * posible: dentro de la transaccion de la accion. Esto escapa por
     * construccion, asi que el caso no existe.
     *
     * Los nulos se omiten en vez de escribirse como `null`: en un registro que
     * alguien va a leer, una clave ausente dice lo mismo y ocupa menos.
     */
    fun detalleDe(vararg datos: Pair<String, Any?>): String =
        kotlinx.serialization.json.buildJsonObject {
            datos.forEach { (clave, valor) ->
                when (valor) {
                    null -> Unit
                    is Boolean -> put(clave, kotlinx.serialization.json.JsonPrimitive(valor))
                    is Number -> put(clave, kotlinx.serialization.json.JsonPrimitive(valor))
                    else -> put(clave, kotlinx.serialization.json.JsonPrimitive(valor.toString()))
                }
            }
        }.toString()

    // ============================================================
    //  Mensajes al usuario
    // ============================================================

    private fun codigoDe(m: Denegacion?) = when (m) {
        Denegacion.SIN_SESION -> 401
        Denegacion.NO_PERTENECE -> 404
        else -> 403
    }

    private fun mensajeDe(m: Denegacion?) = when (m) {
        Denegacion.SIN_SESION -> "Sesion invalida o expirada."
        Denegacion.CUENTA_SUSPENDIDA -> "Tu cuenta esta suspendida."
        Denegacion.BLOQUEADO -> "No puedes interactuar con esta persona."
        Denegacion.NO_PERTENECE -> "No perteneces a esta conversacion."
        Denegacion.EXPULSADO -> "Ya no formas parte de esta conversacion."
        Denegacion.SILENCIADO -> "Estas silenciado en esta conversacion."
        // Se dice QUIEN lo cerro -la plataforma- y no "no tienes permiso": un
        // grupo que de golpe rechaza todo sin explicacion parece la app rota, y
        // la gente se pone a reinstalar en vez de preguntar.
        Denegacion.CONVERSACION_CERRADA ->
            "Esta conversacion fue cerrada por la plataforma. Se puede leer, no escribir."
        Denegacion.PERMISO_DENEGADO_EXPLICITO -> "Un administrador te quito este permiso."
        Denegacion.JERARQUIA_INSUFICIENTE -> "No puedes hacer esto sobre alguien de tu mismo rango o superior."
        else -> "No tienes permiso para hacer esto."
    }
}

private val bitacoraAuditoria = org.slf4j.LoggerFactory.getLogger("auditoria")
