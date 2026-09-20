package com.wtfuck.app.datos

import android.net.Uri
import android.util.Log
import com.wtfuck.app.contenido.nombreDeArchivoSeguro
import com.wtfuck.app.contenido.TOPE_CITA_HISTORIA
import com.wtfuck.app.contenido.recortado
import com.wtfuck.app.contenido.segura
import com.wtfuck.protocol.FORMA_GIF_ID
import com.wtfuck.protocol.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Estado del cifrado de una conversacion, para la pantalla de verificacion.
 *
 * `digitos` vacio significa que todavia no hay sesion criptografica: la sesion
 * se abre al enviar el primer mensaje, asi que antes de eso no hay nada que
 * comparar y la pantalla tiene que decirlo en vez de mostrar ceros.
 */
/**
 * La huella de UN aparato.
 *
 * Es por aparato y no por persona, y esa es la correccion que trajo el modulo
 * J. La huella de Signal se calcula entre dos **claves de identidad**, y cada
 * dispositivo tiene la suya: quien tiene telefono y tablet tiene dos huellas
 * distintas, las dos legitimas. Verificar una y declarar "conversacion
 * verificada" era una respuesta tranquilizadora y falsa - el otro aparato
 * seguia sin comprobar.
 */
data class InfoCifrado(
    val username: String,
    val dispositivoId: String,
    /** Como se llama el aparato. Sin esto habria que verificar un UUID. */
    val etiqueta: String,
    val digitos: String,
    val escaneable: String,
    val verificada: Boolean,
    val cambio: Boolean,
)

/**
 * Todo lo que hay que verificar en una conversacion.
 *
 * `aparatos` puede tener mas de uno, y la pantalla no lo esconde: decir
 * "verificado" cuando falta un aparato por comprobar es exactamente el error
 * que esta pantalla existe para evitar.
 */
data class CifradoDeConversacion(
    val username: String,
    val aparatos: List<InfoCifrado> = emptyList(),
) {
    val verificados get() = aparatos.count { it.verificada }
    val todoVerificado get() = aparatos.isNotEmpty() && verificados == aparatos.size
    val hayCambio get() = aparatos.any { it.cambio }

    /**
     * Los aparatos agrupados por persona, para los grupos.
     *
     * En una directa hay una sola persona y esto sobra; en un grupo es la
     * unica forma de que la pantalla tenga sentido, porque verificar es un
     * acto por PERSONA -se comparan numeros con alguien- aunque el dato sea
     * por aparato.
     */
    val porPersona: Map<String, List<InfoCifrado>>
        get() = aparatos.groupBy { it.username }

    val esGrupo get() = porPersona.size > 1
}

/**
 * El orquestador.
 *
 * Nota de diseno: `enviarTexto` NO manda nada por la red. Guarda el mensaje en
 * la base local y despierta al despachador. Esa separacion es lo que hace que
 * `msg off` funcione: si no hay transporte, el mensaje simplemente se queda
 * PENDIENTE y se envia solo cuando alguno aparece.
 */
/**
 * Cuantos mensajes de contexto se entregan al denunciar.
 *
 * Seis y no la conversacion entera: mas alla de eso deja de ser una prueba y
 * se vuelve una filtracion, y un moderador con cien mensajes delante no lee
 * ninguno. El servidor recorta igual, por si el cliente miente.
 */
private const val VENTANA_EVIDENCIA = 6

