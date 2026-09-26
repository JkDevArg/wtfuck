package com.wtfuck.app.datos

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * El motor de una llamada: una sola conexion WebRTC contra un dispositivo.
 *
 * ## Que hace y que NO hace
 *
 * Hace el medio: captura, codecs, ICE, y el cifrado DTLS-SRTP que WebRTC trae
 * de fabrica. **No hace señalizacion.** El SDP y los candidatos salen por aqui
 * como texto y quien los mueve es el repositorio, dentro de sobres cifrados.
 *
 * Esa separacion es el punto del modulo: si la señalizacion fuera por una ruta
 * del servidor en claro, el servidor podria cambiar las huellas DTLS del SDP,
 * montar dos llamadas -una con cada lado- y escuchar todo, con cada tramo
 * perfectamente cifrado *contra el*. Metiendola en sobres cifrados, el servidor
 * mueve bytes opacos.
 *
 * ## Una conexion por DISPOSITIVO, no por persona
 *
 * Con multi-dispositivo y con llamadas de grupo hay varias conexiones a la vez,
 * y cada una es una instancia de esto. Por eso la clase no sabe nada de
 * "la llamada": sabe de un tunel contra un aparato. El servicio de arriba es el
 * que junta N motores y decide cuando la llamada empezo o termino.
 */
interface MotorLlamada {
    /** Crea la oferta local. Devuelve el SDP para mandar cifrado. */
    suspend fun ofertar(): String

    /** Aplica la oferta remota y devuelve la respuesta local. */
    suspend fun responder(sdpRemoto: String): String

    /** Aplica la respuesta remota a una oferta que hicimos. */
    suspend fun aplicarRespuesta(sdpRemoto: String)

    fun agregarCandidato(candidato: String, sdpMid: String?, indice: Int)

    /** Micro abierto o cerrado. */
    fun silenciar(silenciado: Boolean)

    /** Camara encendida o apagada. Sin efecto en una llamada de solo audio. */
    fun verVideo(activo: Boolean)

    fun colgar()
}

/**
 * Lo que el motor necesita avisar hacia arriba.
 *
 * Son callbacks y no un Flow porque llegan desde hilos de WebRTC y el servicio
 * los reenvia: meter un Flow aqui obligaria a cada motor a tener su propio
 * ambito de corrutinas para algo que solo se reenvia.
 */
interface OyenteLlamada {
    /** Un candidato ICE local, listo para mandar cifrado (trickle). */
    fun onCandidato(candidato: String, sdpMid: String?, indice: Int)

    /**
     * Cambio de estado de la conexion. Son **tres** combinaciones, no dos:
     *
     * | `conectado` | `terminado` | Que paso |
     * |---|---|---|
     * | `true` | `false` | Ya se oye |
     * | `false` | `false` | Se corto y puede volver -cambio de red- |
     * | `false` | `true` | Se cayo de verdad y no vuelve |
     *
     * La del medio es la que faltaba. Sin ella, una conexion caida se veia
     * igual que una viva: la pantalla decia "con joaquin" y el cronometro
     * seguia corriendo contra un telefono muerto.
     */
    fun onEstado(conectado: Boolean, terminado: Boolean)

    /** Llego una pista remota. El servicio decide si la pinta o solo la oye. */
    fun onPistaRemota(pista: MediaStreamTrack)
}

/**
 * Fabrica de conexiones, una sola por proceso.
 *
 * `PeerConnectionFactory.initialize` **no se puede llamar dos veces** y crear
 * dos fabricas duplica los hilos de audio nativos. Con llamadas de grupo en
 * malla hay varias conexiones a la vez y todas comparten esto.
 */
object FabricaWebRtc {

    private const val TAG = "WebRtc"

    @Volatile private var fabrica: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    val egl: EglBase.Context? get() = eglBase?.eglBaseContext

