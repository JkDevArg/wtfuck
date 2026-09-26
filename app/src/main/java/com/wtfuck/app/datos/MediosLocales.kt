package com.wtfuck.app.datos

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Lo que este teléfono **emite**, una sola vez para toda la llamada.
 *
 * ## El defecto que esto arregla
 *
 * Había un `MotorWebRtc` por dispositivo —tres personas en una llamada son
 * tres motores— y **cada uno abría su propia cámara**. Android no permite dos
 * sesiones sobre la misma cámara: la primera la toma y las demás reciben
 * `ERROR_CAMERA_IN_USE`.
 *
 * O sea que en una videollamada de tres, **sólo una persona veía tu cámara**.
 * Las otras recibían audio y un recuadro vacío, sin ningún error visible: el
 * capturador falla en silencio y la llamada sigue.
 *
 * No lo vio ninguna prueba porque las evidencias de llamadas de grupo son de
 * llamadas de **audio**. La rejilla de vídeos se había probado con dos.
 *
 * ## Por qué esto además hace posible el modo cine
 *
 * Por el mismo motivo, sólo que peor: `MediaProjection` tampoco se captura dos
 * veces, y encima pide permiso al sistema una vez por captura. Con un
 * capturador por motor, presentar la pantalla a tres personas habría pedido
 * tres confirmaciones y funcionado para una.
 *
 * Compartir la captura es lo que hace que las dos cosas funcionen, y es
 * posible porque todas las conexiones salen de la MISMA
 * [PeerConnectionFactory]: una pista se puede añadir a varias conexiones.
 *
 * ## Por qué la pista de vídeo existe siempre
 *
 * Incluso en una llamada de sólo audio. No por descuido: añadir una pista a
 * una conexión ya negociada obliga a **renegociar**, y esta app no lo hace
 * —`onRenegotiationNeeded` está vacío a propósito, porque renegociar en malla
 * trae el mismo problema de choque que se resolvió en `Malla` para la oferta
 * inicial, multiplicado por cada cambio—.
 *
 * Con la pista puesta desde el principio, encender el modo cine en una llamada
 * de audio es empezar a capturar sobre algo que ya está negociado. Cuesta unas
 * líneas de SDP y ninguna renegociación.
 */
