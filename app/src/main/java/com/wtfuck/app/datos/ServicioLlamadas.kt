package com.wtfuck.app.datos

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.wtfuck.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * El estado de la llamada, tal como lo necesita la pantalla.
 *
 * Es un solo objeto y no varios flujos porque la pantalla de una llamada
 * cambia como un todo: pasar de "sonando" a "en curso" mueve los botones, el
 * texto y el video a la vez. Con flujos separados habria un instante en que la
 * pantalla dice "sonando" con los botones de "en curso".
 */
data class EstadoLlamada(
    val llamadaId: String,
    val conversacionId: String,
    val conQuien: String,
    val conVideo: Boolean,
    /** La inicie yo. Decide si se muestra "llamando..." o "contestar/rechazar". */
    val saliente: Boolean,
    /**
     * Quien esta en la llamada, por DISPOSITIVO: id del aparato -> username.
     *
     * Por dispositivo y no por persona, igual que los motores, porque esa es
     * la unidad real: alguien con telefono y tablet son dos conexiones y dos
     * recuadros. Y el nombre hace falta para rotular cada recuadro: una
     * rejilla de cuatro videos sin nombres no dice quien es quien.
     */
    val participantes: Map<String, String> = emptyMap(),
    /**
     * En que anda cada persona: username -> `sonando` | `dentro` | `rechazo` |
     * `fuera`.
     *
     * Por PERSONA y no por dispositivo, al reves que [participantes], y eso es
     * deliberado: los recuadros de video son uno por aparato, pero "rechazo la
     * llamada" es algo que hace una persona, no un telefono. Si alguien
     * rechaza desde el movil teniendo la tablet abierta, lo que hay que
     * mostrar es que dijo que no.
     */
    val estadoDe: Map<String, String> = emptyMap(),
    /**
     * El nombre del grupo, cuando la llamada sale de uno. Vacio en una directa.
     *
     * Existe para quien RECIBE. Veia solo "@tatiana" y no tenia como saber
     * que era una llamada de grupo ni de cual: contestar sin saber quien mas
     * esta del otro lado no es lo mismo que contestarle a una persona.
     */
    val grupo: String = "",
    val fase: Fase,
    val silenciado: Boolean = false,
    val camaraActiva: Boolean = true,
    val altavoz: Boolean = false,
    /** Cuando se conecto. Sirve para el cronometro; 0 mientras no conecta. */
    val conectadaEn: Long = 0,
    val motivoFin: String? = null,
    /**
     * Modulo AV · Quien esta presentando su pantalla, o `null`.
     *
     * Es el USERNAME y no un booleano porque la pantalla llega por la misma
     * pista de video que una cara: sin saber de quien es, la rejilla no puede
     * rotularla ni decidir dibujarla entera en vez de recortada.
     *
     * Uno solo a la vez, a proposito. Dos pantallas compartidas en una
     * llamada de cuatro es una rejilla de recuadros ilegibles, y ademas el
     * caso real es que uno muestra y los demas miran.
     */
    val presentando: String? = null,
    /**
     * Si el que presenta soy YO.
     *
     * Aparte de [presentando] y no deducido comparando nombres: el servicio en
     * primer plano necesita este dato para pedir el tipo `mediaProjection`, y
     * ahi no hay a mano quien soy. Un booleano explicito es mas barato que
     * arrastrar el username hasta el servicio para volver a compararlo.
     */
    val presentoYo: Boolean = false,
) {
    enum class Fase { SONANDO, CONECTANDO, EN_CURSO, TERMINADA }
}

/**
 * El servicio de llamadas: junta N motores WebRTC con la señalizacion cifrada.
 *
 * ## Donde vive la señalizacion, y por que ahi
 *
 * El SDP y los candidatos NO van por una ruta del servidor: van dentro de
 * sobres cifrados, por el mismo camino que un mensaje. El motivo es concreto:
 * el SDP contiene la **huella del certificado DTLS** de cada lado, y esa huella
 * es lo unico que hace que el cifrado del medio signifique algo. Un servidor
 * que pudiera cambiarla montaria dos llamadas -una con cada lado- y escucharia
 * todo, con cada tramo perfectamente cifrado *contra el*.
 *
 * Lo que si va por HTTP son los metadatos: iniciar, contestar, terminar. El
 * servidor necesita saber quien llama a quien para autorizar y para el
 * historial, y eso ya lo sabe de todas formas.
 *
 * ## Un motor por DISPOSITIVO
 *
 * No por persona. Con multi-dispositivo, llamar a alguien con telefono y tablet
 * son dos conexiones; y una llamada de grupo en malla son N-1. Por eso el mapa
 * es `dispositivoId -> MotorWebRtc`: es la unidad real.
 *
 * ## Lo que NO esta, y hay que decirlo
 *
 * No hay servicio en primer plano. Una llamada sobrevive mientras la app este
 * viva; si Android mata el proceso, se corta. Para una llamada de verdad hace
 * falta un `ForegroundService` con su notificacion, y eso es trabajo aparte
 * -no una linea- porque arrastra el ciclo de vida de toda la llamada fuera de
 * la Activity.
 */
