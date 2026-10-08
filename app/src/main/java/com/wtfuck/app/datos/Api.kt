package com.wtfuck.app.datos

import android.content.Context
import com.wtfuck.app.BuildConfig
import com.wtfuck.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ApiError(val codigo: Int, mensaje: String) : Exception(mensaje)

val jsonApp = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Sesion persistida. Lo minimo: token, quien soy, y en que dispositivo. */
class Sesion(ctx: Context) {
    private val p = ctx.getSharedPreferences("wtfuck_sesion", Context.MODE_PRIVATE)

    /**
     * El token va ENVUELTO, no en claro.
     *
     * Este token es una credencial portadora: quien lo tenga es la cuenta ante
     * el servidor. Estaba guardado como texto plano en el XML de preferencias,
     * mientras que la frase de paso de SQLCipher —en la carpeta de al lado— ya
     * se guardaba envuelta con una clave no exportable del Keystore. Dos
     * secretos, el mismo sitio, dos niveles de proteccion y ninguna razon
     * escrita para la diferencia.
     *
     * El recinto de Android y `allowBackup="false"` ya cubren lo comun. Esto
     * cubre lo que queda: un aparato con root, una imagen forense, un volcado
     * del almacenamiento. Ver [CajaFuerte].
     *
     * No protege del proceso vivo, y no puede: con la app corriendo, cualquier
     * codigo dentro de ella puede pedirle al Keystore que abra.
     */
    private val caja = CajaFuerte("wtfuck_sesion_v1")

    /** Clave nueva. La vieja, `token`, solo se lee para migrar. */
    private val CAMPO = "token_c"

    /**
     * Lee el token, migrando el que quedo en claro de una version anterior.
     *
     * Sin esta migracion, actualizar la app habria cerrado la sesion de todo
     * el mundo: el campo nuevo esta vacio y el viejo se ignoraria. Cerrar la
     * sesion de todos para mejorar como se guarda el token es pagar el arreglo
     * con la molestia de quien no hizo nada.
     */
    private fun leerToken(): String? {
        p.getString(CAMPO, null)?.let { return caja.abrirTexto(it) }
        val viejo = p.getString("token", null) ?: return null
        runCatching { p.edit().putString(CAMPO, caja.cerrarTexto(viejo)).remove("token").apply() }
        return viejo
    }

    private fun escribirToken(v: String?) {
        val e = p.edit()
        // El campo viejo se borra SIEMPRE, tambien al guardar uno nuevo: si
        // quedara, una sesion vieja en claro sobreviviria a la migracion y
        // seguiria ahi para que alguien la encuentre.
        e.remove("token")
        if (v == null) e.remove(CAMPO) else e.putString(CAMPO, caja.cerrarTexto(v))
        e.apply()
    }

    /**
     * Si el token de este aparato sigue sirviendo.
     *
     * Existe porque el modulo I.5 dejo el cierre remoto a medias: el servidor
     * aprendio a revocar sesiones, pero la app no aprendio a enterarse. El
     * resultado era el peor de los dos mundos: la sesion muerta en el servidor
     * y viva en la pantalla. El aparato seguia mostrando los chats, dejaba
     * escribir, y cada envio moria con "Sesion invalida o expirada" mientras
     * el WebSocket reintentaba con backoff para siempre. Cerrar sesion a
     * distancia no sirve de nada si el aparato cerrado no se da por enterado.
     */
    private val _viva = MutableStateFlow(
        p.getString(CAMPO, null) != null || p.getString("token", null) != null,
    )
    val viva: StateFlow<Boolean> = _viva.asStateFlow()

    /**
     * Por que se cerro, para poder decirlo en la pantalla de entrada.
     *
     * Vale null en un cierre voluntario: ahi la persona ya sabe por que esta
     * viendo el login y explicarselo sobra.
     *
     * Vive en disco y no en memoria porque el cierre remoto suele descubrirse
     * con la app en segundo plano, y Android mata procesos en segundo plano
     * sin avisar. Guardado solo en memoria, el aviso se perdia en el camino y
     * la persona se encontraba de golpe en la pantalla de entrada sin ninguna
     * explicacion, que es exactamente el problema que este aviso arregla.
     */
    val motivoCierre: String? get() = p.getString("cierre", null)

    var token: String?
        get() = leerToken()
        private set(v) { escribirToken(v) }

    val usuarioId: String? get() = p.getString("usuarioId", null)
    val dispositivoId: String? get() = p.getString("dispositivoId", null)
    val username: String? get() = p.getString("username", null)
    val hayS: Boolean get() = token != null

    fun guardar(r: SesionResp) {
        _viva.value = true
        escribirToken(r.token)
        p.edit()
            .remove("cierre")
            .putString("usuarioId", r.usuarioId)
            .putString("dispositivoId", r.dispositivoId)
            .putString("username", r.username)
            .apply()
    }

    /** Cierre voluntario: se va todo, incluido quien era. */
    fun limpiar() {
        _viva.value = false
        p.edit().clear().apply()
    }

    /**
     * El servidor dijo que este token ya no vale.
     *
     * A diferencia de `limpiar`, borra **solo el token** y deja el username y
     * el dispositivoId. Son dos casos distintos y se tratan distinto a
     * proposito:
     *
     *  - Un cierre voluntario es "ya no quiero estar aqui": se borra todo.
     *  - Un cierre involuntario no lo pidio quien esta delante. Puede ser un
     *    cierre remoto legitimo, pero tambien un token caducado. Si aqui se
     *    borrara la base local, un falso positivo se llevaria por delante todo
     *    el historial del aparato, y ese historial no esta en el servidor: el
     *    buzon es tonto y solo guarda lo no entregado. Asi que se tira la
     *    llave y se deja la casa: al volver a entrar en el MISMO aparato
     *    -mismo hardware, mismo dispositivoId- los mensajes siguen ahi.
     *
     * Queda el username a la vista en el login, que es lo que hace cualquier
     * app de mensajeria y lo que evita hacer reescribirlo por algo que quien
     * esta delante no hizo.
     */
    fun invalidar(motivo: String) {
        if (p.getString("token", null) == null) return
        _viva.value = false
        p.edit().remove("token").putString("cierre", motivo).apply()
    }