class MediosLocales(
    private val ctx: Context,
    private val fabrica: PeerConnectionFactory,
) {

    private val TAG = "MediosLocales"

    private val audioSource: AudioSource = fabrica.createAudioSource(
        MediaConstraints().apply {
            // Los tres de siempre. Sin cancelación de eco, una llamada con
            // altavoz se realimenta y es inusable.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
    )

    /**
     * `isScreencast = false`, también para el modo cine, y es deliberado.
     *
     * Esa bandera decide qué sacrifica WebRTC cuando el enlace no da: en
     * `true` mantiene la resolución y tira fotogramas —lo correcto para unas
     * diapositivas, donde lo que importa es leer el texto— y en `false`
     * mantiene los fotogramas y baja la resolución.
     *
     * El modo cine es para mirar algo que se mueve. Una película a resolución
     * perfecta y cinco fotogramas por segundo no es una película; borrosa y
     * fluida, sí. Así que se queda el comportamiento de cámara.
     */
    private val videoSource: VideoSource = fabrica.createVideoSource(false)

    /**
     * Uno NUEVO por capturador, no uno reutilizado.
     *
     * Compartirlo entre la camara y la pantalla parecia lo razonable —es caro
     * de crear y solo hay una captura a la vez— y no funciona: al cambiar de
     * cámara a pantalla, del otro lado dejaban de llegar fotogramas. Cero.
     * Sin ningun error: el capturador nuevo arrancaba, decia que si, y no
     * salia una sola imagen.
     *
     * Se vio comparando dos pruebas que deberian haber dado lo mismo. Desde
     * una llamada de AUDIO el modo cine funcionaba —ahi no habia camara antes,
     * asi que el ayudante estaba recien hecho— y desde una de VIDEO no. La
     * diferencia no era el tipo de llamada: era si el ayudante venia usado.
     */
    private var ayudante: SurfaceTextureHelper? = null

    val audio: AudioTrack = fabrica.createAudioTrack("audio0", audioSource)
    val video: VideoTrack = fabrica.createVideoTrack("video0", videoSource)

    private var capturador: VideoCapturer? = null

    /** Qué se está capturando ahora. */
    var fuente: Fuente = Fuente.NADA
        private set

    enum class Fuente { NADA, CAMARA, PANTALLA }

    // ------------------------------------------------------------------
    // Cámara
    // ------------------------------------------------------------------

    fun camara(): Boolean {
        if (fuente == Fuente.CAMARA) return true
        val cap = abrirCamara() ?: run {
            Log.w(TAG, "Sin camara disponible")
            return false
        }
        // 640x480 a 24 fps. No es un número mágico: es lo que cabe en el
        // enlace de subida de datos móviles cuando hay que subir el vídeo una
        // vez por participante en una llamada en malla.
        return arrancar(cap, 640, 480, 24, Fuente.CAMARA)
    }

    private fun abrirCamara(): VideoCapturer? {
        val enumerador = if (Camera2Enumerator.isSupported(ctx)) {
            Camera2Enumerator(ctx)
        } else {
            Camera1Enumerator(false)
        }
        // La frontal primero: en una videollamada uno se filma a sí mismo.
        val nombres = enumerador.deviceNames
        val frontal = nombres.firstOrNull { enumerador.isFrontFacing(it) } ?: nombres.firstOrNull()
        return frontal?.let { enumerador.createCapturer(it, null) }
    }

    // ------------------------------------------------------------------
    // Pantalla · modo cine
    // ------------------------------------------------------------------

    /**
     * Empieza a emitir la pantalla en vez de la cámara.
     *
     * @param permiso el `Intent` que devolvió el diálogo del sistema. No se
     *   puede fabricar: sale de `createScreenCaptureIntent()` y lo firma el
     *   sistema tras preguntarle a la persona qué quiere mostrar.
     *
     * ## Lo que NO se va a ver, y no tiene arreglo
     *
     * Una app con contenido protegido —HBO, Netflix, Disney+— marca su ventana
     * con `FLAG_SECURE`. El sistema entrega **negro** en su lugar, por diseño:
     * es el mecanismo del DRM y esquivarlo sería exactamente lo que existe
     * para impedir. La pantalla se comparte igual; lo que se ve ahí es negro.
     *
     * Lo demás sí: un vídeo propio, un navegador sin DRM, un juego, una
     * presentación, fotos.
     */
    fun pantalla(permiso: Intent, alRevocar: () -> Unit): Boolean {
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // El sistema puede cortar la proyección por su cuenta: la
                // persona toca "Dejar de compartir" en la barra, o otra app
                // pide la proyección. Si no se atiende, la app seguiría
                // anunciando que presenta sin emitir nada — que es la clase de
                // mentira que peor sienta en una llamada.
                Log.i(TAG, "El sistema corto la proyeccion")
                alRevocar()
            }
        }
        val cap = runCatching { ScreenCapturerAndroid(permiso, cb) }.getOrElse {
            Log.w(TAG, "No se pudo crear el capturador de pantalla: ${it.message}")
            return false
        }
        // El tamaño se calcula con la FORMA de la pantalla, no con un
        // 1280x720 fijo.
        //
        // `ScreenCapturerAndroid` crea una pantalla virtual de exactamente el
        // tamaño que se le pide y mete ahí lo que hay, con bandas negras si no
        // encaja. Pedirle apaisado para una pantalla vertical daba justo eso:
        // del otro lado llegaba una franja estrecha en medio de un cuadro
        // negro, con la imagen reducida a una fracción del sitio disponible.
        //
        // Se ve enseguida en una captura y no se ve nunca leyendo el código.
        val (ancho, alto) = tamanoDePantalla()
        if (!arrancar(cap, ancho, alto, 24, Fuente.PANTALLA)) return false

        // El sonido, con la MISMA proyección que la imagen.
        //
        // `getMediaProjection()` la devuelve ya creada por el capturador. Pedir
        // otra no sería sólo redundante: el resultado del diálogo del sistema
        // vale una vez, así que la segunda petición fallaría y el modo cine
        // habría quedado mudo sin ninguna razón visible.
        //
        // Que el audio falle no cancela nada: se comparte igual, en silencio.
        // Pasa siempre con una app que se niega a ser capturada, y quedarse sin
        // imagen por eso sería cambiar un problema por uno peor.
        runCatching { cap.mediaProjection }.getOrNull()?.let { AudioDeCine.arrancar(it) }
        return true
    }

    // ------------------------------------------------------------------
    // El intercambio
    // ------------------------------------------------------------------

    /**
     * Cambia de capturador sin tocar la pista.
     *
     * Esto es lo que evita renegociar: la pista y su `m=` en el SDP no se
     * mueven, sólo cambia quién le da fotogramas. Del otro lado no hay nada
     * que volver a acordar — la imagen simplemente cambia.
     */
    private fun arrancar(cap: VideoCapturer, ancho: Int, alto: Int, fps: Int, cual: Fuente): Boolean {
        detenerCaptura()
        val helper = SurfaceTextureHelper.create("captura-$cual", FabricaWebRtc.egl)
            ?: return false
        ayudante = helper
        return runCatching {
            cap.initialize(helper, ctx, videoSource.capturerObserver)
            cap.startCapture(ancho, alto, fps)
            capturador = cap
            fuente = cual
            true
        }.getOrElse {
            Log.w(TAG, "No arranco la captura ($cual): ${it.message}")
            runCatching { cap.dispose() }
            fuente = Fuente.NADA
            false
        }
    }

    /** Deja de emitir imagen. La pista sigue existiendo, vacía. */
    fun detenerCaptura() {
        // Siempre, aunque no hubiera capturador: si el sonido quedara
        // encendido, la otra persona seguiria oyendo lo que suena aqui sin ver
        // nada y sin que nada lo dijera. De los dos fallos posibles, ese es el
        // que no se puede permitir.
        AudioDeCine.detener()
        val c = capturador
        capturador = null
        fuente = Fuente.NADA
        if (c != null) {
            runCatching { c.stopCapture() }
            runCatching { c.dispose() }
        }
        // El ayudante se va CON su capturador. Ver la nota de `ayudante`.
        val h = ayudante
        ayudante = null
        runCatching { h?.dispose() }
    }

    /**
     * La pantalla, reducida para que quepa en el enlace.
     *
     * El lado largo se limita a 1280 y el otro se calcula, así la proporción
     * es la de verdad y no hay bandas.
     *
     * ## Múltiplos de 16, y no de 2
     *
     * La primera versión redondeaba a par, que es lo que pide la teoría del
     * submuestreo de color. No alcanza. Con la forma real de esta pantalla
     * salía **570x1278**, y con eso del otro lado llegaban CERO fotogramas —
     * sin ningún error, sin excepción, sin nada en el log: la captura corría,
     * la pantalla virtual estaba viva, y no salía una imagen.
     *
     * Los codificadores por hardware trabajan en macrobloques de 16 y muchos
     * rechazan en silencio lo que no encaja. Se perdía medio por ciento de
     * imagen y se ganaba que funcione.
     */
    private fun tamanoDePantalla(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        val w = m.widthPixels.coerceAtLeast(2)
        val h = m.heightPixels.coerceAtLeast(2)
        val largo = maxOf(w, h)
        val escala = if (largo > 1280) 1280.0 / largo else 1.0
        fun bloque(v: Int) = (v - v % 16).coerceAtLeast(16)
        return bloque((w * escala).toInt()) to bloque((h * escala).toInt())
    }

    fun liberar() {
        detenerCaptura()
        runCatching { videoSource.dispose() }
        runCatching { audioSource.dispose() }
    }
}
