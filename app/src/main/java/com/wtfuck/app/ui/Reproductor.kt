package com.wtfuck.app.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Un unico reproductor para toda la pantalla de chat.
 *
 * Uno por burbuja significaria varias notas de voz sonando encima de otra y un
 * MediaPlayer por mensaje de la lista. Aqui hay uno, sabe cual esta sonando, y
 * empezar otro corta el anterior: es lo que la persona espera.
 */
class Reproductor(private val ambito: CoroutineScope) {

    /** Id del mensaje que esta sonando, o null. */
    var sonando by mutableStateOf<String?>(null)
        private set

    /** Avance de 0 a 1, para pintar la onda. */
    var avance by mutableStateOf(0f)
        private set

    /**
     * Milisegundos ya reproducidos de lo que esta sonando.
     *
     * Se expone aparte del avance porque la burbuja muestra el tiempo que va
     * corriendo, y calcularlo desde la fraccion obligaria a multiplicar por
     * una duracion que la burbuja no tiene -la que trae el metadato la declaro
     * quien envio, y puede no ser la real-.
     */
    var transcurridoMs by mutableStateOf(0)
        private set

    /**
     * Velocidad de reproduccion.
     *
     * Vive aqui y no en la burbuja porque hay un solo reproductor para toda la
     * pantalla: cambiarla mientras suena una nota tiene que afectar a esa nota,
     * y quedar puesta para la siguiente.
     */
    var velocidad by mutableStateOf(1f)
        private set

    private var mp: MediaPlayer? = null
    private var reloj: Job? = null

    /**
     * Lo que pasa cuando una nota TERMINA sola -no cuando se la para-: recibe
     * su id. La pantalla lo usa para seguir con la siguiente.
     */
    var alTerminar: ((String) -> Unit)? = null

    /**
     * Empieza a sonar.
     *
     * ## Por que `prepareAsync` y no `prepare`
     *
     * `prepare()` parsea el archivo **en el hilo que la llama**, y esta funcion
     * se llama desde el `onClick` de una burbuja, o sea desde el hilo de la
     * interfaz. El archivo lo grabo otra persona: nada obliga a que sea una
     * nota de voz de esta app, y un contenedor mal formado puede tener al
     * extractor del sistema trabajando mientras la pantalla no responde.
     *
     * Con `prepareAsync` el parseo se va a un hilo del sistema y la burbuja
     * solo cambia de estado cuando el archivo esta listo. Si nunca lo esta,
     * `setOnErrorListener` lo recoge.
     *
     * Es la API que la documentacion recomienda para cualquier fuente que no
     * sea trivial, y aqui la fuente es ajena por definicion.
     */
    fun reproducir(mensajeId: String, archivo: File) {
        detener()
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(archivo.absolutePath)
                setOnCompletionListener {
                    val termino = sonando
                    detener()
                    termino?.let { id -> alTerminar?.invoke(id) }
                }
                // Un archivo que el decodificador no entiende NO puede dejar la
                // burbuja marcada como sonando para siempre.
                setOnErrorListener { _, que, extra ->
                    android.util.Log.w("Reproductor", "Error $que/$extra en ${archivo.name}")
                    detener()
                    true
                }
                setOnPreparedListener { listo -> if (mp === listo) arrancar(mensajeId, listo) }
                prepareAsync()
            }
        }.getOrElse {
            android.util.Log.w("Reproductor", "No se pudo reproducir ${archivo.name}: ${it.message}")
            null
        } ?: return

        mp = p
    }

    /** Ya preparado: suena y arranca el reloj de la onda. */
    private fun arrancar(mensajeId: String, p: MediaPlayer) {
        runCatching { p.start() }.onFailure {
            android.util.Log.w("Reproductor", "No arranco: ${it.message}")
            detener()
            return
        }
        // La velocidad se aplica DESPUES de `start()` a proposito. Ponerle
        // `playbackParams` a un reproductor que todavia no arranco lo arranca
        // -es el comportamiento documentado de `MediaPlayer`- y entonces el
        // orden de las dos llamadas decidiria si suena o no, que es la clase
        // de dependencia que no se ve al leer.
        aplicarVelocidad(p)

        sonando = mensajeId
        avance = 0f
        transcurridoMs = 0
        reloj = ambito.launch {
            while (true) {
                val actual = mp ?: break
                // `runCatching` porque el reloj y `detener()` corren los dos en
                // el hilo principal pero no en el mismo turno: si la liberacion
                // cae entre dos vueltas, preguntarle la posicion a un
                // reproductor liberado lanza.
                val total = runCatching { actual.duration }.getOrDefault(0)
                if (total > 0) {
                    val donde = runCatching { actual.currentPosition }.getOrDefault(0)
                    avance = (donde.toFloat() / total).coerceIn(0f, 1f)
                    transcurridoMs = donde.coerceIn(0, total)
                }
                delay(80)
            }
        }
    }

    /**
     * Salta a una fraccion de la nota.
     *
     * La fraccion se acota aqui y no en quien llama: viene de un dedo sobre una
     * barra, asi que un valor fuera de rango no es un caso raro sino el caso
     * normal cuando alguien arrastra hasta el borde.
     *
     * No hace nada si no hay nada sonando. Adelantar una nota que no empezo no
     * significa nada, y arrancarla desde el medio por un roce seria peor.
     */
    fun saltarA(fraccion: Float) {
        val p = mp ?: return
        runCatching {
            val total = p.duration
            if (total <= 0) return
            val destino = (total * fraccion.coerceIn(0f, 1f)).toInt()
            // `SEEK_CLOSEST` y no el salto por fotograma clave: en un audio de
            // voz los puntos de sincronizacion estan lejos, y caer un segundo
            // antes de donde se toco se nota.
            p.seekTo(destino.toLong(), MediaPlayer.SEEK_CLOSEST)
            avance = (destino.toFloat() / total).coerceIn(0f, 1f)
            transcurridoMs = destino
        }
    }

    /**
     * Cambia la velocidad, ahora y para las proximas notas.
     *
     * Se aplica al reproductor vivo si hay uno; si no, queda guardada. Las dos
     * mitades importan: cambiarla a mitad de una nota tiene que oirse al
     * instante, y no tener que repetir el gesto en la siguiente es lo que hace
     * que la funcion se use.
     */
    fun cambiarVelocidad(v: Float) {
        velocidad = v.coerceIn(VELOCIDAD_MIN, VELOCIDAD_MAX)
        mp?.let { aplicarVelocidad(it) }
    }

    /** La velocidad recordada, al crear la pantalla. */
    fun recordarVelocidad(v: Float) {
        velocidad = v.coerceIn(VELOCIDAD_MIN, VELOCIDAD_MAX)
    }

    private fun aplicarVelocidad(p: MediaPlayer) {
        if (velocidad == 1f) return
        // Envuelto porque `setSpeed` puede lanzar si el dispositivo no admite
        // ese factor. Que una nota suene a velocidad normal es mucho mejor que
        // que no suene.
        runCatching { p.playbackParams = p.playbackParams.setSpeed(velocidad) }
            .onFailure {
                android.util.Log.w("Reproductor", "Velocidad $velocidad no admitida: ${it.message}")
                velocidad = 1f
            }
    }

    fun detener() {
        reloj?.cancel()
        reloj = null
        // release() y no solo stop(): un MediaPlayer sin liberar se queda con
        // el decodificador de audio del sistema tomado.
        runCatching { mp?.release() }
        mp = null
        sonando = null
        avance = 0f
        transcurridoMs = 0
        // La velocidad NO se reinicia: es una preferencia de quien escucha, no
        // un estado de esta nota.
    }
}