    /** El aviso se dice una vez. */
    fun olvidarMotivo() { p.edit().remove("cierre").apply() }
}

class ApiCliente(private val sesion: Sesion) {

    // Todos los clientes pasan por `Red`: es la que pone el proxy, si hay.
    private val http = Red.construir(Pinning.aplicar(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Sin reintentos automaticos: la cola de salida decide cuando reintentar.
            .retryOnConnectionFailure(false)
            // Cada respuesta trae la hora del servidor: con ella se corrige
            // la de este telefono antes de fechar un mensaje. Ver `Reloj`.
            .addInterceptor { cadena ->
                val resp = cadena.proceed(cadena.request())
                resp.header("X-Hora")?.toLongOrNull()?.let { Reloj.observar(it) }
                resp
            }
    ))

    /**
     * Cliente aparte para el almacen de archivos.
     *
     * Un archivo de 40 MB en 3G tarda minutos, y el timeout de 30 s que sirve
     * para una peticion de API lo cortaria siempre. Compartir el cliente
     * obligaria a elegir un solo timeout para dos cosas muy distintas.
     */
    private val httpAlmacen = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .let(Red::construir)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // --- verbos -------------------------------------------------------

    private suspend inline fun <reified T> pedir(
        ruta: String,
        metodo: String,
        cuerpo: String?,
        autenticado: Boolean,
    ): T = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(BuildConfig.SERVIDOR + ruta)
        if (autenticado) {
            val t = sesion.token ?: throw ApiError(401, "No hay sesion activa.")
            b.header("Authorization", "Bearer $t")
        }
        when (metodo) {
            "POST" -> b.post((cuerpo ?: "{}").toRequestBody(JSON))
            "PUT" -> b.put((cuerpo ?: "{}").toRequestBody(JSON))
            "DELETE" -> b.delete()
            else -> b.get()
        }

        val resp = try {
            http.newCall(b.build()).execute()
        } catch (e: Exception) {
            // Tragarse la excepcion deja el fallo invisible. Se registra la causa
            // real y al usuario se le da el mensaje corto.
            android.util.Log.w("Api", "Fallo $metodo $ruta -> ${e.javaClass.simpleName}: ${e.message}", e)
            throw ApiError(0, "No se pudo conectar con el servidor.")
        }

