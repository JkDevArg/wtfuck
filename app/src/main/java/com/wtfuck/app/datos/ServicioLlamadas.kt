package com.wtfuck.app.datos

import android.content.Context
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
    val fase: Fase,
    val silenciado: Boolean = false,
    val camaraActiva: Boolean = true,
    val altavoz: Boolean = false,
    /** Cuando se conecto. Sirve para el cronometro; 0 mientras no conecta. */
    val conectadaEn: Long = 0,
    val motivoFin: String? = null,
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
class ServicioLlamadas(
    private val ctx: Context,
    private val api: ApiCliente,
    private val sesion: Sesion,
    /** Manda una carga cifrada a UN dispositivo. Lo implementa el Repositorio. */
    private val enviarCifrado: suspend (convId: String, dispositivoId: String, carga: Carga) -> Unit,
) {

    private val TAG = "Llamadas"
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _estado = MutableStateFlow<EstadoLlamada?>(null)
    val estado: StateFlow<EstadoLlamada?> = _estado.asStateFlow()

    private val _videoRemoto = MutableStateFlow<VideoTrack?>(null)
    val videoRemoto: StateFlow<VideoTrack?> = _videoRemoto.asStateFlow()

    private val _videoLocal = MutableStateFlow<VideoTrack?>(null)
    val videoLocal: StateFlow<VideoTrack?> = _videoLocal.asStateFlow()

    private val motores = ConcurrentHashMap<String, MotorWebRtc>()
    private var turn: ConfigTurn = ConfigTurn()

    private val audio by lazy { ctx.getSystemService(AudioManager::class.java) }

    // ============================================================
    //  Llamar
    // ============================================================

    suspend fun llamar(convId: String, conQuien: String, conVideo: Boolean): Result<Unit> =
        runCatching {
            if (_estado.value != null) error("Ya hay una llamada en curso.")

            val creada = api.iniciarLlamada(IniciarLlamadaReq(convId, conVideo))
            turn = creada.turn

            _estado.value = EstadoLlamada(
                llamadaId = creada.llamadaId,
                conversacionId = convId,
                conQuien = conQuien,
                conVideo = conVideo,
                saliente = true,
                fase = EstadoLlamada.Fase.SONANDO,
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

    suspend fun finEntrante(f: Carga.LlamadaFin) {
        val e = _estado.value ?: return
        if (e.llamadaId != f.llamadaId) return
        limpiar(f.motivo)
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

        val motor = MotorWebRtc(
            ctx = ctx,
            turn = turn,
            conVideo = conVideo,
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
                    when {
                        conectado && e.fase != EstadoLlamada.Fase.EN_CURSO ->
                            _estado.value = e.copy(
                                fase = EstadoLlamada.Fase.EN_CURSO,
                                conectadaEn = System.currentTimeMillis(),
                            )

                        // Solo se cuelga cuando NO queda ningun motor vivo: en
                        // una llamada de grupo, que se caiga una conexion no
                        // debe cortar las otras.
                        terminado -> {
                            motores.remove(dispositivoId)?.colgar()
                            if (motores.isEmpty()) {
                                ambito.launch { colgar(FinLlamada.FALLO_RED) }
                            }
                        }
                    }
                }

                override fun onPistaRemota(pista: org.webrtc.MediaStreamTrack) {
                    if (pista is VideoTrack) _videoRemoto.value = pista
                }
            },
        )
        motores[dispositivoId] = motor
        _videoLocal.value = motor.pistaLocal
        return motor
    }

    private fun limpiar(motivo: String?) {
        motores.values.forEach { runCatching { it.colgar() } }
        motores.clear()
        ofertasPendientes.clear()
        _videoRemoto.value = null
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

    /** Al arrancar: si la app se cerro en medio de una llamada, aqui aparece. */
    suspend fun recuperar() {
        val l = runCatching { api.llamadaEnCurso() }.getOrNull() ?: return
        // No se reconstruye el medio: las sesiones WebRTC murieron con el
        // proceso y no hay forma de retomarlas. Lo unico honesto es colgar y
        // que el historial quede coherente.
        Log.i(TAG, "Habia una llamada abierta al arrancar: se cierra")
        runCatching { api.terminarLlamada(l.llamadaId, FinLlamada.FALLO_RED) }
    }

    private fun uuid(s: String) = runCatching { UUID.fromString(s) }.getOrNull()
}