/**
 * Las velocidades que se ofrecen, en orden de rotacion.
 *
 * Tres y no seis: el boton es un toque que rota, no un menu. Un menu para esto
 * cuesta mas gestos que el beneficio que da, y quien quiere 0,75x es una
 * minoria frente a quien quiere terminar antes.
 */
val VELOCIDADES = listOf(1f, 1.5f, 2f)

const val VELOCIDAD_MIN = 0.5f
const val VELOCIDAD_MAX = 3f

/** La siguiente de la rueda. */
fun siguienteVelocidad(actual: Float): Float {
    val i = VELOCIDADES.indexOfFirst { it == actual }
    return if (i < 0) VELOCIDADES.first() else VELOCIDADES[(i + 1) % VELOCIDADES.size]
}

/** Como se escribe una velocidad: `1x`, `1.5x`, `2x`. */
fun velocidadLegible(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

val LocalReproductor = staticCompositionLocalOf<Reproductor> {
    error("No hay Reproductor en este arbol de composicion")
}

/**
 * Grabadora de notas de voz.
 *
 * Graba a M4A/AAC y no a WAV: una nota de un minuto son ~480 KB en AAC contra
 * ~5 MB sin comprimir, y eso se paga en la subida, en la cuota y en los datos
 * de quien la recibe.
 */
/**
 * Lo que dura como mucho una nota de voz.
 *
 * Diez minutos es una decision de producto -una nota de voz mas larga que eso
 * ya no es una nota de voz-, no el resultado de dividir el limite del servidor.
 * Conviene igual mirar esa division: 16 MB a 64 kbps son unos treinta y cinco
 * minutos, asi que diez deja el archivo en torno a 5 MB, holgado.
 */
const val TOPE_GRABACION_MS = 10 * 60 * 1000

/**
 * Segunda red, por si el codificador se sale de la tasa prevista.
 *
 * Va por debajo del limite del servidor a proposito: el archivo todavia tiene
 * que cifrarse antes de subir, y mas vale cortar aqui que ver como lo rechazan
 * al final.
 */
const val TOPE_GRABACION_BYTES = 12L * 1024 * 1024

class Grabadora(private val ctx: Context) {

    private var rec: MediaRecorder? = null
    private var destino: File? = null
    private var inicio = 0L

    /**
     * Las muestras de la nota que se esta grabando.
     *
     * Las lee UN SOLO hilo, el de abajo, y esa es la razon de que exista: leer
     * `maxAmplitude` DEVUELVE el pico y lo pone a cero, asi que dos lectores
     * se roban las muestras entre si. Antes lo leia la pantalla para mover el
     * indicador; ahora lo lee el muestreador y la pantalla mira el resultado.
     */
    private val muestras = java.util.Collections.synchronizedList(mutableListOf<Float>())

    @Volatile
    private var ultimoNivel = 0f

    private var muestreador: Thread? = null

    /**
     * Si el sistema corto la grabacion por llegar al tope.
     *
     * Lo consulta el temporizador de la pantalla para cerrar la nota sola. Va
     * `@Volatile` porque lo escribe el hilo del `MediaRecorder` y lo lee el
     * principal.
     */
    @Volatile
    var topeAlcanzado: Boolean = false
        private set

    val grabando: Boolean get() = rec != null

    /**
     * Arranca y devuelve false si el sistema no lo permitio.
     *
     * ## El tope, y de donde sale
     *
     * Antes no habia ninguno: se podia dejar la grabacion andando y solo se
     * paraba al soltar. El limite del servidor para una nota de voz son 16 MB,
     * que a 64 kbps son unos **treinta y cinco minutos**, asi que lo que
     * pasaba de verdad era que alguien grababa media hora y **recien al subir**
     * se enteraba de que no entraba. Perder media hora de grabacion al final es
     * la peor forma posible de aplicar un limite.
     *
     * `setMaxDuration` lo corta antes, en un numero que es una decision de
     * producto y no la division de arriba: diez minutos de nota de voz ya son
     * muchos, y dejan el archivo en torno a 5 MB, holgadamente por debajo del
     * tope. `setMaxFileSize` va detras como segunda red por si el codificador
     * se sale de la tasa prevista.
     *
     * Quien escucha se entera igual de que se corto: la nota llega con su
     * duracion real.
     */
    fun iniciar(): Boolean {
        topeAlcanzado = false
        muestras.clear()
        ultimoNivel = 0f
        val f = File(ctx.cacheDir, "voz-${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(f.absolutePath)
                setMaxDuration(TOPE_GRABACION_MS)
                setMaxFileSize(TOPE_GRABACION_BYTES)
                // Cuando el sistema corta por tope deja de grabar, pero la
                // pantalla no se entera sola: sin esto la barra seguiria
                // contando segundos sobre un archivo que ya no crece.
                setOnInfoListener { _, que, _ ->
                    if (
                        que == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        que == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) {
                        topeAlcanzado = true
                    }
                }
                prepare()
                start()
            }
            rec = r
            destino = f
            inicio = System.currentTimeMillis()

            // Un hilo propio y no el temporizador de la pantalla: si la
            // pantalla deja de preguntar —se va a segundo plano, se traba un
            // fotograma— la figura saldria con agujeros justo donde la
            // grabacion siguio. Cada 80 ms son unas 12 muestras por segundo,
            // de sobra para 40 barras.
            muestreador = Thread {
                while (rec != null) {
                    val a = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val n = (a / 12000f).coerceIn(0f, 1f)
                    ultimoNivel = n
                    muestras.add(n)
                    runCatching { Thread.sleep(80) }.getOrElse { return@Thread }
                }
            }.also { it.isDaemon = true; it.start() }
            true
        }.getOrElse {
            android.util.Log.w("Grabadora", "No se pudo grabar: ${it.message}")
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** Nivel de entrada de 0 a 1, para mover el indicador mientras se graba. */
    fun nivel(): Float = ultimoNivel

    /**
     * La silueta de lo ultimo que se grabo, lista para viajar.
     *
     * Vacia si no se pudo muestrear. Ver [com.wtfuck.protocol.Onda].
     */
    fun onda(): String = synchronized(muestras) {
        com.wtfuck.protocol.Onda.codificar(muestras.toList())
    }

    fun milisegundos(): Long = if (inicio == 0L) 0 else System.currentTimeMillis() - inicio

    /**
     * Cierra la grabacion y devuelve el archivo.
     *
     * Devuelve null si la nota dura menos de un segundo: eso es un toque sin
     * querer, no un mensaje, y mandarlo solo molesta a quien lo recibe.
     */
    fun terminar(): File? {
        val r = rec ?: return null
        val f = destino
        val duro = milisegundos()
        rec = null
        destino = null
        inicio = 0L
        // `rec = null` ya le dijo al muestreador que pare; esto solo espera a
        // que termine la vuelta en curso, para que `onda()` no lea una lista
        // a la que todavia se le esta escribiendo.
        runCatching { muestreador?.join(300) }
        muestreador = null
        runCatching { r.stop() }
        runCatching { r.release() }
        if (f == null || duro < 1000 || f.length() <= 0) {
            f?.delete()
            return null
        }
        return f
    }

    fun cancelar() {
        terminar()?.delete()
    }
}