    @Synchronized
    fun obtener(ctx: Context): PeerConnectionFactory {
        fabrica?.let { return it }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                // Sin trazas nativas: WebRTC en verbose llena la bitacora y en
                // una llamada eso compite con la propia llamada por la CPU.
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val base = EglBase.create()
        eglBase = base

        // El modulo de audio se construye a mano y no por defecto, por una
        // sola razon: `setAudioBufferCallback` es el unico sitio donde se
        // puede meter mano al audio del microfono ANTES de que WebRTC lo
        // procese, y es donde el modo cine suma lo que esta sonando.
        //
        // Cuando el modo cine esta apagado, `mezclar` sale en la primera
        // linea. El costo en una llamada normal es una llamada a funcion cada
        // 10 ms, que no se mide.
        val audio = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setAudioBufferCallback { pcm, _, canales, ritmo, bytes, marca ->
                runCatching { AudioDeCine.mezclar(pcm, canales, ritmo, bytes) }
                // Se devuelve la marca tal cual: no se esta cambiando CUANDO
                // se capturo, solo que hay dentro.
                marca
            }
            .createAudioDeviceModule()

        val nueva = PeerConnectionFactory.builder()
            // El hardware primero: el software cae a 320x240 y calienta el
            // telefono. `true, true` = intentar H264 y VP8 por hardware.
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()

        fabrica = nueva
        Log.i(TAG, "PeerConnectionFactory lista")
        return nueva
    }
}

/**
 * Un tunel WebRTC contra un dispositivo.
 *
 * ## Por que Unified Plan y trickle ICE
 *
 * Unified Plan es el unico que admite el navegador moderno y el unico que
 * permite varias pistas del mismo tipo, que es lo que hace falta para una
 * llamada de grupo. Trickle ICE manda los candidatos a medida que aparecen en
 * vez de esperar a tenerlos todos: juntarlos ahorraria unos sobres y agregaria
 * uno o dos segundos hasta que se oye la voz, que es justo lo que se nota.
 */