/**
 * Los estados de una persona dentro de una llamada.
 *
 * Constantes y no cadenas sueltas porque las usan el servicio, la pantalla y
 * las pruebas, y una cadena mal escrita en cualquiera de los tres no la caza
 * el compilador: la fila simplemente deja de coincidir y la persona
 * desaparece de la lista sin que nada falle.
 *
 * Los tres primeros los manda el servidor. `cayo` es **solo del cliente**: el
 * servidor no sabe que se cayo una conexion WebRTC, porque la señalizacion va
 * cifrada y el medio no pasa por el.
 */
const val ESTADO_SONANDO = "sonando"
const val ESTADO_DENTRO = "dentro"
const val ESTADO_RECHAZO = "rechazo"
const val ESTADO_CAIDO = "cayo"

class ServicioLlamadas(
    private val ctx: Context,
    private val api: ApiCliente,
    private val sesion: Sesion,
    /** Manda una carga cifrada a UN dispositivo. Lo implementa el Repositorio. */
    private val enviarCifrado: suspend (convId: String, dispositivoId: String, carga: Carga) -> Unit,
    /**
     * El nombre del grupo de una conversacion, o vacio si es una directa.
     *
     * Se inyecta en vez de leer la base aqui por la misma razon que
     * `enviarCifrado`: este servicio no sabe nada de Room ni del Repositorio,
     * y esa frontera es lo que lo hace probable.
     */
    private val nombreDeGrupo: suspend (convId: String) -> String = { "" },
) {

    private val TAG = "Llamadas"
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _estado = MutableStateFlow<EstadoLlamada?>(null)
    val estado: StateFlow<EstadoLlamada?> = _estado.asStateFlow()

    /**
     * Los videos remotos, por dispositivo.
     *
     * ## Por que un mapa y no una pista
     *
     * Era `VideoTrack?`, o sea UNA. Con dos personas funciona; con tres, cada
     * pista que llega pisa a la anterior y **gana la ultima**: se ve a uno
     * solo y ademas cambia sin motivo aparente. El servidor admitia llamadas
     * de hasta cuatro desde el modulo K y la app solo sabia dibujar una.
     *
     * La clave es el dispositivo porque es la unidad de la malla: un motor,
     * una pista, un recuadro.
     */
    /**
     * Los dispositivos cuya conexion esta viva AHORA.
     *
     * No alcanza con `motores`: en una llamada de grupo hay un motor por cada
     * persona que todavia suena, creado al cerrar la malla, y esos no
     * conectaron nunca. Un motor que existe no es una conversacion.
     */
    private val conectados = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    private val _videosRemotos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val videosRemotos: StateFlow<Map<String, VideoTrack>> = _videosRemotos.asStateFlow()

    private val _videoLocal = MutableStateFlow<VideoTrack?>(null)
    val videoLocal: StateFlow<VideoTrack?> = _videoLocal.asStateFlow()

    private val motores = ConcurrentHashMap<String, MotorWebRtc>()

    /**
     * Lo que este telefono emite, **uno para toda la llamada**.
     *
     * Vive aqui y no en cada motor porque la camara —y la proyeccion de
     * pantalla— no se pueden abrir dos veces. Ver [MediosLocales].
     *
     * Se crea con el primer motor y se libera en `limpiar`, no al colgar un
     * motor: colgar con UNA persona de una llamada de tres no puede apagarle
     * la camara a las otras dos.
     */
    private var medios: MediosLocales? = null
    private var turn: ConfigTurn = ConfigTurn()

    private val audio by lazy { ctx.getSystemService(AudioManager::class.java) }

    // ============================================================
    //  Llamar
    // ============================================================

    /**
     * @param invitados a quienes hacer sonar, por `usuarioId`. Vacio = a todos,
     *   que es lo correcto en una directa y en un grupo que entra entero.
     */
    suspend fun llamar(
        convId: String,
        conQuien: String,
        conVideo: Boolean,
        invitados: List<String> = emptyList(),
    ): Result<Unit> =
        runCatching {
            if (_estado.value != null) error("Ya hay una llamada en curso.")

            val creada = api.iniciarLlamada(IniciarLlamadaReq(convId, conVideo, invitados))
            turn = creada.turn

            _estado.value = EstadoLlamada(
                llamadaId = creada.llamadaId,
                conversacionId = convId,
                conQuien = conQuien,
                conVideo = conVideo,
                saliente = true,
                fase = EstadoLlamada.Fase.SONANDO,
                participantes = creada.destinos.associate { it.dispositivoId to it.username },
                // Todos empiezan sonando. Los avisos del servidor los van
                // moviendo a `dentro` o `rechazo` segun contesten o no.
                estadoDe = creada.destinos.associate { it.username to "sonando" },
                grupo = runCatching { nombreDeGrupo(convId) }.getOrDefault(""),
            )

            // K.8. DESPUES de fijar el estado, y el orden no es un detalle: el
            // servicio en primer plano se apaga solo cuando la llamada
            // termina, y "terminada" lo lee del estado. Arrancandolo antes,
            // veia null -la llamada todavia no existia-, se detenia a si mismo
            // y Android mataba la app entera por prometer un servicio en
            // primer plano y no darlo. El sintoma era una app que se cerraba al
            // llamar, y el error del sistema no decia nada de la causa.
            ServicioLlamadaFg.arrancar(ctx)
            modoLlamada(true)

            // Una oferta POR DISPOSITIVO. No es una optimizacion pendiente: con
            // E2EE no hay un cuerpo unico que sirva para todos, y cada
            // dispositivo tiene su propia sesion de Signal y su propia huella
            // DTLS.
            for (d in creada.destinos) {
                ambito.launch {
                    runCatching {
                        val motor = crearMotor(d.dispositivoId, conVideo)
                        val sdp = motor.ofertar()
                        enviarCifrado(
                            convId, d.dispositivoId,
                            Carga.LlamadaOferta(creada.llamadaId, sdp, conVideo),
                        )
                    }.onFailure { Log.w(TAG, "No se pudo ofertar a ${d.dispositivoId}: ${it.message}") }
                }
            }
        }.onFailure { limpiar(null) }

    /**
     * Llega una oferta. Es el timbre.
     *
     * No hace falta un aviso aparte para que suene: este sobre llega por el
     * mismo socket y en el mismo instante que cualquier mensaje.
     */
    suspend fun ofertaEntrante(convId: String, deQuien: String, dispositivoOrigen: String, o: Carga.LlamadaOferta) {
        val actual = _estado.value

        // Una oferta de la llamada en la que YA estoy no es un timbre: es otro
        // participante cerrando la malla conmigo. Se responde en el acto, sin
        // volver a sonar ni pedir que se conteste otra vez.
        if (actual != null && actual.llamadaId == o.llamadaId &&
            actual.fase != EstadoLlamada.Fase.SONANDO
        ) {
            runCatching {
                val motor = crearMotor(dispositivoOrigen, actual.conVideo)
                val sdp = motor.responder(o.sdp)
                enviarCifrado(
                    convId, dispositivoOrigen,
                    Carga.LlamadaRespuesta(actual.llamadaId, sdp),
                )
                _estado.value = _estado.value?.let {
                    it.copy(participantes = it.participantes + (dispositivoOrigen to deQuien))
                }
            }.onFailure { Log.w(TAG, "No se pudo cerrar la malla: ${it.message}") }
            return
        }

        if (actual != null && actual.llamadaId != o.llamadaId) {
            // Ocupado. Se rechaza en el acto en vez de dejar sonar las dos.
            runCatching {
                enviarCifrado(convId, dispositivoOrigen, Carga.LlamadaFin(o.llamadaId, FinLlamada.OCUPADO))
            }
            return
        }

        // Una llamada de grupo trae una oferta por cada participante: la
        // primera arma el estado, las demas solo agregan su motor.
        if (actual == null) {
            turn = runCatching { api.turn() }.getOrElse { ConfigTurn() }
            _estado.value = EstadoLlamada(
                llamadaId = o.llamadaId,
                conversacionId = convId,
                conQuien = deQuien,
                conVideo = o.conVideo,
                saliente = false,
                fase = EstadoLlamada.Fase.SONANDO,
                grupo = runCatching { nombreDeGrupo(convId) }.getOrDefault(""),
            )
        }

        // K.8. Puede fallar -desde Android 12 no se puede arrancar un
        // servicio en primer plano desde el fondo- y esta bien que falle: la
        // notificacion normal sigue avisando. Lo que se pierde es la promesa
        // de que el proceso sobreviva, no la llamada.
        ServicioLlamadaFg.arrancar(ctx)

        // El SDP se guarda y NO se responde todavia: responder aqui
        // conectaria el audio antes de que la persona conteste, que es una
        // llamada que se escucha sola.
        ofertasPendientes[dispositivoOrigen] = o
    }

    private val ofertasPendientes = ConcurrentHashMap<String, Carga.LlamadaOferta>()

    suspend fun contestar(): Result<Unit> = runCatching {
        val e = _estado.value ?: error("No hay llamada que contestar.")
        val curso = api.contestarLlamada(e.llamadaId)
        turn = curso.turn
        _estado.value = e.copy(fase = EstadoLlamada.Fase.CONECTANDO)
        modoLlamada(true)

        // Se anota con quien se esta hablando, para rotular los recuadros, y en
        // que anda cada uno.
        //
        // `estados` es la foto del momento de entrar y hace falta porque los
        // avisos cuentan CAMBIOS: quien se une a una llamada que ya empezo se
        // perdio los anteriores, y alguien que entro antes no va a emitir uno
        // nuevo para el recien llegado. Sin esto se le mostraria como
        // "sonando" para siempre.
        _estado.value = _estado.value?.copy(
            participantes = curso.destinos.associate { it.dispositivoId to it.username },
            estadoDe = curso.estados,
        )

        // Recien ahora se responde a cada oferta guardada.
        for ((dispositivo, oferta) in ofertasPendientes) {
            ambito.launch {
                runCatching {
                    val motor = crearMotor(dispositivo, e.conVideo)
                    val sdp = motor.responder(oferta.sdp)
                    enviarCifrado(
                        e.conversacionId, dispositivo,
                        Carga.LlamadaRespuesta(e.llamadaId, sdp),
                    )
                }.onFailure { Log.w(TAG, "No se pudo responder a $dispositivo: ${it.message}") }
            }
        }
        ofertasPendientes.clear()

        // Y se cierra la MALLA con los demas.
        //
        // ## El agujero que esto tapa
        //
        // Quien llama ofrece a todos, y cada uno le responde a el. Pero entre
        // ellos no pasaba nada: en una llamada de tres, B y C hablaban los dos
        // con A y **no se oian entre si**. La llamada de grupo existia a
        // medias y parecia un fallo de red.
        //
        // ## El desempate, y por que hace falta
        //
        // Si B y C se ofrecen a la vez, cada uno recibe una oferta mientras
        // espera una respuesta: es el *glare* clasico de WebRTC y deja las dos
        // conexiones a medio negociar. Hace falta que **exactamente uno** de
        // los dos ofrezca, decidido sin hablarlo.
        //
        // Se compara el id del dispositivo, que los dos lados ya conocen y es
        // unico: ofrece el menor. No necesita ningun mensaje extra y los dos
        // llegan siempre a la misma conclusion.
        //
        // La regla vive en [Malla] y no aqui: de esa comparacion depende que
        // una llamada de grupo conecte o no, y alli se puede probar sin
        // WebRTC, sin red y sin tres telefonos.
        val mio = sesion.dispositivoId
        if (mio != null) {
            val aOfrecer = Malla.aQuienesOfrecer(
                mio = mio,
                candidatos = curso.destinos.map { it.dispositivoId },
                yaConectados = motores.keys.toSet(),
            ).toSet()
            for (d in curso.destinos) {
                if (d.dispositivoId !in aOfrecer) continue
                ambito.launch {
                    runCatching {
                        val motor = crearMotor(d.dispositivoId, e.conVideo)
                        val sdp = motor.ofertar()
                        enviarCifrado(
                            e.conversacionId, d.dispositivoId,
                            Carga.LlamadaOferta(e.llamadaId, sdp, e.conVideo),
                        )
                    }.onFailure {
                        Log.w(TAG, "No se pudo cerrar la malla con ${d.dispositivoId}: ${it.message}")
                    }
                }
            }
        }
    }

    suspend fun respuestaEntrante(dispositivoOrigen: String, r: Carga.LlamadaRespuesta) {
        val motor = motores[dispositivoOrigen] ?: return
        _estado.value = _estado.value?.copy(fase = EstadoLlamada.Fase.CONECTANDO)
        runCatching { motor.aplicarRespuesta(r.sdp) }
            .onFailure { Log.w(TAG, "Respuesta invalida de $dispositivoOrigen: ${it.message}") }
    }

    fun candidatoEntrante(dispositivoOrigen: String, c: Carga.LlamadaCandidato) {
        motores[dispositivoOrigen]?.agregarCandidato(c.candidato, c.sdpMid, c.sdpMLineIndex)
    }

    /**
     * El servidor dice que la llamada termino.
     *
     * Es la red de seguridad del `LlamadaFin` cifrado: si el otro telefono se
     * queda sin bateria, o si el timbre se agota por tiempo -que lo decide el
     * servidor, no el cliente-, nadie manda el sobre y sin esto la pantalla
     * seguiria sonando para siempre.
     */
    fun terminadaPorServidor(llamadaId: String, motivo: String) {
        val e = _estado.value ?: return
        if (e.llamadaId != llamadaId) return
        Log.i(TAG, "El servidor dio la llamada por terminada: $motivo")
        limpiar(motivo)
    }

    /**
     * Lo que le paso a UNA persona de una llamada que sigue viva.
     *
     * Existe por lo que arreglo el modulo AF: que uno rechace ya no corta el
     * timbre de los demas, y sin este aviso la llamada seguia pero quien
     * llamaba no se enteraba de nada. En una llamada de tres, B declinaba y la
     * pantalla de A decia "llamando" por B durante los cuarenta y cinco
     * segundos del timbre.
     *
     * No cierra nada a proposito: un `llamada_terminada` colgaria, y aqui la
     * llamada sigue.
     */
    fun participanteCambio(llamadaId: String, quien: String, estado: String) {
        val e = _estado.value ?: return
        if (e.llamadaId != llamadaId) return
        _estado.value = e.copy(estadoDe = e.estadoDe + (quien to estado))
    }

    /**
     * Alguien se fue de la llamada.
     *
     * ## Que se arreglo
     *
     * Esto llamaba a `limpiar()` sin mirar nada: **el primer "fin" que llegara
     * cerraba la pantalla**. Con dos personas esta bien —si el otro cuelga, la
     * llamada se acabo— y es lo unico que existia cuando se escribio.
     *
     * En una llamada de grupo estaba mal, y es el gemelo exacto del defecto
     * que AF arreglo en el servidor: alli la regla era `dentro >= 2 &&
     * en_curso` y cualquier rechazo mataba la llamada entera. Se arreglo el
     * servidor y el cliente siguio haciendo lo mismo por su cuenta: se vio en
     * el emulador, con la llamada viva en la base —`dentro tatiana`, `sonando
     * rocio`— y la pantalla de quien llamo de vuelta en el chat.
     *
     * > Arreglar una instancia de un defecto no es arreglar el defecto.
     *
     * Ahora se va **ese aparato** y la llamada sigue mientras quede alguien.
     */
    suspend fun finEntrante(dispositivoOrigen: String, f: Carga.LlamadaFin) {
        val e = _estado.value ?: return
        if (e.llamadaId != f.llamadaId) return

        motores.remove(dispositivoOrigen)?.colgar()
        ofertasPendientes.remove(dispositivoOrigen)
        _videosRemotos.value = _videosRemotos.value - dispositivoOrigen
        val quien = e.participantes[dispositivoOrigen]
        _estado.value = e.copy(
            participantes = e.participantes - dispositivoOrigen,
            // El motivo del sobre dice si dijo que no o si estuvo y se fue. Es
            // el mismo par que maneja el servidor.
            estadoDe = if (quien == null) e.estadoDe else {
                e.estadoDe + (quien to if (f.motivo == FinLlamada.RECHAZADA) "rechazo" else "fuera")
            },
        )

        // Y se cierra solo cuando no queda nadie con quien hablar. Se cuentan
        // los aparatos que siguen en la llamada, no los motores: mientras
        // alguien SUENA todavia no hay motor de su lado y cerrar ahi seria
        // colgarle a quien aun podia contestar.
        if (_estado.value?.participantes.isNullOrEmpty()) limpiar(f.motivo)
    }

    // ============================================================
    //  Colgar
    // ============================================================

    suspend fun colgar(motivo: String = FinLlamada.COLGADA): Result<Unit> = runCatching {
        val e = _estado.value ?: return@runCatching
        // El sobre cifrado le dice al otro telefono que corte YA; la ruta HTTP
        // es la que deja el registro. Si fuera solo el sobre, una llamada
        // cortada sin red quedaria "en curso" para siempre en el historial.
        // Los destinos son los motores MAS las ofertas sin contestar.
        //
        // Mientras suena todavia no hay ningun motor -crearlo antes de
        // contestar conectaria el audio solo-, asi que rechazar una llamada
        // mirando solo `motores` no avisaba a nadie: el que llamaba se quedaba
        // con "Llamando..." hasta que el servidor cortara por tiempo, 45
        // segundos despues. El que rechaza espera que el otro deje de sonar
        // ya.
        val destinos = (motores.keys + ofertasPendientes.keys).toSet()
        for (dispositivo in destinos) {
            runCatching {
                enviarCifrado(e.conversacionId, dispositivo, Carga.LlamadaFin(e.llamadaId, motivo))
            }
        }
        runCatching { api.terminarLlamada(e.llamadaId, motivo) }
        limpiar(motivo)
    }

    suspend fun rechazar(): Result<Unit> = colgar(FinLlamada.RECHAZADA)

    /**
     * Otro de mis aparatos contesto: este tiene que callarse.
     *
     * Es el caso que solo existe con multi-dispositivo. Sin esto, contestar en
     * el telefono deja la tablet sonando hasta que alguien la silencie a mano.
     */
    fun contestadaEnOtroAparato(llamadaId: String) {
        val e = _estado.value ?: return
        if (e.llamadaId != llamadaId || e.saliente) return
        Log.i(TAG, "Contestada en otro dispositivo: este deja de sonar")
        limpiar(null)
    }

    fun silenciar() {
        val e = _estado.value ?: return
        val nuevo = !e.silenciado
        motores.values.forEach { it.silenciar(nuevo) }
        _estado.value = e.copy(silenciado = nuevo)
    }

    fun camara() {
        val e = _estado.value ?: return
        val nuevo = !e.camaraActiva
        motores.values.forEach { it.verVideo(nuevo) }
        _estado.value = e.copy(camaraActiva = nuevo)
    }

    // ============================================================
    //  Modulo AV · Modo cine
    // ============================================================

    /**
     * Empieza a mostrar la pantalla a quien esta en la llamada.
     *
     * @param permiso lo que devolvio el dialogo del sistema. Ahi es donde la
     *   persona elige **que** mostrar —una app sola o la pantalla entera—; esa
     *   eleccion la hace el sistema y esta app no la ve ni puede influir en
     *   ella, que es como tiene que ser.
     *
     * ## El orden no es negociable
     *
     * Primero se marca el estado y se sube el servicio a primer plano con el
     * tipo `mediaProjection`, y **despues** se crea el capturador. Al reves,
     * Android 14 lanza al entregar la proyeccion. Ver `subirAProyeccion`.
     */
    fun iniciarCine(permiso: Intent): Boolean {
        val e = _estado.value ?: return false
        val m = medios ?: return false
        if (e.fase != EstadoLlamada.Fase.EN_CURSO) return false

        val yo = sesion.username.orEmpty()
        _estado.value = e.copy(presentando = yo, presentoYo = true)
        ServicioLlamadaFg.subirAProyeccion(_estado.value)

        val ok = m.pantalla(permiso) {
            // El sistema puede cortar la proyeccion por su cuenta: la persona
            // toca "Dejar de compartir" en la barra del sistema, o otra app
            // pide la proyeccion. Sin atender eso, la llamada seguiria
            // anunciando que alguien presenta sin emitir nada.
            ambito.launch { runCatching { detenerCine() } }
        }
        if (!ok) {
            _estado.value = _estado.value?.copy(presentando = null, presentoYo = false)
            ServicioLlamadaFg.subirAProyeccion(_estado.value)
            return false
        }

        // Mas presupuesto y prioridad a los fotogramas: se esta mirando algo
        // que se mueve, no una cara. Ver `MotorWebRtc.ajustarVideo`.
        motores.values.forEach { it.ajustarVideo(BITRATE_CINE, fluido = true) }

        ambito.launch { avisarPantalla(true) }
        return true
    }

    /** Deja de presentar y vuelve a lo que habia antes. */
    suspend fun detenerCine() {
        val e = _estado.value ?: return
        if (!e.presentoYo) return
        val m = medios

        // Volver a la camara SOLO si la llamada era de video. En una de audio
        // no habia imagen antes del cine y no tiene que haberla despues:
        // encender la camara al dejar de presentar seria mostrarle la cara a
        // alguien que no la pidio.
        if (e.conVideo) m?.camara() else m?.detenerCaptura()

        motores.values.forEach { it.ajustarVideo(BITRATE_CAMARA, fluido = false) }
        _estado.value = e.copy(presentando = null, presentoYo = false)
        ServicioLlamadaFg.subirAProyeccion(_estado.value)
        avisarPantalla(false)
    }

    private suspend fun avisarPantalla(activo: Boolean) {
        val e = _estado.value ?: return
        // A cada dispositivo por separado, como el resto de la senalizacion:
        // va cifrado y el servidor no sabe que es.
        for (dispositivo in motores.keys) {
            runCatching {
                enviarCifrado(
                    e.conversacionId, dispositivo,
                    Carga.LlamadaPantalla(e.llamadaId, activo),
                )
            }
        }
    }

    private companion object {
        /**
         * Techos de subida, en bits por segundo.
         *
         * 3 Mbit/s para el modo cine y 1,2 para una camara. Son techos, no
         * objetivos: WebRTC gasta menos si el enlace no da, y estos numeros
         * solo le dicen hasta donde puede subir si da.
         *
         * La diferencia no es capricho. Una cara a 640x480 no mejora por
         * encima de 1 Mbit/s —lo unico que cambia es el consumo de datos de
         * quien llama—, y una pantalla de 560x1264 con imagen en movimiento a
         * 1 Mbit/s se deshace en bloques en cada corte de plano.
         *
         * En malla esto se multiplica por participante, que es el motivo de
         * que no sean mas altos.
         */
        const val BITRATE_CINE = 3_000_000
        const val BITRATE_CAMARA = 1_200_000
    }

    /** El otro lado empezo o dejo de presentar. */
    fun pantallaEntrante(dispositivoOrigen: String, p: Carga.LlamadaPantalla) {
        val e = _estado.value ?: return
        if (e.llamadaId != p.llamadaId) return
        val quien = e.participantes[dispositivoOrigen] ?: return
        _estado.value = if (p.activo) {
            e.copy(presentando = quien)
        } else {
            // Solo si el que deja de presentar es el que estaba presentando.
            // Sin esta comprobacion, un aviso viejo que llega tarde apaga la
            // presentacion de OTRA persona.
            if (e.presentando == quien) e.copy(presentando = null) else e
        }
    }

    fun altavoz() {
        val e = _estado.value ?: return
        val nuevo = !e.altavoz
        runCatching { audio?.isSpeakerphoneOn = nuevo }
        _estado.value = e.copy(altavoz = nuevo)
    }

    // ============================================================
    //  Piezas
    // ============================================================

    private fun crearMotor(dispositivoId: String, conVideo: Boolean): MotorWebRtc {
        motores[dispositivoId]?.let { return it }

        val m = medios ?: MediosLocales(ctx, FabricaWebRtc.obtener(ctx)).also {
            medios = it
            // La camara arranca con el primer motor y ya vale para todos los
            // que vengan despues: los siguientes solo enganchan la pista.
            if (conVideo) it.camara()
        }

        val motor = MotorWebRtc(
            ctx = ctx,
            turn = turn,
            conVideo = conVideo,
            medios = m,
            oyente = object : OyenteLlamada {
                override fun onCandidato(candidato: String, sdpMid: String?, indice: Int) {
                    val e = _estado.value ?: return
                    ambito.launch {
                        runCatching {
                            enviarCifrado(
                                e.conversacionId, dispositivoId,
                                Carga.LlamadaCandidato(e.llamadaId, candidato, sdpMid, indice),
                            )
                        }
                    }
                }

                override fun onEstado(conectado: Boolean, terminado: Boolean) {
                    val e = _estado.value ?: return
                    val quien = e.participantes[dispositivoId]
                    when {
                        conectado -> {
                            conectados += dispositivoId
                            _estado.value = e.copy(
                                fase = EstadoLlamada.Fase.EN_CURSO,
                                // Si ya estaba en curso se conserva el reloj:
                                // volver de una caida no reinicia la llamada.
                                conectadaEn = if (e.fase == EstadoLlamada.Fase.EN_CURSO) {
                                    e.conectadaEn
                                } else {
                                    System.currentTimeMillis()
                                },
                                estadoDe = if (quien == null) {
                                    e.estadoDe
                                } else {
                                    e.estadoDe + (quien to ESTADO_DENTRO)
                                },
                            )
                        }

                        // Se cayo, definitivamente o no. Las dos ramas hacen
                        // casi lo mismo y la diferencia esta declarada en
                        // [Malla.trasCaida]: la transitoria nunca cuelga.
                        else -> {
                            conectados -= dispositivoId
                            if (terminado) {
                                motores.remove(dispositivoId)?.colgar()
                                // Y se quita su recuadro: dejarlo deja un
                                // video congelado de alguien que ya no esta,
                                // que es peor que no mostrar nada.
                                _videosRemotos.value = _videosRemotos.value - dispositivoId
                            }

                            _estado.value = e.copy(
                                participantes = if (terminado) {
                                    e.participantes - dispositivoId
                                } else {
                                    e.participantes
                                },
                                // Se marca CAIDO, no se saca de la lista: para
                                // el servidor esa persona sigue en la llamada
                                // —no colgo— y decir "no entro" seria mentir.
                                // Lo que pasa es que ahora no se la oye.
                                estadoDe = if (quien == null) {
                                    e.estadoDe
                                } else {
                                    e.estadoDe + (quien to ESTADO_CAIDO)
                                },
                            )

                            // La decision vive en [Malla.trasCaida] porque
                            // antes era `if (motores.isEmpty())` y eso no se
                            // cumplia nunca en una llamada de grupo: hay un
                            // motor por cada persona que todavia suena, y
                            // ninguno conecto. La llamada seguia con el
                            // cronometro andando sin nadie al otro lado.
                            val ahora = _estado.value
                            val sonando = ahora?.estadoDe
                                ?.count { it.value == ESTADO_SONANDO } ?: 0
                            when (Malla.trasCaida(conectados.size, sonando, terminado)) {
                                TrasCaida.SEGUIR -> Unit
                                TrasCaida.ESPERAR -> _estado.value = ahora?.copy(
                                    fase = EstadoLlamada.Fase.CONECTANDO,
                                    conectadaEn = 0L,
                                )
                                TrasCaida.COLGAR ->
                                    ambito.launch { colgar(FinLlamada.FALLO_RED) }
                            }
                        }
                    }
                }

                override fun onPistaRemota(pista: org.webrtc.MediaStreamTrack) {
                    // Se guarda BAJO SU DISPOSITIVO. Antes se asignaba a una
                    // sola variable y la ultima pista tapaba a las demas.
                    if (pista is VideoTrack) {
                        _videosRemotos.value = _videosRemotos.value + (dispositivoId to pista)
                    }
                }
            },
        )
        motores[dispositivoId] = motor
        _videoLocal.value = motor.pistaLocal
        return motor
    }

    private fun limpiar(motivo: String?) {
        motores.values.forEach { runCatching { it.colgar() } }
        // DESPUES de colgar los motores y no antes: una fuente liberada bajo
        // una conexion viva deja al track nativo apuntando a memoria muerta,
        // que es un cierre de la app y no una excepcion.
        runCatching { medios?.liberar() }
        medios = null
        conectados.clear()
        _videosRemotos.value = emptyMap()
        motores.clear()
        ofertasPendientes.clear()
        _videoLocal.value = null
        modoLlamada(false)
        _estado.value = _estado.value?.copy(fase = EstadoLlamada.Fase.TERMINADA, motivoFin = motivo)
        // Se deja un instante el estado TERMINADA para que la pantalla pueda
        // mostrar "llamada finalizada" antes de cerrarse sola. Sin eso, la
        // pantalla desaparece y no queda claro si se colgo o si fallo.
        ambito.launch {
            kotlinx.coroutines.delay(1500)
            if (_estado.value?.fase == EstadoLlamada.Fase.TERMINADA) _estado.value = null
        }
    }

    /**
     * Enruta el audio como una llamada y no como musica.
     *
     * `MODE_IN_COMMUNICATION` es lo que activa la cancelacion de eco del
     * sistema y baja la latencia. Sin esto, el audio sale por el altavoz
     * multimedia y se realimenta con el micro.
     */
    private fun modoLlamada(activo: Boolean) {
        runCatching {
            audio?.mode = if (activo) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            if (!activo) audio?.isSpeakerphoneOn = false
        }
    }

    suspend fun historial(): List<LlamadaEnHistorial> =
        runCatching { api.historialLlamadas().llamadas }.getOrElse { emptyList() }

    /**
     * Al arrancar: si la app se cerro en medio de una llamada, aqui aparece.
     *
     * ## Lo que NO hay que hacer, y se hacia
     *
     * Esto colgaba **cualquier** llamada que el servidor devolviera. El
     * razonamiento era correcto para una llamada en la que yo ya estaba: las
     * sesiones WebRTC murieron con el proceso y no se retoman, asi que lo
     * honesto es colgar y dejar el historial coherente.
     *
     * Pero el servidor tambien devuelve **la llamada que me esta sonando**, y
     * esa no tiene nada que recuperar: todavia no empezo. Colgarla significaba
     * que abrir la app con una llamada entrante la mataba antes de que sonara
     * —y abrir la app al ver el aviso es exactamente lo que hace cualquiera—.
     *
     * Ahora decide por `miEstado`, no por que exista la llamada:
     *
     *  - `dentro`: yo estaba en ella y el medio se perdio. Se cierra.
     *  - `sonando`: me esta llamando. **No se toca.** La oferta cifrada sigue
     *    en el buzon y hace sonar el telefono cuando llega, como siempre.
     */
    suspend fun recuperar() {
        val l = runCatching { api.llamadaEnCurso() }.getOrNull() ?: return
        if (l.miEstado == "sonando") {
            Log.i(TAG, "Hay una llamada sonando al arrancar: se deja sonar")
            return
        }
        Log.i(TAG, "Habia una llamada abierta al arrancar: se cierra")
        runCatching { api.terminarLlamada(l.llamadaId, FinLlamada.FALLO_RED) }
    }

    private fun uuid(s: String) = runCatching { UUID.fromString(s) }.getOrNull()
}