class Repositorio(
    /**
     * Hace falta desde el modulo K: WebRTC necesita contexto para el audio y
     * la camara. Es la primera dependencia de Android que entra aqui, y entra
     * porque una llamada tiene que sobrevivir a que se cierre la pantalla.
     */
    private val contexto: android.content.Context,
    private val api: ApiCliente,
    private val dao: ChatDao,
    private val socket: Socket,
    private val sesion: Sesion,
    private val cifrador: Cifrador,
    private val archivos: ArchivosLocales,
    private val ajustes: Ajustes,
    private val ambito: CoroutineScope,
) {

    private val TAG = "Repo"

    /**
     * Cuanto silencio cuenta como "ya llego todo".
     *
     * Un segundo y medio: el buzon reentrega en rafaga, asi que si en ese
     * tiempo no llego nada mas, no queda nada. Mas corto cortaria una rafaga a
     * medias; mas largo gastaria la ventana del servicio de push esperando.
     */
    private val QUIETUD_MS = 1_500L

    /**
     * Cuanto vale la lista de destinos antes de volver a pedirla.
     *
     * Corto a proposito: si alguien sale del grupo, seguir cifrando para su
     * dispositivo es entregarle mensajes que ya no le corresponden.
     */
    private val VIGENCIA_DESTINOS = 30_000L

    /** Transportes por prioridad. La malla de la fase 7 se suma a esta lista. */
    private val transportes: List<Transporte> = listOf(
        TransporteWebSocket(socket),
        TransporteMalla(),
    ).sortedBy { it.prioridad }

    val conversaciones: Flow<List<ChatFila>> = dao.conversaciones(archivados = false)
    val archivadas: Flow<List<ChatFila>> = dao.conversaciones(archivados = true)
    val cuantosArchivados: Flow<Int> = dao.cuantosArchivados()
    val estadoConexion = socket.estado
    val tamanoCola: Flow<Int> = dao.tamanoCola()
    val tamanoFallidos: Flow<Int> = dao.tamanoFallidos()

    /** Mi propio perfil, para pintarlo en la cabecera y en la pantalla de perfil. */
    private val _miPerfil = MutableStateFlow<UsuarioPublico?>(null)
    val miPerfil = _miPerfil.asStateFlow()

    private val _privacidad = MutableStateFlow(Privacidad())
    val privacidad = _privacidad.asStateFlow()

    /** Avisos recien llegados, para que la UI dispare la notificacion. */
    private val _avisos = MutableSharedFlow<Bajada.Evento>(extraBufferCapacity = 16)
    val avisos = _avisos.asSharedFlow()

    /**
     * L.6 · Lo que merece una notificacion del sistema.
     *
     * Va como flujo y no como una llamada directa a `Notificaciones` porque
     * notificar es de la capa de Android: el Repositorio decide QUE pasa y la
     * Activity decide COMO se muestra. Asi el Repositorio se sigue pudiendo
     * probar sin framework, que es la razon por la que los avisos de grupo ya
     * estaban asi.
     *
     * Aqui NO va el texto del mensaje. Va cifrado, y una notificacion con el
     * contenido dentro anula el cifrado justo en la pantalla de bloqueo.
     */
    data class Notificable(
        val tipo: String,
        val conversacionId: String,
        val titulo: String,
        val autor: String = "",
        val esGrupo: Boolean = false,
        val conVideo: Boolean = false,
    )

    private val _notificables = MutableSharedFlow<Notificable>(extraBufferCapacity = 16)
    val notificables = _notificables.asSharedFlow()

    /**
     * L.1 · Quien esta escribiendo, por conversacion.
     *
     * Vive SOLO en memoria: es una senal de tres segundos y guardarla seria
     * acumular filas que caducan antes de que nadie las lea.
     *
     * El valor es el instante en que llego el ultimo aviso, no un booleano:
     * asi la pantalla puede apagarlo sola pasados unos segundos. No hay aviso
     * de "dejo de escribir" a proposito -se puede perder y dejaria el
     * indicador encendido para siempre-, y el peor caso de este diseno es que
     * se apague tarde.
     */
    private val _escribiendo = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    val escribiendo: StateFlow<Map<String, Pair<String, Long>>> = _escribiendo.asStateFlow()

    /**
     * Manda "estoy escribiendo", con freno.
     *
     * Como maximo uno cada cuatro segundos por conversacion: sin freno, cada
     * tecla seria un mensaje por el socket. El servidor ademas lo limita, y
     * pasarse de ahi solo conseguiria un 429.
     *
     * Si el ajuste esta apagado, no se manda NADA. La comprobacion vive aqui
     * -y no en el servidor- porque es la propia informacion de quien escribe:
     * lo unico que se consigue saltandose la regla es dar un dato propio.
     */
    private val ultimoAvisoEscritura = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun avisarQueEscribo(convId: String) {
        if (_privacidad.value.escribiendo.not()) return
        val ahora = System.currentTimeMillis()
        val previo = ultimoAvisoEscritura[convId] ?: 0L
        if (ahora - previo < 4_000) return
        ultimoAvisoEscritura[convId] = ahora
        socket.enviar(Subida.Escribiendo(convId))
    }


    /**
     * Decide si un mensaje entrante merece aviso, y con que datos.
     *
     * Las tres razones para callarse, en orden: el chat esta abierto en
     * pantalla -notificar lo que la persona esta leyendo es ruido-, la
     * conversacion esta silenciada -para eso la silencio- o no existe
     * localmente todavia.
     */
    private suspend fun avisarMensaje(convId: String, autor: String) {
        if (chatAbierto == convId) return
        val conv = dao.conversacion(convId) ?: return
        val silenciado = conv.silenciadoHasta == -1L ||
            conv.silenciadoHasta > System.currentTimeMillis()
        if (silenciado) return
        _notificables.tryEmit(
            Notificable(
                tipo = if (conv.tipo == "canal") "canal" else "mensaje",
                conversacionId = convId,
                titulo = conv.nombreMostrado.ifBlank { conv.nombre },
                autor = autor,
                esGrupo = conv.tipo == "grupo",
            )
        )
    }

    /** Motivos por los que el servidor rechazo un envio, para mostrarlos. */
    /** Destinos por conversacion: (cuando se pidio, lista). */
    private val cacheDestinos = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<DestinoDispositivo>>>()

    /** Los colectores del socket. Uno solo, aunque `iniciar()` se llame mil veces. */
    private var colectores: Job? = null
    /** Vigila que la sesion siga viva. Ver `Sesion.viva`. */
    private var vigilante: Job? = null

    private val _rechazos = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val rechazos = _rechazos.asSharedFlow()

    /**
     * Chat abierto ahora mismo. Lo que llega a esta conversacion no suma no
     * leidos: ya lo esta viendo la persona.
     */
    @Volatile private var chatAbierto: String? = null

    fun abrirChat(id: String) {
        chatAbierto = id
        ambito.launch {
            dao.marcarLeida(id)
            // L.1. Abrir el chat ES leerlo: es el unico momento en que se
            // puede afirmar que alguien vio los mensajes, y por eso el acuse
            // de lectura se manda aqui y no al recibirlos.
            runCatching { sincronizarLecturas(id) }
        }
    }

    fun cerrarChat() { chatAbierto = null }

    /**
     * L.1 · Avisa al remitente de que sus mensajes se leyeron.
     *
     * Se llama al abrir el chat y cuando llega algo con el chat abierto. Manda
     * SOLO los ajenos que no estuvieran ya marcados: el acuse de lectura no es
     * idempotente gratis -cada uno es un mensaje por el socket- y reenviar la
     * conversacion entera cada vez que se abre seria ruido constante.
     *
     * Y de paso pregunta cuales de los MIOS ya se leyeron: el aviso viaja por
     * el socket y se pierde si no estaba conectado, asi que sin esta consulta
     * un mensaje se queda en "entregado" para siempre.
     */
    suspend fun sincronizarLecturas(convId: String) {
        val ajenos = dao.noLeidosAjenos(convId)
        if (ajenos.isNotEmpty()) {
            socket.enviar(Subida.AcuseLectura(convId, ajenos))
            dao.marcarLeidosLocal(ajenos)
        }
        runCatching { api.mensajesLeidos(convId).mensajeIds }
            .getOrNull()
            ?.forEach { dao.estado(it, EstadoEnvio.LEIDO.name) }
    }

    fun mensajes(convId: String): Flow<List<MensajeEnt>> = dao.mensajes(convId)
    fun fijados(convId: String): Flow<List<MensajeEnt>> = dao.fijados(convId)

    /** Borra los temporales vencidos. Se llama al abrir la app y cada chat. */
    suspend fun limpiarVencidos() = dao.borrarVencidos(System.currentTimeMillis())

    // ============================================================
    //  Ciclo de vida
    // ============================================================

    /**
     * Arranca el transporte. Idempotente.
     *
     * `iniciar()` se llama desde `WtfuckApp.onCreate` Y desde
     * `MainActivity.onStart`, o sea en CADA vuelta al primer plano. Antes cada
     * llamada lanzaba otro colector sobre el mismo `SharedFlow`, asi que un
     * mensaje entrante se procesaba tantas veces como veces se hubiera abierto
     * la app. Se veia como el contador de no leidos subiendo de dos en dos, y
     * en los registros como fallos de descifrado "old counter": el duplicado
     * intentaba abrir un sobre cuyo ratchet ya habia avanzado.
     *
     * Reconectar en cada vuelta al primer plano SI es deseable; lo que no se
     * puede repetir es el cableado de los colectores.
     */
    fun iniciar() {
        val token = sesion.token ?: return
        socket.alRechazarToken = { sesion.invalidar(it) }
        socket.conectar(token)

        // El vigilante vive FUERA de `colectores` a proposito: si estuviera
        // dentro, detener() se cancelaria a si mismo a media ejecucion.
        if (vigilante?.isActive != true) {
            vigilante = ambito.launch {
                sesion.viva.collect { viva -> if (!viva) detener() }
            }
        }

        if (colectores?.isActive == true) return

        colectores = ambito.launch {
            launch { rescatarSubidas() }
            // Publicar o reponer claves publicas antes de que haga falta cifrar.
            launch {
                runCatching { cifrador.prepararClaves() }
                    .onFailure { Log.w(TAG, "No se pudieron preparar las claves: ${it.message}") }
            }
            // Si la app murio en medio de una llamada -y morir en medio de
            // una llamada es facil: no hay servicio en primer plano-, el
            // servidor la sigue creyendo viva y deja a esta persona OCUPADA
            // para siempre: no puede llamar ni recibir. `recuperar` la cierra
            // al arrancar. La funcion existia desde el modulo K y no la
            // llamaba nadie, que es la unica forma de que un arreglo no
            // arregle nada.
            launch { runCatching { llamadas.recuperar() } }
            launch { socket.entrantes.collect { manejar(it) } }
            // Cada reconexion vacia la cola. Es el corazon del comportamiento offline.
            launch {
                socket.conectado.collect {
                    cargarMiPerfil()
                    sincronizar()
                    despachar()
                }
            }
        }
    }

    fun detener() {
        colectores?.cancel()
        colectores = null
        socket.desconectar()
    }

    /**
     * Modulo N. Espera a que el socket termine de traer lo pendiente.
     *
     * La usa `ServicioPush`, que corre en una ventana de unos diez segundos y
     * tiene que decidir cuando soltar el proceso. "Quietud" es: ya se conecto y
     * paso [QUIETUD_MS] sin que llegara nada nuevo. No se usa "la cola esta
     * vacia" porque la cola es de SALIDA y aqui lo que interesa es la entrada.
     *
     * Vuelve sola si el socket no conecta: sin red no hay nada que esperar, y
     * bloquear el servicio hasta el timeout no mejora nada.
     */
    suspend fun esperarQuietud(quietudMs: Long = QUIETUD_MS) {
        val hasta = System.currentTimeMillis() + 20_000
        while (socket.estado.value != EstadoConexion.CONECTADO) {
            if (System.currentTimeMillis() > hasta) return
            kotlinx.coroutines.delay(150)
        }
        var ultimo = ultimaEntrega
        while (true) {
            kotlinx.coroutines.delay(quietudMs)
            if (ultimaEntrega == ultimo) return
            ultimo = ultimaEntrega
            if (System.currentTimeMillis() > hasta) return
        }
    }

    /** Cuando llego el ultimo sobre. Solo lo lee [esperarQuietud]. */
    @Volatile private var ultimaEntrega = 0L

    suspend fun sincronizar() {
        runCatching { api.conversaciones() }
            .onSuccess { lista ->
                lista.forEach { guardarResumen(it) }

                // Lo que el servidor YA NO devuelve es lo que dejo de ser mio:
                // me expulsaron, sali, o el grupo se elimino. Antes solo se
                // insertaba, asi que un grupo del que me habian echado seguia
                // apareciendo como si nada.
                val vigentes = lista.map { it.id }.toSet()
                dao.idsLocales().filter { it !in vigentes }.forEach { dao.marcarFuera(it) }
            }
            .onFailure { Log.w(TAG, "No se pudo sincronizar: ${it.message}") }
    }

    private suspend fun guardarResumen(r: ConversacionResumen) {
        val previa = dao.conversacion(r.id)
        // En una directa la foto es la del otro; en un grupo aun no hay foto de
        // grupo, asi que se cae a las iniciales.
        val otro = if (r.tipo == "directa") r.participantes.firstOrNull() else null
        dao.guardarConversacion(
            ConversacionEnt(
                id = r.id,
                tipo = r.tipo,
                nombre = r.nombre,
                nombreMostrado = otro?.nombreMostrado.orEmpty(),
                participantes = r.participantes.joinToString(",") { it.username },
                avatarUsername = otro?.username.orEmpty(),
                avatarVersion = otro?.avatarVersion ?: 0L,
                noLeidos = previa?.noLeidos ?: 0,
                // Si el servidor lo devuelve, sigo dentro.
                soyMiembro = true,
                miRol = r.miRol,
                miJerarquia = r.miJerarquia,
                silenciadoHasta = r.silenciadoHasta ?: 0L,
                archivado = r.archivado,
                fijado = r.fijado,
            )
        )
    }

    // ============================================================
    //  Perfil
    // ============================================================

    suspend fun cargarMiPerfil() {
        runCatching { api.miPerfil() }.onSuccess { _miPerfil.value = it }
        runCatching { api.privacidad() }.onSuccess { _privacidad.value = it }
    }

    suspend fun excepcionesPrivacidad(): List<ExcepcionesPrivacidad> =
        runCatching { api.excepcionesPrivacidad().ajustes }.getOrElse { emptyList() }

    suspend fun guardarExcepciones(e: ExcepcionesPrivacidad): Result<List<ExcepcionesPrivacidad>> =
        runCatching { api.guardarExcepciones(e).ajustes }

    suspend fun guardarPrivacidad(p: Privacidad) {
        _privacidad.value = api.guardarPrivacidad(p)
    }

    suspend fun guardarPerfil(nombre: String, estado: String) {
        _miPerfil.value = api.guardarPerfil(nombre, estado)
    }

    suspend fun subirImagen(campo: String, bytes: ByteArray) {
        _miPerfil.value = api.subirImagen(campo, bytes)
    }

    // ============================================================
    //  Entrada
    // ============================================================

    private suspend fun manejar(msg: Bajada) {
        when (msg) {
            is Bajada.Entrega -> {
                ultimaEntrega = System.currentTimeMillis()
                val origen = OrigenSobre(
                    usuarioId = msg.origenUsuarioId,
                    username = msg.origenUsername,
                    dispositivoId = msg.origenDispositivo,
                    tipo = msg.tipo,
                )
                val carga = runCatching {
                    cifrador.descifrar(msg.conversacionId, origen, Base64Util.dec(msg.cuerpo))
                }.onFailure {
                    // Un cuerpo que no se puede abrir NO se acusa: acusar lo
                    // borraria del servidor y se perderia para siempre. Se deja
                    // pendiente para reintentar cuando haya sesion.
                    Log.w(TAG, "No se pudo descifrar ${msg.sobreId}: ${it.message}")
                }.getOrNull()

                // Señalizacion de llamada. No es un mensaje: no se guarda, no
                // se muestra en el chat y no cuenta como no leido. Va al
                // servicio de llamadas y se sale.
                //
                // Que llegue por aqui -y no por una ruta del servidor- es el
                // punto del modulo K: el SDP lleva las huellas DTLS, y un
                // servidor que pudiera cambiarlas podria escuchar la llamada.
                when (carga) {
                    is Carga.LlamadaOferta -> {
                        llamadas.ofertaEntrante(
                            msg.conversacionId, msg.origenUsername, msg.origenDispositivo, carga,
                        )
                        // Sin servicio en primer plano, la llamada solo suena
                        // si la app esta en pantalla. La notificacion es lo
                        // unico que la hace visible con la app de fondo.
                        _notificables.tryEmit(
                            Notificable(
                                tipo = "llamada",
                                conversacionId = msg.conversacionId,
                                titulo = msg.origenUsername,
                                autor = msg.origenUsername,
                                conVideo = carga.conVideo,
                            )
                        )
                        socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                        return
                    }
                    is Carga.LlamadaRespuesta -> {
                        llamadas.respuestaEntrante(msg.origenDispositivo, carga)
                        socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                        return
                    }
                    is Carga.LlamadaCandidato -> {
                        llamadas.candidatoEntrante(msg.origenDispositivo, carga)
                        socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                        return
                    }
                    is Carga.LlamadaFin -> {
                        llamadas.finEntrante(carga)
                        socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                        return
                    }
                    else -> Unit
                }

                // Una historia tampoco es un mensaje: no va a ningun chat,
                // no cuenta como no leido y no notifica como tal. Se guarda en
                // su tabla y se sale.
                //
                // El contenido puede llegar ANTES que el metadato -o al reves-,
                // porque van por caminos distintos: esto por el buzon cifrado,
                // aquello por HTTP. Por eso se guarda lo que haya y se completa
                // despues, en vez de descartar lo que llega huerfano.
                if (carga is Carga.Historia) {
                    guardarContenidoHistoria(carga, msg.origenUsername)
                    socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                    return
                }

                // Un lote de historial no es un mensaje: son muchos, viejos, y
                // con sus propios autores y fechas. Se guardan y se sale.
                if (carga is Carga.Historial) {
                    guardarHistorial(carga)
                    socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                    return
                }

                // Un voto tampoco es un mensaje: no se muestra, no notifica y
                // no cuenta como no leido. Se guarda en su tabla y se sale.
                //
                // Se guarda AUNQUE no tengamos la encuesta: en un grupo el voto
                // de alguien puede llegar antes que la encuesta si el sobre de
                // la encuesta todavia no se pudo descifrar. La tabla esta
                // indexada por el id de la consulta, asi que el voto espera
                // ahi y se cuenta cuando la encuesta aparece.
                if (carga is Carga.Voto) {
                    dao.guardarVoto(
                        VotoEnt(
                            consultaId = carga.consultaId,
                            votante = msg.origenUsername,
                            opciones = carga.opciones.joinToString(","),
                            creadoEn = msg.creadoEn,
                        )
                    )
                    socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                    return
                }

                // Una edicion no crea un mensaje nuevo: modifica uno que ya esta.
                if (carga is Carga.Edicion) {
                    dao.marcarEditado(carga.mensajeId, carga.textoNuevo)
                    socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                    return
                }

                val texto = when (carga) {
                    is Carga.Texto -> carga.cuerpo
                    is Carga.EventoGrupo -> textoDeEvento(carga)
                    is CargaAdjunto -> carga.pie
                    // El resumen se calcula aqui, al recibir, y se guarda en
                    // `texto`. Asi la lista de chats y el buscador leen una
                    // columna de texto como con cualquier mensaje, en vez de
                    // deserializar la carga de cada fila para armar un titulo.
                    is Carga.Ubicacion, is Carga.Contacto,
                    is Carga.Encuesta, is Carga.Evento -> resumenDe(carga)
                    // No deberia verse nunca: el cifrador desenvuelve la clave
                    // de emisor y devuelve el interior. Si aparece, es que algo
                    // llego sin pasar por ahi, y mejor que se note.
                    is Carga.ConClaveGrupo -> "(clave de grupo sin procesar)"
                    null -> "(no se pudo descifrar)"
                    else -> "(mensaje no soportado)"
                }

                if (dao.conversacion(msg.conversacionId) == null) sincronizar()

                val cita = carga as? Carga.Texto
                val adj = carga as? CargaAdjunto

                // La cita de una historia **la escribio la otra persona** y el
                // servidor no la vio: nada impide inventarla. Se acepta solo si
                // esa historia existe en este telefono y es MIA, que es el unico
                // caso en que alguien puede estar contestandomela.
                //
                // Si no cuadra, se descarta la cita y el mensaje se guarda igual:
                // el texto es de quien escribe y no hay motivo para perderlo.
                val historiaCitada = cita?.historia?.let { h ->
                    dao.historia(h.historiaId)?.takeIf { it.mia }?.let { h }
                }
                val filas = dao.guardarMensaje(
                    MensajeEnt(
                        // El id del MENSAJE, no el de la fila del buzon. Son
                        // dos cosas: ver `Bajada.Entrega.mensajeId`. Guardar el
                        // del buzon hacia que en un grupo el mismo mensaje
                        // tuviera un id distinto en cada telefono.
                        id = msg.mensajeId.ifBlank { msg.sobreId },
                        conversacionId = msg.conversacionId,
                        autor = msg.origenUsername,
                        esMio = false,
                        texto = texto,
                        creadoEn = msg.creadoEn,
                        estado = EstadoEnvio.ENTREGADO.name,
                        respondeA = cita?.respondeA,
                        respondeTexto = cita?.respondeTexto,
                        respondeAutor = cita?.respondeAutor,
                        reenviadoDe = cita?.reenviadoDe,
                        citaHistoriaId = historiaCitada?.historiaId.orEmpty(),
                        citaHistoriaClase = historiaCitada?.clase.orEmpty(),
                        // Recortado con las mismas reglas que el resto del
                        // contenido ajeno. Ver `ContenidoSeguro`.
                        citaHistoriaTexto = recortado(
                            historiaCitada?.previa.orEmpty(), TOPE_CITA_HISTORIA,
                        ).first,
                        citaHistoriaMiniatura = historiaCitada?.miniatura.orEmpty(),
                        // El archivo NO llega con el mensaje: llega su
                        // referencia, su llave y una miniatura. Lo pesado se
                        // baja despues, y solo si hace falta.
                        adjuntoId = adj?.adjuntoId,
                        adjuntoClase = adj?.clase.orEmpty(),
                        adjuntoMime = adj?.mime.orEmpty(),
                        // Saneado aqui y no al dibujar: tiene cuatro
                        // consumidores y uno se olvidaria. Ver
                        // `nombreDeArchivoSeguro`.
                        adjuntoNombre = nombreDeArchivoSeguro(adj?.nombre.orEmpty()),
                        adjuntoBytes = adj?.bytes ?: 0,
                        adjuntoAncho = adj?.ancho ?: 0,
                        adjuntoAlto = adj?.alto ?: 0,
                        adjuntoDuracionMs = adj?.duracionMs ?: 0,
                        adjuntoClave = adj?.clave.orEmpty(),
                        adjuntoNonce = adj?.nonce.orEmpty(),
                        adjuntoMiniatura = adj?.miniatura.orEmpty(),
                        adjuntoEstado = if (adj != null) "ESPERA" else "",
                        especial = claseDe(carga),
                        // Se guarda la carga tal como vino, sin desarmarla en
                        // columnas: ver `MensajeEnt.especialJson`.
                        especialJson = if (claseDe(carga).isEmpty()) "" else {
                            jsonApp.encodeToString(Carga.serializer(), carga!!)
                        },
                    )
                )

                // Descarga automatica segun los ajustes: en WiFi una foto se
                // baja sola, un video de 40 MB con datos moviles no.
                if (filas != -1L && adj != null && ajustes.descargaSola(adj.clase, adj.bytes)) {
                    ambito.launch { descargarAdjunto(msg.mensajeId.ifBlank { msg.sobreId }) }
                }
                // Solo se cuenta como no leido si el INSERT de verdad inserto.
                //
                // Room devuelve -1 cuando `OnConflictStrategy.IGNORE` descarto
                // la fila porque el mensaje ya estaba. Eso pasa de verdad y no
                // por accidente: el buzon del servidor reentrega todo lo que no
                // se acuso, asi que un mensaje puede llegar dos veces por
                // diseno. Sumar sin comprobarlo hacia que el globo dijera 2
                // cuando habia UN mensaje.
                // Con el chat abierto el mensaje se esta leyendo AHORA: se
                // acusa en el acto en vez de esperar a que se reabra la
                // pantalla.
                if (filas != -1L && chatAbierto == msg.conversacionId) {
                    runCatching { sincronizarLecturas(msg.conversacionId) }
                }
                if (filas != -1L && chatAbierto != msg.conversacionId) {
                    dao.sumarNoLeido(msg.conversacionId)
                    // El mismo `filas != -1L` que evita contar dos veces evita
                    // notificar dos veces: el buzon reentrega lo no acusado, y
                    // sin esto un mensaje repetido sonaba de nuevo.
                    avisarMensaje(msg.conversacionId, msg.origenUsername)
                }

                // Acusar BORRA el sobre del servidor. Solo despues de guardarlo
                // localmente: si se acusa antes y la app muere, el mensaje se pierde.
                socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
            }

            is Bajada.Evento -> manejarEvento(msg)

            is Bajada.Aceptado -> {
                dao.estado(msg.sobreId, EstadoEnvio.ENVIADO.name)
                // El servidor tenia destinos que el cliente no cubrio: alguien
                // entro a la conversacion entre que se pidio la lista y se
                // envio. Se refresca el cache y se reintenta; el id derivado
                // por destino hace que las copias ya entregadas no se dupliquen.
                if (msg.sinCopia.isNotEmpty()) {
                    Log.i(TAG, "Faltaron ${msg.sinCopia.size} copias de ${msg.sobreId}")
                    dao.mensaje(msg.sobreId)?.let { m ->
                        destinosDe(m.conversacionId, forzar = true)
                        dao.devolverACola(m.id)
                        despachar()
                    }
                }
            }
            is Bajada.Entregado -> dao.estado(msg.sobreId, EstadoEnvio.ENTREGADO.name)

            // L.1. No se comprueba nada mas: el servidor ya aplico las dos
            // mitades de la reciprocidad -quien lee comparte, y quien escribio
            // tambien-, asi que si este aviso llego, corresponde mostrarlo.
            is Bajada.Leido -> msg.mensajeIds.forEach {
                dao.estado(it, EstadoEnvio.LEIDO.name)
            }

            // L.1. Quien tiene el ajuste apagado tampoco lo VE: es la otra
            // mitad de la reciprocidad, y se aplica aqui por lo mismo que el
            // envio -es una preferencia propia y no hace falta molestar al
            // servidor en cada rafaga de tecleo-.
            is Bajada.Escribiendo -> {
                if (_privacidad.value.escribiendo) {
                    _escribiendo.value = _escribiendo.value +
                        (msg.conversacionId to (msg.username to System.currentTimeMillis()))
                }
            }

            // L.1. Quien tiene el ajuste apagado tampoco lo VE: es la otra
            // mitad de la reciprocidad, y se aplica aqui por lo mismo que el
            // envio -es una preferencia propia y no hace falta molestar al
            // servidor en cada rafaga de tecleo-.
            // El servidor tambien puede rechazar por el socket; se guarda el
            // motivo igual que en el camino HTTP.
            is Bajada.ErrorMsg -> msg.sobreId?.let { dao.marcarFallido(it, msg.motivo) }
            is Bajada.Pong -> Unit
        }
    }

    /**
     * Un aviso del servidor se convierte en dos cosas: una linea de sistema
     * dentro del chat (queda el rastro) y un aviso para la UI, que decide si
     * dispara una notificacion del sistema operativo.
     */
    private suspend fun manejarEvento(e: Bajada.Evento) {
        // Avisos que no pertenecen a ninguna conversacion: moderacion y
        // dispositivos. Se atienden aparte y se sale, porque el resto de esta
        // funcion asume que hay un chat donde poner una linea.
        //
        // Ojo con el orden: esto va ANTES del `sincronizar()` de abajo. Con un
        // conversacionId vacio, `dao.conversacion("")` siempre da null y se
        // disparaba una sincronizacion completa por cada aviso.
        when (e.tipo) {
            "historial_pedido" -> {
                // Otro dispositivo mio acaba de vincularse y no tiene nada.
                // El detalle trae SU id: se le manda solo a el.
                e.detalle?.takeIf { it.isNotBlank() }?.let { responderHistorial(it) }
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "llamada_contestada" -> {
                // Conteste en otro de mis aparatos: este tiene que callarse.
                // Es el caso que solo existe con multi-dispositivo.
                e.detalle?.let { llamadas.contestadaEnOtroAparato(it) }
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "llamada_terminada" -> {
                // Red de seguridad: si el sobre cifrado con el "fin" no llego
                // -el otro telefono se apago, o el timbre se agoto por tiempo,
                // que lo decide el servidor- este aviso es lo unico que evita
                // una pantalla sonando para siempre.
                //
                // El detalle es un objeto, no un id: `{"llamada":..,"motivo":..}`.
                runCatching {
                    val o = jsonApp.parseToJsonElement(e.detalle.orEmpty()).jsonObject
                    llamadas.terminadaPorServidor(
                        o["llamada"]?.jsonPrimitive?.content.orEmpty(),
                        o["motivo"]?.jsonPrimitive?.content ?: FinLlamada.COLGADA,
                    )
                }
                _avisos.tryEmit(e)
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "llamada_entrante",
            // F.7: la decision del dueno sobre un canal que cree. Se
            // sincroniza porque un canal aprobado cambia de estado y la
            // pantalla tiene que reflejarlo sin que nadie recargue nada.
            "canal_aprobado", "canal_rechazado",
            "dispositivo_vinculado", "dispositivo_revocado", "advertencia", "sancion" -> {
                _avisos.tryEmit(e)
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
        }

        // El grupo puede no existir localmente todavia: primero se trae.
        if (dao.conversacion(e.conversacionId) == null) sincronizar()

        // Acciones del modulo C: no son una linea en el chat, modifican un
        // mensaje que ya esta. Se aplican y se sale.
        when (e.tipo) {
            "mensaje_retirado" -> {
                dao.marcarRetirado(e.detalle.orEmpty())
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "mensaje_editado" -> {
                // El texto nuevo llega por sobre aparte; aqui solo la marca.
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "mensaje_fijado" -> {
                val (id, fijado) = (e.detalle.orEmpty().split(":", limit = 2) + "")
                    .let { it[0] to (it.getOrNull(1) == "true") }
                dao.marcarFijado(id, fijado)
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            "mensaje_reaccion" -> {
                refrescarMensaje(e.detalle.orEmpty().substringBefore(':'))
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
            // Me sacaron: se marca de inmediato, sin esperar la proxima
            // sincronizacion. La linea de sistema se agrega mas abajo.
            "expulsado", "sacado_grupo" -> dao.marcarFuera(e.conversacionId)
            // Publicacion nueva en un canal: NO se guarda como mensaje local.
            // El contenido vive en el servidor y la pantalla del canal lo lee
            // de ahi; meterlo tambien en la base local seria tener dos copias
            // que se pueden desincronizar. Solo se avisa a la UI.
            "canal_publicacion" -> {
                // L.6: un canal que publica mientras no estoy mirando
                // tambien avisa, en su propia categoria y con importancia
                // baja: publica a mucha gente y no espera respuesta.
                avisarMensaje(e.conversacionId, e.actor)
                _avisos.tryEmit(e)
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
        }

        val texto = when (e.tipo) {
            "agregado_grupo" -> "@${e.actor} te agrego a este grupo"
            "expulsado" -> "@${e.actor} te saco de este grupo"
            "sacado_grupo" -> "@${e.actor} te saco del grupo"
            "grupo_renombrado" -> "@${e.actor} cambio el nombre a ${e.detalle.orEmpty()}"
            // H.3. Va como mensaje DENTRO del chat y no como una notificacion
            // que se va: es la explicacion de por que a partir de aqui no se
            // puede escribir, y tiene que seguir ahi cuando alguien abra el
            // chat tres dias despues y no entienda que pasa.
            "conversacion_cerrada" ->
                "La plataforma cerro esta conversacion" +
                    // El motivo lo escribio una persona y puede venir con
                    // punto o sin el: se normaliza para no terminar con
                    // "...spam Se puede leer".
                    (e.detalle?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { ": " + it.trimEnd('.') + "." } ?: ".") +
                    " Se puede leer, no escribir."
            "conversacion_reabierta" -> "La plataforma reabrio esta conversacion."
            else -> "Cambio en el grupo"
        }

        dao.guardarMensaje(
            MensajeEnt(
                id = e.eventoId,
                conversacionId = e.conversacionId,
                autor = e.actor,
                esMio = false,
                texto = texto,
                creadoEn = e.creadoEn,
                estado = EstadoEnvio.ENTREGADO.name,
                esSistema = true,
            )
        )
        if (chatAbierto != e.conversacionId) dao.sumarNoLeido(e.conversacionId)

        _avisos.tryEmit(e)

        // Acusar borra el aviso del servidor. Como con los mensajes: solo
        // despues de guardarlo, nunca antes.
        socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
    }

    private fun textoDeEvento(e: Carga.EventoGrupo): String = when (e.accion) {
        "agrego" -> "Se agrego a @${e.valor.orEmpty()}"
        "salio" -> "@${e.valor.orEmpty()} salio del grupo"
        "renombro" -> "El grupo ahora se llama ${e.valor.orEmpty()}"
        else -> "Cambio en el grupo"
    }

    // ============================================================
    //  Salida
    // ============================================================

    suspend fun enviarTexto(
        convId: String,
        texto: String,
        respondeA: MensajeEnt? = null,
        reenviadoDe: String? = null,
        citaHistoria: HistoriaEnt? = null,
    ) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return

        val m = MensajeEnt(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            autor = sesion.username.orEmpty(),
            esMio = true,
            texto = limpio,
            creadoEn = System.currentTimeMillis(),
            estado = EstadoEnvio.PENDIENTE.name,
            respondeA = respondeA?.id,
            // La cita se copia: si el original se borra despues, el hilo sigue
            // teniendo sentido.
            respondeTexto = respondeA?.texto?.take(140),
            respondeAutor = respondeA?.autor,
            reenviadoDe = reenviadoDe,
            citaHistoriaId = citaHistoria?.id.orEmpty(),
            citaHistoriaClase = citaHistoria?.clase.orEmpty(),
            // La previa de una historia de foto es su pie; la de una de texto,
            // el texto. En los dos casos es lo que se dibujara en la burbuja.
            citaHistoriaTexto = citaHistoria?.texto.orEmpty().take(TOPE_CITA_HISTORIA),
            citaHistoriaMiniatura = citaHistoria?.miniatura.orEmpty(),
        )
        dao.guardarMensaje(m)
        despachar()
    }

    // ============================================================
    //  Modulo M: contenido con estructura
    // ============================================================

    /**
     * Encola una carga con estructura como un mensaje normal.
     *
     * Pasa por la MISMA cola que el texto, y eso es el punto: hereda el
     * reintento sin red, el orden de envio, el registro del metadato -donde el
     * servidor comprueba el permiso- y el estado en la burbuja. Un camino
     * aparte para las encuestas habria tenido que volver a resolver las cuatro
     * cosas, y peor.
     */
    private suspend fun encolarEspecial(
        convId: String,
        clase: String,
        carga: Carga,
        oculto: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.guardarMensaje(
            MensajeEnt(
                id = id,
                conversacionId = convId,
                autor = sesion.username.orEmpty(),
                esMio = true,
                texto = if (oculto) "" else resumenDe(carga),
                creadoEn = System.currentTimeMillis(),
                estado = EstadoEnvio.PENDIENTE.name,
                especial = clase,
                especialJson = jsonApp.encodeToString(Carga.serializer(), carga),
                oculto = oculto,
            )
        )
        despachar()
        return id
    }

    suspend fun enviarUbicacion(
        convId: String,
        lat: Double,
        lon: Double,
        precisionM: Int,
        etiqueta: String = "",
    ) = encolarEspecial(
        convId,
        ClaseContenido.UBICACION,
        Carga.Ubicacion(lat, lon, precisionM, etiqueta.trim().take(80)),
    )

    suspend fun enviarContacto(convId: String, username: String, nombre: String) =
        encolarEspecial(
            convId,
            ClaseContenido.CONTACTO,
            Carga.Contacto(username.lowercase().trim(), nombre.trim().take(48)),
        )

    /**
     * Crea una encuesta.
     *
     * Las opciones se limpian aqui y no en la pantalla: vacias y repetidas
     * romperian el recuento -dos opciones iguales dan dos barras que dicen lo
     * mismo- y el recuento lo hacen todos los clientes, no solo el que la creo.
     */
    suspend fun crearEncuesta(
        convId: String,
        pregunta: String,
        opciones: List<String>,
        multiple: Boolean,
        cierraEn: Long = 0,
    ): String {
        val limpias = opciones.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(limpias.size in 2..TopesConsulta.OPCIONES) {
            "Una encuesta necesita entre 2 y ${TopesConsulta.OPCIONES} opciones distintas."
        }
        require(pregunta.isNotBlank()) { "La encuesta necesita una pregunta." }
        return encolarEspecial(
            convId,
            ClaseContenido.ENCUESTA,
            Carga.Encuesta(pregunta.trim().take(200), limpias.map { it.take(100) }, multiple, cierraEn),
        )
    }

    suspend fun crearEvento(
        convId: String,
        titulo: String,
        cuandoMs: Long,
        lugar: String,
        nota: String,
    ): String {
        require(titulo.isNotBlank()) { "El evento necesita un titulo." }
        return encolarEspecial(
            convId,
            ClaseContenido.EVENTO,
            Carga.Evento(titulo.trim().take(120), cuandoMs, lugar.trim().take(120), nota.trim().take(280)),
        )
    }

    /**
     * Vota, o retira el voto con una lista vacia.
     *
     * El voto propio se guarda ANTES de mandarlo: la barra se mueve al instante
     * y sigue movida si no hay red, igual que una burbuja pendiente. El sobre
     * va por la cola, asi que el voto de un tunel de subte sale solo al salir.
     */
    suspend fun votar(convId: String, consultaId: String, opciones: List<Int>) {
        dao.guardarVoto(
            VotoEnt(
                consultaId = consultaId,
                votante = sesion.username.orEmpty(),
                opciones = opciones.joinToString(","),
                creadoEn = System.currentTimeMillis(),
            )
        )
        encolarEspecial(
            convId,
            ClaseContenido.VOTO,
            Carga.Voto(consultaId, opciones),
            oculto = true,
        )
    }

    fun votos(consultaId: String): Flow<List<VotoEnt>> = dao.votos(consultaId)

    /** M.3 · Buscar dentro de una conversacion. Ver `ChatDao.buscarEn`. */
    suspend fun buscarEnChat(convId: String, consulta: String): List<MensajeEnt> {
        val q = consulta.trim()
        if (q.length < 2) return emptyList()
        // `_` y `%` son comodines de LIKE: sin escaparlos, buscar "100%" o
        // "a_b" devolveria cualquier cosa.
        val escapado = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // No se deja propagar: buscar es una comodidad, y una consulta que
        // falle tiene que devolver "sin resultados", no cerrar la app. Paso
        // exactamente eso con un `ESCAPE` de dos caracteres.
        return runCatching { dao.buscarEn(convId, escapado) }
            .onFailure { Log.w(TAG, "Fallo la busqueda en $convId: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** La posicion de un mensaje en la lista, para saltar a el desde la busqueda. */
    suspend fun posicionDe(convId: String, m: MensajeEnt): Int =
        (dao.posicionDe(convId, m.creadoEn, m.id) - 1).coerceAtLeast(0)

    /** M.3 · Marcar un chat como no leido. Se apaga al abrirlo. */
    suspend fun marcarNoLeida(convId: String) = dao.marcarNoLeida(convId)

    /**
     * La clase que se le declara al servidor, derivada de la carga.
     *
     * Solo se declara lo que hace falta para autorizar: ubicacion y contacto no
     * piden permiso propio, asi que el servidor no tiene por que enterarse.
     * Ver `ClaseContenido`.
     */
    private fun claseDe(carga: Carga?): String = when (carga) {
        is Carga.Ubicacion -> ClaseContenido.UBICACION
        is Carga.Contacto -> ClaseContenido.CONTACTO
        is Carga.Encuesta -> ClaseContenido.ENCUESTA
        is Carga.Evento -> ClaseContenido.EVENTO
        is Carga.Voto -> ClaseContenido.VOTO
        else -> ClaseContenido.TEXTO
    }

    /**
     * Lo que cabe en una linea de la lista de chats.
     *
     * No es un limite de seguridad -ese ya lo puso `segura()`- sino de forma:
     * un resumen que no cabe en una linea no es un resumen.
     */
    private val LARGO_RESUMEN = 120

    /**
     * Una linea para la lista de chats, las notificaciones y el buscador.
     *
     * ## Por que aqui tambien hacen falta las reglas de contenido
     *
     * Este es el segundo camino por el que un campo de un sobre ajeno llega a
     * la pantalla, y es facil de pasar por alto porque no parece dibujo: es una
     * cadena que se guarda en la base. Pero termina en la lista de chats, en el
     * buscador, en lo que anuncia el lector de pantalla y **en el texto de una
     * notificacion**.
     *
     * Esa ultima es la peor de las cuatro. Un `"Contacto: @soporte ·
     * Administrador"` en una notificacion, fuera de la app y sin el resto de la
     * conversacion alrededor, es mas creible que la misma tarjeta dentro del
     * chat. Y una etiqueta de sesenta mil caracteres guardada en la columna
     * `texto` estorba en la lista, en el buscador y en la notificacion a la vez.
     *
     * Asi que pasa por las mismas funciones que las burbujas —un solo sitio
     * donde estan las reglas— y ademas se acota a una linea, que es lo que un
     * resumen es.
     *
     * ## Lo que NO se toca
     *
     * `Carga.Texto`. Ahi la cadena no es un resumen: **es el mensaje**, y se
     * guarda entera. Recortarlo seria perder lo que alguien escribio.
     */
    private fun resumenDe(carga: Carga): String = when (carga) {
        is Carga.Ubicacion -> segura(carga).etiqueta.ifBlank { "Ubicacion" }
        is Carga.Contacto -> segura(carga).let { c ->
            // Sin username valido no se pone `@`: el resumen no puede afirmar
            // una cuenta que la tarjeta misma se niega a afirmar.
            if (c.username != null) "Contacto: @${c.username}" else "Contacto: ${c.nombre}"
        }
        is Carga.Encuesta -> "Encuesta: ${segura(carga).pregunta}".take(LARGO_RESUMEN)
        is Carga.Evento -> "Evento: ${segura(carga).titulo}".take(LARGO_RESUMEN)
        is Carga.Texto -> carga.cuerpo
        else -> ""
    }

    // ============================================================
    //  Modulo P: tipo de cuenta
    // ============================================================

    /**
     * Lo que esta cuenta es y puede.
     *
     * No se cachea en la base local a proposito: el tipo lo puede cambiar staff
     * desde el panel, y una copia local convertiria "ya no sos desarrollador"
     * en algo que la app tarda en enterarse. Se pide cuando la pantalla lo
     * necesita, que es pocas veces.
     */
    suspend fun capacidadesDeCuenta(): CapacidadesCuenta =
        runCatching { api.capacidades() }.getOrElse { CapacidadesCuenta() }

    /**
     * Cambia el tipo de la cuenta propia.
     *
     * Devuelve `Result` porque el servidor puede decir que no por tres motivos
     * distintos —fuera de la beta, tipo no permitido, ya sos desarrollador— y
     * cada uno tiene su mensaje. Tragarselos todos en un `false` dejaria a la
     * pantalla sin nada que decir.
     */
    suspend fun elegirTipoCuenta(tipo: String): Result<CapacidadesCuenta> =
        runCatching { api.elegirTipoCuenta(tipo) }

    suspend fun guardarFichaEmpresa(req: FichaEmpresaReq): Result<FichaEmpresa> =
        runCatching { api.guardarFichaEmpresa(req) }

    // ============================================================
    //  Modulo O: historias
    // ============================================================

    /**
     * Las historias vivas, para la fila de arriba y el visor.
     *
     * Sale de la base local y no del servidor: lo que se puede DIBUJAR es lo
     * que este aparato pudo descifrar, y eso solo lo sabe la base.
     */
    fun historias(): Flow<List<HistoriaEnt>> =
        dao.historiasVivas(System.currentTimeMillis())

    /**
     * Publica una historia de texto.
     *
     * ## El orden de los tres pasos, y por que es ese
     *
     *   1. Se piden los destinos.
     *   2. Se cifra y se mandan los sobres.
     *   3. Se registra el metadato.
     *
     * Al reves —registrar primero— una historia podria existir en la lista de
     * alguien sin que le haya llegado el sobre: la veria anunciada y no podria
     * abrirla. Asi, si algo falla en el camino, lo que queda es una historia
     * que nadie ve, que es el fallo barato.
     *
     * El id lo genera este cliente para que los tres pasos hablen de la misma
     * historia y para que un reintento no publique dos veces.
     */
    suspend fun publicarHistoria(
        texto: String,
        fondo: String = "",
        medio: Uri? = null,
    ): Result<Unit> = runCatching {
        val id = UUID.randomUUID().toString()
        val destinos = api.destinosHistoria().destinos

        // Lo que se va a publicar. Con archivo, la clase sale de lo que ES el
        // archivo y no de lo que diga quien lo eligio.
        val preparado = medio?.let { prepararMedioDeHistoria(id, it) }
        val clase = preparado?.clase ?: ClaseHistoria.TEXTO

        // El metadato se registra ANTES de los sobres porque el servidor exige
        // que la historia exista para aceptarlos -es lo que le permite
        // comprobar que cada destino esta en la audiencia-. Lo que se evita es
        // lo otro: que el sobre llegue sin que haya historia a la que pegarlo.
        //
        // Con archivo hay una razon mas: el servidor solo deja reservar un
        // adjunto contra una historia **que ya existe y es tuya**, que es
        // exactamente la comprobacion que impide colgarle archivos a la
        // historia de otro.
        val mia = api.publicarHistoria(PublicarHistoriaReq(historiaId = id, clase = clase))

        // Subir puede fallar, y entonces quedaria una historia anunciada que
        // nadie puede abrir. Se retira: mejor que no exista a que exista rota.
        val adjunto = if (preparado == null) null else {
            try {
                subirArchivoDeHistoria(id, preparado)
            } catch (e: Exception) {
                runCatching { api.retirarHistoria(id) }
                preparado.local.delete()
                throw e
            }
        }

        val carga = Carga.Historia(
            historiaId = id,
            clase = clase,
            texto = texto,
            fondo = fondo,
            adjunto = adjunto,
        )

        if (destinos.isNotEmpty()) {
            val copias = cifrador.cifrar(
                conversacionId = id, esGrupo = false, destinos = destinos, carga = carga,
            )
            val faltan = api.sobresHistoria(id, SobresHistoriaReq(copias))
            // Los que quedaron sin copia NO se inventan: si alguno falta es
            // porque no se le pudo abrir sesion, y lo honesto es que su
            // historia no exista para el en vez de que exista y no abra.
            if (faltan.sinCopia.isNotEmpty()) {
                Log.w(TAG, "Historia $id sin copia para ${faltan.sinCopia.size} aparatos")
            }
        }

        // La propia se guarda local igual: quien publica tiene que poder verla
        // sin esperar a nada, y su sobre no se manda a si mismo.
        dao.guardarHistoria(
            HistoriaEnt(
                id = id,
                autor = sesion.username.orEmpty(),
                clase = clase,
                texto = texto,
                fondo = fondo,
                creadaEn = mia.creadaEn,
                expiraEn = mia.expiraEn,
                vista = true,
                mia = true,
                vistas = mia.vistas ?: 0,
                destinatarios = mia.destinatarios,
                conContenido = true,
                adjuntoId = adjunto?.adjuntoId.orEmpty(),
                adjuntoClave = adjunto?.clave.orEmpty(),
                adjuntoNonce = adjunto?.nonce.orEmpty(),
                adjuntoMime = adjunto?.mime.orEmpty(),
                adjuntoBytes = adjunto?.bytes ?: 0,
                adjuntoAncho = adjunto?.ancho ?: 0,
                adjuntoAlto = adjunto?.alto ?: 0,
                adjuntoDuracionMs = adjunto?.duracionMs ?: 0,
                miniatura = adjunto?.miniatura.orEmpty(),
                // La propia NO se descarga: el archivo en claro ya esta en este
                // telefono, que es de donde salio.
                rutaLocal = preparado?.local?.absolutePath.orEmpty(),
                adjuntoEstado = if (adjunto != null) "LISTO" else "",
            )
        )
        sincronizarHistorias()
    }

    /**
     * Un archivo listo para ser la historia: copiado, reducido y medido.
     *
     * `local` es el archivo EN CLARO dentro de la app. Se queda: quien publica
     * tiene que poder volver a ver su propia historia sin bajarsela.
     */
    private data class MedioDeHistoria(
        val clase: String,
        val datos: DatosArchivo,
        val local: File,
        val miniatura: String,
    )

    /**
     * Prepara el archivo antes de tocar la red.
     *
     * La clase sale del **tipo real** del archivo y no de lo que diga quien lo
     * eligio: es lo que decide como se dibuja y que limite de tamano se aplica.
     * Un documento no es una historia, asi que se rechaza aqui y no despues de
     * haberlo subido.
     */
    private fun prepararMedioDeHistoria(id: String, uri: Uri): MedioDeHistoria {
        val original = archivos.datosDe(uri, ClaseAdjunto.IMAGEN)
        val clase = when (Media.claseDe(original.mime)) {
            ClaseAdjunto.IMAGEN -> ClaseHistoria.IMAGEN
            ClaseAdjunto.VIDEO -> ClaseHistoria.VIDEO
            else -> throw IllegalArgumentException("Una historia solo admite una foto o un video.")
        }

        // Se reduce igual que una foto de chat: ahorra datos de quien publica,
        // de todos los que la abran y cuota en el almacen. Un GIF no, que
        // pasarlo por el compresor JPEG lo deja quieto.
        var fuente = uri
        var datos = archivos.datosDe(uri, clase)
        if (clase == ClaseHistoria.IMAGEN && original.mime != "image/gif") {
            val reducida = archivos.temporal(id + "-red")
            if (archivos.prepararImagen(uri, ajustes.calidadImagen, reducida)) {
                fuente = Uri.fromFile(reducida)
                datos = archivos.datosDe(fuente, clase).copy(nombre = original.nombre)
            }
        }

        val local = archivos.archivoDe(id, datos.nombre)
        if (!archivos.copiarDesde(fuente, local)) {
            throw IllegalStateException("No se pudo leer el archivo.")
        }
        if (fuente != uri) fuente.path?.let { File(it).delete() }

        val claseAdjunto =
            if (clase == ClaseHistoria.VIDEO) ClaseAdjunto.VIDEO else ClaseAdjunto.IMAGEN
        val limite = ClaseAdjunto.limite(claseAdjunto)
        // Se comprueba aqui y no solo en el servidor para no cifrar y
        // transferir algo que va a ser rechazado igual.
        if (local.length() + CifradorArchivo.SOBRECOSTO > limite) {
            local.delete()
            throw IllegalArgumentException(
                "El archivo pasa el limite de " + Media.tamanoLegible(limite) + "."
            )
        }

        return MedioDeHistoria(
            clase = clase,
            datos = datos.copy(bytes = local.length()),
            local = local,
            miniatura = archivos.miniaturaDe(Uri.fromFile(local), claseAdjunto),
        )
    }

    /**
     * Cifra el archivo, lo sube y devuelve lo que ira DENTRO del sobre.
     *
     * La clave del archivo viaja en el sobre cifrado y en ningun otro sitio: el
     * servidor guarda bytes que no puede abrir, igual que con los mensajes.
     * Se reserva contra la historia -y no contra una conversacion- para que la
     * autorizacion de la descarga sea "estabas en la audiencia" y no "sos
     * miembro de un chat", que para una historia no significa nada.
     */
    private suspend fun subirArchivoDeHistoria(
        id: String,
        medio: MedioDeHistoria,
    ): CargaAdjunto {
        val temp = archivos.temporal(id)
        try {
            val llave = withContext(Dispatchers.IO) {
                CifradorArchivo.cifrarA(medio.local.inputStream(), temp)
            }
            val claseAdjunto =
                if (medio.clase == ClaseHistoria.VIDEO) ClaseAdjunto.VIDEO else ClaseAdjunto.IMAGEN
            val reserva = api.reservarAdjunto(
                ReservarAdjuntoReq(
                    historiaId = id,
                    clase = claseAdjunto,
                    // El tamano declarado es el del archivo CIFRADO, que es lo
                    // que se transfiere y lo que ocupa.
                    bytes = temp.length(),
                    mime = medio.datos.mime,
                    nombre = medio.datos.nombre,
                    ancho = medio.datos.ancho,
                    alto = medio.datos.alto,
                    duracionMs = medio.datos.duracionMs,
                )
            )
            api.subirAlAlmacen(reserva.urlSubida, temp)
            api.confirmarAdjunto(reserva.adjuntoId)

            return CargaAdjunto(
                adjuntoId = reserva.adjuntoId,
                clase = claseAdjunto,
                clave = llave.claveB64,
                nonce = llave.nonceB64,
                mime = medio.datos.mime,
                nombre = medio.datos.nombre,
                bytes = medio.datos.bytes,
                ancho = medio.datos.ancho,
                alto = medio.datos.alto,
                duracionMs = medio.datos.duracionMs,
                miniatura = medio.miniatura,
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * La extension del archivo de una historia, a partir de su tipo.
     *
     * Solo un puñado de valores conocidos; cualquier otra cosa cae al generico
     * de su clase. Es una lista blanca a proposito: el MIME viene del sobre, o
     * sea de otra persona, y de aqui sale un nombre de archivo.
     */
    private fun extensionDeHistoria(mime: String, clase: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        else -> if (clase == ClaseHistoria.VIDEO) "mp4" else "jpg"
    }

    /**
     * Baja y descifra el archivo de una historia ajena.
     *
     * Se pide al abrirla y no al recibirla: la miniatura viaja en el sobre y ya
     * dibuja algo, asi que bajar entero lo que quiza nadie mire seria gastar
     * datos de otro. La URL se pide en este momento porque las firmadas caducan.
     */
    suspend fun descargarArchivoDeHistoria(id: String) {
        val h = dao.historia(id) ?: return
        if (h.adjuntoId.isBlank()) return
        if (h.adjuntoEstado == "DESCARGANDO") return
        if (h.rutaLocal.isNotBlank() && File(h.rutaLocal).exists()) return

        dao.estadoArchivoHistoria(id, "DESCARGANDO")
        // La extension sale del MIME y no de ningun nombre que haya mandado
        // la otra persona: de esa extension depende que el reproductor sepa
        // que abrir, y un nombre ajeno no es sitio para decidir eso.
        val destino = archivos.archivoDe(id, "h." + extensionDeHistoria(h.adjuntoMime, h.clase))
        try {
            val info = api.adjunto(h.adjuntoId)
            val llave = CifradorArchivo.Llave(h.adjuntoClave, h.adjuntoNonce)
            val ok = api.bajarDelAlmacen(info.urlDescarga) { flujo ->
                CifradorArchivo.descifrarA(flujo, llave, destino)
            }
            if (ok) {
                dao.archivoDeHistoriaListo(id, destino.absolutePath)
            } else {
                // GCM rechazo el contenido: no es "no se pudo bajar", es que
                // esos bytes no son los que se cifraron.
                dao.estadoArchivoHistoria(id, "FALLIDO")
                _rechazos.tryEmit("El archivo no coincide con su firma: pudo ser alterado.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Historia " + id + ": no se pudo bajar el archivo: " + e.message)
            destino.delete()
            // Vuelve a ESPERA y no a FALLIDO: un corte de red no es un archivo
            // roto, y tiene que poder tocarse otra vez.
            dao.estadoArchivoHistoria(id, "ESPERA")
        }
    }

    /** El contenido que llego por el buzon. */
    private suspend fun guardarContenidoHistoria(carga: Carga.Historia, autor: String) {
        val previa = dao.historia(carga.historiaId)
        dao.guardarHistoria(
            HistoriaEnt(
                id = carga.historiaId,
                autor = autor,
                clase = carga.clase,
                // Se recorta con las mismas reglas que el resto del contenido
                // ajeno: esto lo escribio otra persona. Ver `ContenidoSeguro`.
                texto = recortado(carga.texto, TopesConsulta.LARGO_NOTA).first,
                fondo = carga.fondo,
                // Si el metadato ya llego se conserva. Si no, se estima: el
                // sobre acaba de llegar, asi que la historia es de ahora y dura
                // lo que dura cualquiera.
                //
                // Estimar y no dejar en cero, porque cero significa "vencida"
                // para las consultas que filtran por caducidad: la fila con el
                // texto recien descifrado se borraba sola antes de que el
                // metadato llegara a corregirla.
                creadaEn = previa?.creadaEn ?: System.currentTimeMillis(),
                expiraEn = previa?.expiraEn
                    ?: (System.currentTimeMillis() + HORAS_DE_VIDA_HISTORIA * 3_600_000L),
                vista = previa?.vista ?: false,
                mia = previa?.mia ?: false,
                vistas = previa?.vistas ?: 0,
                destinatarios = previa?.destinatarios ?: 0,
                conContenido = true,
                // El archivo NO se baja aqui. Lo que llega en el sobre es la
                // referencia y la miniatura; el archivo entero se pide al
                // abrirla. Bajar de oficio lo que quiza nadie mire seria gastar
                // los datos de esta persona por una decision que no tomo.
                adjuntoId = carga.adjunto?.adjuntoId.orEmpty(),
                adjuntoClave = carga.adjunto?.clave.orEmpty(),
                adjuntoNonce = carga.adjunto?.nonce.orEmpty(),
                adjuntoMime = carga.adjunto?.mime.orEmpty().take(120),
                adjuntoBytes = carga.adjunto?.bytes ?: 0,
                adjuntoAncho = carga.adjunto?.ancho ?: 0,
                adjuntoAlto = carga.adjunto?.alto ?: 0,
                // La duracion la declara quien subio el archivo y nadie la
                // comprueba: se guarda tal cual y quien la dibuja la pasa por
                // `Media.duracionLegible`, que es donde se para un negativo.
                adjuntoDuracionMs = carga.adjunto?.duracionMs ?: 0,
                // Lo mismo con la miniatura: son bytes de otra persona. Se
                // guardan y se validan al decodificar, con `miniaturaAjena`.
                // Ver `MiniaturaSegura.kt`.
                miniatura = carga.adjunto?.miniatura.orEmpty(),
                rutaLocal = previa?.rutaLocal.orEmpty(),
                adjuntoEstado = if (carga.adjunto != null) "ESPERA" else "",
            )
        )
    }

    /**
     * Trae el metadato del servidor y lo casa con lo que ya hay.
     *
     * No pisa el contenido: `actualizarMetadato` toca solo las columnas del
     * servidor. Si la fila no existe todavia -el sobre no llego- se crea sin
     * contenido, y la pantalla dibuja "no se pudo descifrar", que es lo que de
     * verdad pasa.
     */
    suspend fun sincronizarHistorias() {
        runCatching {
            dao.limpiarHistorias(System.currentTimeMillis())

            val ajenas = api.historiasParaMi().historias
            for (h in ajenas) {
                val tocadas = dao.actualizarMetadato(
                    id = h.historiaId, autor = h.autorUsername, clase = h.clase,
                    creadaEn = h.creadaEn, expiraEn = h.expiraEn, vista = h.vista,
                    mia = false, vistas = 0, destinatarios = 0,
                )
                if (tocadas == 0) {
                    dao.guardarHistoria(
                        HistoriaEnt(
                            id = h.historiaId, autor = h.autorUsername, clase = h.clase,
                            creadaEn = h.creadaEn, expiraEn = h.expiraEn, vista = h.vista,
                            conContenido = false,
                        )
                    )
                }
            }

            val mias = api.misHistorias().historias
            val yo = sesion.username.orEmpty()
            for (h in mias) {
                dao.actualizarMetadato(
                    id = h.historiaId, autor = yo, clase = h.clase,
                    creadaEn = h.creadaEn, expiraEn = h.expiraEn, vista = true,
                    mia = true, vistas = h.vistas ?: 0, destinatarios = h.destinatarios,
                )
            }

            // Lo que el servidor ya no lista y no es mio se fue: caduco o lo
            // retiraron. Borrarlo aqui es lo que hace que retirar una historia
            // la quite de verdad del telefono de quien la recibio.
            val vivas = (ajenas.map { it.historiaId } + mias.map { it.historiaId }).toSet()
            dao.historiasVivas(System.currentTimeMillis()).first()
                .filter { it.id !in vivas }
                .forEach { dao.borrarHistoria(it.id) }
        }
    }

    /**
     * Contesta una historia con un mensaje directo a quien la publico.
     *
     * ## Por que no hay ruta nueva en el servidor
     *
     * Responder a una historia **es** escribir un mensaje directo. La unica
     * parte propia es la cita, y la cita es contenido: viaja dentro del sobre
     * cifrado, donde el servidor no la ve. Inventar una ruta
     * `/v1/historias/{id}/responder` habria obligado a que el servidor supiera
     * que ese mensaje contesta a una historia -metadato nuevo, sin que nadie lo
     * necesite- y a volver a resolver permisos, cola de salida y reintentos que
     * el camino de los mensajes ya resuelve.
     *
     * ## La autorizacion que SI existe, y puede decir que no
     *
     * Abrir el chat pasa por `priv_escribe`, y ese ajuste **no es el mismo** que
     * `priv_historias`: se puede ver una historia y no poder contestarla -quien
     * publica para todos pero acepta mensajes solo de conocidos-. Por eso esto
     * devuelve `Result` y el mensaje del servidor llega tal cual a la pantalla:
     * un boton de responder que falla en silencio es peor que uno que no esta.
     */
    suspend fun responderHistoria(historiaId: String, texto: String): Result<Unit> = runCatching {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return@runCatching

        val h = dao.historia(historiaId) ?: throw IllegalStateException("Esa historia ya no esta.")
        if (h.mia) throw IllegalStateException("Es tu propia historia.")

        // `nuevaDirecta` devuelve la conversacion que ya existia si existia:
        // contestar dos historias de la misma persona no abre dos chats.
        val convId = nuevaDirecta(h.autor)
        enviarTexto(convId, limpio, citaHistoria = h)
    }

    /** Marca una historia como vista, aqui y en el servidor. */
    suspend fun verHistoria(id: String) {
        dao.marcarHistoriaVista(id)
        runCatching { api.marcarHistoriaVista(id) }
    }

    suspend fun vistasDeHistoria(id: String): VistasDeHistoria =
        runCatching { api.vistasDeHistoria(id) }.getOrElse { VistasDeHistoria() }

    suspend fun retirarHistoria(id: String): Result<Unit> = runCatching {
        api.retirarHistoria(id)
        dao.borrarHistoria(id)
    }

    // ============================================================
    //  Modulo D: adjuntos
    // ============================================================

    /**
     * Envia un archivo.
     *
     * El orden importa y es este:
     *
     *   1. La FILA LOCAL primero, con su miniatura. La burbuja aparece al
     *      instante, como en cualquier mensajero decente: quien envia no tiene
     *      que esperar la red para ver que su foto entro al chat.
     *   2. Se cifra con una clave nueva y se sube al almacen.
     *   3. Recien cuando el archivo esta arriba el mensaje entra a la cola,
     *      porque el sobre lleva el id del adjunto y la clave. Mandarlo antes
     *      seria mandar una referencia a algo que todavia no existe.
     *
     * Mientras dura el paso 2 el mensaje queda en SUBIENDO y la cola lo saltea.
     */
    suspend fun enviarAdjunto(
        convId: String,
        uri: Uri,
        clase: String,
        pie: String = "",
        respondeA: MensajeEnt? = null,
    ) {
        val mensajeId = UUID.randomUUID().toString()
        val original = archivos.datosDe(uri, clase)

        // Una foto se reduce ANTES de cifrar: ahorra datos de quien envia, de
        // quien recibe y cuota en el almacen, los tres de una vez.
        var fuente = uri
        var datos = original
        // Un GIF NO se recomprime: pasarlo por el compresor JPEG lo dejaria
        // como una sola imagen quieta, que es justo lo contrario de un GIF.
        if (clase == ClaseAdjunto.IMAGEN && original.mime != "image/gif") {
            val reducida = archivos.temporal(mensajeId + "-red")
            if (archivos.prepararImagen(uri, ajustes.calidadImagen, reducida)) {
                fuente = Uri.fromFile(reducida)
                datos = archivos.datosDe(fuente, clase).copy(nombre = original.nombre)
            }
        }

        val local = archivos.archivoDe(mensajeId, datos.nombre)
        if (!archivos.copiarDesde(fuente, local)) {
            _rechazos.tryEmit("No se pudo leer el archivo.")
            return
        }
        // La copia reducida ya cumplio: el que vale es el de media/.
        if (fuente != uri) fuente.path?.let { File(it).delete() }

        dao.guardarMensaje(
            MensajeEnt(
                id = mensajeId,
                conversacionId = convId,
                autor = sesion.username.orEmpty(),
                esMio = true,
                texto = pie,
                creadoEn = System.currentTimeMillis(),
                estado = EstadoEnvio.PENDIENTE.name,
                respondeA = respondeA?.id,
                respondeTexto = respondeA?.texto?.take(140),
                respondeAutor = respondeA?.autor,
                adjuntoClase = clase,
                adjuntoMime = datos.mime,
                adjuntoNombre = datos.nombre,
                adjuntoBytes = local.length(),
                adjuntoAncho = datos.ancho,
                adjuntoAlto = datos.alto,
                adjuntoDuracionMs = datos.duracionMs,
                adjuntoMiniatura = archivos.miniaturaDe(Uri.fromFile(local), clase),
                rutaLocal = local.absolutePath,
                adjuntoEstado = "SUBIENDO",
            )
        )

        // El limite se comprueba aqui y no solo en el servidor para no cifrar y
        // transferir algo que va a ser rechazado de todas formas.
        val limite = ClaseAdjunto.limite(clase)
        if (local.length() + CifradorArchivo.SOBRECOSTO > limite) {
            val motivo = "El archivo pasa el limite de " + Media.tamanoLegible(limite) + "."
            dao.marcarFallido(mensajeId, motivo)
            dao.estadoAdjunto(mensajeId, "FALLIDO")
            _rechazos.tryEmit(motivo)
            return
        }

        subirAdjunto(mensajeId, convId, clase, datos, local)
    }

    /**
     * Cifra y sube el archivo de un mensaje que ya existe localmente.
     *
     * Esta aparte de [enviarAdjunto] porque es exactamente lo que hay que
     * repetir al reintentar: la fila, la miniatura y el archivo ya estan.
     */
    private suspend fun subirAdjunto(
        mensajeId: String,
        convId: String,
        clase: String,
        datos: DatosArchivo,
        local: File,
    ) {
        val temp = archivos.temporal(mensajeId)
        try {
            val llave = withContext(Dispatchers.IO) {
                CifradorArchivo.cifrarA(local.inputStream(), temp)
            }

            val reserva = api.reservarAdjunto(
                ReservarAdjuntoReq(
                    conversacionId = convId,
                    clase = clase,
                    // Se declara el tamano del archivo CIFRADO, que es lo que
                    // se transfiere y lo que ocupa en el almacen.
                    bytes = temp.length(),
                    mime = datos.mime,
                    nombre = datos.nombre,
                    ancho = datos.ancho,
                    alto = datos.alto,
                    duracionMs = datos.duracionMs,
                )
            )
            api.subirAlAlmacen(reserva.urlSubida, temp)
            // Confirmar no lleva el id del mensaje: en el servidor ese mensaje
            // todavia no existe. El enlace lo hace el registro, al despachar.
            api.confirmarAdjunto(reserva.adjuntoId)

            dao.adjuntoSubido(mensajeId, reserva.adjuntoId, llave.claveB64, llave.nonceB64)
            despachar()
        } catch (e: Exception) {
            val motivo = (e as? ApiError)?.message ?: "No se pudo subir el archivo."
            Log.w(TAG, "Subida fallida de " + mensajeId + ": " + e.message)
            dao.marcarFallido(mensajeId, motivo)
            dao.estadoAdjunto(mensajeId, "FALLIDO")
            _rechazos.tryEmit(motivo)
        } finally {
            temp.delete()
        }
    }

    /** Vuelve a intentar la subida de un adjunto que fallo. */
    suspend fun reintentarAdjunto(mensajeId: String) {
        val m = dao.mensaje(mensajeId) ?: return
        val local = m.rutaLocal?.let { File(it) }
        if (local == null || !local.exists()) {
            _rechazos.tryEmit("El archivo ya no esta en este dispositivo.")
            return
        }
        dao.devolverACola(mensajeId)
        dao.estadoAdjunto(mensajeId, "SUBIENDO")
        subirAdjunto(
            mensajeId, m.conversacionId, m.adjuntoClase,
            DatosArchivo(
                m.adjuntoNombre, m.adjuntoMime, m.adjuntoBytes,
                m.adjuntoAncho, m.adjuntoAlto, m.adjuntoDuracionMs,
            ),
            local,
        )
    }

    /**
     * Baja el archivo y lo descifra al disco.
     *
     * La URL se pide en este momento: las firmadas caducan, asi que una URL
     * guardada hace una hora ya no sirve de nada.
     */
    suspend fun descargarAdjunto(mensajeId: String) {
        val m = dao.mensaje(mensajeId) ?: return
        val adjuntoId = m.adjuntoId ?: return
        if (m.adjuntoEstado == "DESCARGANDO") return
        if (m.rutaLocal?.let { File(it).exists() } == true) return

        dao.estadoAdjunto(mensajeId, "DESCARGANDO")
        val destino = archivos.archivoDe(mensajeId, m.adjuntoNombre)
        try {
            val info = api.adjunto(adjuntoId)
            val llave = CifradorArchivo.Llave(m.adjuntoClave, m.adjuntoNonce)
            val ok = api.bajarDelAlmacen(info.urlDescarga) { flujo ->
                CifradorArchivo.descifrarA(flujo, llave, destino)
            }
            if (ok) {
                dao.adjuntoListo(mensajeId, destino.absolutePath)
            } else {
                // GCM rechazo el contenido: los bytes no son los que se
                // cifraron. No es "no se pudo bajar", es que no son de fiar, y
                // hay que decirlo con esas palabras.
                dao.estadoAdjunto(mensajeId, "FALLIDO")
                _rechazos.tryEmit("El archivo no coincide con su firma: pudo ser alterado.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Descarga fallida de " + mensajeId + ": " + e.message)
            destino.delete()
            // Vuelve a ESPERA y no a FALLIDO: un corte de red no es un archivo
            // roto, y la persona tiene que poder tocar otra vez.
            dao.estadoAdjunto(mensajeId, "ESPERA")
            if (e is ApiError && e.codigo in 400..499) {
                _rechazos.tryEmit(e.message ?: "El archivo ya no esta disponible.")
            }
        }
    }

    // --- D.6: GIFs ----------------------------------------------------

    suspend fun buscarGifs(consulta: String): BusquedaGifs =
        runCatching { api.buscarGifs(consulta) }.getOrElse {
            BusquedaGifs(aviso = (it as? ApiError)?.message ?: "No se pudo buscar.")
        }

    suspend fun bytesGifPrevia(id: String): ByteArray? =
        runCatching { api.bytesGif(id, previa = true) }.getOrNull()

    /**
     * Manda un GIF.
     *
     * Se baja por nuestro intermediario, se guarda como archivo y desde ahi
     * sigue el camino de cualquier adjunto: se cifra con su clave y se sube.
     * Un GIF no es un caso especial en el transporte, solo en el origen.
     *
     * Va como IMAGEN y no como STICKER porque el limite de sticker son 2 MB y
     * un GIF de chat los pasa con facilidad.
     */
    suspend fun enviarGif(convId: String, gifId: String, pie: String = "") {
        // El id se interpola mas abajo en un NOMBRE DE ARCHIVO, y viene de la
        // respuesta de un tercero. Con un `../` dentro, el temporal se escribe
        // fuera de `cacheDir`, y un poco mas alla estan las bases de la app.
        //
        // El servidor ya lo valida en dos sitios; esto es el tercero, y el
        // unico que escribe en disco. Comprobar aqui no es redundante: es el
        // sitio donde el dato hace dano.
        if (!FORMA_GIF_ID.matches(gifId)) {
            _rechazos.tryEmit("Ese GIF tiene un identificador invalido.")
            return
        }
        val bytes = runCatching { api.bytesGif(gifId, previa = false) }.getOrElse {
            _rechazos.tryEmit((it as? ApiError)?.message ?: "No se pudo traer el GIF.")
            return
        }
        val temp = withContext(Dispatchers.IO) {
            archivos.temporalNombrado("gif-$gifId.gif").also { it.writeBytes(bytes) }
        }
        try {
            enviarAdjunto(convId, Uri.fromFile(temp), ClaseAdjunto.IMAGEN, pie)
        } finally {
            temp.delete()
        }
    }

    /** Borra el archivo local sin borrar el mensaje: se puede volver a bajar. */
    suspend fun liberarAdjunto(mensajeId: String) {
        val m = dao.mensaje(mensajeId) ?: return
        m.rutaLocal?.let { File(it).delete() }
        dao.olvidarArchivoLocal(mensajeId)
    }

    suspend fun usoAlmacenLocal(): Pair<Int, Long> =
        withContext(Dispatchers.IO) { archivos.cuantosArchivos() to archivos.bytesUsados() }

    suspend fun usoAlmacenServidor(): UsoAlmacenamiento? =
        runCatching { api.usoAlmacen() }.getOrNull()

    suspend fun vaciarMediaLocal(): Int = withContext(Dispatchers.IO) {
        val n = archivos.vaciar()
        dao.conArchivoLocal().forEach { m ->
            if (m.rutaLocal?.let { File(it).exists() } != true) dao.olvidarArchivoLocal(m.id)
        }
        n
    }

    /**
     * Rescata las subidas que quedaron a medias.
     *
     * Si la app murio mientras subia, el mensaje quedo en SUBIENDO: la cola lo
     * saltea y nadie lo ve fallar. Se marcan al arrancar para que aparezcan con
     * su boton de reintentar, que es la verdad de lo que paso.
     */
    private suspend fun rescatarSubidas() {
        archivos.limpiarTemporales()
        dao.subidasInterrumpidas().forEach { m ->
            Log.i(TAG, "Subida interrumpida: " + m.id)
            dao.marcarFallido(m.id, "La subida se interrumpio.")
            dao.estadoAdjunto(m.id, "FALLIDO")
        }
    }

    // ============================================================
    //  Modulo K: llamadas
    // ============================================================

    /**
     * El servicio de llamadas.
     *
     * Se crea aqui y no en la Activity porque una llamada tiene que sobrevivir
     * a que la pantalla se gire o se cierre: mientras el proceso viva, la
     * llamada vive. (Lo que NO sobrevive es que Android mate el proceso; para
     * eso haria falta un servicio en primer plano, que esta declarado como
     * pendiente.)
     */
    val llamadas: ServicioLlamadas by lazy {
        ServicioLlamadas(
            ctx = contexto,
            api = api,
            sesion = sesion,
            // El puente: el servicio de llamadas NO sabe cifrar ni tiene
            // transporte. Le pasa una carga y un dispositivo, y este lambda la
            // mete en un sobre cifrado como cualquier mensaje. Es lo que hace
            // que la señalizacion herede el E2EE sin duplicar nada.
            enviarCifrado = ::enviarCifradoA,
        )
    }

    /**
     * Manda una carga cifrada a UN dispositivo concreto.
     *
     * Existe para la señalizacion de llamadas y para el historial del modulo
     * J: los dos casos donde el destinatario es un aparato y no "todos los de
     * la conversacion".
     */
    private suspend fun enviarCifradoA(convId: String, dispositivoId: String, carga: Carga) {
        val activo = transportes.firstOrNull { it.disponible() }
            ?: error("Sin transporte disponible.")
        val destinos = destinosDe(convId, forzar = true)
            ?.filter { it.dispositivoId == dispositivoId }
            .orEmpty()
        if (destinos.isEmpty()) error("Ese dispositivo no esta en la conversacion.")

        val sobre = Sobre(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            origenDispositivo = sesion.dispositivoId.orEmpty(),
            creadoEn = System.currentTimeMillis(),
            copias = cifrador.cifrar(convId, esGrupo = false, destinos = destinos, carga = carga),
        )
        activo.entregar(sobre).getOrThrow()
        cifrador.confirmarEnvio(convId, destinos)
    }

    suspend fun historialLlamadas(): List<LlamadaEnHistorial> = llamadas.historial()

    // ============================================================
    //  Modulo J: varios dispositivos por cuenta
    // ============================================================

    suspend fun dispositivos(): List<DispositivoInfo> =
        runCatching { api.dispositivos().dispositivos }.getOrElse { emptyList() }

    suspend fun emitirCodigoVinculacion(password: String): Result<CodigoVinculacion> =
        runCatching { api.emitirCodigoVinculacion(password) }

    suspend fun revocarDispositivo(id: String): Result<Unit> =
        runCatching { api.revocarDispositivo(id) }

    suspend fun promoverDispositivo(id: String, password: String): Result<Unit> =
        runCatching { api.promoverDispositivo(id, password) }

    /**
     * Vincula ESTE aparato a una cuenta que ya existe.
     *
     * Guarda la sesion igual que un registro y arranca el socket. Lo unico
     * distinto es lo que pasa despues: este dispositivo no tiene historial, y
     * lo pide a los otros. Ver [pedirHistorial].
     */
    suspend fun vincularEsteDispositivo(
        username: String,
        codigo: String,
        id: Hardware.Identidad,
        etiqueta: String,
    ): Result<VincularHechoResp> = runCatching {
        val r = api.vincular(
            VincularReq(
                username = username,
                codigo = codigo,
                etiquetaDispositivo = etiqueta,
                identidadPub = id.identidadPub,
                hardwareHash = id.hardwareHash,
                hardwareNivel = id.nivel,
            )
        )
        // Se reusa `SesionResp` para guardar: son los mismos cuatro campos, y
        // tener dos caminos para escribir la sesion es como se desincronizan.
        sesion.guardar(SesionResp(r.token, r.usuarioId, r.dispositivoId, r.username))

        // Las claves se publican AQUI y esperando el resultado, no en el
        // `launch` de `iniciar()`.
        //
        // Esto se descubrio probando: al vincular, el pedido de historial salia
        // antes de que este dispositivo hubiera publicado sus claves, asi que
        // el otro no tenia con que cifrarle y los siete mensajes se perdian en
        // el camino. El sintoma era el peor: vinculacion "exitosa" y lista de
        // chats vacia, sin ningun error en ninguna de las dos pantallas.
        //
        // Un dispositivo sin claves publicadas NO PUEDE RECIBIR NADA, asi que
        // publicarlas es parte de vincular y no una tarea de fondo.
        runCatching { cifrador.prepararClaves() }
            .onFailure { Log.w(TAG, "No se pudieron publicar las claves al vincular: ${it.message}") }

        r
    }

    /**
     * Pide historial a los otros dispositivos de esta misma cuenta.
     *
     * El servidor no puede responder: nunca lo tuvo. Solo reparte el aviso.
     * Si no hay ningun otro dispositivo con claves publicadas, la respuesta lo
     * dice y la pantalla puede explicar por que el aparato arranca vacio en
     * vez de dejar al usuario pensando que se perdio algo.
     */
    suspend fun pedirHistorial(): EstadoHistorial? =
        runCatching { api.pedirHistorial() }.getOrNull()

    /**
     * Responde el pedido de historial de otro dispositivo MIO.
     *
     * Se manda por el buzon normal, cifrado, y **solo a ese dispositivo**: es
     * una copia de mi propio historial, no un mensaje para nadie mas.
     *
     * Cada entrada lleva su autor y su fecha ORIGINALES. Si se guardaran con
     * mi nombre y la hora del reenvio, el historial del aparato nuevo quedaria
     * falsificado, y eso es peor que no tener historial.
     */
    private suspend fun responderHistorial(destinoDispositivo: String) {
        val activo = transportes.firstOrNull { it.disponible() } ?: return
        var total = 0

        for (convId in dao.idsLocales()) {
            val destinos = destinosDe(convId, forzar = true)
                ?.filter { it.dispositivoId == destinoDispositivo }
                .orEmpty()
            // Si ese dispositivo no esta entre los destinos de esta
            // conversacion, no tiene claves publicadas todavia o ya no
            // pertenece. No hay nada que hacer y no es un error.
            if (destinos.isEmpty()) continue

            val mensajes = dao.ultimos(convId, HistorialPedido.MENSAJES_POR_CONVERSACION)
                .filter { !it.esSistema && !it.retirado && it.texto.isNotBlank() }
                .map {
                    MensajeHistorico(
                        id = it.id,
                        autor = it.autor,
                        esMio = it.esMio,
                        texto = it.texto,
                        creadoEn = it.creadoEn,
                    )
                }
                // La consulta devuelve del mas nuevo al mas viejo porque el
                // LIMIT tiene que cortar por el lado viejo.
                .reversed()
            if (mensajes.isEmpty()) continue

            val carga = Carga.Historial(conversacionId = convId, mensajes = mensajes)
            val sobre = Sobre(
                id = UUID.randomUUID().toString(),
                conversacionId = convId,
                origenDispositivo = sesion.dispositivoId.orEmpty(),
                creadoEn = System.currentTimeMillis(),
                copias = cifrador.cifrar(convId, esGrupo = false, destinos = destinos, carga = carga),
            )
            activo.entregar(sobre)
                .onSuccess {
                    cifrador.confirmarEnvio(convId, destinos)
                    total += mensajes.size
                }
                .onFailure { Log.w(TAG, "No se pudo enviar historial de $convId: ${it.message}") }
        }

        if (total > 0) {
            runCatching { api.anotarHistorialEnviado(destinoDispositivo, total) }
            Log.i(TAG, "Historial enviado a $destinoDispositivo: $total mensajes")
        }
    }

    /**
     * Guarda un lote de historial recibido de otro dispositivo mio.
     *
     * Tres diferencias con un mensaje normal, y ninguna es cosmetica:
     * no notifica, no cuenta como no leido, y conserva autor y fecha
     * originales. Un aparato recien vinculado que suena cuarenta veces al
     * terminar de sincronizar es un defecto, no una funcion.
     */
    private suspend fun guardarHistorial(carga: Carga.Historial) {
        if (dao.conversacion(carga.conversacionId) == null) sincronizar()
        for (m in carga.mensajes) {
            dao.guardarMensaje(
                MensajeEnt(
                    id = m.id,
                    conversacionId = carga.conversacionId,
                    autor = m.autor,
                    esMio = m.esMio,
                    texto = m.texto,
                    creadoEn = m.creadoEn,
                    // LEIDO y no ENTREGADO: ya los lei, en el otro aparato.
                    estado = if (m.esMio) EstadoEnvio.ENTREGADO.name else EstadoEnvio.LEIDO.name,
                )
            )
        }
        Log.i(TAG, "Historial recibido: ${carga.mensajes.size} de ${carga.conversacionId}")
    }

    // ============================================================
    //  Modulo I: identidad y cuenta
    // ============================================================

    suspend fun estadoCuenta(): EstadoCuenta? =
        runCatching { api.estadoCuenta() }.getOrNull()

    suspend fun ajustarCuenta(biografia: String? = null, descubrible: Boolean? = null): Result<EstadoCuenta> =
        runCatching { api.ajustarCuenta(AjustesCuentaReq(biografia, descubrible)) }

    suspend fun pedirCodigoTelefono(telefono: String): Result<CodigoPedido> =
        runCatching { api.pedirCodigo(PedirCodigoReq(telefono, PropositoCodigo.VERIFICAR_TELEFONO)) }

    suspend fun verificarTelefono(telefono: String, codigo: String): Result<TelefonoVerificado> =
        runCatching { api.verificarTelefono(VerificarTelefonoReq(telefono, codigo)) }

    suspend fun quitarTelefono(): Result<Unit> = runCatching { api.quitarTelefono() }

    /**
     * Pide el codigo para recuperar. Sin sesion: es el caso en que no se puede
     * entrar, que es el motivo por el que todo el modulo existe.
     */
    suspend fun pedirCodigoRecuperar(username: String, telefono: String): Result<CodigoPedido> =
        runCatching {
            api.pedirCodigo(
                PedirCodigoReq(telefono, PropositoCodigo.RECUPERAR_CUENTA, username),
                autenticado = false,
            )
        }

    suspend fun recuperarCuenta(
        username: String,
        telefono: String,
        codigo: String,
        passwordNueva: String,
    ): Result<Unit> = runCatching {
        api.recuperarCuenta(RecuperarReq(username, telefono, codigo, passwordNueva))
    }

    suspend fun iniciarTotp(): Result<TotpIniciado> = runCatching { api.iniciarTotp() }

    suspend fun confirmarTotp(codigo: String): Result<TotpActivado> =
        runCatching { api.confirmarTotp(TotpConfirmarReq(codigo)) }

    suspend fun apagarTotp(password: String): Result<Unit> =
        runCatching { api.apagarTotp(password) }

    suspend fun pedirEliminacion(password: String, totp: String?): Result<EliminacionPedida> =
        runCatching { api.pedirEliminacion(EliminarCuentaReq(password, totp?.ifBlank { null })) }

    suspend fun sesiones(): List<SesionActiva> =
        runCatching { api.sesiones().sesiones }.getOrElse { emptyList() }

    suspend fun cerrarSesionRemota(id: String): Result<Unit> =
        runCatching { api.cerrarSesionRemota(id) }

    suspend fun cerrarOtrasSesiones(): Result<Int> =
        runCatching { api.cerrarOtrasSesiones()["cerradas"] ?: 0 }

    suspend fun contactos(): List<Contacto> =
        runCatching { api.contactos().contactos }.getOrElse { emptyList() }

    suspend fun guardarContacto(
        username: String,
        alias: String? = null,
        favorito: Boolean? = null,
    ): Result<List<Contacto>> =
        runCatching { api.guardarContacto(GuardarContactoReq(username, alias, favorito)).contactos }

    suspend fun borrarContacto(username: String): Result<List<Contacto>> =
        runCatching { api.borrarContacto(username).contactos }

    suspend fun descubrir(telefonos: List<String>): Result<List<Descubierto>> =
        runCatching { api.descubrir(telefonos).encontrados }

    // ============================================================
    //  Modulo G y H: moderacion
    // ============================================================

    /**
     * Denuncia un mensaje entregando su texto.
     *
     * Aqui esta la parte incomoda del modulo G: el servidor no puede leer un
     * mensaje, asi que si nadie le entrega el texto no hay nada que moderar. El
     * unico que puede entregarlo es este telefono, que ya lo descifro.
     *
     * Por eso el texto se saca de la base LOCAL y no se le pide al servidor: es
     * literalmente lo que el usuario esta viendo en pantalla, y eso es lo que
     * esta aceptando entregar.
     *
     * `conContexto` decide si van solo el mensaje denunciado o tambien los de
     * alrededor. Se manda una ventana corta: la conversacion entera deja de ser
     * una prueba y se vuelve una filtracion.
     */
    suspend fun denunciarMensaje(
        convId: String,
        mensajeId: String,
        motivo: String,
        detalle: String,
        conContexto: Boolean,
    ): Result<DenunciaCreada> = runCatching {
        val evidencia = if (!conContexto) {
            listOfNotNull(dao.mensaje(mensajeId)?.let { aEvidencia(it) })
        } else {
            // La consulta devuelve del mas nuevo al mas viejo porque el LIMIT
            // tiene que cortar por el lado viejo; se invierte para que el
            // moderador lea la conversacion en el orden en que paso.
            dao.contexto(convId, mensajeId, VENTANA_EVIDENCIA).reversed().map { aEvidencia(it) }
        }
        api.denunciar(
            DenunciaReq(
                tipo = TipoDenuncia.MENSAJE,
                objetivoMensaje = mensajeId,
                motivo = motivo,
                detalle = detalle,
                evidencia = evidencia,
            )
        )
    }

    /** Denunciar a una persona o una conversacion. Sin texto: no hay que entregar. */
    suspend fun denunciar(
        tipo: String,
        motivo: String,
        detalle: String,
        username: String? = null,
        convId: String? = null,
    ): Result<DenunciaCreada> = runCatching {
        api.denunciar(
            DenunciaReq(
                tipo = tipo,
                objetivoUsuario = username,
                objetivoConversacion = convId,
                motivo = motivo,
                detalle = detalle,
            )
        )
    }

    private fun aEvidencia(m: MensajeEnt) = Evidencia(
        autor = m.autor,
        enviadoEn = m.creadoEn,
        contenido = m.texto.ifBlank { "[adjunto]" },
    )

    suspend fun miEstadoModeracion(): MiEstadoModeracion? =
        runCatching { api.miEstadoModeracion() }.getOrNull()

    suspend fun reconocerAdvertencia(id: String): Result<Unit> =
        runCatching { api.reconocerAdvertencia(id) }

    suspend fun misEventosSeguridad(): List<EventoSeguridad> =
        runCatching { api.misEventos().eventos }.getOrElse { emptyList() }

    /**
     * Mi nivel de plataforma, o 0 si no soy staff.
     *
     * Se resuelve preguntando por el resumen del panel: si no soy staff, el
     * servidor responde 404 y aqui eso es simplemente cero. La interfaz usa
     * esto para decidir si MOSTRAR el panel; quien autoriza sigue siendo el
     * servidor en cada peticion.
     */
    suspend fun miNivelStaff(): Int =
        runCatching { api.resumenPanel().miNivel }.getOrElse { 0 }

    suspend fun resumenPanel(): ResumenPanel? =
        runCatching { api.resumenPanel() }.getOrNull()

    suspend fun colaModeracion(estado: String? = null): List<DenunciaEnCola> =
        runCatching { api.colaModeracion(estado).denuncias }.getOrElse { emptyList() }

    suspend fun denunciaDetalle(id: String): DenunciaDetalle? =
        runCatching { api.denuncia(id) }.getOrNull()

    suspend fun tomarDenuncia(id: String): Result<DenunciaEnCola> =
        runCatching { api.tomarDenuncia(id) }

    suspend fun resolverDenuncia(id: String, req: ResolverReq): Result<ResolucionHecha> =
        runCatching { api.resolverDenuncia(id, req) }

    suspend fun buscarUsuariosPanel(q: String): List<UsuarioPanel> =
        runCatching { api.usuariosPanel(q).usuarios }.getOrElse { emptyList() }

    suspend fun suspenderUsuario(username: String, motivo: String, horas: Int?): Result<UsuarioPanel> =
        runCatching { api.suspenderUsuario(username, SuspenderReq(motivo, horas)) }

    suspend fun restaurarUsuario(username: String): Result<UsuarioPanel> =
        runCatching { api.restaurarUsuario(username) }

    // ============================================================
    //  Modulo F: canales
    // ============================================================

    suspend fun crearCanal(req: CrearCanalReq): ConfigCanal =
        api.crearCanal(req).also { sincronizar() }

    suspend fun canal(convId: String): ConfigCanal? =
        runCatching { api.canal(convId) }.getOrNull()

    suspend fun canalPorAlias(alias: String): Result<ConfigCanal> =
        runCatching { api.canalPorAlias(alias) }

    suspend fun configurarCanal(convId: String, req: ConfigCanalReq): ConfigCanal =
        api.configurarCanal(convId, req).also { sincronizar() }

    suspend fun suscribirCanal(convId: String): ConfigCanal =
        api.suscribirCanal(convId).also { sincronizar() }

    suspend fun desuscribirCanal(convId: String) {
        api.desuscribirCanal(convId)
        dao.marcarFuera(convId)
        sincronizar()
    }

    /**
     * El perfil publico de alguien, ya filtrado por SU privacidad.
     *
     * Devuelve null cuando no existe o cuando esa persona se oculto de la
     * busqueda: los dos casos son el mismo 404 a proposito, porque
     * distinguirlos convertiria la ruta en un oraculo de "esta persona existe
     * pero no quiere que la encuentres".
     */
    suspend fun perfilDe(username: String): UsuarioPublico? =
        runCatching { api.buscar(username) }.getOrNull()

    suspend fun buscarCanales(consulta: String): List<CanalEnBusqueda> =
        runCatching { api.buscarCanales(consulta).canales }.getOrElse { emptyList() }

    suspend fun directorioCanales(): List<CanalEnBusqueda> =
        runCatching { api.directorioCanales().canales }.getOrElse { emptyList() }

    suspend fun conversacionesPanel(q: String?, cerradas: Boolean): List<ConversacionPanel> =
        runCatching { api.conversacionesPanel(q, cerradas).conversaciones }.getOrElse { emptyList() }

    suspend fun cerrarConversacion(id: String, motivo: String): Result<Unit> =
        runCatching { api.cerrarConversacion(id, motivo) }

    suspend fun reabrirConversacion(id: String): Result<Unit> =
        runCatching { api.reabrirConversacion(id) }

    suspend fun abrirConsola(password: String, etiqueta: String?): Result<TokenConsola> =
        runCatching { api.abrirConsola(EmitirConsolaReq(password, etiqueta)) }

    suspend fun consolasAbiertas(): List<ConsolaAbierta> =
        runCatching { api.consolasAbiertas().consolas }.getOrElse { emptyList() }

    suspend fun cerrarConsola(id: String): Result<Unit> =
        runCatching { api.cerrarConsola(id) }

    suspend fun limitesPanel(): List<LimiteAjustable> =
        runCatching { api.limitesPanel().limites }.getOrElse { emptyList() }

    suspend fun ajustarLimite(clave: String, tope: Int, ventanaSegundos: Int): Result<LimiteAjustable> =
        runCatching { api.ajustarLimite(clave, AjustarLimiteReq(tope, ventanaSegundos)) }

    suspend fun restaurarLimite(clave: String): Result<Unit> =
        runCatching { api.restaurarLimite(clave) }

    suspend fun bitacora(filtro: String? = null): List<LineaBitacora> =
        runCatching { api.bitacora(filtro).lineas }.getOrElse { emptyList() }

    suspend fun colaCanales(estado: String = EstadoCanal.PENDIENTE): List<CanalPendiente> =
        runCatching { api.colaCanales(estado).canales }.getOrElse { emptyList() }

    suspend fun revisarCanal(convId: String, aprobado: Boolean, motivo: String = ""): Result<Unit> =
        runCatching { api.revisarCanal(convId, RevisarCanalReq(aprobado, motivo)) }

    suspend fun publicaciones(convId: String, antesDe: String? = null): List<Publicacion> =
        runCatching { api.publicaciones(convId, antesDe) }.getOrElse { emptyList() }

    suspend fun estadisticasCanal(convId: String): EstadisticasCanal? =
        runCatching { api.estadisticasCanal(convId) }.getOrNull()

    /**
     * Publica en un canal publico.
     *
     * No pasa por la cola de salida ni reparte sobres, y esa es la diferencia
     * de fondo con un mensaje de grupo: el contenido queda guardado en el
     * servidor y a los suscriptores les llega un aviso, no una copia. Con diez
     * mil suscriptores, un sobre por dispositivo serian diez mil filas por
     * publicacion.
     *
     * El precio, declarado en la interfaz: un canal publico no va cifrado de
     * extremo a extremo.
     */
    suspend fun publicar(convId: String, texto: String): Result<Unit> {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return Result.success(Unit)
        val mensajeId = UUID.randomUUID().toString()
        return runCatching {
            api.registrarMensaje(
                RegistrarMensajeReq(
                    mensajeId = mensajeId,
                    conversacionId = convId,
                    menciones = mencionesDe(limpio),
                )
            )
            api.publicarEnCanal(convId, PublicarReq(mensajeId, limpio))
        }
    }

    /** Comenta una publicacion. El comentario SI es un mensaje normal. */
    suspend fun comentar(convId: String, publicacionId: String, texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        val m = MensajeEnt(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            autor = sesion.username.orEmpty(),
            esMio = true,
            texto = limpio,
            creadoEn = System.currentTimeMillis(),
            estado = EstadoEnvio.PENDIENTE.name,
            respondeA = publicacionId,
        )
        dao.guardarMensaje(m)
        despachar()
    }

    // ============================================================
    //  Modulo E: verificacion de identidad
    // ============================================================

    /**
     * Todo lo que la pantalla de verificacion necesita, en una sola ida.
     *
     * Va con `withContext(Dispatchers.IO)` porque el almacen de Signal se
     * consulta de forma SINCRONA -libsignal no es suspendido- y Room rechaza
     * eso en el hilo principal. La primera version llamaba a estas funciones
     * desde un `LaunchedEffect` y la app se cayo con
     * "Cannot access database on the main thread". Ese rechazo de Room es una
     * red de seguridad, no un estorbo: hacer la consulta en el hilo de la
     * interfaz habria congelado la pantalla sin avisar.
     */
    /**
     * Las huellas de una conversacion directa: UNA POR APARATO.
     *
     * Antes devolvia la del primer dispositivo de la lista y la pantalla la
     * presentaba como "la" huella de la conversacion. Con un solo aparato por
     * cuenta daba lo mismo; desde el modulo J no: la segunda tablet de alguien
     * tiene su propia clave de identidad, su propia huella, y quedaba sin
     * comprobar detras de un cartel verde que decia "verificado".
     */
    suspend fun infoCifrado(convId: String): CifradoDeConversacion? {
        val c = cifrador as? CifradorSignal ?: return null
        val destinos = destinosDe(convId)?.takeIf { it.isNotEmpty() } ?: return null
        val yoId = sesion.usuarioId.orEmpty()
        return withContext(Dispatchers.IO) {
            CifradoDeConversacion(
                username = destinos.first().username,
                aparatos = destinos.map { d ->
                    val h = c.huellaCon(d.usuarioId, d.dispositivoId, yoId)
                    InfoCifrado(
                        username = d.username,
                        dispositivoId = d.dispositivoId,
                        etiqueta = d.etiqueta.ifBlank { "Aparato sin nombre" },
                        digitos = h?.digitos.orEmpty(),
                        escaneable = h?.escaneable.orEmpty(),
                        verificada = c.verificada(d.dispositivoId),
                        cambio = c.cambioSinResolver(d.dispositivoId),
                    )
                },
            )
        }
    }

    /**
     * Marca o desmarca una identidad como verificada a mano.
     *
     * No cambia el cifrado: los mensajes ya van cifrados igual. Lo que cambia
     * es que a partir de aqui, si esa clave cambia, la app puede avisarlo.
     */
    suspend fun marcarVerificado(dispositivoId: String, verificada: Boolean) {
        val c = cifrador as? CifradorSignal ?: return
        withContext(Dispatchers.IO) { c.marcarVerificada(dispositivoId, verificada) }
    }

    /** Si hay un cambio de identidad sin resolver en esta conversacion. */
    suspend fun claveCambiada(convId: String): String? {
        val c = cifrador as? CifradorSignal ?: return null
        val destinos = destinosDe(convId) ?: return null
        return withContext(Dispatchers.IO) {
            destinos.firstOrNull { c.cambioSinResolver(it.dispositivoId) }?.username
        }
    }

    /** Menciones que el remitente extrae del texto: @usuario */
    private fun mencionesDe(texto: String): List<String> =
        Regex("@([a-z0-9_]{3,24})").findAll(texto.lowercase()).map { it.groupValues[1] }.toList()

    // ============================================================
    //  Modulo C: acciones sobre un mensaje
    // ============================================================

    /**
     * Retira un mensaje para todos.
     *
     * Se pide permiso al servidor ANTES de borrarlo localmente: si el servidor
     * dice que no, el mensaje debe seguir ahi. Borrar primero y preguntar
     * despues dejaria la app mostrando algo distinto a la realidad.
     */
    suspend fun retirarMensaje(id: String) {
        api.retirarMensaje(id)
        dao.marcarRetirado(id)
    }

    /** Borra el mensaje solo de este dispositivo. No avisa a nadie. */
    suspend fun borrarSoloParaMi(id: String) = dao.borrarMensaje(id)

    suspend fun editarMensaje(id: String, textoNuevo: String) {
        val limpio = textoNuevo.trim()
        if (limpio.isEmpty()) return
        api.editarMensaje(id)
        dao.marcarEditado(id, limpio)
        // El texto nuevo viaja como un sobre aparte, igual que cualquier
        // contenido: el servidor no lo ve.
        val m = dao.mensaje(id) ?: return
        despacharEdicion(m.conversacionId, id, limpio)
    }

    private suspend fun despacharEdicion(convId: String, mensajeId: String, texto: String) {
        val activo = transportes.firstOrNull { it.disponible() } ?: return
        val destinos = destinosDe(convId) ?: return
        val esGrupo = dao.conversacion(convId)?.tipo == "grupo"
        val sobre = Sobre(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            origenDispositivo = sesion.dispositivoId.orEmpty(),
            creadoEn = System.currentTimeMillis(),
            copias = cifrador.cifrar(convId, esGrupo, destinos, Carga.Edicion(mensajeId, texto)),
        )
        activo.entregar(sobre).onSuccess { cifrador.confirmarEnvio(convId, destinos) }
    }

    /**
     * Dispositivos a los que hay que entregar copia, con cache corto.
     *
     * Se consulta al servidor porque solo el sabe quien esta en la
     * conversacion AHORA. El cache evita una peticion por mensaje cuando
     * alguien escribe varios seguidos; si queda desactualizado, el servidor
     * responde que faltaron copias y se vuelve a pedir.
     */
    private suspend fun destinosDe(convId: String, forzar: Boolean = false): List<DestinoDispositivo>? {
        val ahora = System.currentTimeMillis()
        if (!forzar) {
            cacheDestinos[convId]?.let { (cuando, lista) ->
                if (ahora - cuando < VIGENCIA_DESTINOS) return lista
            }
        }
        val lista = runCatching { api.destinosDe(convId).destinos }.getOrElse {
            Log.w(TAG, "No se pudieron pedir los destinos de $convId: ${it.message}")
            // Si no hay red, sirve lo ultimo que se sepa antes que nada.
            return cacheDestinos[convId]?.second
        }
        cacheDestinos[convId] = ahora to lista
        return lista
    }

    suspend fun fijarMensaje(id: String, fijar: Boolean) {
        api.fijarMensaje(id, fijar)
        dao.marcarFijado(id, fijar)
    }

    suspend fun reaccionar(id: String, emoji: String, poner: Boolean) {
        val meta = api.reaccionar(id, emoji, poner)
        dao.guardarReacciones(id, jsonApp.encodeToString(meta.reacciones))
    }

    /** Refresca el metadato de un mensaje desde el servidor. */
    suspend fun refrescarMensaje(id: String) {
        runCatching { api.mensajeMeta(id) }.onSuccess { meta ->
            dao.guardarReacciones(id, jsonApp.encodeToString(meta.reacciones))
            if (meta.retiradoEn != null) dao.marcarRetirado(id)
            dao.marcarFijado(id, meta.fijadoEn != null)
        }
    }

    suspend fun configurarTemporales(convId: String, segundos: Int?) =
        api.configurarTemporales(convId, segundos)

    suspend fun reintentar(mensajeId: String) {
        dao.devolverACola(mensajeId)
        despachar()
    }

    /** Descarta un mensaje que fallo y no se va a reintentar. */
    suspend fun descartarFallido(mensajeId: String) = dao.borrarMensaje(mensajeId)

    /**
     * Recorre la cola y entrega por el primer transporte disponible.
     * Si ninguno lo esta, los mensajes se quedan PENDIENTE sin perderse.
     */
    suspend fun despachar() {
        val pendientes = dao.cola()
        if (pendientes.isEmpty()) return

        val activo = transportes.firstOrNull { it.disponible() }
        if (activo == null) {
            Log.i(TAG, "${pendientes.size} en cola, ningun transporte disponible")
            return
        }

        for (m in pendientes) {
            // Primero se registra el METADATO por HTTP. Es ahi donde el servidor
            // comprueba el permiso de enviar: si esta silenciado o expulsado, el
            // mensaje queda FALLIDO con un motivo legible en vez de perderse.
            val registro = runCatching {
                api.registrarMensaje(
                    RegistrarMensajeReq(
                        mensajeId = m.id,
                        conversacionId = m.conversacionId,
                        respondeA = m.respondeA,
                        menciones = mencionesDe(m.texto),
                        reenviadoDe = m.reenviadoDe,
                        adjuntoId = m.adjuntoId,
                        // Aqui es donde el servidor comprueba `encuesta.crear`
                        // y `evento.crear`. Si no lo tenes, el mensaje queda
                        // FALLIDO con el motivo del servidor, como cualquier
                        // otro rechazo.
                        clase = m.especial,
                    )
                )
            }
            val fallo = registro.exceptionOrNull()
            if (fallo != null) {
                val api = fallo as? ApiError
                if (api != null && api.codigo in 400..499) {
                    // Rechazo definitivo: te sacaron del grupo, estas silenciado,
                    // te bloquearon. Reintentar no va a cambiar nada.
                    //
                    // Se usa `continue` y NO `return`: antes un mensaje rechazado
                    // para siempre bloqueaba toda la cola, incluidos los de otras
                    // conversaciones que si podian salir.
                    Log.w(TAG, "Rechazado ${m.id}: ${api.message}")
                    dao.marcarFallido(m.id, api.message)
                    _rechazos.tryEmit(api.message ?: "El servidor rechazo el mensaje.")
                    continue
                }
                // Fallo de red: el mensaje sigue PENDIENTE y se corta el bucle
                // para no romper el orden de envio.
                Log.w(TAG, "Sin red para registrar ${m.id}")
                return
            }
            registro.getOrNull()?.expiraEn?.let { vence ->
                dao.fijarVencimiento(m.id, vence)
            }

            // Los destinos se piden ANTES de cifrar: con E2EE hay un cuerpo
            // por dispositivo y no se puede cifrar sin saber para quien.
            val destinos = destinosDe(m.conversacionId)
            if (destinos == null) {
                Log.w(TAG, "Sin destinos para ${m.conversacionId}, el mensaje espera")
                return
            }

            // Si es grupo cambia el esquema de cifrado: clave de emisor en vez
            // de una sesion por dispositivo.
            val esGrupo = dao.conversacion(m.conversacionId)?.tipo == "grupo"
            val copias = runCatching { cifrador.cifrar(m.conversacionId, esGrupo, destinos, cargaDe(m)) }
                .getOrElse {
                    Log.w(TAG, "No se pudo cifrar ${m.id}: ${it.message}")
                    return
                }

            // Una conversacion donde nadie tiene claves publicadas todavia: el
            // mensaje se queda en cola. Es lo correcto: mandarlo sin cifrar
            // seria romper en silencio la promesa del producto.
            if (copias.isEmpty() && destinos.isNotEmpty()) {
                Log.w(TAG, "Nadie con sesion en ${m.conversacionId}; ${m.id} sigue en cola")
                _rechazos.tryEmit("Todavia no se pudo establecer el cifrado con esa persona.")
                return
            }

            val sobre = Sobre(
                id = m.id,
                conversacionId = m.conversacionId,
                origenDispositivo = sesion.dispositivoId.orEmpty(),
                creadoEn = m.creadoEn,
                copias = copias,
            )
            activo.entregar(sobre)
                .onFailure {
                    Log.w(TAG, "No se pudo entregar ${m.id}: ${it.message}")
                    return  // si falla uno, el resto espera: preserva el orden
                }
            // Recien con la entrega aceptada se da la clave de emisor por
            // repartida. Ver `Cifrador.confirmarEnvio`.
            cifrador.confirmarEnvio(m.conversacionId, destinos)
        }
    }

    /**
     * Que viaja dentro del sobre.
     *
     * Un adjunto no es "un texto con un archivo pegado": es otra carga, con la
     * clave del archivo adentro. El texto que la persona escribio va como pie.
     */
    private fun cargaDe(m: MensajeEnt): Carga =
        if (m.especialJson.isNotEmpty()) {
            // Sale tal como entro. No se vuelve a armar campo por campo: si se
            // armara, cualquier campo que la pantalla no hubiera copiado a la
            // base se perderia en silencio al enviar.
            jsonApp.decodeFromString(Carga.serializer(), m.especialJson)
        } else if (m.adjuntoId == null) {
            Carga.Texto(
                cuerpo = m.texto,
                respondeA = m.respondeA,
                respondeTexto = m.respondeTexto,
                respondeAutor = m.respondeAutor,
                reenviadoDe = m.reenviadoDe,
                historia = if (m.citaHistoriaId.isBlank()) null else CitaHistoria(
                    historiaId = m.citaHistoriaId,
                    clase = m.citaHistoriaClase,
                    previa = m.citaHistoriaTexto,
                    miniatura = m.citaHistoriaMiniatura,
                ),
            )
        } else {
            CargaAdjunto(
                adjuntoId = m.adjuntoId,
                clase = m.adjuntoClase,
                clave = m.adjuntoClave,
                nonce = m.adjuntoNonce,
                mime = m.adjuntoMime,
                nombre = m.adjuntoNombre,
                bytes = m.adjuntoBytes,
                ancho = m.adjuntoAncho,
                alto = m.adjuntoAlto,
                duracionMs = m.adjuntoDuracionMs,
                pie = m.texto,
                miniatura = m.adjuntoMiniatura,
            )
        }

    // ============================================================
    //  Cuenta y conversaciones
    // ============================================================

    suspend fun registrar(username: String, password: String, id: Hardware.Identidad, etiqueta: String) {
        val antes = sesion.usuarioId
        api.registrar(
            RegistroReq(
                username = username, password = password, etiquetaDispositivo = etiqueta,
                identidadPub = id.identidadPub, hardwareHash = id.hardwareHash, hardwareNivel = id.nivel,
            )
        )
        limpiarSiCambioDeCuenta(antes)
    }

    suspend fun login(
        username: String,
        password: String,
        id: Hardware.Identidad,
        /** Segundo factor. Nulo mientras no se sepa si la cuenta lo pide. */
        totp: String? = null,
    ) {
        val antes = sesion.usuarioId
        api.login(SesionReq(username, password, id.hardwareHash, totp))
        limpiarSiCambioDeCuenta(antes)
    }

    /**
     * Si entro OTRA cuenta en este aparato, la base local se va entera.
     *
     * Es la contraparte obligatoria de `Sesion.invalidar`, que conserva los
     * mensajes locales cuando el servidor tira el token para no perder el
     * historial en un cierre remoto. Conservar es correcto mientras vuelva la
     * MISMA persona; si vuelve otra, esos mensajes son de alguien mas y
     * mostrarselos seria filtrar conversaciones ajenas dentro de una app cuyo
     * argumento entero es el cifrado de punta a punta. Sirve de poco cifrar en
     * la red si la app se los ensena a quien entra despues en el mismo
     * telefono.
     *
     * Antes del modulo J esto no podia pasar: un hardware era una cuenta y
     * cerrar sesion borraba todo. Ahora un aparato puede pasar de una cuenta a
     * otra -se revoco el dispositivo, entra el duenio original-, asi que el
     * cambio hay que detectarlo y no suponerlo.
     */
    private suspend fun limpiarSiCambioDeCuenta(antes: String?) {
        val ahora = sesion.usuarioId
        if (antes == null || ahora == null || antes == ahora) return
        Log.i(TAG, "cuenta distinta en este aparato: se descarta lo local")
        dao.borrarVotos()
        dao.borrarMensajes()
        dao.borrarConversaciones()
    }

    suspend fun nuevaDirecta(username: String): String =
        api.crearDirecta(username).also { guardarResumen(it) }.id

    suspend fun nuevoGrupo(nombre: String, usernames: List<String>): String =
        api.crearGrupo(nombre, usernames).also { guardarResumen(it) }.id

    /** Borra el historial local de un chat. El servidor no guarda historial. */
    suspend fun vaciarChat(convId: String) {
        // Los votos primero: la consulta los busca por el id del mensaje, y si
        // los mensajes ya no estan no hay forma de saber cuales eran.
        dao.borrarVotosDe(convId)
        dao.borrarMensajesDe(convId)
    }

    // ============================================================
    //  Modulo B: acciones sobre un chat
    // ============================================================

    /**
     * Silenciar, archivar y fijar.
     *
     * Se manda al servidor y se re-sincroniza: no se toca la base local por
     * separado para que no queden dos verdades distintas si la peticion falla.
     */
    suspend fun preferencias(convId: String, p: PreferenciasChat) {
        api.preferencias(convId, p)
        sincronizar()
    }

    suspend fun bloquear(username: String) { api.bloquear(username); sincronizar() }
    suspend fun desbloquear(username: String) { api.desbloquear(username); sincronizar() }

    suspend fun miembros(convId: String) = api.miembros(convId)

    suspend fun agregarMiembros(convId: String, usernames: List<String>) =
        api.agregarMiembros(convId, usernames).also { sincronizar() }
    suspend fun rolesDe(convId: String) = api.rolesDe(convId)
    suspend fun configGrupo(convId: String) = api.configGrupo(convId)

    suspend fun guardarConfigGrupo(convId: String, cfg: ConfigGrupo): ConfigGrupo =
        api.guardarConfigGrupo(convId, cfg).also { sincronizar() }

    suspend fun cambiarRol(convId: String, usuarioId: String, rol: String) =
        api.cambiarRol(convId, usuarioId, rol)

    suspend fun expulsar(convId: String, usuarioId: String, vetar: Boolean) =
        api.expulsar(convId, usuarioId, "", vetar)

    suspend fun silenciarMiembro(convId: String, usuarioId: String, minutos: Int) =
        api.silenciarMiembro(convId, usuarioId, minutos)

    suspend fun crearInvitacion(convId: String, horas: Int, usosMax: Int) =
        api.crearInvitacion(convId, horas, usosMax)

    suspend fun solicitudes(convId: String) = api.solicitudes(convId)

    suspend fun resolverSolicitud(convId: String, usuarioId: String, aprobar: Boolean) =
        api.resolverSolicitud(convId, usuarioId, aprobar)

    /** Borra el chat de esta app y sale de la conversacion en el servidor. */
    suspend fun eliminarChat(convId: String) {
        runCatching { api.salir(convId) }
        dao.borrarMensajesDe(convId)
        dao.borrarConversacion(convId)
    }

    suspend fun salirDe(convId: String) {
        api.salir(convId)
        sincronizar()
    }

    suspend fun cerrarSesion() {
        // Se avisa al servidor ANTES de tirar el token, porque despues ya no
        // habria con que avisar. Hasta el modulo I esto no existia: "salir"
        // era solo local y el token seguia sirviendo noventa dias, asi que
        // cerrar sesion en un telefono prestado no cerraba nada.
        //
        // Y no se propaga el fallo: si el servidor no contesta, la sesion local
        // igual tiene que cerrarse. Quedarse dentro porque no hay red seria lo
        // contrario de lo que la persona pidio.
        runCatching { api.salir() }

        socket.desconectar()
        dao.borrarVotos()
        dao.borrarMensajes()
        dao.borrarConversaciones()
        sesion.limpiar()
    }
}

// ============================================================
//  Transportes
// ============================================================

/** Fase 2. Internet, por cualquier portadora. */
class TransporteWebSocket(private val socket: Socket) : Transporte {
    override val nombre = "websocket"
    override val prioridad = 0
    override fun disponible() = socket.estado.value == EstadoConexion.CONECTADO

    override suspend fun entregar(sobre: Sobre): Result<Unit> {
        val ok = socket.enviar(
            Subida.Enviar(
                sobreId = sobre.id,
                conversacionId = sobre.conversacionId,
                creadoEn = sobre.creadoEn,
                copias = sobre.copias,
            )
        )
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("socket cerrado"))
    }
}

/**
 * FASE 7 - `msg off`: entrega directa entre dispositivos cercanos por BLE o
 * Wi-Fi Direct, sin internet.
 *
 * Esta declarado y enchufado al despachador, pero `disponible()` devuelve
 * false: NO esta implementado. Se dejo cableado a proposito para que activarlo
 * sea implementar esta clase y nada mas — ni el despachador, ni la cola, ni la
 * UI, ni el esquema cambian.
 *
 * Lo que falta:
 *   1. Descubrimiento de pares (Nearby Connections o BLE + Wi-Fi Direct).
 *   2. Reenvio store-and-forward: un tercero transporta el sobre sin leerlo.
 *      Solo es seguro DESPUES de la fase 4: sin E2EE, el intermediario lee todo.
 *   3. Deduplicacion por id de sobre al reencontrarse con el servidor.
 *
 * NO se puede probar en emulador: no hay radios. Requiere dos equipos fisicos.
 */
class TransporteMalla : Transporte {
    override val nombre = "malla"
    override val prioridad = 10
    override fun disponible() = false
    override suspend fun entregar(sobre: Sobre): Result<Unit> =
        Result.failure(NotImplementedError("Transporte de malla: fase 7"))
}
