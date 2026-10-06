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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    /**
     * El almacen criptografico, solo para la COPIA DE SEGURIDAD.
     *
     * El repositorio no hace cripto -de eso se encarga `cifrador`- y esta es
     * la excepcion declarada: la copia tiene que poder guardar y devolver la
     * identidad Signal, y eso es leer y escribir una fila, no cifrar.
     *
     * Podria haber ido detras de `Cifrador`, y no se hizo porque
     * `CifradorPlano` -el de desarrollo- no tiene identidad ninguna, y la
     * interfaz habria ganado dos metodos que una de sus dos implementaciones
     * no puede cumplir. Con el DAO directo, "no hay identidad" es simplemente
     * una fila que no existe.
     */
    private val signalDao: SignalDao,
    private val archivos: ArchivosLocales,
    private val ajustes: Ajustes,
    private val ambito: CoroutineScope,
) {

    private val TAG = "Repo"

    /** Clave del tono de piel en `ajuste_local`. */
    private val CLAVE_TONO = "tono_piel"

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

    /**
     * Mensajeria sin internet, entre telefonos que estan cerca (modulo AZ).
     *
     * Ocupa el hueco que la fase 2 dejo reservado para esto. Ver
     * [TransporteCerca].
     *
     * Empieza APAGADO: prender la radio y aceptar conexiones de cualquiera que
     * pase no puede ser el comportamiento por defecto de una app de
     * mensajeria. Se enciende desde la pantalla, para la sala en la que uno
     * esta.
     */
    val cerca: TransporteCerca = TransporteCerca(contexto, sesion) { sobre ->
        recibirDeCerca(sobre)
    }

    /** Transportes por prioridad. */
    private val transportes: List<Transporte> = listOf(
        TransporteWebSocket(socket),
        cerca,
    ).sortedBy { it.prioridad }

    val conversaciones: Flow<List<ChatFila>> = dao.conversaciones(archivados = false)
    val archivadas: Flow<List<ChatFila>> = dao.conversaciones(archivados = true)

    /**
     * Todas, archivadas incluidas. Para BUSCAR un chat por id, no para
     * listarlos: la pantalla de un chat archivado se abria "vacia" -sin
     * titulo, sin menu de grupo, sin "Info"- porque lo buscaba entre las no
     * archivadas y no lo encontraba.
     */
    val todasLasConversaciones: Flow<List<ChatFila>> = dao.conversaciones(todas = true)
    val cuantosArchivados: Flow<Int> = dao.cuantosArchivados()
    val estadoConexion = socket.estado
    val tamanoCola: Flow<Int> = dao.tamanoCola()
    val tamanoFallidos: Flow<Int> = dao.tamanoFallidos()

    /** En que conversaciones quedaron, para que el aviso lleve a alguna parte. */
    val conversacionesConFallidos: Flow<List<String>> = dao.conversacionesConFallidos()

    /** Cual es, para que el aviso lleve al mensaje y no solo al chat. */
    suspend fun primerFallidoDe(conv: String): String? = dao.primerFallidoDe(conv)

    /** Mi propio perfil, para pintarlo en la cabecera y en la pantalla de perfil. */
    private val _miPerfil = MutableStateFlow<UsuarioPublico?>(null)
    val miPerfil = _miPerfil.asStateFlow()

    /**
     * Mis ajustes de privacidad, o **null si todavia no se pudieron leer**.
     *
     * ## Por que es nullable y antes no lo era
     *
     * Arrancaba en `Privacidad()`, o sea en los valores por DEFECTO, que son
     * los mas permisivos: foto, estado, nombre y biografia en "todos". Cuando
     * la lectura fallaba —sin red, servidor caido— nadie se enteraba y la
     * pantalla dibujaba esos defectos **como si fueran la configuracion de la
     * persona**.
     *
     * Medido: con la base diciendo `nadie` en cinco ajustes, la pantalla
     * mostraba **"Todos" en los cinco**. No "no se pudo cargar": lo contrario
     * de la verdad, en la pantalla cuyo unico trabajo es decir quien te ve.
     *
     * Y hay una segunda cara peor. Guardar manda **los quince campos** y el
     * servidor sobrescribe las quince columnas, asi que tocar un solo ajuste
     * partiendo de los defectos escribiria los otros catorce con los valores
     * permisivos. Con `null` eso deja de ser posible por construccion: no se
     * puede guardar lo que no se pudo leer.
     */
    private val _privacidad = MutableStateFlow<Privacidad?>(null)
    val privacidad = _privacidad.asStateFlow()

    /**
     * Lo que se asume mientras no se sepa. **Solo para decidir que MANDA este
     * aparato**, nunca para dibujar.
     *
     * Con `escribiendo = false`: si no se sabe si la persona permite el aviso
     * de "escribiendo", no se manda. Es una senal sobre ella, y ante la duda
     * no se emite. Lo contrario —asumir que si— filtra un dato propio por un
     * fallo de red.
     */
    private val PRIVACIDAD_PRUDENTE = Privacidad(escribiendo = false, lectura = false)


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
        /** Mandado "sin sonido": se notifica sin sonar ni vibrar. */
        val silencioso: Boolean = false,
        /** Me menciona (lo decidio el servidor): avisa aunque el chat este silenciado. */
        val mencionado: Boolean = false,
        /** Chat protegido: ni quien ni de donde. Ver `ConversacionEnt.protegido`. */
        val protegido: Boolean = false,
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
        if (!emiteEscribiendo(_privacidad.value)) return
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
    private suspend fun avisarMensaje(
        convId: String,
        autor: String,
        silencioso: Boolean = false,
        mencionado: Boolean = false,
    ) {
        if (chatVisible == convId) return
        val conv = dao.conversacion(convId) ?: return
        val silenciado = conv.silenciadoHasta == -1L ||
            conv.silenciadoHasta > System.currentTimeMillis()
        // Una mencion atraviesa el silencio del chat, como en WhatsApp: para
        // eso es. Quien decide si hubo mencion es el servidor, no el texto:
        // ver `Bajada.Entrega.mencionado`.
        if (silenciado && !mencionado) return
        _notificables.tryEmit(
            Notificable(
                tipo = if (conv.tipo == "canal") "canal" else "mensaje",
                conversacionId = convId,
                titulo = conv.nombreMostrado.ifBlank { conv.nombre },
                autor = autor,
                esGrupo = conv.tipo == "grupo",
                silencioso = silencioso,
                mencionado = mencionado,
                protegido = conv.protegido,
            )
        )
    }

    /**
     * Transcribe una nota de voz, en el telefono, y la guarda con el mensaje:
     * se hace una vez. Si todavia no se bajo, la baja primero. Lanza
     * `NoEnEsteTelefono` si no se puede sin red. Ver `Transcriptor`.
     */
    suspend fun transcribir(id: String) {
        var m = dao.mensaje(id) ?: return
        if (m.unaVez) return
        if (m.rutaLocal?.let { File(it).exists() } != true) {
            descargarAdjunto(id)
            m = dao.mensaje(id) ?: return
        }
        val archivo = m.rutaLocal?.let { File(it) }?.takeIf { it.exists() }
            ?: throw NoEnEsteTelefono("No se pudo bajar el audio.")
        dao.guardarTranscripcion(id, Transcriptor.transcribir(contexto, archivo))
    }

    /** Traduce al idioma del telefono, en el telefono. Ver `Traductor`. */
    suspend fun traducir(texto: String): String = Traductor.traducir(contexto, texto)

    /**
     * Manda lo que llego con "Compartir" desde otra app: el texto como un
     * mensaje y cada archivo como adjunto. Ver `Pedidos`.
     *
     * Los archivos salen en segundo plano, en el ambito del repositorio y no
     * en el de la pantalla: un video tarda, y cerrar la hoja no tiene que
     * cortar la subida. La copia ya esta hecha, asi que no depende del
     * permiso de la otra app.
     */
    suspend fun enviarCompartido(convId: String, texto: String?, adjuntos: List<File>) {
        texto?.takeIf { it.isNotBlank() }?.let { enviarTexto(convId, it) }
        for (f in adjuntos) {
            val uri = archivos.uriCompartible(f)
            val clase = Media.claseDe(contexto.contentResolver.getType(uri).orEmpty())
            ambito.launch {
                runCatching { enviarAdjunto(convId, uri, clase) }
                    .onFailure { _rechazos.tryEmit(it.message ?: "No se pudo enviar ${f.name}.") }
            }
        }
    }

    /**
     * Varias fotos o videos a la vez. Salen en el orden elegido y en segundo
     * plano, en el ambito del repositorio: diez videos tardan, y salir del
     * chat no tiene que cortarlos. El pie va con el primero.
     */
    fun enviarVarios(convId: String, uris: List<Uri>, pie: String, spoiler: Boolean = false) {
        ambito.launch {
            uris.forEachIndexed { i, uri ->
                val clase = Media.claseDe(contexto.contentResolver.getType(uri).orEmpty())
                runCatching { enviarAdjunto(convId, uri, clase, if (i == 0) pie else "", spoiler = spoiler) }
                    .onFailure { _rechazos.tryEmit(it.message ?: "No se pudo enviar uno de los archivos.") }
            }
        }
    }

    // --- Responder en privado ------------------------------------------------

    /**
     * La cita que espera en un chat que se esta por abrir: "Responder en
     * privado" crea o abre la directa y la deja aqui, y la pantalla del chat la
     * toma al abrirse. En memoria: es un paso de una pantalla a otra.
     */
    private val respuestasPendientes = java.util.concurrent.ConcurrentHashMap<String, MensajeEnt>()

    /**
     * Abre -o crea- la directa con quien escribio [m] en un grupo, con la cita
     * puesta. Devuelve el id del chat. Lanza si esa persona no deja que le
     * escriban: se aplica su "quien me escribe", como en cualquier directa.
     */
    suspend fun responderEnPrivado(m: MensajeEnt): String {
        val id = dao.directaCon(m.autor)?.id ?: nuevaDirecta(m.autor)
        respuestasPendientes[id] = m
        return id
    }

    fun tomarRespuestaPendiente(convId: String): MensajeEnt? = respuestasPendientes.remove(convId)

    /** El fondo de un chat; null = el general. Solo en este telefono. */
    suspend fun fijarFondo(convId: String, fondo: String?) = dao.fijarFondo(convId, fondo.orEmpty())

    // --- Destacados: solo en este telefono ------------------------------------

    fun destacados(convId: String = ""): Flow<List<MensajeEnt>> = dao.destacados(convId)

    suspend fun destacar(ids: Collection<String>, destacado: Boolean) =
        dao.fijarDestacado(ids.toList(), destacado)

    /** "Info del mensaje" de uno mio en un grupo. Ver `Mensajes.info` en el servidor. */
    suspend fun infoMensaje(id: String): InfoMensaje = api.infoMensaje(id)

    init {
        // Ver `Red`: con el proxy nuevo, el socket abierto sigue por el camino
        // viejo hasta que se cae. Se rehace en el momento.
        ambito.launch {
            Red.cambios.collect {
                val t = sesion.token ?: return@collect
                socket.desconectar()
                socket.conectar(t)
            }
        }
    }

    // --- Carpetas: ver `Carpetas` -------------------------------------------

    val carpetas: Flow<List<CarpetaEnt>> get() = dao.carpetas()
    val chatsEnCarpetas: Flow<List<CarpetaChatEnt>> get() = dao.chatsEnCarpetas()

    /** Crea una carpeta con esos chats adentro. Devuelve su id. */
    suspend fun crearCarpeta(nombre: String, chats: Collection<String>): String {
        val id = UUID.randomUUID().toString()
        dao.guardarCarpeta(CarpetaEnt(id, nombre.trim(), dao.ultimoOrdenCarpeta() + 1))
        chats.forEach { dao.meterEnCarpeta(CarpetaChatEnt(id, it)) }
        return id
    }

    /** Cambia el nombre y deja adentro exactamente [chats]. */
    suspend fun guardarCarpeta(id: String, nombre: String, chats: Collection<String>) {
        val c = dao.carpeta(id) ?: return
        dao.guardarCarpeta(c.copy(nombre = nombre.trim()))
        dao.vaciarCarpeta(id)
        chats.forEach { dao.meterEnCarpeta(CarpetaChatEnt(id, it)) }
    }

    suspend fun borrarCarpeta(id: String) {
        dao.vaciarCarpeta(id)
        dao.borrarCarpeta(id)
    }

    suspend fun ponerEnCarpeta(carpeta: String, conv: String, dentro: Boolean) {
        if (dentro) dao.meterEnCarpeta(CarpetaChatEnt(carpeta, conv)) else dao.sacarDeCarpeta(carpeta, conv)
    }

    // --- Chats protegidos ---------------------------------------------------

    /**
     * Los que se abrieron hace poco, hasta cuando: un minuto. Asi ir a la lista
     * y volver no pide la huella cada vez. En memoria a proposito: un reinicio
     * no tiene que recordarlo.
     */
    private val abiertosHasta = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun estaProtegido(id: String): Boolean = dao.estaProtegido(id) == true

    fun recienAbierto(id: String): Boolean = (abiertosHasta[id] ?: 0L) > System.currentTimeMillis()

    fun marcarAbierto(id: String) { abiertosHasta[id] = System.currentTimeMillis() + 60_000 }

    suspend fun proteger(id: String, protegido: Boolean) {
        dao.fijarProtegido(id, protegido)
        if (protegido) abiertosHasta.remove(id)
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
     * Si la app esta en pantalla. Lo fija `MainActivity` en onStart/onStop.
     *
     * ## Por que "chat abierto" no bastaba
     *
     * `chatAbierto` dice que pantalla esta arriba de la pila, no que alguien
     * la este mirando. Quien estaba en un chat y tocaba Inicio lo dejaba
     * "abierto" para siempre, y todo lo que llegaba a esa conversacion:
     *
     *  - **no notificaba**, porque "ya lo esta viendo";
     *  - **no sumaba no leidos**;
     *  - y mandaba un **acuse de lectura** falso: el otro veia la palomita
     *    cian de un mensaje que nadie habia mirado.
     *
     * Se vio en dos emuladores: chat abierto, Inicio, mensaje del otro lado,
     * cero notificaciones y un `GET .../leidos` del aparato en el bolsillo.
     */
    @Volatile private var enPantalla = false

    /** El chat que de verdad se esta viendo: abierto Y con la app en pantalla. */
    private val chatVisible: String? get() = if (enPantalla) chatAbierto else null

    fun alEntrarEnPantalla() {
        enPantalla = true
        // Si se vuelve a un chat que quedo abierto, es AHORA cuando se lee lo
        // que llego mientras tanto: se limpia el contador y se acusa.
        chatAbierto?.let { abrirChat(it) }
    }

    fun alSalirDePantalla() { enPantalla = false }

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

    /**
     * Escribe la conversacion en claro al flujo que eligio la persona.
     *
     * Devuelve el resumen para poder decir cuantos mensajes salieron y
     * -sobre todo- **cuantos temporales se dejaron fuera**. Ver
     * [ExportarChat], donde esta el por que.
     *
     * Antes de llamar aqui se barren los vencidos: exportar un mensaje que ya
     * tenia que haber desaparecido, solo porque nadie abrio el chat desde que
     * vencio, seria colarlo por la puerta de atras.
     */
    suspend fun exportarChatA(
        convId: String,
        salida: java.io.OutputStream,
    ): ExportarChat.Resumen = withContext(Dispatchers.IO) {
        dao.borrarVencidos(System.currentTimeMillis())

        val conv = dao.conversacion(convId)
        val mensajes = dao.todosLosMensajes(convId)
        // Los nombres que YO les puse: es como los reconozco. Si el chat se usa
        // como prueba, el username sigue estando en la cabecera del titulo.
        val gente = mensajes.map { it.autor }.filter { it.isNotBlank() }.distinct()
        val alias = if (gente.isEmpty()) emptyMap() else runCatching {
            nombresDeLibreta(gente)
        }.getOrDefault(emptyMap())

        val out = ExportarChat.construir(
            titulo = conv?.nombreMostrado?.takeIf { it.isNotBlank() }
                ?: conv?.nombre.orEmpty().ifEmpty { "(sin nombre)" },
            mensajes = mensajes,
            cuando = System.currentTimeMillis(),
            alias = alias,
        )
        salida.bufferedWriter().use { it.write(out.texto) }
        out.resumen
    }

    /** Borra los temporales vencidos. Se llama al abrir la app y cada chat. */
    suspend fun limpiarVencidos() = dao.borrarVencidos(System.currentTimeMillis())

    /**
     * Cuando vence un mensaje de esta conversacion. `0` = nunca.
     *
     * ## Por que lo calcula el cliente y no viene del servidor
     *
     * Porque **el servidor no puede hacerlo cumplir**. No tiene el mensaje: lo
     * entrega y borra el sobre. El unico que puede borrar esta copia es este
     * telefono, asi que este telefono es el que tiene que saber cuando.
     *
     * Podria haber viajado en la entrega, y se descarto por dos motivos. Uno:
     * el sobre se empuja por el socket mientras el metadato se registra por
     * HTTP, asi que en el momento del empuje el vencimiento puede no existir
     * todavia. Dos: el servidor BORRA los metadatos vencidos, asi que un
     * telefono que estuvo apagado una semana recibiria un vencimiento nulo —y
     * guardaria como permanente justamente el mensaje mas viejo—. El
     * temporizador de la conversacion, en cambio, esta siempre.
     *
     * ## El tope contra un reloj mentiroso
     *
     * La cuenta arranca en `creadoEn`, que es el reloj de QUIEN ESCRIBIO. Un
     * emisor con la hora adelantada -o que la adelanta a proposito- pondria un
     * `creadoEn` en el futuro y su mensaje "de 30 segundos" viviria dias en el
     * telefono ajeno. Por eso el resultado se topa con `ahora + segundos`:
     * nadie puede estirar el plazo mas alla de lo que este telefono acepto.
     *
     * Al reves no se corrige: si `creadoEn` viene del pasado el mensaje vence
     * antes, o incluso al instante. Es deliberado — un mensaje que llega tarde
     * YA es viejo, y equivocarse del lado de borrar es el lado correcto en el
     * que equivocarse.
     *
     * La cuenta vive en [Temporales], con pruebas: es la pieza que puede
     * fallar sin que se note.
     */
    private suspend fun vencimientoDe(convId: String, creadoEn: Long): Long =
        Temporales.vencimiento(
            segundos = dao.conversacion(convId)?.temporalesSegundos ?: 0,
            creadoEn = creadoEn,
            ahora = System.currentTimeMillis(),
        )

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
        // Lo de "ver una vez" que haya quedado en el disco. Ver `barrerUnaVez`.
        ambito.launch { barrerUnaVez() }
        // Y los programados que vencieron con la app cerrada y sin alarma -un
        // reinicio se las lleva-. Ver `Programados`.
        ambito.launch { runCatching { liberarProgramados() } }

        // El vigilante vive FUERA de `colectores` a proposito: si estuviera
        // dentro, detener() se cancelaria a si mismo a media ejecucion.
        if (vigilante?.isActive != true) {
            vigilante = ambito.launch {
                sesion.viva.collect { viva -> if (!viva) runCatching { detener() } }
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
            launch { runCatching { reanudarUbicacionEnVivo() } }
            // Los chats temporales vencidos, al arrancar.
            //
            // Hace falta ademas del aviso del servidor: si el telefono estuvo
            // apagado cuando vencio, ese aviso ya paso y nadie lo recibio. La
            // fecha esta guardada, asi que el reloj sigue corriendo aunque la
            // app no.
            launch { runCatching { barrerChatsVencidos() } }
            // Cada sobre se maneja dentro de su propio `runCatching`.
            //
            // Sin esto, UNA excepcion mata el `collect` y con el todo el canal
            // de entrada: no llega ni un mensaje mas, ni una llamada, ni un
            // acuse, hasta que alguien reinicie la app. Y el sintoma es
            // silencio, que es el peor de todos — no hay error, no hay aviso,
            // simplemente deja de llegar.
            //
            // Importa aqui mas que en otros sitios porque lo que viene dentro
            // de un sobre LO ESCRIBIO otra persona. El servidor no puede
            // abrirlo, asi que no valida nada de lo de dentro: un participante
            // con un cliente modificado elige exactamente los bytes que manda.
            // Con el bucle desprotegido, eso es una negacion de servicio
            // contra alguien concreto, y barata.
            //
            // `runCatching` y no `catch (e: Exception)`: un JSON muy anidado
            // tira `StackOverflowError`, que es un `Error` y no una
            // `Exception`. Ver `FuzzSobreTest`.
            launch {
                socket.entrantes.collect { entrante ->
                    runCatching { manejar(entrante) }
                        .onFailure { Log.e(TAG, "Sobre que no se pudo manejar: ${it.message}", it) }
                }
            }
            // Cada reconexion vacia la cola. Es el corazon del comportamiento offline.
            //
            // Protegido por el mismo motivo que el bucle de arriba, y aqui el
            // riesgo es MAS probable que el hostil: las tres llamadas salen a
            // la red. Un 500 pasajero del servidor, o un cuerpo que no parsea,
            // mataba el `collect` — y entonces la app no volvia a sincronizar
            // ni a vaciar su cola de salida en lo que durara el proceso. Los
            // mensajes escritos sin red se quedaban sin salir para siempre,
            // que es justo lo que el modo offline vino a evitar.
            launch {
                socket.conectado.collect {
                    runCatching {
                        cargarMiPerfil()
                        sincronizar()
                        despachar()
                    }.onFailure { Log.w(TAG, "Fallo al reconectar: ${it.message}") }
                }
            }
        }
    }

    /**
     * Cada vez que el socket (re)conecta.
     *
     * Se expone para que la interfaz pueda volver a preguntar por la version
     * publicada: publicar una exige reiniciar el servidor, un reinicio corta
     * todos los sockets, y esta es la senal que llega justo despues. Es lo que
     * hace innecesario un push para avisar de una actualizacion.
     */
    val reconectado get() = socket.conectado

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

        // La libreta entra en la sincronizacion normal: el alias de un contacto
        // es parte de como se ve la lista, y si solo se refrescara al abrir la
        // pestaña de Contactos, cambiarlo en otro aparato no llegaria nunca
        // aqui. Falla en silencio a proposito: sin libreta la lista sale con
        // usernames, que es exactamente lo que hacia antes.
        contactos()
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
                expiraEn = r.expiraEn,
                // `0` cuando el servidor dice `null`: permanentes. Ver
                // `ConversacionEnt.temporalesSegundos`.
                temporalesSegundos = r.temporalesSegundos ?: 0,
                // Lo que solo vive en este telefono se copia de la fila de
                // antes. `guardarConversacion` REEMPLAZA la fila entera, y sin
                // esto cada sincronizacion -cada vez que se abre la app- borraba
                // los borradores y la marca de "no leido". Se vio al agregar
                // `protegido`, que habria tenido el mismo final.
                borrador = previa?.borrador.orEmpty(),
                marcadaNoLeida = previa?.marcadaNoLeida ?: false,
                protegido = previa?.protegido ?: false,
                fondo = previa?.fondo.orEmpty(),
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

    /**
     * Guarda los ajustes. **Falla si no se habian leido antes.**
     *
     * La comprobacion parece de mas —la pantalla ya no ofrece los controles
     * sin datos— y es justo la que impide que el defecto vuelva por otra
     * puerta: cualquier pantalla futura que llame a esto con un objeto armado
     * a mano estaria escribiendo los quince campos sobre la configuracion real
     * de alguien.
     */
    suspend fun guardarPrivacidad(p: Privacidad) {
        check(_privacidad.value != null) {
            "No se pueden guardar ajustes de privacidad que nunca se leyeron."
        }
        _privacidad.value = api.guardarPrivacidad(p)
    }

    /** Reintenta leer los ajustes. Devuelve si se pudo. */
    suspend fun cargarPrivacidad(): Boolean =
        runCatching { api.privacidad() }.onSuccess { _privacidad.value = it }.isSuccess

    suspend fun guardarPerfil(nombre: String, estado: String) {
        _miPerfil.value = api.guardarPerfil(nombre, estado)
    }

    suspend fun subirImagen(campo: String, bytes: ByteArray) {
        _miPerfil.value = api.subirImagen(campo, bytes)
    }

    // ============================================================
    //  Entrada
    // ============================================================

    /**
     * Un sobre que llego por el aire.
     *
     * ## Entra por el MISMO camino que los del buzon
     *
     * Se arma una `Bajada.Entrega` y se pasa al manejador de siempre. No es
     * pereza: un segundo camino de entrada seria un segundo sitio donde
     * equivocarse con contenido que escribio otra persona, y el primero ya
     * tiene la deduplicacion, el descifrado, el guardado y los avisos.
     *
     * ## Lo que se comprueba ANTES
     *
     * Que el sobre continue una sesion que ya existe. Por el aire no hay
     * ninguna cuenta detras: un mensaje que ABRE sesion dejaria a cualquiera
     * con una radio aparecer en esta pantalla con el nombre que quisiera. Ver
     * `aceptable`.
     *
     * @return si se acepto. El transporte no hace nada con eso todavia, pero
     *   lo devuelve para que un rechazo se pueda contar y no sea invisible.
     */
    private suspend fun recibirDeCerca(s: com.wtfuck.protocol.MensajeCerca.Sobre): Boolean {
        val hay = cifrador.haySesionCon(s.origenUsuarioId, s.origenDispositivo)
        if (!com.wtfuck.protocol.aceptable(s.tipo, hay)) {
            Log.w(TAG, "Sobre de cerca rechazado: tipo=${s.tipo} sesion=$hay")
            return false
        }
        manejar(
            Bajada.Entrega(
                sobreId = s.sobreId,
                mensajeId = s.mensajeId,
                conversacionId = s.conversacionId,
                origenUsuarioId = s.origenUsuarioId,
                origenUsername = s.origenUsername,
                origenDispositivo = s.origenDispositivo,
                cuerpo = s.cuerpo,
                tipo = s.tipo,
                creadoEn = s.creadoEn,
            )
        )
        return true
    }

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
                        llamadas.finEntrante(msg.origenDispositivo, carga)
                        socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                        return
                    }
                    is Carga.LlamadaPantalla -> {
                        llamadas.pantallaEntrante(msg.origenDispositivo, carga)
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

                // Una posicion nueva no crea un mensaje: pisa la carga del
                // que abrio el compartido.
                //
                // ## Como se distingue una de otro, sin un campo mas
                //
                // El sobre que ABRE el compartido lleva su propio id como
                // `mensajeId` de la carga —es el mismo mensaje— y los de las
                // actualizaciones no, porque cada uno es un sobre aparte que
                // apunta al primero. Comparar los dos ids es la diferencia, y
                // no hizo falta inventar una bandera para decirla.
                //
                // Se vio en el emulador: sin esta comparacion la rama se
                // tragaba TAMBIEN el mensaje que abre, la burbuja no llegaba
                // a crearse del otro lado y el compartido parecia no salir.
                val esActualizacion =
                    carga is Carga.UbicacionEnVivo && msg.mensajeId.isNotBlank() &&
                        msg.mensajeId != carga.mensajeId
                if (carga is Carga.UbicacionEnVivo && esActualizacion) {
                    aplicarUbicacionEnVivo(carga)
                    socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
                    return
                }

                if (carga is Carga.UbicacionEnVivoFin) {
                    marcarVivaTerminada(carga.mensajeId)
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
                    // Un "ver una vez" no trae pie aunque lo traiga: se
                    // quedaria en el chat, la lista y la notificacion.
                    is CargaAdjunto -> if (carga.unaVez) "" else carga.pie
                    // El resumen se calcula aqui, al recibir, y se guarda en
                    // `texto`. Asi la lista de chats y el buscador leen una
                    // columna de texto como con cualquier mensaje, en vez de
                    // deserializar la carga de cada fila para armar un titulo.
                    is Carga.Ubicacion, is Carga.Contacto,
                    is Carga.Encuesta, is Carga.Evento,
                    is Carga.UbicacionEnVivo -> resumenDe(carga)
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
                val silenciosoEntrante = cita?.silencioso == true || adj?.silencioso == true

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
                // Lo que escribi en OTRO aparato mio. Con varios aparatos, cada
                // mensaje que mando le llega tambien a mis otros -son destinos
                // como cualquiera-, y se guardaba como si lo hubiera escrito
                // otra persona que se llama como yo: a la izquierda, sumando
                // "no leidos" y con notificacion. Con la "Nota para mi" eso es
                // todo lo que pasa en ese chat, y ahi se vio.
                val deMiOtroAparato = msg.origenUsername.equals(sesion.username, ignoreCase = true)
                val filas = dao.guardarMensaje(
                    MensajeEnt(
                        // El id del MENSAJE, no el de la fila del buzon. Son
                        // dos cosas: ver `Bajada.Entrega.mensajeId`. Guardar el
                        // del buzon hacia que en un grupo el mismo mensaje
                        // tuviera un id distinto en cada telefono.
                        id = msg.mensajeId.ifBlank { msg.sobreId },
                        conversacionId = msg.conversacionId,
                        autor = msg.origenUsername,
                        esMio = deMiOtroAparato,
                        texto = texto,
                        creadoEn = msg.creadoEn,
                        // Si es mio, ya salio: el servidor lo acepto antes de
                        // traermelo.
                        estado = if (deMiOtroAparato) EstadoEnvio.ENVIADO.name else EstadoEnvio.ENTREGADO.name,
                        silencioso = silenciosoEntrante,
                        previaJson = previaAceptable(cita?.previa, texto),
                        respondeA = cita?.respondeA,
                        respondeTexto = cita?.respondeTexto,
                        respondeAutor = cita?.respondeAutor,
                        reenviadoDe = cita?.reenviadoDe ?: adj?.reenviadoDe,
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
                        adjuntoMiniatura = if (adj?.unaVez == true) "" else adj?.miniatura.orEmpty(),
                        unaVez = adj?.unaVez == true,
                        spoiler = adj?.spoiler == true &&
                            (adj.clase == ClaseAdjunto.IMAGEN || adj.clase == ClaseAdjunto.VIDEO),
                        // Solo las formas que se saben dibujar: el resto es un
                        // video normal. Lo escribio otra persona.
                        adjuntoForma = adj?.forma?.takeIf { it == "circulo" && adj.clase == ClaseAdjunto.VIDEO }.orEmpty(),
                        // Se guarda tal cual vino. Que sea una figura
                        // dibujable lo decide quien la dibuja, con
                        // `Onda.decodificar`: esto lo escribio otra persona y
                        // aqui no se valida nada que no haga falta validar.
                        adjuntoOnda = adj?.onda.orEmpty(),
                        adjuntoEstado = if (adj != null) "ESPERA" else "",
                        // El vencimiento lo pone QUIEN RECIBE, aqui. Ver
                        // `vencimientoDe`: sin esta linea el mensaje temporal
                        // se borraba en el telefono de quien lo escribio y se
                        // quedaba para siempre en el de quien lo leyo.
                        expiraEn = vencimientoDe(msg.conversacionId, msg.creadoEn),
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
                // Un "ver una vez" no se baja solo: el archivo descifrado no
                // espera en el disco a que alguien decida abrirlo.
                if (filas != -1L && adj != null && !adj.unaVez && ajustes.descargaSola(adj.clase, adj.bytes)) {
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
                if (filas != -1L && chatVisible == msg.conversacionId) {
                    runCatching { sincronizarLecturas(msg.conversacionId) }
                }
                if (filas != -1L && chatVisible != msg.conversacionId && !deMiOtroAparato) {
                    dao.sumarNoLeido(msg.conversacionId)
                    // El mismo `filas != -1L` que evita contar dos veces evita
                    // notificar dos veces: el buzon reentrega lo no acusado, y
                    // sin esto un mensaje repetido sonaba de nuevo.
                    avisarMensaje(msg.conversacionId, msg.origenUsername, silenciosoEntrante, msg.mencionado)
                }

                // Acusar BORRA el sobre del servidor. Solo despues de guardarlo
                // localmente: si se acusa antes y la app muere, el mensaje se pierde.
                socket.enviar(Subida.Acuse(listOf(msg.sobreId)))
            }

            is Bajada.Evento -> manejarEvento(msg)

            is Bajada.Aceptado -> {
                dao.estado(msg.sobreId, EstadoEnvio.ENVIADO.name)
                // Un "ver una vez" mio no se queda en mi telefono: tampoco yo
                // lo vuelvo a ver, como en WhatsApp y Signal. Hasta aqui hacia
                // falta, para cifrarlo y para reintentar.
                dao.mensaje(msg.sobreId)?.takeIf { it.unaVez && it.esMio }?.let { soltarUnaVez(it) }
                // La hora del servidor NO reemplaza la de mi mensaje -es la de
                // autoria, y si lo escribi sin red tiene que seguir siendo de
                // cuando lo escribi-. Sirve para afinar el desfase del reloj.
                Reloj.observar(msg.servidorEn)
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
                if (emiteEscribiendo(_privacidad.value)) {
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
            "llamada_participante" -> {
                // Lo que le paso a UNA persona de una llamada que SIGUE viva:
                // rechazo, entro, se fue. No cierra nada —un
                // `llamada_terminada` colgaria— y por eso es un tipo aparte.
                //
                // El actor del evento es quien cambio; el detalle trae la
                // llamada y el estado.
                runCatching {
                    val o = jsonApp.parseToJsonElement(e.detalle.orEmpty()).jsonObject
                    llamadas.participanteCambio(
                        o["llamada"]?.jsonPrimitive?.content.orEmpty(),
                        e.actor,
                        o["estado"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
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
            // Ver V47: lo abrio quien lo recibio, o yo desde otro aparato.
            "una_vez_abierta" -> {
                val id = e.detalle.orEmpty()
                dao.mensaje(id)?.let { m ->
                    when {
                        m.esMio -> dao.miUnaVezAbierta(id)
                        !m.unaVezAbierta -> {
                            dao.abrirUnaVez(id)
                            soltarUnaVez(m)
                        }
                    }
                }
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
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
            // Alguien cambio el temporizador de mensajes.
            //
            // Se aplica EN EL ACTO y no en la proxima sincronizacion, porque
            // hasta que este telefono lo sepa todo lo que llegue se guarda como
            // permanente. Cada minuto de retraso es un mensaje que prometia
            // borrarse y no lo hace.
            //
            // No toca los mensajes que YA estan, igual que en Signal: el
            // temporizador rige lo que se escriba de aqui en adelante. Aplicarlo
            // hacia atras convertiria "activar los temporales" en "borrar el
            // historial", que es otra accion y nadie la pidio.
            //
            // Sigue de largo -sin `return`- para que caiga en la linea de
            // sistema de mas abajo: que el temporizador cambio tiene que quedar
            // ESCRITO en el chat. Es la diferencia entre una funcion de
            // privacidad y una trampa; si se pudiera apagar en silencio, nadie
            // podria confiar en que sigue encendido.
            "conversacion_temporales" ->
                dao.fijarTemporales(
                    e.conversacionId, e.detalle.orEmpty().trim().toIntOrNull() ?: 0,
                )
            // Me sacaron: se marca de inmediato, sin esperar la proxima
            // sincronizacion. La linea de sistema se agrega mas abajo.
            "expulsado", "sacado_grupo" -> dao.marcarFuera(e.conversacionId)
            // Un chat temporal vencio. Se borra AQUI y ahora.
            //
            // El barrido del arranque cubre al telefono que estaba apagado;
            // esto cubre al que esta mirando la pantalla. Sin los dos, o el
            // chat se queda visible hasta reiniciar, o solo desaparece para
            // quien tuvo la mala suerte de reiniciar.
            //
            // No deja linea de sistema: seria una linea en un chat que acaba
            // de dejar de existir.
            "conversacion_vencida" -> {
                // El id viene en el DETALLE y no en `conversacionId`, y no es
                // un capricho: el aviso se emite despues de borrar la fila, y
                // la columna apunta a `conversacion` con ON DELETE CASCADE —
                // con el id ahi, el propio borrado se llevaria el aviso por
                // delante y quien estuviera apagado no se enteraria nunca.
                val cual = runCatching {
                    jsonApp.parseToJsonElement(e.detalle.orEmpty())
                        .jsonObject["conversacion"]?.jsonPrimitive?.content
                }.getOrNull() ?: e.conversacionId
                runCatching { vaciarChat(cual) }
                runCatching { dao.borrarConversacion(cual) }
                Notificaciones.quitarSonidoPropio(contexto, cual)
                socket.enviar(Subida.AcuseEvento(listOf(e.eventoId)))
                return
            }
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
            "conversacion_temporales" ->
                (e.detalle.orEmpty().trim().toIntOrNull() ?: 0).let { s ->
                    if (s > 0) {
                        "@${e.actor} puso los mensajes temporales en ${DuracionMensaje.texto(s)}"
                    } else {
                        "@${e.actor} desactivo los mensajes temporales"
                    }
                }
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
        if (chatVisible != e.conversacionId) dao.sumarNoLeido(e.conversacionId)

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
        /** "Enviar sin sonido". Ver `Carga.Texto.silencioso`. */
        silencioso: Boolean = false,
        /** La vista previa ya armada del primer enlace, si la hay. */
        previa: VistaPreviaEnlace? = null,
        /** Si es > 0, no sale ahora sino a esa hora. Ver `Programados`. */
        programadoPara: Long = 0,
    ) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return
        val programado = programadoPara > 0

        val m = MensajeEnt(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            autor = sesion.username.orEmpty(),
            esMio = true,
            texto = limpio,
            creadoEn = horaParaMio(convId),
            estado = EstadoEnvio.PENDIENTE.name,
            silencioso = silencioso,
            previaJson = previa?.takeIf { it.url in limpio }
                ?.let { jsonApp.encodeToString(VistaPreviaEnlace.serializer(), it) }.orEmpty(),
            // El id solo si la cita es de ESTE chat. "Responder en privado"
            // cita un mensaje de un grupo, y el servidor rechaza -con razon-
            // un respondeA de otra conversacion: viaja solo la copia de la
            // cita, dentro del sobre, que es lo que se dibuja.
            respondeA = respondeA?.id?.takeIf { respondeA.conversacionId == convId },
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
        ).let {
            if (!programado) it
            // Oculto y fuera de la cola hasta su hora. `creadoEn` queda en la
            // de ahora y no en la programada: una hora futura en un mensaje
            // oculto empujaria hacia adelante la de todo lo que escriba
            // despues. Ver `horaParaMio`.
            else it.copy(estado = Programados.ESTADO, oculto = true, programadoPara = programadoPara)
        }
        dao.guardarMensaje(m)
        if (programado) rearmarProgramados() else despachar()
    }

    // --- Mensajes programados ------------------------------------------------

    fun programadosDe(convId: String): Flow<List<MensajeEnt>> = dao.programadosDe(convId)

    /**
     * Los que ya tocan, a la cola. Con la hora de AHORA, que es cuando
     * salen: mostrar la que se programo seria mentir si el telefono estuvo
     * apagado una hora.
     */
    suspend fun liberarProgramados() {
        // La misma alarma despierta a los recordatorios. Ver `rearmarProgramados`.
        runCatching { dispararRecordatorios() }
        val vencidos = dao.programadosVencidos(System.currentTimeMillis())
        for (m in vencidos) dao.liberarProgramado(m.id, horaParaMio(m.conversacionId))
        if (vencidos.isNotEmpty()) {
            Log.i(TAG, "${vencidos.size} programados a la cola")
            despachar()
        }
        rearmarProgramados()
    }

    suspend fun enviarProgramadoYa(id: String) {
        val m = dao.mensaje(id) ?: return
        dao.liberarProgramado(id, horaParaMio(m.conversacionId))
        despachar()
        rearmarProgramados()
    }

    suspend fun cancelarProgramado(id: String) {
        dao.cancelarProgramado(id)
        rearmarProgramados()
    }

    /**
     * Una sola alarma para programados y recordatorios: la de lo que toque
     * primero. Cuando suena, se atienden los dos (`liberarProgramados`).
     */
    private suspend fun rearmarProgramados() {
        val proximo = listOfNotNull(dao.proximoProgramado(), dao.proximoRecordatorio()).minOrNull()
        runCatching { Programados.armar(contexto, proximo) }
            .onFailure { Log.w(TAG, "No se pudo armar la alarma: ${it.message}") }
    }

    // --- Recordatorios: ver `RecordatorioEnt` -----------------------------------

    fun recordatoriosDe(convId: String): Flow<List<RecordatorioEnt>> = dao.recordatoriosDe(convId)

    suspend fun recordar(m: MensajeEnt, cuando: Long) {
        dao.guardarRecordatorio(RecordatorioEnt(m.id, m.conversacionId, cuando))
        rearmarProgramados()
    }

    suspend fun quitarRecordatorio(mensajeId: String) {
        dao.borrarRecordatorio(mensajeId)
        rearmarProgramados()
    }

    /** Los que ya tocan: una notificacion cada uno, y fuera de la lista. */
    private suspend fun dispararRecordatorios() {
        for (r in dao.recordatoriosVencidos(System.currentTimeMillis())) {
            val m = dao.mensaje(r.mensajeId)
            val conv = dao.conversacion(r.conversacionId)
            val texto = when {
                m == null || m.retirado -> "El mensaje ya no está"
                m.adjuntoClase.isNotBlank() -> Media.resumen(m.adjuntoClase, m.texto, m.adjuntoNombre)
                else -> m.texto
            }
            Notificaciones.recordatorio(
                contexto,
                conversacionId = r.conversacionId,
                mensajeId = r.mensajeId,
                donde = conv?.let { it.nombreMostrado.ifBlank { it.nombre } } ?: "un chat",
                texto = texto,
                protegido = conv?.protegido == true,
            )
            dao.borrarRecordatorio(r.mensajeId)
        }
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
                creadoEn = horaParaMio(convId),
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
        /** Ver [Carga.Ubicacion.conMapa]: lo decide quien la manda. */
        conMapa: Boolean = false,
    ) = encolarEspecial(
        convId,
        ClaseContenido.UBICACION,
        Carga.Ubicacion(lat, lon, precisionM, etiqueta.trim().take(80), conMapa),
    )

    // ============================================================
    //  Modulo AM: ubicacion en tiempo real
    // ============================================================
    //
    // ## Lo que este modulo NO agrega al servidor
    //
    // Nada. Ni una tabla, ni una ruta, ni una caducidad en la base. Las
    // actualizaciones son sobres cifrados como cualquier mensaje y el
    // vencimiento viaja DENTRO de la carga, asi que el servidor no puede
    // saber donde esta nadie ni hasta cuando. Lo unico que cambio alli fue
    // agregar dos nombres a la lista de clases validas.
    //
    // Es exactamente lo que el buzon tonto compra: la funcion mas sensible de
    // la app es la que menos le pide al servidor.

    /**
     * Empieza a compartir la ubicacion, hasta `hasta`.
     *
     * Devuelve el id del mensaje, que es tambien el del compartido: las
     * actualizaciones lo usan para encontrar su burbuja.
     */
    suspend fun iniciarUbicacionEnVivo(
        convId: String,
        lat: Double,
        lon: Double,
        precisionM: Int,
        hasta: Long,
        /** Ver [Carga.UbicacionEnVivo.conMapa]: lo decide quien comparte. */
        conMapa: Boolean = false,
    ): String {
        // Primero se cierran los MIOS que sigan vivos, en cualquier chat.
        //
        // El servicio ya cerraba el anterior, pero solo el que el recordaba:
        // si la app se habia cerrado en el medio, el servicio arrancaba en
        // blanco y el compartido viejo quedaba huerfano —"en vivo" con un
        // punto congelado hasta 24 horas—. Se vio en el emulador con dos
        // burbujas contando a la vez.
        //
        // Va aqui y no en el servicio porque esto no depende de que nada
        // siga corriendo: la verdad de que hay un compartido vivo esta en la
        // base, no en la memoria de un proceso que pueden matar.
        for (viejo in vivosMios()) {
            runCatching { terminarUbicacionEnVivo(viejo.conversacionId, viejo.id) }
        }

        val id = UUID.randomUUID().toString()
        val carga = Carga.UbicacionEnVivo(
            mensajeId = id, lat = lat, lon = lon,
            precisionM = precisionM, hasta = hasta, secuencia = 0,
            conMapa = conMapa,
        )
        val ahora = System.currentTimeMillis()
        dao.guardarMensaje(
            MensajeEnt(
                id = id,
                conversacionId = convId,
                autor = sesion.username.orEmpty(),
                esMio = true,
                texto = resumenDe(carga),
                creadoEn = horaParaMio(convId),
                estado = EstadoEnvio.PENDIENTE.name,
                especial = ClaseContenido.UBICACION_VIVA,
                especialJson = jsonApp.encodeToString(
                    Carga.serializer(),
                    carga.copy(
                        recibidaEn = ahora,
                        estela = Geo.conPunto(emptyList(), lat, lon, ahora),
                    ),
                ),
            )
        )
        despachar()
        return id
    }

    /**
     * Una posicion nueva del mismo compartido.
     *
     * Viaja como sobre OCULTO, igual que un voto: tiene que pasar por la cola,
     * reintentarse sin red y respetar el orden, pero no es algo que nadie haya
     * dicho en la conversacion.
     */
    suspend fun actualizarUbicacionEnVivo(
        convId: String,
        mensajeId: String,
        lat: Double,
        lon: Double,
        precisionM: Int,
        secuencia: Int,
    ) {
        // El `hasta` sale de lo que ya esta guardado y no de quien llama: es
        // el compartido el que tiene fecha, no cada posicion. Pasarlo por
        // parametro dejaria que un error de la app extendiera un compartido
        // sin que nadie lo decidiera.
        val actual = vivaGuardada(mensajeId) ?: return
        if (actual.hasta <= 0L) return  // ya se termino a mano

        val ahora = System.currentTimeMillis()
        val carga = actual.copy(
            lat = lat, lon = lon, precisionM = precisionM, secuencia = secuencia,
        )
        // Primero la fila propia: quien comparte tiene que ver su posicion
        // moverse aunque la red este caida.
        dao.actualizarEspecial(
            mensajeId,
            ClaseContenido.UBICACION_VIVA,
            jsonApp.encodeToString(
                Carga.serializer(),
                carga.copy(
                    recibidaEn = ahora,
                    estela = Geo.conPunto(actual.estela, lat, lon, ahora),
                ),
            ),
        )
        encolarEspecial(convId, ClaseContenido.UBICACION_VIVA, carga.paraLaRed(), oculto = true)
    }

    /**
     * Deja de compartir antes de tiempo.
     *
     * La fecha ya caduca sola, asi que esto no es imprescindible para que la
     * otra pantalla deje de mostrarla en vivo. Lo es para que deje de
     * mostrarla YA: sin el aviso, quien corta a los dos minutos de un
     * compartido de ocho horas seguiria apareciendo en vivo casi ocho horas
     * con una posicion congelada.
     */
    suspend fun terminarUbicacionEnVivo(convId: String, mensajeId: String) {
        marcarVivaTerminada(mensajeId)
        encolarEspecial(
            convId,
            ClaseContenido.UBICACION_VIVA_FIN,
            Carga.UbicacionEnVivoFin(mensajeId),
            oculto = true,
        )
    }

    /**
     * Al arrancar: si habia un compartido en curso, se vuelve a arrancar.
     *
     * ## Por que reanudar y no cerrar
     *
     * En las llamadas, `recuperar()` cierra lo que encuentra: una sesion
     * WebRTC murio con el proceso y no se retoma. Aqui es al reves. Quien
     * pidio compartir OCHO HORAS no pidio "ocho horas o hasta que Android
     * mate la app", y nada se perdio con el proceso: la posicion se vuelve a
     * leer del GPS y la fecha sigue guardada.
     *
     * Cerrarlo seria mas facil y seria peor: el compartido largo es
     * justamente el que mas tiempo pasa con la app en segundo plano, o sea el
     * que mas probabilidades tiene de que lo maten.
     *
     * ## Solo el mas nuevo
     *
     * Si quedaron varios —de una version anterior, o de un cierre a
     * destiempo—, se reanuda uno y se cierran los demas. Es la misma regla de
     * `iniciarUbicacionEnVivo`: un compartido a la vez, porque la
     * notificacion tambien es una sola.
     */
    private suspend fun reanudarUbicacionEnVivo() {
        val vivos = vivosMios()
        if (vivos.isEmpty()) return
        for (sobrante in vivos.drop(1)) {
            runCatching { terminarUbicacionEnVivo(sobrante.conversacionId, sobrante.id) }
        }
        val fila = vivos.first()
        val carga = vivaGuardada(fila.id) ?: return
        ServicioUbicacionViva.arrancar(contexto, fila.conversacionId, fila.id, carga.hasta)
    }

    /**
     * Mis compartidos que TODAVIA valen.
     *
     * Vivo es `hasta > ahora`. El cero de "cortado a mano" queda fuera solo,
     * que es justo lo que se queria de usar cero como estado.
     */
    suspend fun vivosMios(): List<MensajeEnt> =
        dao.misCompartidosDeUbicacion().filter { fila ->
            val c = runCatching {
                jsonApp.decodeFromString(Carga.serializer(), fila.especialJson)
            }.getOrNull() as? Carga.UbicacionEnVivo
            c != null && c.hasta > System.currentTimeMillis()
        }

    /** La carga guardada de un compartido, o null si no esta o no es de esta clase. */
    suspend fun vivaGuardada(mensajeId: String): Carga.UbicacionEnVivo? {
        val fila = dao.mensaje(mensajeId) ?: return null
        if (fila.especial != ClaseContenido.UBICACION_VIVA) return null
        return runCatching {
            jsonApp.decodeFromString(Carga.serializer(), fila.especialJson)
        }.getOrNull() as? Carga.UbicacionEnVivo
    }

    /**
     * Marca un compartido como terminado poniendo `hasta = 0`.
     *
     * Cero y no "ahora": una actualizacion que venia en camino puede llegar
     * DESPUES del final, y con `hasta = ahora` bastaria un reloj un segundo
     * atrasado para revivirla. Cero no es una fecha, es un estado, y ninguna
     * comparacion lo confunde con el futuro.
     */
    private suspend fun marcarVivaTerminada(mensajeId: String) {
        val actual = vivaGuardada(mensajeId) ?: return
        dao.actualizarEspecial(
            mensajeId,
            ClaseContenido.UBICACION_VIVA,
            jsonApp.encodeToString(Carga.serializer(), actual.copy(hasta = 0L)),
        )
    }

    /**
     * Aplica una posicion que llego de otro aparato.
     *
     * Descarta dos cosas, y las dos pasan de verdad:
     *
     *  - la que llega **sin su mensaje**: los sobres se reintentan y no hay
     *    garantia de orden entre reintentos, asi que una actualizacion puede
     *    adelantarse al mensaje que abre el compartido. Se descarta en vez de
     *    crear una fila suelta que la pantalla no sabria a que atar;
     *  - la que llega **vieja**: por lo mismo, un reintento tardio moveria el
     *    punto hacia atras en el tiempo.
     */
    private suspend fun aplicarUbicacionEnVivo(carga: Carga.UbicacionEnVivo) {
        val actual = vivaGuardada(carga.mensajeId) ?: return
        if (actual.hasta <= 0L) return
        if (carga.secuencia <= actual.secuencia) return
        val ahora = System.currentTimeMillis()
        dao.actualizarEspecial(
            carga.mensajeId,
            ClaseContenido.UBICACION_VIVA,
            jsonApp.encodeToString(
                Carga.serializer(),
                // La estela sale de LA FILA y no de lo que llego: lo que llega
                // la trae vacia siempre (ver `soloDeLaRed`), asi que copiar la
                // carga entrante tal cual borraria el recorrido en cada
                // actualizacion y la estela no pasaria nunca de un punto.
                carga.copy(
                    recibidaEn = ahora,
                    estela = Geo.conPunto(actual.estela, carga.lat, carga.lon, ahora),
                ),
            ),
        )
    }


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
        val escapado = paraLike(consulta) ?: return emptyList()
        // No se deja propagar: buscar es una comodidad, y una consulta que
        // falle tiene que devolver "sin resultados", no cerrar la app. Paso
        // exactamente eso con un `ESCAPE` de dos caracteres.
        return runCatching { dao.buscarEn(convId, escapado) }
            .onFailure { Log.w(TAG, "Fallo la busqueda en $convId: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * Buscar en TODO el historial de este telefono.
     *
     * Solo aqui se puede: el servidor guarda sobres opacos y no podria
     * ofrecerlo ni queriendo. Es la misma razon por la que el panel de
     * administracion no busca mensajes.
     */
    suspend fun buscarEnTodo(consulta: String): List<ResultadoBusqueda> {
        val escapado = paraLike(consulta) ?: return emptyList()
        return runCatching { dao.buscarEnTodo(escapado) }
            .onFailure { Log.w(TAG, "Fallo la busqueda global: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * Prepara una consulta para `LIKE`, o `null` si no vale la pena buscarla.
     *
     * Compartida por las dos busquedas a proposito. Estaba escrita dentro de
     * `buscarEnChat`, y al agregar la global la tentacion era copiarla: dos
     * copias de un escapado son como una de las dos se queda sin arreglar el
     * dia que aparezca el tercer comodin.
     *
     * `_` y `%` son comodines: sin escaparlos, buscar "100%" o "a_b"
     * devolveria cualquier cosa. La barra se escapa PRIMERO — si no, volveria
     * a escapar las barras que acaban de anadir los otros dos reemplazos.
     *
     * Menos de dos caracteres no se busca: una sola letra devuelve el
     * historial entero recortado a 200, que no es un resultado sino ruido.
     */
    private fun paraLike(consulta: String): String? {
        val q = consulta.trim()
        if (q.length < 2) return null
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    /** La posicion de un mensaje en la lista, para saltar a el desde la busqueda. */
    suspend fun posicionDe(convId: String, m: MensajeEnt): Int =
        (dao.posicionDe(convId, m.creadoEn, m.id) - 1).coerceAtLeast(0)

    /** M.3 · Marcar un chat como no leido. Se apaga al abrirlo. */
    suspend fun marcarNoLeida(convId: String) = dao.marcarNoLeida(convId)

    /**
     * Baja el contador de no leidos SIN mandar acuse de lectura.
     *
     * ## Por que no llama a [abrirChat]
     *
     * Porque no es lo mismo. `abrirChat` manda el acuse, y lo hace porque
     * abrir el chat **es** leerlo: es el unico momento en que se puede afirmar
     * que alguien vio los mensajes. Esto de aqui lo usa "marcar como leidas"
     * desde la lista, sobre un lote, y ahi nadie leyo nada: lo que se pide es
     * bajar el globo rojo.
     *
     * Mandar el acuse tambien seria **decirle a la otra persona que leiste su
     * mensaje cuando no lo hiciste**, y en una app cuyo argumento es la
     * privacidad eso no es un detalle: la confirmacion de lectura vale
     * justamente por ser cierta.
     *
     * Es local y de este aparato, igual que [marcarNoLeida].
     */
    suspend fun marcarLeidaLocal(convId: String) = dao.marcarLeida(convId)

    /**
     * La vista previa que llego, si se puede mostrar sin riesgo.
     *
     * Solo si su enlace ESTA en el texto del mensaje. Si no, cualquiera podria
     * mandar una tarjeta que dice "banco.com" y abre otro sitio: la tarjeta la
     * arma quien envia, y aqui no se le cree nada que no se pueda comprobar.
     * Con topes en cada campo y en la miniatura, por lo mismo.
     */
    private fun previaAceptable(p: VistaPreviaEnlace?, texto: String): String {
        if (p == null) return ""
        if (VistaPreviaHtml.enlacesEn(texto).none { it.second == p.url }) return ""
        val limpia = p.copy(
            titulo = p.titulo.take(200),
            descripcion = p.descripcion.take(300),
            sitio = p.sitio.take(80),
            imagen = if (p.imagen.length > 120_000) "" else p.imagen,
        )
        return jsonApp.encodeToString(VistaPreviaEnlace.serializer(), limpia)
    }

    /**
     * Cliente aparte para armar vistas previas: va a sitios cualquiera, asi
     * que sin el pinning de nuestro servidor, sin cookies y con tiempos cortos.
     * Una pagina lenta no puede trabar el envio.
     */
    private val httpPrevia by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .let(Red::construir)
    }

    /**
     * Arma la vista previa de un enlace. La llama quien ESCRIBE, antes de
     * enviar: ver `VistaPreviaHtml` para por que es asi.
     *
     * Null si no se pudo o si no hay nada que mostrar. Nunca lanza: una vista
     * previa que falla solo significa un mensaje sin tarjeta.
     */
    suspend fun vistaPreviaDe(url: String): VistaPreviaEnlace? = withContext(Dispatchers.IO) {
        runCatching {
            val pedido = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) wtfuck")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val (html, final) = httpPrevia.newCall(pedido).execute().use { r ->
                val tipo = r.header("Content-Type").orEmpty()
                if (!r.isSuccessful || !tipo.contains("html", ignoreCase = true)) return@runCatching null
                // Con tope: solo hace falta la cabecera, y una pagina de 50 MB
                // no puede gastar los datos de nadie.
                val bytes = r.body?.byteStream()?.use { it.readNBytes(512 * 1024) } ?: return@runCatching null
                String(bytes, Charsets.UTF_8) to r.request.url.toString()
            }
            val meta = VistaPreviaHtml.extraer(html, final)
            if (meta.titulo.isBlank() && meta.descripcion.isBlank()) return@runCatching null
            val imagen = if (meta.imagen.isBlank()) "" else miniaturaDePrevia(meta.imagen)
            VistaPreviaEnlace(
                url = url, titulo = meta.titulo, descripcion = meta.descripcion,
                sitio = meta.sitio, imagen = imagen,
            )
        }.getOrNull()
    }

    /** La imagen del enlace, chica: 360 px y JPEG. Va dentro de un sobre. */
    private fun miniaturaDePrevia(url: String): String = runCatching {
        val pedido = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) wtfuck").build()
        val bytes = httpPrevia.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful || r.header("Content-Type").orEmpty().startsWith("image/").not()) return@runCatching ""
            r.body?.byteStream()?.use { it.readNBytes(3 * 1024 * 1024) } ?: return@runCatching ""
        }
        val limites = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / (muestra * 2) >= 360) muestra *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = muestra },
        ) ?: return@runCatching ""
        val f = 360f / maxOf(bmp.width, bmp.height)
        val chica = if (f < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true) else bmp
        val salida = java.io.ByteArrayOutputStream()
        chica.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, salida)
        android.util.Base64.encodeToString(salida.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    /**
     * Lo que quedo escrito sin enviar. En la base cifrada: ver
     * `ConversacionEnt.borrador`.
     */
    suspend fun borradorDe(convId: String): String =
        runCatching { dao.conversacion(convId)?.borrador.orEmpty() }.getOrDefault("")

    /** Con tope: un borrador no es un documento, y la fila va en cada lista de chats. */
    suspend fun guardarBorrador(convId: String, texto: String) {
        runCatching { dao.guardarBorrador(convId, texto.take(4000)) }
    }

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
        is Carga.UbicacionEnVivo -> ClaseContenido.UBICACION_VIVA
        is Carga.UbicacionEnVivoFin -> ClaseContenido.UBICACION_VIVA_FIN
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
        is Carga.Ubicacion -> segura(carga).etiqueta.ifBlank { "Ubicación" }
        is Carga.UbicacionEnVivo -> "Ubicación en tiempo real"
        is Carga.Contacto -> segura(carga).let { c ->
            // Sin username valido no se pone `@`: el resumen no puede afirmar
            // una cuenta que la tarjeta misma se niega a afirmar.
            if (c.username != null) "Contacto: @${c.username}" else "Contacto: ${c.nombre}"
        }
        is Carga.Encuesta -> "Encuesta: ${segura(carga).pregunta}".take(LARGO_RESUMEN)
        is Carga.Evento -> "Evento: ${segura(carga).titulo}".take(LARGO_RESUMEN)
        is Carga.ResumenLlamada -> llamadaEnElChat(
            carga.conVideo, carga.saliente, carga.motivoFin, carga.segundos,
        ).let { r -> if (r.detalle.isBlank()) r.titulo else r.titulo + " · " + r.detalle }
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
        /**
         * La foto ya sale del editor a 1080x1920: no pasa por el reductor.
         * Con el ajuste de calidad "Media", el reductor la bajaba a 1600 de
         * lado mayor, y un estado se ve a pantalla completa.
         */
        renderizado: Boolean = false,
    ): Result<Unit> = runCatching {
        val id = UUID.randomUUID().toString()
        val destinos = api.destinosHistoria().destinos

        // Lo que se va a publicar. Con archivo, la clase sale de lo que ES el
        // archivo y no de lo que diga quien lo eligio.
        val preparado = medio?.let { prepararMedioDeHistoria(id, it, renderizado) }
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
    private fun prepararMedioDeHistoria(id: String, uri: Uri, renderizado: Boolean = false): MedioDeHistoria {
        val original = archivos.datosDe(uri, ClaseAdjunto.IMAGEN)
        val clase = when (Media.claseDe(original.mime)) {
            ClaseAdjunto.IMAGEN -> ClaseHistoria.IMAGEN
            ClaseAdjunto.VIDEO -> ClaseHistoria.VIDEO
            ClaseAdjunto.AUDIO -> ClaseHistoria.AUDIO
            else -> throw IllegalArgumentException("Un estado admite una foto, un video o un audio.")
        }

        // Se reduce igual que una foto de chat: ahorra datos de quien publica,
        // de todos los que la abran y cuota en el almacen. Un GIF no, que
        // pasarlo por el compresor JPEG lo deja quieto.
        var fuente = uri
        var datos = archivos.datosDe(uri, clase)
        if (clase == ClaseHistoria.IMAGEN && original.mime != "image/gif" && !renderizado) {
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

        val claseAdjunto = claseAdjuntoDeHistoria(clase)
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
            // Un audio no tiene miniatura: el visor dibuja su fondo de color.
            miniatura = if (clase == ClaseHistoria.AUDIO) "" else archivos.miniaturaDe(Uri.fromFile(local), claseAdjunto),
        )
    }

    private fun claseAdjuntoDeHistoria(clase: String): String = when (clase) {
        ClaseHistoria.VIDEO -> ClaseAdjunto.VIDEO
        ClaseHistoria.AUDIO -> ClaseAdjunto.AUDIO
        else -> ClaseAdjunto.IMAGEN
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
            val claseAdjunto = claseAdjuntoDeHistoria(medio.clase)
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
        /**
         * La silueta de una nota de voz, si quien graba la midio.
         *
         * Llega desde arriba y no se calcula aqui porque los niveles solo
         * existen MIENTRAS se graba: el microfono los da gratis y el archivo
         * ya no. Sacarla del .m4a obligaria a decodificarlo entero.
         */
        onda: String = "",
        /** "Ver una vez". Sale sin pie y sin miniatura: ver `CargaAdjunto.unaVez`. */
        unaVez: Boolean = false,
        /** Ver `CargaAdjunto.forma`. */
        forma: String = "",
        /** Ver `CargaAdjunto.spoiler`. */
        spoiler: Boolean = false,
    ) {
        val pie = if (unaVez) "" else pie
        val mensajeId = UUID.randomUUID().toString()
        val original = archivos.datosDe(uri, clase)

        // El limite, ANTES de crear el mensaje.
        //
        // Se comprobaba dentro de `subirAdjunto`, cuando la burbuja ya estaba
        // en el chat: la persona veia un mensaje rojo de "fallo" en vez de un
        // "no cabe" a tiempo. Con 64 MB de tope y un minuto de 4K pasando de
        // 300, es el camino normal, no el raro.
        //
        // La comprobacion de `subirAdjunto` se queda igual: una imagen se
        // recomprime despues de esto y podria seguir sin caber, y ademas dos
        // guardias en un limite de red nunca sobran.
        if (clase != ClaseAdjunto.IMAGEN) {
            val v = CabeAdjunto.evaluar(original.bytes, clase)
            if (!v.cabe) {
                _rechazos.tryEmit(CabeAdjunto.aviso(v, clase))
                return
            }
        }

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
                creadoEn = horaParaMio(convId),
                estado = EstadoEnvio.PENDIENTE.name,
                respondeA = respondeA?.id?.takeIf { respondeA.conversacionId == convId },
                respondeTexto = respondeA?.texto?.take(140),
                respondeAutor = respondeA?.autor,
                adjuntoClase = clase,
                adjuntoMime = datos.mime,
                adjuntoNombre = datos.nombre,
                adjuntoBytes = local.length(),
                adjuntoAncho = datos.ancho,
                adjuntoAlto = datos.alto,
                adjuntoDuracionMs = datos.duracionMs,
                adjuntoMiniatura = if (unaVez) "" else archivos.miniaturaDe(Uri.fromFile(local), clase),
                adjuntoOnda = onda,
                rutaLocal = local.absolutePath,
                adjuntoEstado = "SUBIENDO",
                unaVez = unaVez,
                adjuntoForma = forma,
                spoiler = spoiler && (clase == ClaseAdjunto.IMAGEN || clase == ClaseAdjunto.VIDEO),
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

    /**
     * Reenvia un mensaje a [destino], que puede ser cualquier conversacion.
     *
     * Antes "Reenviar" volvia a mandar el texto al MISMO chat, y de una foto
     * mandaba solo el pie. Ahora:
     *  - un texto sale con su vista previa, que ya esta armada: su enlace esta
     *    en el texto, asi que quien recibe la acepta igual que la primera vez;
     *  - un archivo se vuelve a cifrar con una llave NUEVA y se sube otra vez.
     *    Reusar el adjunto no se puede ni se deberia: el servidor solo deja
     *    bajarlo a quien esta en la conversacion donde se subio, y compartir
     *    la llave entre chats ataria los dos para siempre.
     *
     * El archivo sale de la copia local, tal cual se recibio: sin volver a
     * comprimir una foto que ya se comprimio una vez. Si todavia no se bajo,
     * no hay de donde sacarlo y se dice.
     *
     * "Reenviado de" lleva al autor ORIGINAL, aunque ya sea un reenvio.
     */
    suspend fun reenviar(m: MensajeEnt, destino: String) {
        if (m.unaVez) throw IllegalStateException("Lo que se ve una vez no se reenvía.")
        val origen = m.reenviadoDe ?: m.autor
        if (m.adjuntoClase.isBlank()) {
            val previa = if (m.previaJson.isBlank()) null else runCatching {
                jsonApp.decodeFromString(VistaPreviaEnlace.serializer(), m.previaJson)
            }.getOrNull()
            enviarTexto(destino, m.texto, reenviadoDe = origen, previa = previa)
            return
        }
        val original = m.rutaLocal?.let { File(it) }?.takeIf { it.exists() }
            ?: throw IllegalStateException("Abre el archivo una vez para que se descargue y después reenvíalo.")
        val nuevoId = UUID.randomUUID().toString()
        val local = archivos.archivoDe(nuevoId, m.adjuntoNombre)
        withContext(Dispatchers.IO) { original.copyTo(local, overwrite = true) }
        dao.guardarMensaje(
            MensajeEnt(
                id = nuevoId,
                conversacionId = destino,
                autor = sesion.username.orEmpty(),
                esMio = true,
                texto = m.texto,
                creadoEn = horaParaMio(destino),
                estado = EstadoEnvio.PENDIENTE.name,
                reenviadoDe = origen,
                adjuntoClase = m.adjuntoClase,
                adjuntoMime = m.adjuntoMime,
                adjuntoNombre = m.adjuntoNombre,
                adjuntoBytes = local.length(),
                adjuntoAncho = m.adjuntoAncho,
                adjuntoAlto = m.adjuntoAlto,
                adjuntoDuracionMs = m.adjuntoDuracionMs,
                adjuntoMiniatura = m.adjuntoMiniatura,
                adjuntoOnda = m.adjuntoOnda,
                rutaLocal = local.absolutePath,
                adjuntoEstado = "SUBIENDO",
                adjuntoForma = m.adjuntoForma,
                spoiler = m.spoiler,
            )
        )
        val datos = DatosArchivo(
            m.adjuntoNombre, m.adjuntoMime, local.length(),
            m.adjuntoAncho, m.adjuntoAlto, m.adjuntoDuracionMs,
        )
        // La subida, aparte: un video de 60 MB no puede dejar la hoja de
        // reenviar abierta hasta que termine. La burbuja ya muestra el avance.
        ambito.launch { subirAdjunto(nuevoId, destino, m.adjuntoClase, datos, local) }
    }

    // --- "Ver una vez" -----------------------------------------------------

    /**
     * Abre un "ver una vez" que me mandaron: lo baja si hace falta, lo marca
     * abierto y devuelve el archivo para el visor. Null si ya se abrio o no se
     * pudo bajar.
     *
     * Se marca ANTES de mostrarlo: ver `dao.abrirUnaVez`.
     */
    suspend fun abrirUnaVez(id: String): File? {
        var m = dao.mensaje(id) ?: return null
        if (!m.unaVez || m.esMio || m.unaVezAbierta) return null
        if (m.rutaLocal?.let { File(it).exists() } != true) {
            descargarAdjunto(id)
            m = dao.mensaje(id) ?: return null
        }
        val archivo = m.rutaLocal?.let { File(it) }?.takeIf { it.exists() } ?: return null
        dao.abrirUnaVez(id)
        // El aviso: a quien lo mando y a mis otros aparatos. Sin esperarlo y
        // sin que su falta impida verlo: es un aviso, no un permiso.
        ambito.launch { runCatching { api.unaVezAbierta(id) } }
        return archivo
    }

    /** Al cerrar el visor: el archivo se va del disco. */
    suspend fun cerrarUnaVez(id: String) {
        dao.mensaje(id)?.let { soltarUnaVez(it) }
    }

    private suspend fun soltarUnaVez(m: MensajeEnt) {
        withContext(Dispatchers.IO) { m.rutaLocal?.let { File(it).delete() } }
        dao.soltarArchivo(m.id)
    }

    /**
     * Lo que quedo en el disco y no deberia: un visor que no llego a cerrarse
     * porque la app murio, o uno mio que salio con la app cerrada. Al arrancar.
     */
    suspend fun barrerUnaVez() {
        runCatching { dao.unaVezConArchivo().forEach { soltarUnaVez(it) } }
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
            // Solo un grupo tiene nombre que mostrar: en una directa el
            // titulo ya ES la persona, y repetirlo debajo no dice nada.
            nombreDeGrupo = { convId ->
                dao.conversacion(convId)
                    ?.takeIf { it.tipo == "grupo" }
                    ?.let { it.nombreMostrado.ifBlank { it.nombre } }
                    .orEmpty()
            },
            alTerminar = ::anotarLlamadaEnElChat,
        )
    }

    /**
     * Deja el rastro de una llamada terminada en su chat.
     *
     * ## El id es derivado, y eso es lo que lo hace seguro
     *
     * `llamada-<llamadaId>` en vez de uno nuevo: `guardarMensaje` reemplaza
     * por clave, asi que si esto corre dos veces para la misma llamada —y
     * corre, porque el estado TERMINADA se puede emitir mas de una vez— queda
     * una sola linea en vez de dos.
     *
     * ## No suma no leidos
     *
     * Una llamada perdida ya sono y ya dejo su notificacion. Sumarle ademas un
     * globo de no leido al chat haria que un chat sin mensajes nuevos se vea
     * como si los tuviera, y abrirlo para no encontrar nada nuevo es como se
     * aprende a ignorar los globos.
     */
    private suspend fun anotarLlamadaEnElChat(e: EstadoLlamada, segundos: Long) {
        val carga = Carga.ResumenLlamada(
            conVideo = e.conVideo,
            saliente = e.saliente,
            motivoFin = e.motivoFin.orEmpty(),
            segundos = segundos,
        )
        dao.guardarMensaje(
            MensajeEnt(
                id = "llamada-" + e.llamadaId,
                conversacionId = e.conversacionId,
                // Quien llamo. En una entrante es la otra persona, y de eso
                // depende de que lado se dibuja la burbuja.
                autor = if (e.saliente) sesion.username.orEmpty() else e.conQuien,
                esMio = e.saliente,
                // El texto es el resumen para la lista de chats. La burbuja no
                // lo usa —se dibuja desde la carga— pero la lista si, y sin
                // esto el ultimo renglon del chat quedaria en blanco.
                texto = resumenDe(carga),
                creadoEn = System.currentTimeMillis(),
                estado = EstadoEnvio.ENTREGADO.name,
                especial = ClaseContenido.LLAMADA,
                especialJson = jsonApp.encodeToString(Carga.serializer(), carga),
            )
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

    /**
     * Modulo Z.5 - Mis dispositivos, o `null` si no se pudo preguntar.
     *
     * **Nullable, y esto no es un detalle de estilo.** Devolvia lista vacia al
     * fallar, y una lista vacia aqui significa "esta cuenta no tiene ningun
     * otro aparato vinculado". Eso es una **afirmacion de seguridad**: es
     * justo lo que alguien viene a mirar cuando sospecha que le entraron a la
     * cuenta. Decirselo porque se cayo la red es la peor respuesta posible,
     * porque es tranquilizadora y falsa.
     *
     * Con `null` la pantalla puede decir "no se pudo comprobar" y ofrecer
     * reintentar, que es lo unico honesto cuando no se pudo preguntar.
     */
    suspend fun dispositivos(): List<DispositivoInfo>? =
        runCatching { api.dispositivos().dispositivos }.getOrNull()

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
    //  Copia de seguridad cifrada (exportar / restaurar)
    // ============================================================
    //
    // Formato v2: un ZIP con
    //   - "manifiesto": el JSON del historial, cifrado con la frase
    //     (CopiaSeguridad). Lleva, por cada adjunto incluido, la LLAVE del
    //     archivo.
    //   - "m/<mensajeId>": cada adjunto, cifrado con su propia llave
    //     (CifradorArchivo, streaming, GCM por archivo).
    //
    // Es cifrado de sobre, el mismo que la app ya usa para adjuntos: sin la
    // frase no hay manifiesto, sin manifiesto no hay llaves, y los archivos del
    // zip son ruido. Nada de cripto en streaming artesanal: cada pieza es un
    // AES-GCM cerrado, con su etiqueta.

    /** Lo que se le cuenta a la persona tras restaurar. */
    data class ResumenRestauracion(
        val mensajes: Int, val conversaciones: Int, val adjuntos: Int, val cuenta: String,
        /** Que paso con la identidad Signal. Ver [Identidad]. */
        val identidad: Identidad = Identidad.NO_VENIA,
    ) {
        /**
         * Hay que decirlo con precision porque cada caso pide otra cosa de la
         * persona, y el mas importante -`CODIGO_NO_ABRE`- es el que se puede
         * arreglar todavia si se avisa a tiempo.
         */
        enum class Identidad {
            /** Copia v1/v2, o hecha sin codigo. No hay nada que restaurar. */
            NO_VENIA,
            /** Venia y se restauro: los contactos NO veran que la clave cambio. */
            RESTAURADA,
            /** Venia, pero no se dio ningun codigo. */
            SIN_CODIGO,
            /** Venia y el codigo dado no la abre. Probablemente es de otra cuenta. */
            CODIGO_NO_ABRE,
            /** Ya habia una identidad distinta en este telefono y se dejo la de aqui. */
            YA_HABIA_OTRA,
        }
    }

    /**
     * La identidad Signal sellada para meterla en la copia, o `null`.
     *
     * Devuelve `null` -y la copia sale sin identidad- en tres casos, todos
     * normales: no se dio codigo, el codigo esta mal escrito, o este telefono
     * todavia no tiene identidad (pasa con `CifradorPlano`, el de desarrollo).
     * Ninguno es un error que deba abortar la copia: perder los mensajes por
     * no poder guardar la identidad seria el peor cambio posible.
     */
    private suspend fun identidadSellada(codigo: String?): CopiaSeguridad.IdentidadRespaldo? {
        val limpio = codigo?.let { CodigoRecuperacion.normalizar(it) } ?: return null
        val mia = withContext(Dispatchers.IO) { signalDao.identidadPropia() } ?: return null
        return CopiaSeguridad.sellarIdentidad(
            CopiaSeguridad.IdentidadClara(
                parClavesB64 = Base64Util.enc(mia.parClaves),
                registrationId = mia.registrationId,
                proximoPreKeyId = mia.proximoPreKeyId,
                proximoFirmadaId = mia.proximoFirmadaId,
                proximoKyberId = mia.proximoKyberId,
            ),
            CodigoRecuperacion.claveDeIdentidad(limpio),
        )
    }

    /**
     * Devuelve la identidad Signal de la copia a este telefono.
     *
     * ## Por que NO pisa una identidad distinta que ya este en uso
     *
     * Porque seria destructivo y silencioso. Si este telefono ya hablo con
     * alguien, tiene sesiones montadas sobre su identidad actual; cambiarla
     * por debajo las deja invalidas -los mensajes que lleguen no se van a
     * poder abrir- y ademas a todos los contactos les salta el aviso de clave
     * cambiada. Restaurar una copia sobre una cuenta que ya funciona es un
     * caso REAL -la gente restaura para recuperar mensajes viejos- y no tiene
     * por que costar la identidad.
     *
     * Asi que solo se escribe cuando no hay ninguna, o cuando la que hay es
     * exactamente la misma. En los demas casos se informa y se deja la de
     * aqui: el escenario que esta funcion existe para servir -un telefono
     * nuevo, recien registrado- cae siempre en el primer caso.
     *
     * ## Lo que se resetea al restaurarla
     *
     * `publicadoEn = 0`, para que este aparato vuelva a publicar sus prekeys.
     * Las de la copia son del telefono viejo y el servidor puede haberlas
     * entregado ya; sin republicar, nadie podria abrir una sesion nueva.
     */
    private suspend fun restaurarIdentidad(
        sellada: CopiaSeguridad.IdentidadRespaldo?,
        codigo: String?,
    ): ResumenRestauracion.Identidad = withContext(Dispatchers.IO) {
        if (sellada == null) return@withContext ResumenRestauracion.Identidad.NO_VENIA
        val limpio = codigo?.let { CodigoRecuperacion.normalizar(it) }
            ?: return@withContext ResumenRestauracion.Identidad.SIN_CODIGO

        val clara = CopiaSeguridad.abrirIdentidad(
            sellada, CodigoRecuperacion.claveDeIdentidad(limpio),
        ) ?: return@withContext ResumenRestauracion.Identidad.CODIGO_NO_ABRE

        val par = runCatching { Base64Util.dec(clara.parClavesB64) }.getOrNull()
            ?: return@withContext ResumenRestauracion.Identidad.CODIGO_NO_ABRE

        signalDao.identidadPropia()?.let { actual ->
            if (!actual.parClaves.contentEquals(par)) {
                return@withContext ResumenRestauracion.Identidad.YA_HABIA_OTRA
            }
            // La misma que ya esta: no se toca nada y se cuenta como puesta,
            // que es lo que la persona quiere saber.
            return@withContext ResumenRestauracion.Identidad.RESTAURADA
        }

        signalDao.guardarIdentidadPropia(
            IdentidadPropiaEnt(
                parClaves = par,
                registrationId = clara.registrationId,
                publicadoEn = 0,
                proximoPreKeyId = clara.proximoPreKeyId,
                proximoFirmadaId = clara.proximoFirmadaId,
                proximoKyberId = clara.proximoKyberId,
            )
        )
        Log.i(TAG, "Identidad restaurada desde la copia")
        ResumenRestauracion.Identidad.RESTAURADA
    }

    /**
     * Escribe la copia cifrada -texto y adjuntos- directo al flujo de salida
     * (el archivo que eligio la persona). En streaming: los adjuntos pasan de a
     * uno, nunca todos en memoria.
     *
     * @param codigo el codigo de recuperacion. Si se da, la copia incluye la
     *   identidad Signal sellada con el; si no, la copia sale sin identidad y
     *   restaurarla en otro telefono cambiara la huella de la persona. No es
     *   obligatorio a proposito: quien no tenga el codigo a mano igual deberia
     *   poder guardar sus mensajes.
     */
    suspend fun exportarCopiaA(
        salida: java.io.OutputStream,
        frase: CharArray,
        codigo: String? = null,
    ) {
        val convs = mutableListOf<CopiaSeguridad.ConversacionRespaldo>()
        java.util.zip.ZipOutputStream(salida.buffered()).use { zip ->
            for (convId in dao.idsLocales()) {
                val c = dao.conversacion(convId) ?: continue
                val mensajes = mutableListOf<CopiaSeguridad.MensajeRespaldo>()
                for (m in dao.todosLosMensajes(convId)) {
                    if (m.esSistema || m.retirado) continue
                    if (m.texto.isBlank() && m.adjuntoId == null) continue

                    var adj: CopiaSeguridad.AdjuntoRespaldo? = null
                    if (m.adjuntoId != null && m.adjuntoNombre.isNotBlank()) {
                        val f = archivos.archivoDe(m.id, m.adjuntoNombre)
                        if (f.exists()) {
                            // Se cifra a un temporal y de ahi al zip: CifradorArchivo
                            // escribe a un File, y el temporal se borra enseguida.
                            val tmp = java.io.File(contexto.cacheDir, "bkp-${m.id}.enc")
                            val llave = runCatching {
                                CifradorArchivo.cifrarA(f.inputStream(), tmp)
                            }.getOrNull()
                            if (llave != null) {
                                zip.putNextEntry(java.util.zip.ZipEntry("m/${m.id}"))
                                tmp.inputStream().use { it.copyTo(zip, 64 * 1024) }
                                zip.closeEntry()
                                adj = CopiaSeguridad.AdjuntoRespaldo(
                                    clase = m.adjuntoClase, mime = m.adjuntoMime,
                                    nombre = m.adjuntoNombre, bytes = m.adjuntoBytes,
                                    ancho = m.adjuntoAncho, alto = m.adjuntoAlto,
                                    duracionMs = m.adjuntoDuracionMs,
                                    claveB64 = llave.claveB64, nonceB64 = llave.nonceB64,
                                )
                            }
                            tmp.delete()
                        }
                    }
                    mensajes += CopiaSeguridad.MensajeRespaldo(
                        id = m.id, autor = m.autor, esMio = m.esMio,
                        texto = m.texto, creadoEn = m.creadoEn, adjunto = adj,
                    )
                }
                if (mensajes.isNotEmpty()) {
                    convs += CopiaSeguridad.ConversacionRespaldo(
                        id = c.id, tipo = c.tipo, nombre = c.nombre,
                        participantes = c.participantes, mensajes = mensajes,
                    )
                }
            }

            // El manifiesto va AL FINAL: ya tiene todas las llaves de los
            // adjuntos que se escribieron arriba. Cifrado con la frase.
            val respaldo = CopiaSeguridad.Respaldo(
                creado = System.currentTimeMillis(),
                cuenta = sesion.username.orEmpty(),
                conversaciones = convs,
                identidad = identidadSellada(codigo),
            )
            val json = jsonApp.encodeToString(CopiaSeguridad.Respaldo.serializer(), respaldo)
            zip.putNextEntry(java.util.zip.ZipEntry("manifiesto"))
            zip.write(CopiaSeguridad.cifrar(json.toByteArray(), frase))
            zip.closeEntry()
        }
    }

    /**
     * Restaura desde el flujo de una copia. Se vuelca a un temporal para poder
     * leer el zip con acceso aleatorio (el manifiesto primero, los adjuntos
     * despues). guardarMensaje es un upsert por id: restaurar dos veces no
     * duplica.
     */
    suspend fun restaurarCopiaDe(
        entrada: java.io.InputStream,
        frase: CharArray,
        /**
         * El codigo de recuperacion, si se tiene. Sin el se restauran los
         * mensajes igual; lo que no vuelve es la identidad, y entonces a los
         * contactos les saltara el aviso de que la clave cambio.
         */
        codigo: String? = null,
    ): Result<ResumenRestauracion> {
        val tempZip = java.io.File(contexto.cacheDir, "restaurar-${System.currentTimeMillis()}.zip")
        try {
            entrada.use { ent -> tempZip.outputStream().use { ent.copyTo(it, 64 * 1024) } }

            java.util.zip.ZipFile(tempZip).use { zf ->
                val man = zf.getEntry("manifiesto")
                    ?: return Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO))
                val enc = zf.getInputStream(man).use { it.readBytes() }
                val json = CopiaSeguridad.descifrar(enc, frase).getOrElse { return Result.failure(it) }
                val respaldo = runCatching {
                    jsonApp.decodeFromString(CopiaSeguridad.Respaldo.serializer(), String(json))
                }.getOrElse {
                    return Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO))
                }

                var mensajes = 0
                var adjuntos = 0
                for (conv in respaldo.conversaciones) {
                    if (dao.conversacion(conv.id) == null) {
                        dao.guardarConversacion(
                            ConversacionEnt(
                                id = conv.id, tipo = conv.tipo, nombre = conv.nombre,
                                participantes = conv.participantes,
                            )
                        )
                    }
                    for (m in conv.mensajes) {
                        var rutaLocal: String? = null
                        var estado = ""
                        val a = m.adjunto
                        if (a != null) {
                            val e = zf.getEntry("m/${m.id}")
                            if (e != null) {
                                val destino = archivos.archivoDe(m.id, a.nombre)
                                val ok = CifradorArchivo.descifrarA(
                                    zf.getInputStream(e),
                                    CifradorArchivo.Llave(a.claveB64, a.nonceB64),
                                    destino,
                                )
                                if (ok) {
                                    rutaLocal = destino.absolutePath
                                    estado = "LISTO"
                                    adjuntos++
                                }
                            }
                        }
                        dao.guardarMensaje(
                            MensajeEnt(
                                id = m.id, conversacionId = conv.id, autor = m.autor,
                                esMio = m.esMio, texto = m.texto, creadoEn = m.creadoEn,
                                estado = if (m.esMio) EstadoEnvio.ENTREGADO.name else EstadoEnvio.LEIDO.name,
                                // Si el adjunto se restauro, el mensaje queda como uno con
                                // archivo LISTO y su ruta local; si no, solo texto.
                                adjuntoId = if (rutaLocal != null) m.id else null,
                                adjuntoClase = a?.clase ?: "",
                                adjuntoMime = a?.mime ?: "",
                                adjuntoNombre = a?.nombre ?: "",
                                adjuntoBytes = a?.bytes ?: 0,
                                adjuntoAncho = a?.ancho ?: 0,
                                adjuntoAlto = a?.alto ?: 0,
                                adjuntoDuracionMs = a?.duracionMs ?: 0,
                                rutaLocal = rutaLocal,
                                adjuntoEstado = estado,
                            )
                        )
                        mensajes++
                    }
                }
                return Result.success(
                    ResumenRestauracion(
                        mensajes, respaldo.conversaciones.size, adjuntos, respaldo.cuenta,
                        // La identidad, al final: si algo de arriba fallara,
                        // mejor no haber tocado la cripto de este telefono.
                        identidad = restaurarIdentidad(respaldo.identidad, codigo),
                    )
                )
            }
        } catch (e: Exception) {
            return Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO))
        } finally {
            tempZip.delete()
        }
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

    /**
     * Cambia la contrasena con el codigo del SMS.
     *
     * `totp` solo hace falta si la cuenta tiene dos pasos activado, y el
     * servidor lo pide **despues** de canjear el SMS: primero se manda sin el,
     * y si responde 401 la pantalla lo pregunta. Asi no hay que preguntarle un
     * codigo de dos pasos a la mayoria, que no lo tiene.
     */
    suspend fun recuperarCuenta(
        username: String,
        telefono: String,
        codigo: String,
        passwordNueva: String,
        totp: String? = null,
    ): Result<Unit> = runCatching {
        api.recuperarCuenta(RecuperarReq(username, telefono, codigo, passwordNueva, totp))
    }

    suspend fun iniciarTotp(): Result<TotpIniciado> = runCatching { api.iniciarTotp() }

    suspend fun confirmarTotp(codigo: String): Result<TotpActivado> =
        runCatching { api.confirmarTotp(TotpConfirmarReq(codigo)) }

    suspend fun apagarTotp(password: String): Result<Unit> =
        runCatching { api.apagarTotp(password) }

    // ------------------------------------------------ codigo de recuperacion

    /**
     * Fija -o rota- el codigo de recuperacion.
     *
     * Recibe el CODIGO y manda el VERIFICADOR: el codigo no sale de este
     * telefono nunca. Quien llama ya lo genero con `CodigoRecuperacion.generar`
     * y se lo mostro a la persona.
     */
    suspend fun fijarRecuperacion(
        codigo: String,
        password: String,
        totp: String? = null,
    ): Result<Unit> = runCatching {
        val limpio = CodigoRecuperacion.normalizar(codigo)
            ?: throw IllegalArgumentException("Ese código de recuperación no es válido.")
        api.fijarRecuperacion(
            FijarRecuperacionReq(
                verificadorB64 = Base64Util.enc(CodigoRecuperacion.verificadorServidor(limpio)),
                password = password,
                totp = totp,
            )
        )
    }

    suspend fun estadoRecuperacion(): Result<EstadoRecuperacion> =
        runCatching { api.estadoRecuperacion() }

    /**
     * Da de alta ESTE telefono con el codigo de recuperacion.
     *
     * ## El orden importa, y la pantalla lo impone
     *
     * Conviene **restaurar la copia antes** de llamar aqui: asi `identidadPub`
     * es la identidad de siempre y a los contactos no les salta el aviso de
     * clave cambiada. Si se llama antes, la cuenta se recupera igual pero con
     * identidad nueva, y eso ya no se puede deshacer.
     *
     * Por eso `id.identidadPub` se lee en el momento de llamar y no antes: si
     * la restauracion acaba de escribir la identidad de la copia, es esa la
     * que se publica.
     */
    suspend fun recuperarDispositivo(
        username: String,
        telefono: String,
        codigoSms: String,
        codigoRecuperacion: String,
        passwordNueva: String,
        id: Hardware.Identidad,
        etiqueta: String,
        totp: String? = null,
    ): Result<Unit> = runCatching {
        val limpio = CodigoRecuperacion.normalizar(codigoRecuperacion)
            ?: throw IllegalArgumentException("Ese código de recuperación no es válido.")
        val antes = sesion.usuarioId
        api.recuperarDispositivo(
            RecuperarDispositivoReq(
                username = username,
                telefono = telefono,
                codigo = codigoSms,
                verificadorB64 = Base64Util.enc(CodigoRecuperacion.verificadorServidor(limpio)),
                passwordNueva = passwordNueva,
                totp = totp,
                etiquetaDispositivo = etiqueta,
                identidadPub = id.identidadPub,
                hardwareHash = id.hardwareHash,
                hardwareNivel = id.nivel,
            )
        )
        limpiarSiCambioDeCuenta(antes)
    }

    suspend fun pedirEliminacion(password: String, totp: String?): Result<EliminacionPedida> =
        runCatching { api.pedirEliminacion(EliminarCuentaReq(password, totp?.ifBlank { null })) }

    /**
     * Mis sesiones abiertas, o `null` si no se pudo preguntar.
     *
     * Mismo caso que `dispositivos`, y si cabe peor: una lista vacia de
     * sesiones se lee como "nadie mas tiene tu cuenta abierta". Ver la nota de
     * alli.
     */
    suspend fun sesiones(): List<SesionActiva>? =
        runCatching { api.sesiones().sesiones }.getOrNull()

    suspend fun cerrarSesionRemota(id: String): Result<Unit> =
        runCatching { api.cerrarSesionRemota(id) }

    suspend fun cerrarOtrasSesiones(): Result<Int> =
        runCatching { api.cerrarOtrasSesiones()["cerradas"] ?: 0 }

    /**
     * Como llamo yo a cada uno de estos usernames.
     *
     * Devuelve el username cuando no esta en la libreta, para que quien lo use
     * no tenga que decidir que poner: siempre hay algo que mostrar y nunca es
     * un hueco. Lo usa el selector de menciones.
     */
    suspend fun nombresDeLibreta(usernames: List<String>): Map<String, String> {
        val libreta = runCatching { dao.libreta() }.getOrDefault(emptyList())
            .filter { it.alias.isNotBlank() }
            .associate { it.username to it.alias }
        return usernames.associateWith { libreta[it.lowercase()] ?: it }
    }

    // ============================================================
    //  Modulo Z.1 - Emojis usados
    // ============================================================

    /**
     * Los emojis que esta persona mas usa, para la pestana Recientes.
     *
     * Devuelve solo los glifos: la pestana no necesita el contador, y sacarlo
     * de la firma evita que alguien lo pinte en la pantalla. Cuantas veces uso
     * alguien un emoji es un dato para ordenar, no para mostrarle.
     */
    val emojisRecientes: Flow<List<String>> =
        dao.emojisUsados().map { filas -> filas.map { it.emoji } }

    /**
     * Anota que se uso uno.
     *
     * Se guarda el glifo **con su tono**: quien eligio un tono de piel espera
     * verlo en Recientes con ese tono, no el amarillo de fabrica.
     *
     * No propaga el fallo. Perder una cuenta de uso no puede impedir que el
     * emoji se mande: el efecto que la persona pidio es escribirlo.
     */
    suspend fun usarEmoji(glifo: String) {
        if (glifo.isBlank()) return
        runCatching { dao.sumarEmoji(glifo, System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "No se pudo anotar el emoji usado: ${it.message}") }
    }

    /** Vacia la lista de Recientes. Lo pide Privacidad, y es solo local. */
    suspend fun olvidarEmojisUsados() = dao.olvidarEmojis()

    /**
     * El tono de piel elegido para los emojis que lo admiten.
     *
     * Vacio = el amarillo de fabrica, que **no** es "el primero de la lista"
     * sino una opcion mas y la que elige quien no quiere elegir.
     */
    val tonoDePiel: Flow<String> =
        dao.ajusteLocal(CLAVE_TONO).map { it ?: TONO_POR_DEFECTO }

    suspend fun ponerTonoDePiel(tono: String) =
        dao.guardarAjusteLocal(AjusteLocalEnt(CLAVE_TONO, tono))

    /**
     * Modulo Z.4 - Los stickers etiquetados con este emoji.
     *
     * Se filtra en memoria y no con una consulta: la coleccion entera de una
     * persona son decenas de filas, ya esta en un Flow vivo, y una consulta
     * nueva por cada tecla del compositor seria pegarle a la base cifrada
     * mientras alguien escribe.
     *
     * Devuelve **vacio** si el emoji viene vacio, y eso es correcto: aqui "no
     * hay nada que sugerir" y "no se pudo preguntar" son lo mismo, porque la
     * fuente es local y no falla por red. Donde no lo son -las lecturas contra
     * el servidor- este repositorio devuelve `null`. Ver `publicaciones`.
     */
    fun stickersConEmoji(emoji: String): Flow<List<StickerEnt>> =
        stickers.map { todos ->
            if (emoji.isBlank()) emptyList()
            else todos.filter { it.emoji == emoji }.sortedByDescending { it.usadoEn }
        }

    // ============================================================
    //  Modulo Y · Stickers propios
    // ============================================================

    val stickers: Flow<List<StickerEnt>> = dao.stickers()
    val packsDeStickers: Flow<List<PackEnt>> = dao.packs()
    val stickersRecientes: Flow<List<StickerEnt>> = dao.recientes()

    /**
     * Adopta los archivos sueltos de la version anterior.
     *
     * La primera version guardaba stickers como archivos en `files/stickers/`
     * y nada mas. Al pasar a tabla, esos archivos existen y la base no los
     * conoce: sin esto, quien ya habia recortado unos cuantos abriria la
     * bandeja y la veria vacia, con los archivos ocupando disco.
     *
     * Es idempotente: solo mira los archivos que no tienen fila.
     */
    suspend fun adoptarStickersSueltos() {
        runCatching {
            val conocidos = dao.stickers().first().map { it.archivo }.toSet()
            Stickers.mios(contexto).filter { it.absolutePath !in conocidos }.forEach { f ->
                dao.guardarSticker(
                    StickerEnt(
                        id = UUID.randomUUID().toString(),
                        packId = "",
                        archivo = f.absolutePath,
                        emoji = "",
                        favorito = false,
                        usadoEn = 0L,
                        creadoEn = f.lastModified(),
                        animado = runCatching { Stickers.esAnimado(f.readBytes().take(4096).toByteArray()) }
                            .getOrDefault(false),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "No se pudieron adoptar los stickers sueltos: ${it.message}") }
    }

    /** Registra un sticker recien creado. Devuelve su id. */
    suspend fun registrarSticker(archivo: File, animado: Boolean, packId: String = ""): String {
        val id = UUID.randomUUID().toString()
        dao.guardarSticker(
            StickerEnt(
                id = id,
                packId = packId,
                archivo = archivo.absolutePath,
                emoji = "",
                favorito = false,
                usadoEn = 0L,
                creadoEn = System.currentTimeMillis(),
                animado = animado,
            )
        )
        return id
    }

    /**
     * Guarda en mi coleccion un sticker que me mandaron.
     *
     * Es lo que hace que la funcion sirva para dos: sin esto, cada quien solo
     * puede usar los que recorto, y un sticker que llega es un callejon sin
     * salida. Se **copia** el archivo, no se referencia: el original vive con
     * el mensaje y vaciar el chat se lo llevaria.
     */
    suspend fun guardarStickerRecibido(origen: File): Boolean = runCatching {
        val animado = Stickers.esAnimado(origen.readBytes().take(256 * 1024).toByteArray())
        val destino = Stickers.nuevoAnimado(contexto, origen.extension.ifBlank { "webp" })
        origen.copyTo(destino, overwrite = true)
        registrarSticker(destino, animado)
        true
    }.onFailure { Log.w(TAG, "No se pudo guardar el sticker recibido: ${it.message}") }
        .getOrDefault(false)

    /** Al mandarlo sube a "recientes". La hora real, que es la que ordena entre sesiones. */
    suspend fun usarSticker(id: String) = dao.marcarUsado(id, System.currentTimeMillis())

    suspend fun favoritoSticker(id: String, v: Boolean) = dao.marcarFavorito(id, v)
    suspend fun emojiSticker(id: String, e: String) = dao.ponerEmoji(id, e.take(8))
    suspend fun moverSticker(id: String, packId: String) = dao.moverA(id, packId)

    suspend fun borrarSticker(s: StickerEnt) {
        dao.borrarSticker(s.id)
        Stickers.borrar(File(s.archivo))
    }

    suspend fun crearPack(nombre: String): String {
        val id = UUID.randomUUID().toString()
        dao.guardarPack(PackEnt(id, nombre.trim().take(40), System.currentTimeMillis()))
        return id
    }

    suspend fun renombrarPack(id: String, nombre: String) =
        dao.renombrarPack(id, nombre.trim().take(40))

    /** Borra el pack y **suelta** sus stickers. Ver la nota del DAO. */
    suspend fun borrarPack(id: String) {
        dao.soltarDelPack(id)
        dao.borrarPack(id)
    }

    // ============================================================
    //  Modulo AO: lo que se compartio en un chat
    // ============================================================

    /**
     * Una conversacion por su id, de la base.
     *
     * Se lee del DAO y no del flujo `conversaciones`: ese flujo deja fuera lo
     * archivado, y el perfil de alguien tiene que abrirse igual aunque su
     * chat este archivado.
     */
    suspend fun conversacion(id: String): ConversacionEnt? = dao.conversacion(id)

    /**
     * Borra los chats temporales que ya vencieron, con todo lo suyo.
     *
     * ## Por que lo hace el telefono y no el servidor
     *
     * Porque el servidor **no tiene** el historial: lo borra al confirmarse la
     * entrega. Lo unico que puede borrar alla es el rastro de que esa
     * conversacion existio. Los mensajes estan aqui, y aqui se borran.
     *
     * Es la misma reparticion que en la ubicacion en vivo: la fecha viaja
     * dentro del dato y cada lado la hace cumplir con lo que tiene.
     *
     * ## Que NO promete
     *
     * Que nadie se lo haya llevado antes. Una captura de pantalla, una foto
     * del telefono con otro telefono, o alguien mirando por encima del hombro
     * no los para nada. Un chat temporal reduce cuanto tiempo existe el
     * registro; no convierte lo dicho en irrecuperable, y la pantalla lo dice
     * con esas palabras.
     */
    suspend fun barrerChatsVencidos(): Int {
        val vencidas = dao.conversacionesVencidas(System.currentTimeMillis())
        for (conv in vencidas) {
            // Primero el contenido y despues la fila: al reves, borrar la
            // conversacion deja los mensajes huerfanos si algo falla en el
            // medio, y esos ya no los encuentra nadie para borrarlos.
            runCatching { vaciarChat(conv.id) }
            runCatching { dao.borrarConversacion(conv.id) }
            Notificaciones.quitarSonidoPropio(contexto, conv.id)
        }
        return vencidas.size
    }

    /** La directa que YA existe con alguien, sin crearla. Ver el DAO. */
    suspend fun directaCon(username: String): ConversacionEnt? =
        dao.directaCon(username.lowercase().trim())

    /**
     * Cuantas cosas de cada clase hay en una conversacion.
     *
     * Sale de la base LOCAL, y no hay otro sitio de donde pudiera salir: el
     * servidor no guarda el historial, asi que no sabe cuantas fotos se
     * mandaron en un chat. Es la misma propiedad del buzon tonto vista desde
     * el otro lado — la cuenta no se le pide a nadie porque nadie la tiene.
     *
     * Las notas de voz van aparte de los audios a proposito: un audio es un
     * archivo que alguien eligio y una nota de voz es alguien hablando. En la
     * lista de Telegram tambien estan separados, y por lo mismo.
     */
    suspend fun compartidoResumen(convId: String): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (r in dao.recuentoAdjuntos(convId)) if (r.cuantos > 0) m[r.clase] = r.cuantos
        for (r in dao.recuentoEspeciales(convId)) {
            // Solo las clases que son "una cosa que se mando". Un voto o una
            // posicion en vivo no se cuentan: la fila existe, pero no es algo
            // que nadie haya compartido para volver a mirarlo.
            if (r.clase in CLASES_COMPARTIBLES && r.cuantos > 0) m[r.clase] = r.cuantos
        }
        val enlaces = dao.cuantosConEnlace(convId)
        if (enlaces > 0) m[CLASE_ENLACE] = enlaces
        return m
    }

    /** Las filas de una clase, para la galeria. */
    suspend fun compartidoDe(convId: String, clase: String): List<MensajeEnt> =
        if (clase == CLASE_ENLACE) dao.conEnlace(convId)
        else if (clase in CLASES_COMPARTIBLES) dao.especialesDe(convId, clase)
        else dao.adjuntosDe(convId, clase)

    suspend fun contactos(): List<Contacto> =
        runCatching { api.contactos().contactos }
            .onSuccess { guardarLibretaLocal(it) }
            .getOrElse { emptyList() }

    suspend fun guardarContacto(
        username: String,
        alias: String? = null,
        favorito: Boolean? = null,
    ): Result<List<Contacto>> =
        runCatching { api.guardarContacto(GuardarContactoReq(username, alias, favorito)).contactos }
            .onSuccess { guardarLibretaLocal(it) }

    suspend fun borrarContacto(username: String): Result<List<Contacto>> =
        runCatching { api.borrarContacto(username).contactos }
            .onSuccess { guardarLibretaLocal(it) }

    /**
     * Copia la libreta a la base local, que es de donde sale la lista de chats.
     *
     * Reemplaza y **poda**: sin la poda, borrar un contacto lo quitaria del
     * servidor y su nombre seguiria saliendo en la lista de este aparato para
     * siempre, porque un REPLACE solo pisa lo que vuelve.
     *
     * Se llama desde las tres rutas que pueden cambiar la libreta -leerla,
     * guardar y borrar- porque las tres devuelven la lista entera ya
     * actualizada, asi que no hace falta una peticion mas.
     */
    private suspend fun guardarLibretaLocal(lista: List<Contacto>) {
        runCatching {
            dao.guardarContactos(
                lista.map {
                    ContactoEnt(
                        // En minusculas porque el JOIN compara con
                        // `conversacion.nombre`, que el servidor guarda asi.
                        // Con una mayuscula de diferencia el nombre no saldria
                        // y no habria error en ningun sitio.
                        username = it.username.lowercase(),
                        alias = it.alias.orEmpty().trim(),
                        favorito = it.favorito,
                    )
                },
            )
            val vivos = lista.map { it.username.lowercase() }
            if (vivos.isEmpty()) dao.borrarContactos() else dao.podarContactos(vivos)
        }.onFailure { Log.w(TAG, "No se pudo copiar la libreta: ${it.message}") }
    }

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

    /**
     * Mis eventos de seguridad, o `null` si no se pudo preguntar.
     *
     * Es el registro de ingresos, cambios de clave e intentos fallidos. Vacio
     * significa "no paso nada raro con tu cuenta"; no poder preguntarlo
     * significa otra cosa y tiene que verse distinto.
     */
    suspend fun misEventosSeguridad(): List<EventoSeguridad>? =
        runCatching { api.misEventos().eventos }.getOrNull()

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

    /**
     * Asigna un tipo de cuenta a otra persona. Solo administrador.
     *
     * Este es el **unico** camino por el que se reparte `desarrollador`: la
     * ruta de autoservicio lo rechaza a proposito. Ver `Cuentas.kt`.
     */
    suspend fun asignarTipoCuenta(username: String, tipo: String): Result<Unit> =
        runCatching { api.asignarTipoCuenta(username, tipo) }

    /**
     * Pone o quita el distintivo de empresa verificada.
     *
     * Lo mas delicado del modulo P: es la plataforma diciendo "comprobamos que
     * esta cuenta es quien dice ser". El servidor lo deja por escrito en la
     * bitacora con quien lo firmo.
     */
    suspend fun verificarEmpresa(username: String, valor: Boolean): Result<Unit> =
        runCatching { api.verificarEmpresa(username, valor) }

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

    /**
     * Una pagina del directorio de Usuarios: solo quien se apunto. El filtro
     * de privacidad lo aplica el servidor; ver `Repo.directorio`.
     */
    suspend fun directorio(consulta: String, desde: String = ""): Result<DirectorioResp> =
        runCatching { api.directorio(consulta, desde) }

    suspend fun buscarCanales(consulta: String): List<CanalEnBusqueda> =
        runCatching { api.buscarCanales(consulta).canales }.getOrElse { emptyList() }

    /**
     * El directorio de canales, o **null si no se pudo pedir**.
     *
     * Misma correccion que en [publicaciones] y por el mismo motivo: con
     * `emptyList()` en el fallo, la pantalla anunciaba "todavia no hay canales
     * publicos" cada vez que no habia red. Es una afirmacion sobre la
     * plataforma entera, hecha sin haber podido preguntar.
     */
    suspend fun directorioCanales(): List<CanalEnBusqueda>? =
        runCatching { api.directorioCanales().canales }.getOrNull()

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

    /**
     * Las publicaciones de un canal, o **null si no se pudieron pedir**.
     *
     * Devolvia `emptyList()` al fallar, y esa lista vacia llegaba a la
     * pantalla como si fuera la respuesta del servidor: el canal se dibujaba
     * diciendo "todavia no tiene publicaciones". O sea, **afirmaba algo sobre
     * el canal cuando lo unico que habia pasado era que no habia red**.
     *
     * "No pude preguntar" y "pregunte y no hay" son dos cosas distintas y
     * tienen que verse distinto. Un `getOrElse { emptyList() }` las junta y es
     * comodo justo hasta que alguien lee la pantalla y le cree.
     */
    suspend fun publicaciones(convId: String, antesDe: String? = null): List<Publicacion>? =
        runCatching { api.publicaciones(convId, antesDe) }.getOrNull()

    /**
     * La URL firmada para ver la imagen de una publicacion, o `null`.
     *
     * Se pide cuando hace falta y no viene en el muro, porque **caduca**: una
     * URL firmada metida en la lista se vence mientras alguien lee, y la foto
     * dejaria de cargar a mitad del scroll.
     *
     * Va sin cifrar, asi que Coil la puede pedir directo: es el unico adjunto
     * del que eso es cierto. Ver `V36`.
     */
    suspend fun urlImagenPublicacion(adjuntoId: String): String? =
        runCatching { api.adjunto(adjuntoId).urlDescarga }.getOrNull()

    // ============================================================
    //  Modulo AD: comunidades
    // ============================================================

    /**
     * Mis comunidades, o `null` si no se pudo preguntar.
     *
     * Nullable por lo mismo que `publicaciones` y las seis de Z.5: "no tenes
     * ninguna" y "no pude leerlas" no son lo mismo, y en una pantalla que
     * existe para listarlas la diferencia es toda la pantalla.
     */
    suspend fun misComunidades(): List<ComunidadResumen>? =
        runCatching { api.misComunidades().comunidades }.getOrNull()

    suspend fun comunidad(id: String): ComunidadDetalle? =
        runCatching { api.comunidad(id) }.getOrNull()

    suspend fun crearComunidad(
        nombre: String,
        descripcion: String,
        grupos: List<String>,
    ): Result<ComunidadDetalle> =
        runCatching { api.crearComunidad(CrearComunidadReq(nombre, descripcion, grupos)) }

    suspend fun editarComunidad(
        id: String,
        nombre: String,
        descripcion: String,
    ): Result<ComunidadDetalle> =
        runCatching { api.editarComunidad(id, EditarComunidadReq(nombre, descripcion)) }

    suspend fun agregarGruposAComunidad(id: String, grupos: List<String>): Result<ComunidadDetalle> =
        runCatching { api.agregarGruposAComunidad(id, AgregarGruposReq(grupos)) }

    suspend fun quitarGrupoDeComunidad(id: String, grupo: String): Result<Unit> =
        runCatching { api.quitarGrupoDeComunidad(id, grupo) }

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
    suspend fun publicar(
        convId: String,
        texto: String,
        imagen: Uri? = null,
    ): Result<Unit> {
        val limpio = texto.trim()
        if (limpio.isEmpty() && imagen == null) return Result.success(Unit)
        val mensajeId = UUID.randomUUID().toString()
        return runCatching {
            api.registrarMensaje(
                RegistrarMensajeReq(
                    mensajeId = mensajeId,
                    conversacionId = convId,
                    menciones = mencionesDe(limpio),
                )
            )
            // La imagen ANTES del cuerpo, por lo mismo que en
            // `publicarHistoria`: si algo falla en el medio, lo que queda es
            // un adjunto que nadie ve -y que barre el limpiador- en vez de una
            // publicacion visible con un hueco donde deberia estar la foto.
            // El fallo barato es el invisible.
            val adjuntoId = imagen?.let { subirImagenDeCanal(convId, it) }
            api.publicarEnCanal(convId, PublicarReq(mensajeId, limpio, adjuntoId))
        }
    }

    /**
     * Sube la imagen de una publicacion de canal. **Sin cifrar.**
     *
     * Es el unico sitio de la app que sube un archivo en claro a proposito, y
     * la razon esta en `V36`: un canal publico no reparte sobres, asi que no
     * hay donde meter la clave. Quien se suscriba manana tendria el archivo y
     * no la llave.
     *
     * A cambio, es el unico sitio donde el servidor **puede** comprobar que el
     * archivo es una imagen de verdad, y lo hace al confirmar.
     *
     * Se reduce antes de subir con el mismo ajuste de calidad que una foto de
     * chat: un anuncio no necesita 12 megapixeles y los paga quien lo lee.
     */
    private suspend fun subirImagenDeCanal(convId: String, uri: Uri): String {
        val temp = archivos.temporal("pub-" + UUID.randomUUID())
        try {
            val fuente = if (archivos.prepararImagen(uri, ajustes.calidadImagen, temp)) {
                Uri.fromFile(temp)
            } else {
                uri
            }
            val datos = archivos.datosDe(fuente, ClaseAdjunto.IMAGEN)
            val reserva = api.reservarAdjunto(
                ReservarAdjuntoReq(
                    conversacionId = convId,
                    clase = ClaseAdjunto.IMAGEN,
                    bytes = datos.bytes,
                    mime = datos.mime,
                    nombre = datos.nombre,
                    ancho = datos.ancho,
                    alto = datos.alto,
                )
            )
            val aSubir = if (fuente == uri) {
                // No se pudo reducir: se sube el original, copiandolo a un
                // temporal porque `subirAlAlmacen` necesita un File.
                archivos.temporal("pub-orig-" + UUID.randomUUID()).also {
                    if (!archivos.copiarDesde(uri, it)) error("No se pudo leer la imagen.")
                }
            } else {
                temp
            }
            try {
                api.subirAlAlmacen(reserva.urlSubida, aSubir)
            } finally {
                if (aSubir != temp) aSubir.delete()
            }
            api.confirmarAdjunto(reserva.adjuntoId)
            return reserva.adjuntoId
        } finally {
            temp.delete()
        }
    }

    /**
     * Comenta una publicacion.
     *
     * ## El defecto que esto arregla
     *
     * Antes hacia **solo** la rama de abajo: guardaba un `MensajeEnt` y lo
     * despachaba como sobre cifrado. En un grupo eso esta bien. En un canal
     * publico no hay a quien entregarle el sobre -un canal publico no reparte
     * sobres, es lo que le permite escalar-, asi que quedaba el metadato en el
     * servidor, **el contador subia** y el texto no quedaba en ninguna parte.
     *
     * Medido en la base antes de arreglarlo: cada comentario de canal tenia
     * cero sobres y cero cuerpo guardado. Se veia como "hay 1 comentario y no
     * lo puedo ver", que es como lo reporto el usuario, y la causa no estaba en
     * la pantalla.
     *
     * Ahora el comentario **sigue al cuerpo de la publicacion**: en un canal
     * publico va al servidor en claro, bajo la misma excepcion declarada; en
     * uno privado sigue siendo un mensaje cifrado.
     */
    suspend fun comentar(
        convId: String,
        publicacionId: String,
        texto: String,
        canalPublico: Boolean,
    ): Result<Unit> {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return Result.success(Unit)

        if (canalPublico) {
            val mensajeId = UUID.randomUUID().toString()
            return runCatching {
                api.registrarMensaje(
                    RegistrarMensajeReq(
                        mensajeId = mensajeId,
                        conversacionId = convId,
                        respondeA = publicacionId,
                        menciones = mencionesDe(limpio),
                    )
                )
                api.comentarEnCanal(convId, ComentarReq(mensajeId, publicacionId, limpio))
            }
        }

        // Canal privado: es un mensaje como cualquier otro y solo lo ve quien
        // reciba el sobre. Mismo precio declarado que el resto del canal
        // privado: quien se suscribe despues no ve lo de antes.
        val m = MensajeEnt(
            id = UUID.randomUUID().toString(),
            conversacionId = convId,
            autor = sesion.username.orEmpty(),
            esMio = true,
            texto = limpio,
            creadoEn = horaParaMio(convId),
            estado = EstadoEnvio.PENDIENTE.name,
            respondeA = publicacionId,
        )
        dao.guardarMensaje(m)
        despachar()
        return Result.success(Unit)
    }

    /**
     * Los comentarios de una publicacion, o `null` si no se pudo preguntar.
     *
     * Nullable por lo mismo que `publicaciones`: "no hay comentarios" y "no
     * pude leerlos" no son lo mismo, y en una hoja que se abre para leerlos la
     * diferencia es toda la pantalla. Ver el modulo Z.5.
     */
    suspend fun comentarios(convId: String, publicacionId: String): List<Comentario>? =
        runCatching { api.comentariosDeCanal(convId, publicacionId).comentarios }.getOrNull()

    /**
     * Los comentarios de un canal PRIVADO, que viven solo en este telefono.
     *
     * Un Flow y no una llamada: son mensajes locales y pueden llegar mas
     * mientras la hoja esta abierta.
     */
    fun comentariosLocales(convId: String, publicacionId: String): Flow<List<MensajeEnt>> =
        dao.comentariosLocales(convId, publicacionId)

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

    /**
     * Menciones que el remitente extrae del texto: @usuario.
     *
     * La regla vive en el contrato (`mencionesEn`) y no aqui, porque la
     * pantalla tiene que resaltar EXACTAMENTE lo que esto manda. Con dos
     * copias de la expresion, un cambio en una dibujaba menciones que no
     * avisaban a nadie.
     *
     * Ahora ademas quita repetidos: mencionar a alguien tres veces en la misma
     * frase mandaba su username tres veces y el servidor insertaba tres filas
     * en `mencion` para la misma persona.
     */
    private fun mencionesDe(texto: String): List<String> = mencionesEn(texto)

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

    /**
     * Enciende o apaga el temporizador de mensajes. `null` = permanentes.
     *
     * Primero el servidor y despues lo local, en ese orden: si el servidor
     * rechaza el cambio -sin permiso, sin red-, la excepcion sale antes de
     * tocar la base y este telefono no se queda creyendo un temporizador que
     * nadie mas tiene. Quien lo cree de mas borraria mensajes que a los demas
     * les quedan; quien lo crea de menos guardaria los que los demas borran.
     *
     * El aviso `conversacion_temporales` va a los OTROS participantes, no a
     * quien lo cambio: por eso hay que escribirlo aqui a mano.
     */
    suspend fun configurarTemporales(convId: String, segundos: Int?) {
        api.configurarTemporales(convId, segundos)
        dao.fijarTemporales(convId, segundos ?: 0)
    }

    /** El temporizador de mensajes de un chat, en segundos. `0` = permanentes. */
    suspend fun temporalesDe(convId: String): Int =
        dao.conversacion(convId)?.temporalesSegundos ?: 0

    /**
     * Lo mismo, observable: se repinta cuando el otro lado lo cambia.
     *
     * `filterNotNull` no vale aqui —un chat que no esta en la base todavia
     * tiene que emitir algo— asi que el nulo se traduce a `0`, que es
     * "permanentes" y es el defecto correcto mientras no se sepa.
     */
    fun temporalesFlow(convId: String): Flow<Int> =
        dao.conversacionFlow(convId).map { it?.temporalesSegundos ?: 0 }

    suspend fun reintentar(mensajeId: String) {
        dao.devolverACola(mensajeId)
        despachar()
    }

    /** Descarta un mensaje que fallo y no se va a reintentar. */
    suspend fun descartarFallido(mensajeId: String) = dao.borrarMensaje(mensajeId)

    /**
     * Candado del despacho. Ver [despachar].
     *
     * `Mutex` y no un `Boolean`: con un flag, dos corrutinas pueden leerlo en
     * `false` antes de que ninguna lo ponga en `true`. Es el mismo error que se
     * evita en el servidor consumiendo el codigo de respaldo con el UPDATE
     * mismo en vez de leer-y-despues-marcar.
     */
    private val candadoDespacho = Mutex()

    /**
     * Recorre la cola y entrega por el primer transporte disponible.
     * Si ninguno lo esta, los mensajes se quedan PENDIENTE sin perderse.
     *
     * ## Por que va con candado
     *
     * Porque la cola se selecciona por `estado = 'PENDIENTE'` y **no se marca
     * en vuelo**. Dos invocaciones a la vez recorren la MISMA lista y registran
     * y entregan los mismos sobres dos veces. Y pasa de verdad: hay ocho
     * llamadores, y al menos dos son `launch` hermanos del mismo ambito -el
     * `collect` de la reconexion y el manejo de `sinCopia`-, asi que una
     * reconexion mientras se completan copias los dispara juntos.
     *
     * El sintoma no se parece a la causa: el receptor descarta el duplicado por
     * el id -`OnConflictStrategy.IGNORE`, ver el `filas != -1L`-, asi que no se
     * ve un mensaje repetido. Se ve como trabajo de mas, cupo del servidor
     * gastado al doble y, en un grupo, claves de emisor confirmadas por un
     * envio que la otra corrutina ya estaba haciendo.
     *
     * `withLock` y no "salir si esta ocupado": si otra vuelta ya esta en curso,
     * lo correcto es esperarla y volver a mirar la cola. Salir en silencio
     * dejaria sin despachar justo lo que se acaba de encolar.
     */
    suspend fun despachar() = candadoDespacho.withLock {
        despacharSinCandado()
        // En UN solo sitio, al final y pase lo que pase dentro.
        //
        // `despacharSinCandado` tiene varios `return` tempranos -sin red, sin
        // destinos, sin sesion de cifrado-, y son justo los casos en que hace
        // falta reintentar mas tarde. Ponerlo en cada uno seria olvidarse en
        // el proximo que se agregue; aqui se cubre solo.
        runCatching { ColaEnSegundoPlano.ajustar(contexto, hayPendientes()) }
    }

    /** Si queda algo por salir. Lo usa el reintento en segundo plano. */
    suspend fun hayPendientes(): Boolean = dao.cola().isNotEmpty()

    /**
     * La hora con la que nace un mensaje mio: la de este telefono corregida
     * contra el servidor (`Reloj`), y nunca por debajo del ultimo mensaje de
     * la conversacion.
     *
     * Con el reloj atrasado, el mensaje recien escrito se ordenaba ENTRE los
     * viejos -un cuarto de hora arriba, con la hora quince minutos atrasada-
     * y quedaba fuera de la vista, debajo del compositor. Y ese mismo
     * mensaje le llegaba al otro enterrado igual.
     *
     * El "nunca por debajo del ultimo" cubre lo que el desfase no alcanza a
     * corregir: el primer mensaje sin ninguna medida, o uno que llego fechado
     * de mas por un telefono que todavia no se actualizo.
     */
    private suspend fun horaParaMio(convId: String): Long =
        maxOf(Reloj.ahora(), (dao.ultimoCreadoEn(convId) ?: 0L) + 1)

    /** Conversaciones de las que ya se aviso que no hay cifrado. Ver el despacho. */
    private val avisadosSinCifrado: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Por que no sale lo de cada conversacion atascada, en palabras.
     *
     * Antes la barra decia "Enviando 2 pendientes..." para siempre y no habia
     * forma de saber que mensajes eran, por que no salian ni de sacarlos de la
     * cola. Ver `HojaPendientes`.
     */
    private val _atascos = MutableStateFlow<Map<String, String>>(emptyMap())
    val atascos: StateFlow<Map<String, String>> = _atascos.asStateFlow()

    private fun anotarAtasco(conv: String, motivo: String) {
        if (_atascos.value[conv] != motivo) _atascos.value = _atascos.value + (conv to motivo)
    }

    private fun limpiarAtasco(conv: String) {
        if (conv in _atascos.value) _atascos.value = _atascos.value - conv
    }

    /** Lo que espera salir. */
    val pendientes: Flow<List<MensajeEnt>> = dao.colaFlow()

    /** Saca un mensaje de la cola: no se manda. */
    suspend fun descartarPendiente(id: String) {
        val m = dao.mensaje(id) ?: return
        if (m.esMio && m.estado == EstadoEnvio.PENDIENTE.name) dao.borrarMensaje(id)
    }

    private suspend fun despacharSinCandado() {
        val pendientes = dao.cola()
        if (pendientes.isEmpty()) return

        val activo = transportes.firstOrNull { it.disponible() }
        if (activo == null) {
            Log.i(TAG, "${pendientes.size} en cola, ningun transporte disponible")
            return
        }

        // Conversaciones que en esta pasada ya no pueden avanzar.
        //
        // El orden se respeta DENTRO de cada conversacion: si el primero de una
        // no sale, los siguientes de esa misma esperan, o llegarian
        // desordenados. Pero entre conversaciones no hay orden que cuidar.
        //
        // Antes estos casos hacian `return` y frenaban la cola entera: un
        // contacto sin claves publicadas -una cuenta que nunca termino de
        // arrancar, un dispositivo sin prekeys- dejaba sin salir los mensajes
        // a TODOS los demas, y la barra se quedaba en "Enviando 2
        // pendientes..." para siempre. Paso de verdad, en dos emuladores: el
        // mensaje a una persona en linea no salia porque delante habia dos a
        // una cuenta sin prekeys.
        //
        // Los fallos de TRANSPORTE siguen cortando todo, porque no son de una
        // conversacion: sin red no sale ninguna.
        val atascadas = mutableSetOf<String>()

        for (m in pendientes) {
            if (m.conversacionId in atascadas) continue
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
            // El vencimiento de MI copia.
            //
            // Manda lo que diga el servidor, que es quien lo sello al registrar
            // el mensaje. Pero si no lo dice —un servidor viejo, o el metadato
            // ya barrido— se calcula igual con el temporizador local, porque la
            // alternativa es peor: mi copia se quedaria para siempre mientras
            // la del otro se borra, y yo creeria que el mensaje ya no existe en
            // ningun lado justo cuando es al reves.
            val vence = registro.getOrNull()?.expiraEn
                ?: vencimientoDe(m.conversacionId, m.creadoEn).takeIf { it > 0 }
            vence?.let { dao.fijarVencimiento(m.id, it) }

            // Los destinos se piden ANTES de cifrar: con E2EE hay un cuerpo
            // por dispositivo y no se puede cifrar sin saber para quien.
            val destinos = destinosDe(m.conversacionId)
            if (destinos == null) {
                Log.w(TAG, "Sin destinos para ${m.conversacionId}, el mensaje espera")
                anotarAtasco(m.conversacionId, "No se pudo saber a qué aparatos mandarlo: sin red, o el servidor no respondió.")
                atascadas += m.conversacionId
                continue
            }

            // Si es grupo cambia el esquema de cifrado: clave de emisor en vez
            // de una sesion por dispositivo.
            val esGrupo = dao.conversacion(m.conversacionId)?.tipo == "grupo"
            val copias = runCatching { cifrador.cifrar(m.conversacionId, esGrupo, destinos, cargaDe(m)) }
                .getOrElse {
                    Log.w(TAG, "No se pudo cifrar ${m.id}: ${it.message}")
                    anotarAtasco(m.conversacionId, "No se pudo cifrar: ${it.message ?: "error desconocido"}.")
                    atascadas += m.conversacionId
                    null
                }
                ?: continue

            // Una conversacion donde nadie tiene claves publicadas todavia: el
            // mensaje se queda en cola. Es lo correcto: mandarlo sin cifrar
            // seria romper en silencio la promesa del producto.
            if (copias.isEmpty() && destinos.isNotEmpty()) {
                Log.w(TAG, "Nadie con sesion en ${m.conversacionId}; ${m.id} sigue en cola")
                anotarAtasco(
                    m.conversacionId,
                    "Todavía no hay cifrado con esa cuenta: no publicó sus claves. Sale solo cuando lo haga.",
                )
                // Una vez por conversacion, y diciendo con quien. Se emitia en
                // CADA pasada del despacho y lo mostraba el chat que estuviera
                // abierto: hablando con una persona saltaba, una y otra vez,
                // "no se pudo establecer el cifrado con esa persona" por
                // mensajes atascados a OTRA.
                if (avisadosSinCifrado.add(m.conversacionId)) {
                    val quien = dao.conversacion(m.conversacionId)
                        ?.let { "@" + it.nombreMostrado.ifBlank { it.nombre } } ?: "esa persona"
                    _rechazos.tryEmit(
                        "Todavía no se pudo establecer el cifrado con $quien. " +
                            "El mensaje sigue en cola y saldrá solo."
                    )
                }
                atascadas += m.conversacionId
                continue
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
            // Si vuelve a atascarse mas adelante, se vuelve a avisar.
            avisadosSinCifrado.remove(m.conversacionId)
            limpiarAtasco(m.conversacionId)
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
                silencioso = m.silencioso,
                previa = if (m.previaJson.isBlank()) null else runCatching {
                    jsonApp.decodeFromString(VistaPreviaEnlace.serializer(), m.previaJson)
                }.getOrNull(),
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
                onda = m.adjuntoOnda,
                pie = m.texto,
                miniatura = m.adjuntoMiniatura,
                silencioso = m.silencioso,
                reenviadoDe = m.reenviadoDe,
                unaVez = m.unaVez,
                forma = m.adjuntoForma,
                spoiler = m.spoiler,
            )
        }

    // ============================================================
    //  Cuenta y conversaciones
    // ============================================================

    suspend fun registrar(
        username: String,
        password: String,
        id: Hardware.Identidad,
        etiqueta: String,
        codigoInvitacion: String = "",
    ) {
        val antes = sesion.usuarioId
        api.registrar(
            RegistroReq(
                username = username, password = password, etiquetaDispositivo = etiqueta,
                identidadPub = id.identidadPub, hardwareHash = id.hardwareHash, hardwareNivel = id.nivel,
                codigoInvitacion = codigoInvitacion,
            )
        )
        limpiarSiCambioDeCuenta(antes)
    }

    /**
     * Si este servidor pide invitacion para registrarse.
     *
     * Ante un fallo devuelve `false`, o sea "abierto". Es la respuesta menos
     * danina de las dos: si el servidor SI pide codigo y aqui se asume que no,
     * el campo no aparece y el registro falla con el mensaje del servidor, que
     * lo explica. Al reves —asumir que pide codigo cuando no— se le plantaria
     * un campo obligatorio a quien no tiene ninguno, y ahi no hay salida.
     *
     * El caso normal de este fallo es no tener red todavia, y entonces el
     * registro tampoco va a funcionar.
     */
    suspend fun registroPideInvitacion(): Boolean =
        runCatching { api.modoRegistro().requiereInvitacion }.getOrElse { false }

    suspend fun crearInvitacion(usos: Int, diasValida: Int, nota: String): InvitacionResp =
        api.crearInvitacion(NuevaInvitacionReq(usos = usos, diasValida = diasValida, nota = nota))

    suspend fun listarInvitaciones(): List<InvitacionResp> = api.listarInvitaciones()

    suspend fun revocarInvitacion(codigo: String) = api.revocarInvitacion(codigo)

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
        // Las carpetas tambien son de la otra cuenta: sus nombres dicen de
        // quien eran.
        dao.borrarChatsEnCarpetas()
        dao.borrarCarpetas()
        Notificaciones.quitarSonidosPropios(contexto)
    }

    /**
     * Abre la "Nota para mi": la conversacion donde solo estoy yo.
     *
     * Vive en el servidor -ver V45- para que lo que se apunta en un aparato
     * aparezca en los otros. Pero si este aparato ya la conoce se abre sin
     * preguntar: apuntar algo sin red es justo uno de sus usos, y lo que se
     * escriba queda en la cola como cualquier mensaje.
     */
    suspend fun abrirNotaParaMi(): String {
        dao.notaParaMi()?.let { return it.id }
        return api.notaParaMi().also { guardarResumen(it) }.id
    }

    suspend fun nuevaDirecta(username: String, duracionMs: Long = 0): String =
        api.crearDirecta(username, duracionMs).also { guardarResumen(it) }.id

    suspend fun nuevoGrupo(
        nombre: String,
        usernames: List<String>,
        duracionMs: Long = 0,
    ): String = api.crearGrupo(nombre, usernames, duracionMs).also { guardarResumen(it) }.id

    /**
     * Anade a alguien a una llamada de dos, convirtiendola en una de grupo.
     *
     * ## Por que no se "invita" a la llamada actual
     *
     * Porque una llamada pertenece a una CONVERSACION, y esa es la pieza que
     * hace cumplir todo lo demas: el servidor autoriza llamar con
     * `mensaje.enviar` sobre la conversacion, asi que los bloqueos, el
     * silencio y la pertenencia se aplican a las llamadas sin codigo propio.
     *
     * Una llamada de dos vive en una conversacion DIRECTA, que tiene
     * exactamente dos miembros. Meter a un tercero ahi pedia una de dos:
     * permitir participantes fuera de la conversacion -y entonces se podria
     * arrastrar a alguien a una llamada con una persona que lo bloqueo- o
     * inventar un modelo de permisos propio para llamadas. Las dos son peores
     * que crear el grupo.
     *
     * Asi que se crea un grupo con los tres y se llama ahi. Se nota -la
     * llamada se corta y empieza otra- y es honesto: de verdad es otra
     * conversacion, y va a seguir existiendo despues de colgar.
     *
     * ## El orden NO es casual
     *
     * Primero se crea el grupo, DESPUES se cuelga, y al final se llama.
     *
     * Al reves -colgar primero- parece mas natural y es peor: si la creacion
     * del grupo fallara -sin red, sin permiso para agregar a esa persona- la
     * llamada ya estaria muerta y no habria nada que recuperar. Creando
     * primero, un fallo deja la llamada intacta y la persona solo ve un
     * aviso.
     *
     * Colgar antes de llamar SI hace falta: el servidor rechaza una llamada
     * nueva si ya estas en una (`ocupado`), asi que sin esto el `llamar` de
     * abajo devolveria 409.
     */
    suspend fun ampliarLlamadaAGrupo(
        otroUsuario: String,
        nuevoUsuario: String,
        conVideo: Boolean,
    ): Result<Unit> = runCatching {
        val nombre = nombreDeGrupoPara(listOf(otroUsuario, nuevoUsuario))
        val convId = nuevoGrupo(nombre, listOf(otroUsuario, nuevoUsuario))

        // De aqui en adelante, en el ambito de la APP y no en el de quien llama.
        //
        // Quien llama es la pantalla de la llamada, y colgar la saca de la
        // composicion: su `rememberCoroutineScope` se cancelaba durante el
        // `delay` y la llamada nueva no se pedia nunca. Paso de verdad en dos
        // emuladores: el grupo se creaba, la llamada vieja se colgaba (204) y
        // despues nada, ni llamada ni aviso. Parecia que el boton cortaba la
        // llamada sin mas.
        //
        // Un fallo aqui ya no puede volver por el `Result`: la pantalla que lo
        // mostraria ya no existe. Sale por `rechazos`, que lo ensena el chat
        // al que se vuelve al colgar.
        ambito.launch {
            llamadas.colgar(FinLlamada.COLGADA)
            // Un respiro para que el servidor procese el fin antes de pedir la
            // llamada nueva. Sin esto, `ocupado` puede ver todavia la anterior
            // y devolver 409 por una carrera de milisegundos.
            kotlinx.coroutines.delay(600)

            runCatching { llamadas.llamar(convId, nombre, conVideo).getOrThrow() }
                .onFailure {
                    Log.w(TAG, "Grupo creado pero la llamada no arranco: ${it.message}")
                    _rechazos.tryEmit(
                        "Se creó el grupo, pero la llamada no pudo empezar. Llama desde el grupo."
                    )
                }
        }
        Unit
    }

    /**
     * Un nombre para el grupo que se crea al ampliar una llamada.
     *
     * Con los nombres de la libreta, no los usernames: el grupo va a quedar
     * en la lista de chats y "Ana, Beto" se reconoce antes que dos arrobas.
     *
     * Se recorta a 64 porque es el limite del servidor, y se recorta por
     * NOMBRES enteros y no por caracteres: "Ana, Beto y 2 mas" se lee; "Ana,
     * Bet" parece un error.
     */
    private suspend fun nombreDeGrupoPara(usernames: List<String>): String {
        val alias = runCatching { nombresDeLibreta(usernames) }.getOrDefault(emptyMap())
        val nombres = usernames.map { alias[it] ?: it }
        val junto = nombres.joinToString(", ")
        return if (junto.length <= 64) junto else {
            val primero = nombres.first().take(40)
            "$primero y ${nombres.size - 1} mas"
        }
    }

    /**
     * La libreta, tal como esta en este telefono.
     *
     * Local y no del servidor a proposito: lo usa el selector de "anadir a la
     * llamada", y en medio de una llamada no es momento de esperar una
     * peticion de red que puede tardar o fallar.
     */
    suspend fun contactosLocales(): List<ContactoEnt> =
        runCatching { dao.libreta().sortedBy { (it.alias.ifBlank { it.username }).lowercase() } }
            .getOrDefault(emptyList())

    /**
     * A quien se puede sumar a la llamada con [enLlamada]. Local, por lo mismo
     * que [contactosLocales]. La regla esta en `candidatosParaLlamada`.
     */
    suspend fun candidatosParaLlamada(enLlamada: String): List<CandidatoLlamada> =
        runCatching {
            candidatosParaLlamada(
                libreta = dao.libreta(),
                directas = dao.directasRecientes(),
                excluir = listOf(enLlamada, sesion.username.orEmpty()),
            )
        }.getOrDefault(emptyList())

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
        Notificaciones.quitarSonidoPropio(contexto, convId)
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
        // Las carpetas tambien son de la otra cuenta: sus nombres dicen de
        // quien eran.
        dao.borrarChatsEnCarpetas()
        dao.borrarCarpetas()
        dao.borrarRecordatorios()
        Notificaciones.quitarSonidosPropios(contexto)
        // Los atajos y el widget hablan de esta cuenta.
        Atajos.borrarTodos(contexto)
        WidgetWtfuck.actualizar(contexto, 0)
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
 * La clase inventada para los enlaces.
 *
 * No es una clase del contrato: un enlace no es un adjunto, es un mensaje de
 * texto que ademas lleva una direccion. Vive aqui, del lado de la vista, y
 * por eso lleva un nombre que no puede chocar con los del protocolo.
 */
const val CLASE_ENLACE = "_enlace"

/**
 * Que contenido con estructura cuenta como "algo compartido".
 *
 * Una ubicacion se comparte; un voto de una encuesta no. La diferencia es si
 * tiene sentido volver a buscarlo despues, que es para lo que existe esta
 * pantalla.
 */
val CLASES_COMPARTIBLES = setOf(
    ClaseContenido.UBICACION,
    ClaseContenido.CONTACTO,
    ClaseContenido.ENCUESTA,
)