class MotorWebRtc(
    private val ctx: Context,
    private val turn: com.wtfuck.protocol.ConfigTurn,
    private val conVideo: Boolean,
    /**
     * Lo que este telefono emite, **compartido con los demas motores**.
     *
     * Antes cada motor abria su propia camara, y Android no da dos sesiones
     * sobre la misma: en una videollamada de tres, solo una persona veia tu
     * camara. Ver [MediosLocales].
     */
    private val medios: MediosLocales,
    private val oyente: OyenteLlamada,
) : MotorLlamada {

    private val TAG = "MotorWebRtc"

    private val fabrica = FabricaWebRtc.obtener(ctx)

    private val pc: PeerConnection = crearConexion()

    /**
     * Candidatos que llegaron ANTES de tener descripcion remota.
     *
     * Pasa de verdad: con trickle, el otro lado empieza a mandar candidatos en
     * cuanto hace la oferta, y pueden llegar antes de que nosotros hayamos
     * aplicado esa oferta. `addIceCandidate` antes de la descripcion remota se
     * descarta en silencio, y el sintoma es una llamada que nunca conecta sin
     * ningun error. Por eso se guardan y se aplican despues.
     */
    private val pendientes = mutableListOf<IceCandidate>()
    @Volatile private var hayRemoto = false

    /** Que ya se colgo. Ver [colgar]: liberar dos veces es una caida nativa. */
    private val colgado = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun crearConexion(): PeerConnection {
        val servidores = buildList {
            // STUN publico de Google para descubrir la IP publica. No ve el
            // medio: solo responde "te veo desde aqui".
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            if (turn.hay) {
                add(
                    PeerConnection.IceServer.builder(turn.urls)
                        .setUsername(turn.usuario)
                        .setPassword(turn.clave)
                        .createIceServer()
                )
            }
        }

        val cfg = PeerConnection.RTCConfiguration(servidores).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Todas las rutas: host, STUN y TURN. Con `RELAY` funcionaria
            // siempre pero pagando relevo incluso en la misma red.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // No hay nada que activar para el cifrado del medio.
            //
            // `enableDtlsSrtp` existia y se quito de la API: DTLS-SRTP dejo de
            // ser opcional en WebRTC y ya no hay forma de negociar SDES ni de
            // apagarlo. Que la bandera no compile es la mejor noticia posible
            // -significa que no se puede configurar mal-, y vale anotarlo aqui
            // porque su ausencia se lee como si nadie hubiera pensado en el
            // cifrado.
        }

        val conexion = fabrica.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                oyente.onCandidato(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }

            override fun onIceConnectionChange(estado: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE: $estado")
                when (estado) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED,
                    -> oyente.onEstado(conectado = true, terminado = false)

                    // FAILED es la unica caida de verdad.
                    //
                    // CLOSED NO se avisa, aunque parezca lo mismo: solo ocurre
                    // porque nosotros cerramos la conexion, y `close()` lo
                    // dispara de forma sincrona en el hilo de señalizacion. Si
                    // se avisara, el servicio -que interpreta "terminado" como
                    // "se cayo"- volveria a colgar ESTE motor desde dentro del
                    // callback, mientras el colgado original sigue en curso:
                    // dos `dispose()` sobre el mismo objeto nativo y SIGSEGV.
                    // Se veia como la app cerrandose entera al colgar.
                    PeerConnection.IceConnectionState.FAILED,
                    -> oyente.onEstado(conectado = false, terminado = true)

                    // DISCONNECTED no es el final: puede recuperarse solo
                    // cuando cambia la red. Tratarlo como fin cortaria la
                    // llamada cada vez que el telefono pasa de wifi a datos.
                    //
                    // Pero **si hay que avisarlo**, y antes no se avisaba: es
                    // la tercera combinacion, `conectado = false` y
                    // `terminado = false`, o sea "ahora mismo no se oye, pero
                    // puede volver". Sin ella la pantalla seguia diciendo "con
                    // joaquin" y el cronometro corriendo con el otro telefono
                    // muerto; se vio en un emulador, 0:54 contra una conexion
                    // que llevaba medio minuto caida.
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    -> oyente.onEstado(conectado = false, terminado = false)

                    else -> Unit
                }
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { oyente.onPistaRemota(it) }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionReceivingChange(p0: Boolean) = Unit
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) = Unit
            override fun onAddStream(p0: org.webrtc.MediaStream?) = Unit
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(p0: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
        }) ?: error("No se pudo crear la conexion WebRTC")

        agregarMedioLocal(conexion)
        return conexion
    }

    /**
     * Engancha a esta conexion lo que YA se esta capturando.
     *
     * No crea nada. Las pistas son las de [MediosLocales] y la misma pista se
     * anade a todas las conexiones — se puede porque todas salen de la misma
     * `PeerConnectionFactory`.
     *
     * La pista de video se anade **siempre**, tambien en una llamada de solo
     * audio. Ahi no lleva fotogramas, pero deja el `m=` negociado desde el
     * principio: es lo que permite encender el modo cine sin renegociar, que
     * esta app no sabe hacer.
     */
    private fun agregarMedioLocal(conexion: PeerConnection) {
        conexion.addTrack(medios.audio, listOf("wtfuck"))
        conexion.addTrack(medios.video, listOf("wtfuck"))
    }


    val pistaLocal: VideoTrack? get() = medios.video

    // ============================================================
    //  Señalizacion: entra y sale como texto
    // ============================================================

    override suspend fun ofertar(): String {
        val sdp = crearSdp(oferta = true)
        aplicarLocal(sdp)
        return sdp.description
    }

    override suspend fun responder(sdpRemoto: String): String {
        aplicarRemoto(SessionDescription(SessionDescription.Type.OFFER, sdpRemoto))
        val sdp = crearSdp(oferta = false)
        aplicarLocal(sdp)
        return sdp.description
    }

    override suspend fun aplicarRespuesta(sdpRemoto: String) {
        aplicarRemoto(SessionDescription(SessionDescription.Type.ANSWER, sdpRemoto))
    }

    override fun agregarCandidato(candidato: String, sdpMid: String?, indice: Int) {
        val c = IceCandidate(sdpMid, indice, candidato)
        // Si todavia no hay descripcion remota, se guarda. `addIceCandidate`
        // antes de eso se descarta EN SILENCIO y la llamada no conecta nunca
        // sin dar ningun error.
        synchronized(pendientes) {
            if (!hayRemoto) {
                pendientes += c
                return
            }
        }
        pc.addIceCandidate(c)
    }

    // Las dos tocan la pista COMPARTIDA, asi que valen para todos los
    // participantes a la vez. Es lo correcto —silenciarse es silenciarse para
    // todos, no para uno— y ademas hace que llamarlas una vez por motor,
    // como hace el servicio, sea idempotente en lugar de contradictorio.
    override fun silenciar(silenciado: Boolean) {
        medios.audio.setEnabled(!silenciado)
    }

    override fun verVideo(activo: Boolean) {
        medios.video.setEnabled(activo)
    }

    override fun colgar() {
        // ================================================================
        //  El orden de esto NO es estetico: al reves, la app se cae
        // ================================================================
        //
        // La PeerConnection se cierra PRIMERO. Liberar una fuente de audio o
        // de video mientras la conexion sigue viva deja al track nativo
        // apuntando a memoria liberada, y el proceso se muere de SIGSEGV en el
        // hilo de señalizacion de WebRTC en cuanto llega el siguiente callback
        // de estado. No es una excepcion de Kotlin: es una caida nativa, asi
        // que ningun `runCatching` la atrapa -de hecho aqui habia seis y la
        // app se moria igual, sin dejar ni un rastro en el log de la app-.
        //
        // `close()` detiene los transportes y deja de emitir callbacks; a
        // partir de ahi las fuentes se pueden soltar. `dispose()` va al final,
        // porque libera el objeto nativo entero.
        //
        // La captura se para antes de soltarla para que la camara quede libre:
        // si no, la siguiente llamada abre una pantalla en negro.
        // Colgar dos veces libera dos veces, y lo segundo es una caida
        // nativa. Pasa mas facil de lo que parece: colgar a mano y una caida
        // de red pueden llegar a la vez desde hilos distintos.
        if (!colgado.compareAndSet(false, true)) return

        // Solo la conexion. Las pistas y los capturadores son de
        // [MediosLocales], que es de la llamada entera y no de este motor:
        // soltarlos aqui dejaria a los OTROS motores con pistas muertas.
        // Quien creo los medios los libera cuando termina la llamada.
        runCatching { pc.dispose() }
    }

    // ============================================================
    //  Puentes a la API de callbacks de WebRTC
    // ============================================================

    private suspend fun crearSdp(oferta: Boolean): SessionDescription =
        suspendCoroutine { cont ->
            val restricciones = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                // SIEMPRE, tambien en una llamada de solo audio.
                //
                // Con `false`, una llamada de audio negocia sin `m=video` y
                // encender el modo cine despues exigiria renegociar. Aceptarlo
                // desde el principio cuesta unas lineas de SDP y ningun medio:
                // sin nadie capturando, por ahi no viaja un solo byte.
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            val obs = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(e: String?) =
                    cont.resumeWithException(IllegalStateException("No se pudo crear el SDP: $e"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(e: String?) = Unit
            }
            if (oferta) pc.createOffer(obs, restricciones) else pc.createAnswer(obs, restricciones)
        }

    private suspend fun aplicarLocal(sdp: SessionDescription) = suspendCoroutine<Unit> { cont ->
        pc.setLocalDescription(observadorSet(cont), sdp)
    }

    private suspend fun aplicarRemoto(sdp: SessionDescription) {
        suspendCoroutine<Unit> { cont -> pc.setRemoteDescription(observadorSet(cont), sdp) }
        // Recien ahora los candidatos guardados sirven.
        val cola = synchronized(pendientes) {
            hayRemoto = true
            pendientes.toList().also { pendientes.clear() }
        }
        cola.forEach { pc.addIceCandidate(it) }
        if (cola.isNotEmpty()) Log.i(TAG, "Aplicados ${cola.size} candidatos que llegaron antes")
    }

    private fun observadorSet(cont: kotlin.coroutines.Continuation<Unit>) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) = Unit
        override fun onCreateFailure(p0: String?) = Unit
        override fun onSetSuccess() = cont.resume(Unit)
        override fun onSetFailure(e: String?) =
            cont.resumeWithException(IllegalStateException("No se pudo aplicar el SDP: $e"))
    }
}
