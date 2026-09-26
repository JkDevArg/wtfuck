package com.wtfuck.app.datos

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * El sonido de lo que se está mostrando, mezclado con la voz.
 *
 * ## Por qué hace falta algo tan raro
 *
 * Sin esto, el modo cine es una película muda. El micrófono no sirve: la
 * cancelación de eco de una llamada existe justamente para **borrar** lo que
 * sale por el altavoz, así que lo que mejor funciona en una llamada normal es
 * lo que destruye este caso.
 *
 * Android tiene la pieza correcta desde la 29: `AudioPlaybackCapture`, que
 * entrega el audio que están reproduciendo otras apps. Lo autoriza la **misma**
 * proyección que ya se concedió para la pantalla —una sola confirmación, no
 * dos— y por eso se toma de `ScreenCapturerAndroid.getMediaProjection()` en
 * vez de pedir otra.
 *
 * ## Dónde se engancha
 *
 * En `JavaAudioDeviceModule.setAudioBufferCallback`, que deja **modificar el
 * búfer del micrófono antes de que WebRTC lo procese**. Se suman las dos
 * señales ahí y aguas abajo todo sigue igual: un solo flujo de audio, una sola
 * pista, ninguna renegociación.
 *
 * La alternativa era una segunda pista de audio, que sí obliga a renegociar —
 * y esta app no renegocia, por lo mismo que se explica en [MediosLocales].
 *
 * ## Lo que NO se va a oír, y no tiene arreglo
 *
 * Una app puede negarse a que la capturen, y las de vídeo con DRM se niegan:
 * HBO, Netflix y Disney+ marcan su audio como no capturable, igual que marcan
 * su vídeo con `FLAG_SECURE`. Ahí no hay nada que hacer del lado de acá, y
 * buscarle la vuelta sería justamente lo que ese mecanismo existe para
 * impedir.
 *
 * Funciona con lo demás: un vídeo propio, un navegador sin DRM, un juego,
 * cualquier app que no se haya excluido.
 */
object AudioDeCine {

    private const val TAG = "AudioDeCine"

    /**
     * Cuánto se baja lo que suena, respecto de la voz.
     *
     * Una película sale mucho más fuerte que alguien hablando, y sumadas a
     * volumen pleno la voz queda debajo y además se satura. Al 70 % la voz
     * sigue por encima —que es lo que hace falta: se está viendo algo *con*
     * alguien, no en vez de alguien— y queda margen antes de recortar.
     */
    private const val GANANCIA = 0.7f

    @Volatile private var proyeccion: MediaProjection? = null
    @Volatile private var grabador: AudioRecord? = null
    @Volatile private var activo = false

    /** Se reserva una vez y se reusa: esto corre en el hilo de audio. */
    private var buffer = ByteArray(0)

    val encendido: Boolean get() = activo

    /**
     * Empieza a capturar lo que suena.
     *
     * @return `false` si el sistema no lo permite. Pasa de verdad: en Android
     *   anterior a la 29 la API no existe. No es un error que haya que
     *   anunciar como fallo — el modo cine sigue, sin sonido.
     */
    fun arrancar(p: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Sin captura de audio: hace falta Android 10")
            return false
        }
        proyeccion = p
        activo = true
        return true
    }

    fun detener() {
        activo = false
        // El grabador se suelta aqui y no en el hilo de audio: `stop()` puede
        // bloquear unos milisegundos, y el hilo de audio no tiene esos
        // milisegundos — se oiria como un chasquido.
        val g = grabador
        grabador = null
        runCatching { g?.stop() }
        runCatching { g?.release() }
        proyeccion = null
    }

    /**
     * Suma lo que suena dentro del búfer del micrófono, **en el sitio**.
     *
     * Corre en el hilo de audio de WebRTC, cada 10 ms. Todo lo de aquí está
     * escrito para no reservar memoria ni bloquear: una pausa en este hilo no
     * es una pausa, es un chasquido en el oído de la otra persona.
     *
     * El grabador se crea en la PRIMERA llamada y no antes, con el formato que
     * WebRTC dice estar usando. Es lo que evita tener que remuestrear: en vez
     * de adivinar 48 kHz y convertir, se pregunta y se usa lo mismo.
     */
    @SuppressLint("MissingPermission")
    fun mezclar(pcm: ByteBuffer, canales: Int, ritmo: Int, bytes: Int) {
        if (!activo || bytes <= 0) return
        val g = grabador ?: abrir(canales, ritmo) ?: return

        if (buffer.size < bytes) buffer = ByteArray(bytes)
        val leidos = runCatching {
            g.read(buffer, 0, bytes, AudioRecord.READ_NON_BLOCKING)
        }.getOrDefault(0)
        // Menos de lo pedido es normal: al arrancar todavia no hay nada
        // grabado. Se mezcla lo que haya y el resto queda como venia — silencio
        // sobre la voz, que es mejor que un hueco en la voz.
        if (leidos <= 1) return

        val voz = pcm.order(ByteOrder.LITTLE_ENDIAN)
        val medio = ByteBuffer.wrap(buffer, 0, leidos).order(ByteOrder.LITTLE_ENDIAN)
        val muestras = minOf(bytes, leidos) / 2
        for (i in 0 until muestras) {
            val a = voz.getShort(i * 2).toInt()
            val b = (medio.getShort(i * 2) * GANANCIA).toInt()
            // Recorte duro. Sumar dos señales de 16 bits se pasa de rango, y
            // dejar que dé la vuelta convierte un pico en un estallido.
            val s = (a + b).coerceIn(-32768, 32767)
            voz.putShort(i * 2, s.toShort())
        }
    }

    private fun abrir(canales: Int, ritmo: Int): AudioRecord? {
        val p = proyeccion ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                // Las tres unicas que el sistema deja capturar. No es una
                // eleccion: una app decide si se la puede capturar, y una de
                // video con DRM dice que no.
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val formato = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(ritmo)
                .setChannelMask(
                    if (canales >= 2) AudioFormat.CHANNEL_IN_STEREO
                    else AudioFormat.CHANNEL_IN_MONO
                )
                .build()

            val minimo = AudioRecord.getMinBufferSize(
                ritmo,
                if (canales >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            AudioRecord.Builder()
                .setAudioFormat(formato)
                // Cuatro veces el minimo: este grabador se lee desde el hilo de
                // audio con lecturas que NO bloquean, asi que lo que no se
                // alcanza a leer se pierde. Un buzon holgado es lo que evita
                // que un hipo del sistema se oiga como un corte.
                .setBufferSizeInBytes(minimo * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
                .also {
                    it.startRecording()
                    grabador = it
                    Log.i(TAG, "Capturando audio a $ritmo Hz, $canales canal(es)")
                }
        }.getOrElse {
            Log.w(TAG, "No se pudo capturar el audio: ${it.message}")
            // Se apaga para no reintentar en CADA bloque de 10 ms. Si el
            // sistema lo nego una vez, lo va a negar siempre, y reintentar
            // sesenta veces por minuto desde el hilo de audio es peor que no
            // tener sonido.
            activo = false
            null
        }
    }
}