        resp.use {
            val txt = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val motivo = runCatching {
                    jsonApp.decodeFromString(ErrorResp.serializer(), txt).motivo
                }.getOrDefault("Error ${it.code}.")
                // Un 401 en una peticion QUE LLEVABA token significa que ese
                // token ya no sirve, y ninguna cantidad de reintentos lo va a
                // arreglar. Se corta aqui, en el unico punto por donde pasan
                // todas las llamadas, y no en cada pantalla.
                //
                // La condicion `autenticado` no es decorativa: en el login un
                // 401 es "contrasena incorrecta" o "hace falta el codigo de
                // dos pasos", y tratar eso como sesion muerta borraria la
                // sesion de alguien que todavia no tiene ninguna.
                if (autenticado && it.code == 401) sesion.invalidar(motivo)
                throw ApiError(it.code, motivo)
            }
            if (T::class == Unit::class) Unit as T
            else jsonApp.decodeFromString(txt)
        }
    }

    // --- operaciones --------------------------------------------------

    suspend fun registrar(req: RegistroReq): SesionResp =
        pedir<SesionResp>(RUTA_REGISTRO, "POST", jsonApp.encodeToString(req), false)
            .also { sesion.guardar(it) }

    /**
     * La version publicada del APK. Sin autenticar.
     *
     * Tiene que funcionar en una app tan vieja que ya no puede entrar: si un
     * cambio de protocolo la dejo fuera, "actualizate" es justo la respuesta
     * que necesita, y no puede depender de un login que ya no le sirve.
     */
    suspend fun versionPublicada(): VersionResp =
        pedir(RUTA_VERSION, "GET", null, false)

    /**
     * Baja un archivo a disco, informando del avance.
     *
     * Usa el cliente del almacen -minutos de timeout, no 30 segundos-: un APK
     * son decenas de megas y en una red mala el cliente de API lo cortaria
     * siempre, justo en los telefonos donde mas falta hace poder actualizar.
     *
     * La URL se comprueba aqui ademas de en quien llama. Es la unica funcion
     * de esta clase que escribe en disco lo que diga una direccion que viene
     * del servidor, asi que el `https` no puede depender de que el de arriba
     * se acuerde.
     */
    suspend fun descargarA(url: String, destino: File, progreso: (Int) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "La descarga tiene que ir por https." }
            val r = httpAlmacen.newCall(Request.Builder().url(url).get().build()).execute()
            r.use {
                if (!it.isSuccessful) throw ApiError(it.code, "No se pudo descargar.")
                val cuerpo = it.body ?: throw ApiError(it.code, "Respuesta vacia.")
                val total = cuerpo.contentLength()
                destino.outputStream().use { salida ->
                    cuerpo.byteStream().use { entrada ->
                        val buf = ByteArray(64 * 1024)
                        var hechos = 0L
                        while (true) {
                            val n = entrada.read(buf)
                            if (n <= 0) break
                            salida.write(buf, 0, n)
                            hechos += n
                            // Solo si se sabe cuanto pesa: sin
                            // `Content-Length` un porcentaje seria inventado,
                            // y una barra que miente es peor que ninguna.
                            if (total > 0) progreso(((hechos * 100) / total).toInt())
                        }
                    }
                }
            }
        }

    /**
     * Si este servidor pide invitacion, preguntado ANTES del formulario.
     *
     * Sin autenticar, porque quien lo pregunta todavia no tiene cuenta: ese es
     * el unico momento en que la respuesta sirve de algo.
     *
     * No filtra nada que no se descubra igual intentando registrarse. Lo que
     * evita es que alguien rellene el formulario entero para que lo rechacen
     * al final por un campo que no sabia que existia.
     */
    suspend fun modoRegistro(): ModoRegistroResp =
        pedir(RUTA_REGISTRO_MODO, "GET", null, false)

    /** W5e · El reto para la atestacion. Sin sesion: va antes de tener cuenta. */
    suspend fun retoAtestacion(): RetoAtestacion =
        pedir(RUTA_ATESTACION_RETO, "POST", null, false)

    suspend fun crearInvitacion(req: NuevaInvitacionReq): InvitacionResp =
        pedir(RUTA_INVITACIONES_REGISTRO, "POST", jsonApp.encodeToString(req), true)

    suspend fun listarInvitaciones(): List<InvitacionResp> =
        pedir(RUTA_INVITACIONES_REGISTRO, "GET", null, true)

    suspend fun revocarInvitacion(codigo: String): Unit =
        pedir("$RUTA_INVITACIONES_REGISTRO/$codigo", "DELETE", null, true)

    suspend fun login(req: SesionReq): SesionResp =
        pedir<SesionResp>(RUTA_SESION, "POST", jsonApp.encodeToString(req), false)
            .also { sesion.guardar(it) }

    suspend fun buscar(username: String): UsuarioPublico =
        pedir("$RUTA_USUARIO/$username", "GET", null, true)

    /** Una pagina del directorio de Usuarios. Ver V44. */
    suspend fun directorio(consulta: String, desde: String): DirectorioResp =
        pedir(
            "$RUTA_DIRECTORIO?q=" + java.net.URLEncoder.encode(consulta, "UTF-8") +
                "&desde=" + java.net.URLEncoder.encode(desde, "UTF-8"),
            "GET", null, true,
        )

    suspend fun excepcionesPrivacidad(): TodasLasExcepciones =
        pedir("$RUTA_PRIVACIDAD/excepciones", "GET", null, true)

    suspend fun guardarExcepciones(req: ExcepcionesPrivacidad): TodasLasExcepciones =
        pedir("$RUTA_PRIVACIDAD/excepciones", "PUT", jsonApp.encodeToString(req), true)

    suspend fun mensajesLeidos(convId: String): MensajesLeidos =
        pedir("$RUTA_CONVERSACIONES/$convId/leidos", "GET", null, true)

    suspend fun conversaciones(): List<ConversacionResumen> =
        pedir(RUTA_CONVERSACIONES, "GET", null, true)

    suspend fun crearDirecta(username: String, duracionMs: Long = 0, enlace: String? = null): ConversacionResumen =
        pedir(RUTA_DIRECTA, "POST", jsonApp.encodeToString(DirectaReq(username, duracionMs, enlace)), true)

    // --- Enlace de contacto. Ver `EnlaceDeContacto`. ---------------------------
    suspend fun miEnlace(): MiEnlace = pedir(RUTA_MI_ENLACE, "GET", null, true)

    suspend fun crearEnlace(): MiEnlace = pedir(RUTA_MI_ENLACE, "POST", null, true)

    suspend fun borrarEnlace(): Unit = pedir(RUTA_MI_ENLACE, "DELETE", null, true)

    suspend fun resolverEnlace(codigo: String): UsuarioPublico =
        pedir("$RUTA_ENLACES/$codigo", "GET", null, true)

    suspend fun notaParaMi(): ConversacionResumen = pedir(RUTA_NOTAS, "POST", null, true)

    suspend fun infoMensaje(id: String): InfoMensaje = pedir("$RUTA_MENSAJES/$id/info", "GET", null, true)

    suspend fun crearGrupo(
        nombre: String,
        usernames: List<String>,
        duracionMs: Long = 0,
    ): ConversacionResumen =
        pedir(RUTA_GRUPOS, "POST", jsonApp.encodeToString(GrupoReq(nombre, usernames, duracionMs)), true)

    suspend fun agregarMiembros(convId: String, usernames: List<String>): ConversacionResumen =
        pedir("$RUTA_CONVERSACIONES/$convId/miembros", "POST", jsonApp.encodeToString(MiembrosReq(usernames)), true)

    suspend fun salir(convId: String): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/salir", "POST", null, true)

    // --- perfil -------------------------------------------------------

    suspend fun miPerfil(): UsuarioPublico = pedir(RUTA_PERFIL, "GET", null, true)

    // --- modulo C: mensajes -------------------------------------------

    suspend fun registrarMensaje(req: RegistrarMensajeReq): MensajeMeta =
        pedir(RUTA_MENSAJES, "POST", jsonApp.encodeToString(req), true)

    suspend fun mensajeMeta(id: String): MensajeMeta =
        pedir("$RUTA_MENSAJES/$id", "GET", null, true)

    /** Avisa que se abrio un "ver una vez". Ver V47. */
    suspend fun unaVezAbierta(id: String): Unit =
        pedir("$RUTA_MENSAJES/$id/abierto", "POST", null, true)

    suspend fun retirarMensaje(id: String): Unit =
        pedir("$RUTA_MENSAJES/$id/retirar", "POST", null, true)

    suspend fun editarMensaje(id: String): Unit =
        pedir("$RUTA_MENSAJES/$id/editar", "POST", null, true)

    suspend fun fijarMensaje(id: String, fijar: Boolean): Unit =
        pedir("$RUTA_MENSAJES/$id/fijar", "POST", jsonApp.encodeToString(FijarReq(fijar)), true)

    suspend fun reaccionar(mensajeId: String, emoji: String, poner: Boolean): MensajeMeta =
        pedir("$RUTA_MENSAJES/reaccion", "POST",
            jsonApp.encodeToString(ReaccionReq(mensajeId, emoji, poner)), true)

    suspend fun fijadosDe(convId: String): List<MensajeMeta> =
        pedir("$RUTA_CONVERSACIONES/$convId/fijados", "GET", null, true)

    suspend fun configurarTemporales(convId: String, segundos: Int?): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/temporales", "PUT",
            jsonApp.encodeToString(TemporalesReq(segundos)), true)

    // --- modulo B: grupos ---------------------------------------------

    suspend fun configGrupo(convId: String): ConfigGrupo =
        pedir("$RUTA_CONVERSACIONES/$convId/config", "GET", null, true)

    suspend fun guardarConfigGrupo(convId: String, cfg: ConfigGrupo): ConfigGrupo =
        pedir("$RUTA_CONVERSACIONES/$convId/config", "PUT", jsonApp.encodeToString(cfg), true)

    suspend fun miembros(convId: String): List<MiembroDetalle> =
        pedir("$RUTA_CONVERSACIONES/$convId/miembros", "GET", null, true)

    suspend fun rolesDe(convId: String): List<RolDetalle> =
        pedir("$RUTA_CONVERSACIONES/$convId/roles", "GET", null, true)

    suspend fun cambiarRol(convId: String, usuarioId: String, rolClave: String): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/miembros/$usuarioId/rol", "POST",
            jsonApp.encodeToString(CambiarRolReq(rolClave)), true)

    suspend fun expulsar(convId: String, usuarioId: String, motivo: String, vetar: Boolean): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/miembros/$usuarioId/expulsar", "POST",
            jsonApp.encodeToString(ExpulsarReq(motivo, vetar)), true)

    suspend fun silenciarMiembro(convId: String, usuarioId: String, minutos: Int): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/miembros/$usuarioId/silenciar", "POST",
            jsonApp.encodeToString(SilenciarReq(minutos)), true)

    suspend fun crearInvitacion(convId: String, horas: Int, usosMax: Int): Invitacion =
        pedir("$RUTA_CONVERSACIONES/$convId/invitaciones", "POST",
            jsonApp.encodeToString(CrearInvitacionReq(horas, usosMax)), true)

    suspend fun solicitudes(convId: String): List<Solicitud> =
        pedir("$RUTA_CONVERSACIONES/$convId/solicitudes", "GET", null, true)

    suspend fun resolverSolicitud(convId: String, usuarioId: String, aprobar: Boolean): Unit =
        pedir("$RUTA_CONVERSACIONES/$convId/solicitudes/$usuarioId", "POST",
            jsonApp.encodeToString(ResolverSolicitudReq(aprobar)), true)

    // --- preferencias personales y bloqueos ---------------------------

    suspend fun preferencias(convId: String, p: PreferenciasChat): EstadoChat =
        pedir("$RUTA_CONVERSACIONES/$convId/preferencias", "PUT", jsonApp.encodeToString(p), true)

    suspend fun bloquear(username: String): Unit =
        pedir("$RUTA_BLOQUEOS/$username", "POST", null, true)

    suspend fun desbloquear(username: String): Unit =
        pedir("$RUTA_BLOQUEOS/$username", "DELETE", null, true)

    suspend fun bloqueados(): List<Bloqueado> = pedir(RUTA_BLOQUEOS, "GET", null, true)

    suspend fun privacidad(): Privacidad = pedir(RUTA_PRIVACIDAD, "GET", null, true)

    suspend fun guardarPrivacidad(p: Privacidad): Privacidad =
        pedir(RUTA_PRIVACIDAD, "PUT", jsonApp.encodeToString(p), true)

    // --- modulo P: tipos de cuenta ---------------------------------
    //
    // `capacidades` responde para todo el mundo y trae `puedeElegirTipo`. Las
    // otras dos contestan 404 a quien esta fuera de la beta, y esta bien que
    // asi sea: la puerta la cierra el servidor, no esta pantalla.
    suspend fun capacidades(): CapacidadesCuenta = pedir(RUTA_TIPO_CUENTA, "GET", null, true)

    suspend fun elegirTipoCuenta(tipo: String): CapacidadesCuenta =
        pedir(RUTA_TIPO_CUENTA, "PUT", jsonApp.encodeToString(CambiarTipoReq(tipo)), true)

    suspend fun guardarFichaEmpresa(req: FichaEmpresaReq): FichaEmpresa =
        pedir(RUTA_EMPRESA, "PUT", jsonApp.encodeToString(req), true)

    suspend fun guardarPerfil(nombre: String, estado: String): UsuarioPublico =
        pedir(RUTA_PERFIL, "PUT", jsonApp.encodeToString(PerfilReq(nombre, estado)), true)

    /** `campo` es "avatar" o "portada". Sube los bytes crudos de la imagen. */
    suspend fun subirImagen(campo: String, bytes: ByteArray): UsuarioPublico =
        withContext(Dispatchers.IO) {
            val t = sesion.token ?: throw ApiError(401, "No hay sesion activa.")
            val req = Request.Builder()
                .url(BuildConfig.SERVIDOR + RUTA_PERFIL + "/" + campo)
                .header("Authorization", "Bearer $t")
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: Exception) {
                android.util.Log.w("Api", "Fallo subida de $campo: ${e.message}", e)
                throw ApiError(0, "No se pudo subir la imagen.")
            }
            resp.use {
                val txt = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val motivo = runCatching {
                        jsonApp.decodeFromString(ErrorResp.serializer(), txt).motivo
                    }.getOrDefault("Error ${it.code}.")
                    throw ApiError(it.code, motivo)
                }
                jsonApp.decodeFromString<UsuarioPublico>(txt)
            }
        }

    // --- modulo D: adjuntos -------------------------------------------

    suspend fun reservarAdjunto(req: ReservarAdjuntoReq): AdjuntoReservado =
        pedir(RUTA_ADJUNTOS, "POST", jsonApp.encodeToString(req), true)

    suspend fun confirmarAdjunto(adjuntoId: String): AdjuntoInfo =
        pedir("$RUTA_ADJUNTOS/$adjuntoId/confirmar", "POST", null, true)

    /**
     * Pide una URL de descarga fresca.
     *
     * Las URL firmadas caducan, asi que NO se guardan: se piden en el momento
     * de usarlas. Guardar una en la base seria guardar algo que manana no sirve.
     */
    suspend fun adjunto(adjuntoId: String): AdjuntoInfo =
        pedir("$RUTA_ADJUNTOS/$adjuntoId", "GET", null, true)

    suspend fun usoAlmacen(): UsoAlmacenamiento =
        pedir("$RUTA_ADJUNTOS/uso", "GET", null, true)

    /**
     * Sube el archivo YA CIFRADO a la URL firmada.
     *
     * Va SIN el token de sesion: la autorizacion es la firma de la URL, y los
     * bytes no pasan por el servidor de la aplicacion. Tampoco se toca el host
     * de la URL: la firma incluye el header Host y reescribirlo la invalida.
     */
    suspend fun subirAlAlmacen(url: String, archivo: File): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .put(archivo.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val resp = try {
            httpAlmacen.newCall(req).execute()
        } catch (e: Exception) {
            android.util.Log.w("Api", "Fallo la subida al almacen: ${e.message}", e)
            throw ApiError(0, "No se pudo subir el archivo.")
        }
        resp.use {
            if (!it.isSuccessful) {
                android.util.Log.w("Api", "El almacen rechazo la subida: ${it.code}")
                throw ApiError(it.code, "El almacen rechazo el archivo.")
            }
        }
    }

    /**
     * Descarga el archivo cifrado y entrega el flujo a `bloque` para
     * descifrarlo al vuelo.
     *
     * Se pasa el flujo y no los bytes para no tener el archivo entero en
     * memoria: un video de 64 MB no cabe dos veces en el heap.
     */
    suspend fun <T> bajarDelAlmacen(url: String, bloque: (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val resp = try {
                httpAlmacen.newCall(Request.Builder().url(url).get().build()).execute()
            } catch (e: Exception) {
                android.util.Log.w("Api", "Fallo la descarga del almacen: ${e.message}", e)
                throw ApiError(0, "No se pudo descargar el archivo.")
            }
            resp.use {
                if (!it.isSuccessful) throw ApiError(it.code, "El archivo ya no esta disponible.")
                bloque(it.body.byteStream())
            }
        }

    // --- modulo O: historias ------------------------------------------

    suspend fun destinosHistoria(): DestinosHistoria =
        pedir("$RUTA_HISTORIAS/destinos", "GET", null, true)

    suspend fun publicarHistoria(req: PublicarHistoriaReq): HistoriaMia =
        pedir(RUTA_HISTORIAS, "POST", jsonApp.encodeToString(req), true)

    suspend fun sobresHistoria(id: String, req: SobresHistoriaReq): SobresPendientesResp =
        pedir("$RUTA_HISTORIAS/$id/sobres", "POST", jsonApp.encodeToString(req), true)

    suspend fun historiasParaMi(): HistoriasParaMi =
        pedir(RUTA_HISTORIAS, "GET", null, true)

    suspend fun misHistorias(): MisHistorias =
        pedir("$RUTA_HISTORIAS/mias", "GET", null, true)

    suspend fun marcarHistoriaVista(id: String): Unit =
        pedir("$RUTA_HISTORIAS/$id/vista", "POST", null, true)

    suspend fun vistasDeHistoria(id: String): VistasDeHistoria =
        pedir("$RUTA_HISTORIAS/$id/vistas", "GET", null, true)

    suspend fun retirarHistoria(id: String): Unit =
        pedir("$RUTA_HISTORIAS/$id", "DELETE", null, true)

    // --- modulo F: canales --------------------------------------------

    suspend fun crearCanal(req: CrearCanalReq): ConfigCanal =
        pedir(RUTA_CANALES, "POST", jsonApp.encodeToString(req), true)

    suspend fun canal(convId: String): ConfigCanal =
        pedir("$RUTA_CANALES/$convId", "GET", null, true)

    suspend fun canalPorAlias(alias: String): ConfigCanal =
        pedir("$RUTA_CANALES/alias/${alias.removePrefix("@")}", "GET", null, true)

    suspend fun configurarCanal(convId: String, req: ConfigCanalReq): ConfigCanal =
        pedir("$RUTA_CANALES/$convId", "PUT", jsonApp.encodeToString(req), true)

    suspend fun suscribirCanal(convId: String): ConfigCanal =
        pedir("$RUTA_CANALES/$convId/suscribir", "POST", null, true)

    suspend fun desuscribirCanal(convId: String): Unit =
        pedir("$RUTA_CANALES/$convId/desuscribir", "POST", null, true)

    /** F.7: la lista curada. Es la puerta normal; buscar es el filtro. */
    suspend fun directorioCanales(): DirectorioCanales =
        pedir("$RUTA_CANALES/directorio?limite=60", "GET", null, true)

    // --- H.6: limites y bitacora ---------------------------------------

    suspend fun conversacionesPanel(q: String?, cerradas: Boolean): ConversacionesPanel {
        val filtro = q?.takeIf { it.isNotBlank() }
            ?.let { "q=" + java.net.URLEncoder.encode(it, "UTF-8") + "&" }.orEmpty()
        return pedir("$RUTA_PANEL/conversaciones?${filtro}cerradas=$cerradas", "GET", null, true)
    }

    suspend fun cerrarConversacion(id: String, motivo: String): Unit =
        pedir(
            "$RUTA_PANEL/conversaciones/$id/cerrar", "POST",
            jsonApp.encodeToString(CerrarConversacionReq(motivo)), true,
        )

    suspend fun reabrirConversacion(id: String): Unit =
        pedir("$RUTA_PANEL/conversaciones/$id/reabrir", "POST", null, true)

    // --- L.8: la consola web -------------------------------------------

    suspend fun abrirConsola(req: EmitirConsolaReq): TokenConsola =
        pedir("$RUTA_PANEL/consola", "POST", jsonApp.encodeToString(req), true)

    suspend fun consolasAbiertas(): ConsolasAbiertas =
        pedir("$RUTA_PANEL/consola", "GET", null, true)

    suspend fun cerrarConsola(id: String): Unit =
        pedir("$RUTA_PANEL/consola/$id", "DELETE", null, true)

    suspend fun limitesPanel(): LimitesPanel =
        pedir("$RUTA_PANEL/limites", "GET", null, true)

    suspend fun ajustarLimite(clave: String, req: AjustarLimiteReq): LimiteAjustable =
        pedir("$RUTA_PANEL/limites/$clave", "PUT", jsonApp.encodeToString(req), true)

    suspend fun restaurarLimite(clave: String): Unit =
        pedir("$RUTA_PANEL/limites/$clave", "DELETE", null, true)

    suspend fun bitacora(filtro: String?): Bitacora {
        val q = filtro?.takeIf { it.isNotBlank() }
            ?.let { "&q=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return pedir("$RUTA_PANEL/bitacora?limite=80$q", "GET", null, true)
    }

    suspend fun colaCanales(estado: String = EstadoCanal.PENDIENTE): ColaCanales =
        pedir("$RUTA_PANEL/canales?estado=$estado", "GET", null, true)

    suspend fun revisarCanal(convId: String, req: RevisarCanalReq): Unit =
        pedir("$RUTA_PANEL/canales/$convId", "POST", jsonApp.encodeToString(req), true)

    suspend fun buscarCanales(consulta: String): ResultadoBusquedaCanales {
        val q = java.net.URLEncoder.encode(consulta, "UTF-8")
        return pedir("$RUTA_CANALES/buscar?q=$q", "GET", null, true)
    }

    suspend fun publicaciones(convId: String, antesDe: String? = null): List<Publicacion> =
        pedir(
            "$RUTA_CANALES/$convId/publicaciones?limite=30" + (antesDe?.let { "&antes=$it" } ?: ""),
            "GET", null, true,
        )

    /** Guarda el cuerpo de una publicacion. Solo en canales publicos. */
    suspend fun publicarEnCanal(convId: String, req: PublicarReq): Unit =
        pedir("$RUTA_CANALES/$convId/publicaciones", "POST", jsonApp.encodeToString(req), true)

    /** Guarda el cuerpo de un comentario. Solo en canales publicos. */
    suspend fun comentarEnCanal(convId: String, req: ComentarReq): Unit =
        pedir("$RUTA_CANALES/$convId/comentarios", "POST", jsonApp.encodeToString(req), true)

    suspend fun comentariosDeCanal(convId: String, publicacionId: String): ComentariosResp =
        pedir(
            "$RUTA_CANALES/$convId/publicaciones/$publicacionId/comentarios?limite=100",
            "GET", null, true,
        )

    // --- modulo AD: comunidades ---------------------------------------

    suspend fun crearComunidad(req: CrearComunidadReq): ComunidadDetalle =
        pedir(RUTA_COMUNIDADES, "POST", jsonApp.encodeToString(req), true)

    suspend fun misComunidades(): ListaComunidades =
        pedir(RUTA_COMUNIDADES, "GET", null, true)

    suspend fun comunidad(id: String): ComunidadDetalle =
        pedir("$RUTA_COMUNIDADES/$id", "GET", null, true)

    suspend fun editarComunidad(id: String, req: EditarComunidadReq): ComunidadDetalle =
        pedir("$RUTA_COMUNIDADES/$id", "PUT", jsonApp.encodeToString(req), true)

    suspend fun agregarGruposAComunidad(id: String, req: AgregarGruposReq): ComunidadDetalle =
        pedir("$RUTA_COMUNIDADES/$id/grupos", "POST", jsonApp.encodeToString(req), true)

    suspend fun quitarGrupoDeComunidad(id: String, grupo: String): Unit =
        pedir("$RUTA_COMUNIDADES/$id/grupos/$grupo", "DELETE", null, true)

    suspend fun estadisticasCanal(convId: String): EstadisticasCanal =
        pedir("$RUTA_CANALES/$convId/estadisticas", "GET", null, true)

    // --- modulo K: llamadas -------------------------------------------
    //
    // Aqui NO hay señalizacion. El SDP y los candidatos van en sobres cifrados
    // porque llevan las huellas DTLS. Estas rutas son metadatos.

    suspend fun iniciarLlamada(req: IniciarLlamadaReq): LlamadaCreada =
        pedir(RUTA_LLAMADAS, "POST", jsonApp.encodeToString(req), true)

    suspend fun contestarLlamada(id: String): LlamadaEnCurso =
        pedir("$RUTA_LLAMADAS/$id/contestar", "POST", null, true)

    suspend fun terminarLlamada(id: String, motivo: String): Unit =
        pedir(
            "$RUTA_LLAMADAS/$id/terminar", "POST",
            jsonApp.encodeToString(TerminarLlamadaReq(motivo)), true,
        )

    suspend fun historialLlamadas(): HistorialLlamadas =
        pedir("$RUTA_LLAMADAS/historial?limite=80", "GET", null, true)

    suspend fun llamadaEnCurso(): LlamadaEnCurso =
        pedir("$RUTA_LLAMADAS/en-curso", "GET", null, true)

    suspend fun turn(): ConfigTurn =
        pedir("$RUTA_LLAMADAS/turn", "GET", null, true)

    // --- modulo J: varios dispositivos ---------------------------------

    // --- modulo N: push ---

    suspend fun configPush(): ConfigPush =
        pedir("$RUTA_PUSH/config", "GET", null, true)

    suspend fun registrarPush(req: RegistrarPushReq): Unit =
        pedir(RUTA_PUSH, "PUT", jsonApp.encodeToString(req), true)

    suspend fun borrarPush(): Unit =
        pedir(RUTA_PUSH, "DELETE", null, true)

    suspend fun dispositivos(): DispositivosResp =
        pedir(RUTA_DISPOSITIVOS, "GET", null, true)

    suspend fun emitirCodigoVinculacion(password: String): CodigoVinculacion =
        pedir(
            "$RUTA_DISPOSITIVOS/codigo", "POST",
            jsonApp.encodeToString(EliminarCuentaReq(password)), true,
        )

    /** Sin sesion: este aparato todavia no tiene ninguna. */
    suspend fun vincular(req: VincularReq): VincularHechoResp =
        pedir("$RUTA_DISPOSITIVOS/vincular", "POST", jsonApp.encodeToString(req), false)

    suspend fun revocarDispositivo(id: String): Unit =
        pedir("$RUTA_DISPOSITIVOS/$id", "DELETE", null, true)

    suspend fun promoverDispositivo(id: String, password: String): Unit =
        pedir(
            "$RUTA_DISPOSITIVOS/$id/principal", "POST",
            jsonApp.encodeToString(EliminarCuentaReq(password)), true,
        )

    suspend fun pedirHistorial(): EstadoHistorial =
        pedir("$RUTA_DISPOSITIVOS/historial", "POST", null, true)

    suspend fun anotarHistorialEnviado(destinoId: String, cuantos: Int): Unit =
        pedir("$RUTA_DISPOSITIVOS/$destinoId/historial-enviado?n=$cuantos", "POST", null, true)

    // --- modulo I: identidad y cuenta ---------------------------------

    suspend fun estadoCuenta(): EstadoCuenta =
        pedir(RUTA_CUENTA, "GET", null, true)

    suspend fun ajustarCuenta(req: AjustesCuentaReq): EstadoCuenta =
        pedir(RUTA_CUENTA, "PUT", jsonApp.encodeToString(req), true)

    /**
     * Pide un codigo por SMS.
     *
     * `autenticado` es un parametro porque recuperar la cuenta es justamente
     * el caso en que no hay sesion. Verificar un numero si la necesita, y eso
     * lo comprueba el servidor segun el proposito.
     */
    suspend fun pedirCodigo(req: PedirCodigoReq, autenticado: Boolean = true): CodigoPedido =
        pedir(
            "$RUTA_CUENTA/codigo", "POST", jsonApp.encodeToString(req), autenticado,
        )

    suspend fun verificarTelefono(req: VerificarTelefonoReq): TelefonoVerificado =
        pedir("$RUTA_CUENTA/telefono", "POST", jsonApp.encodeToString(req), true)

    suspend fun quitarTelefono(): Unit =
        pedir("$RUTA_CUENTA/telefono", "DELETE", null, true)

    suspend fun recuperarCuenta(req: RecuperarReq): Unit =
        pedir("$RUTA_CUENTA/recuperar", "POST", jsonApp.encodeToString(req), false)

    // --- codigo de recuperacion ---------------------------------------
    //
    // Lo que viaja es el VERIFICADOR derivado, nunca el codigo. Ver
    // `CodigoRecuperacion`.

    suspend fun fijarRecuperacion(req: FijarRecuperacionReq): Unit =
        pedir("$RUTA_CUENTA/recuperacion", "PUT", jsonApp.encodeToString(req), true)

    suspend fun estadoRecuperacion(): EstadoRecuperacion =
        pedir("$RUTA_CUENTA/recuperacion", "GET", null, true)

    /** Sin sesion: es justo el caso en que no se puede tener una. */
    suspend fun recuperarDispositivo(req: RecuperarDispositivoReq): SesionResp =
        pedir("$RUTA_CUENTA/recuperar-dispositivo", "POST", jsonApp.encodeToString(req), false)

    suspend fun iniciarTotp(): TotpIniciado =
        pedir("$RUTA_CUENTA/totp", "POST", null, true)

    suspend fun confirmarTotp(req: TotpConfirmarReq): TotpActivado =
        pedir("$RUTA_CUENTA/totp/confirmar", "POST", jsonApp.encodeToString(req), true)

    suspend fun apagarTotp(password: String): Unit =
        pedir(
            "$RUTA_CUENTA/totp", "DELETE",
            jsonApp.encodeToString(EliminarCuentaReq(password)), true,
        )

    suspend fun pedirEliminacion(req: EliminarCuentaReq): EliminacionPedida =
        pedir("$RUTA_CUENTA/eliminar", "POST", jsonApp.encodeToString(req), true)

    suspend fun sesiones(): SesionesResp =
        pedir(RUTA_SESIONES, "GET", null, true)

    suspend fun cerrarSesionRemota(id: String): Unit =
        pedir("$RUTA_SESIONES/$id", "DELETE", null, true)

    suspend fun cerrarOtrasSesiones(): Map<String, Int> =
        pedir("$RUTA_SESIONES/otras", "DELETE", null, true)

    /** Cierra la sesion EN EL SERVIDOR. Hasta el modulo I esto no existia. */
    suspend fun salir(): Unit =
        pedir(RUTA_SESIONES, "DELETE", null, true)

    suspend fun contactos(): ContactosResp =
        pedir(RUTA_CONTACTOS, "GET", null, true)

    suspend fun guardarContacto(req: GuardarContactoReq): ContactosResp =
        pedir(RUTA_CONTACTOS, "POST", jsonApp.encodeToString(req), true)

    suspend fun borrarContacto(username: String): ContactosResp =
        pedir("$RUTA_CONTACTOS/$username", "DELETE", null, true)

    suspend fun descubrir(telefonos: List<String>): DescubrirResp =
        pedir(
            "$RUTA_CONTACTOS/descubrir", "POST",
            jsonApp.encodeToString(DescubrirReq(telefonos)), true,
        )

    // --- modulo G y H: moderacion -------------------------------------

    /**
     * Denuncia algo.
     *
     * Si `req.evidencia` no esta vacia, esto manda TEXTO EN CLARO al servidor.
     * Es la unica forma de moderar lo que el servidor no puede leer: su
     * telefono ya descifro esos mensajes y es el unico que puede entregarlos.
     * La pantalla que llama aqui tiene que haberlo dicho ANTES de confirmar.
     */
    suspend fun denunciar(req: DenunciaReq): DenunciaCreada =
        pedir("$RUTA_MODERACION/denuncias", "POST", jsonApp.encodeToString(req), true)

    suspend fun miEstadoModeracion(): MiEstadoModeracion =
        pedir("$RUTA_MODERACION/mi-estado", "GET", null, true)

    suspend fun reconocerAdvertencia(id: String): Unit =
        pedir("$RUTA_MODERACION/advertencias/$id/reconocer", "POST", null, true)

    suspend fun misEventos(): EventosSeguridadResp =
        pedir("$RUTA_MODERACION/mis-eventos?limite=60", "GET", null, true)

    suspend fun colaModeracion(estado: String? = null): ColaModeracion =
        pedir(
            "$RUTA_MODERACION/cola" + (estado?.let { "?estado=$it" } ?: ""),
            "GET", null, true,
        )

    suspend fun denuncia(id: String): DenunciaDetalle =
        pedir("$RUTA_MODERACION/denuncias/$id", "GET", null, true)

    suspend fun tomarDenuncia(id: String): DenunciaEnCola =
        pedir("$RUTA_MODERACION/denuncias/$id/tomar", "POST", null, true)

    suspend fun resolverDenuncia(id: String, req: ResolverReq): ResolucionHecha =
        pedir("$RUTA_MODERACION/denuncias/$id/resolver", "POST", jsonApp.encodeToString(req), true)

    suspend fun resumenPanel(): ResumenPanel =
        pedir("$RUTA_PANEL/resumen", "GET", null, true)

    suspend fun usuariosPanel(consulta: String): UsuariosPanel {
        val q = java.net.URLEncoder.encode(consulta, "UTF-8")
        return pedir("$RUTA_PANEL/usuarios?q=$q", "GET", null, true)
    }

    suspend fun suspenderUsuario(username: String, req: SuspenderReq): UsuarioPanel =
        pedir("$RUTA_PANEL/usuarios/$username/suspender", "POST", jsonApp.encodeToString(req), true)

    suspend fun restaurarUsuario(username: String): UsuarioPanel =
        pedir("$RUTA_PANEL/usuarios/$username/restaurar", "POST", null, true)

    // --- modulo P: tipos de cuenta desde el panel ---------------------
    //
    // Cuelgan de /v1/panel y no de /v1/cuenta porque son de staff actuando
    // SOBRE otra persona, que es otra cosa que administrar la propia.

    suspend fun asignarTipoCuenta(username: String, tipo: String): Unit =
        pedir(
            "$RUTA_PANEL/cuentas/tipo", "PUT",
            jsonApp.encodeToString(AsignarTipoReq(username, tipo)), true,
        )

    suspend fun verificarEmpresa(username: String, valor: Boolean): Unit =
        pedir("$RUTA_PANEL/cuentas/$username/verificar?valor=$valor", "PUT", null, true)

    // --- modulo E: claves publicas ------------------------------------

    suspend fun publicarClaves(req: PublicarClavesReq): Unit =
        pedir(RUTA_CLAVES, "PUT", jsonApp.encodeToString(req), true)

    suspend fun estadoClaves(): EstadoClaves =
        pedir("$RUTA_CLAVES/estado", "GET", null, true)

    /**
     * Paquete de claves de un dispositivo.
     *
     * Ojo: el servidor CONSUME una prekey de un solo uso al responder. No se
     * llama para mirar; se llama cuando de verdad hay que abrir sesion.
     */
    suspend fun paqueteClaves(dispositivoId: String): PaqueteClaves =
        pedir("$RUTA_CLAVES/dispositivo/$dispositivoId", "GET", null, true)

    /** Dispositivos a los que hay que entregar copia en esta conversacion. */
    suspend fun destinosDe(convId: String): DestinosConversacion =
        pedir("$RUTA_CONVERSACIONES/$convId/destinos", "GET", null, true)

    // --- modulo D.6: GIFs (por nuestro intermediario) -----------------

    suspend fun buscarGifs(consulta: String, limite: Int = 24): BusquedaGifs {
        val q = java.net.URLEncoder.encode(consulta, "UTF-8")
        return pedir("$RUTA_GIFS/buscar?q=$q&limite=$limite", "GET", null, true)
    }

    /**
     * Bytes de un GIF, traidos por nuestro servidor.
     *
     * Van por aqui y no al CDN del proveedor para no filtrar la IP de quien
     * mira el selector. Ver protocol/Gifs.kt.
     */
    suspend fun bytesGif(id: String, previa: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val t = sesion.token ?: throw ApiError(401, "No hay sesion activa.")
        val req = Request.Builder()
            .url(BuildConfig.SERVIDOR + RUTA_GIFS + "/" + id + "/bytes?previa=" + previa)
            .header("Authorization", "Bearer $t")
            .get()
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw ApiError(0, "No se pudo traer el GIF.")
        }
        resp.use {
            if (!it.isSuccessful) throw ApiError(it.code, "No se pudo traer el GIF.")
            it.body.bytes()
        }
    }

    companion object {
        /**
         * URL de la foto de un usuario.
         *
         * Lleva la version como parametro: al cambiar la foto cambia la URL, asi
         * que el cache de Coil sirve la nueva sin invalidar nada a mano.
         * Devuelve null cuando la persona no tiene foto, para que la UI dibuje
         * las iniciales en vez de pedir un 404.
         */
        fun urlImagen(username: String, campo: String, version: Long): String? =
            if (version <= 0L) null
            else "${BuildConfig.SERVIDOR}$RUTA_USUARIO/$username/$campo?v=$version"
    }
}
