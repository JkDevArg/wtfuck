package com.wtfuck.app.datos

import android.content.Context
import android.content.Intent
import android.icu.util.ULocale
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribir notas de voz y traducir mensajes, **en el telefono**.
 *
 * ## Por que solo en el telefono
 *
 * Mandar el audio o el texto a un servicio en internet para transcribirlo o
 * traducirlo es mandarle el mensaje descifrado a un tercero: el cifrado de
 * punta a punta dejaria de significar algo justo en el momento de leer. Asi
 * que se usan las piezas que Android trae para hacerlo dentro del aparato
 * -el reconocedor de voz "on device" y el traductor "on device", que en los
 * telefonos con Android System Intelligence corren sin red- y, si el telefono
 * no las tiene, se dice. **No hay plan B en la nube.**
 *
 * Lo unico que puede ir a internet es la descarga del MODELO de un idioma, que
 * la hace el sistema y no lleva nada de nadie.
 */
class NoEnEsteTelefono(mensaje: String) : Exception(mensaje)

object Transcriptor {

    /** Lo que se le entrega al reconocedor: 16 kHz, mono, 16 bits. Es lo que espera para voz. */
    private const val TASA = 16_000

    fun disponible(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    /**
     * El texto de una nota de voz. Lanza [NoEnEsteTelefono] si el telefono no
     * puede hacerlo sin red, o si le falta el idioma.
     */
    suspend fun transcribir(ctx: Context, archivo: File): String {
        if (Build.VERSION.SDK_INT < 33 || !disponible(ctx)) {
            throw NoEnEsteTelefono("Este teléfono no puede transcribir sin mandar el audio a internet, y wtfuck no lo manda.")
        }
        val pcm = withContext(Dispatchers.IO) { aPcm16kMono(archivo) }
        return withContext(Dispatchers.Main) { reconocer(ctx, pcm) }
    }

    @RequiresApi(33)
    private suspend fun reconocer(ctx: Context, pcm: ByteArray): String {
        val reconocedor = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        try {
            val idioma = idiomaInstalado(ctx, reconocedor)
            return escuchar(reconocedor, pcm, idioma)
        } finally {
            reconocedor.destroy()
        }
    }

    /**
     * El idioma del telefono, si su modelo esta instalado. Si se puede bajar,
     * se le pide al sistema que lo baje y se avisa: la proxima vez funciona.
     */
    @RequiresApi(33)
    private suspend fun idiomaInstalado(ctx: Context, r: SpeechRecognizer): String {
        val pedido = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        val soporte = suspendCancellableCoroutine<RecognitionSupport?> { cont ->
            r.checkRecognitionSupport(pedido, ContextCompat.getMainExecutor(ctx), object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) { if (cont.isActive) cont.resume(s) }
                override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
            })
        } ?: throw NoEnEsteTelefono("El reconocedor del teléfono no respondió.")
        val mio = Locale.getDefault()
        fun mismoIdioma(tag: String) = Locale.forLanguageTag(tag).language == mio.language
        soporte.installedOnDeviceLanguages.firstOrNull { it.equals(mio.toLanguageTag(), true) }
            ?.let { return it }
        soporte.installedOnDeviceLanguages.firstOrNull { mismoIdioma(it) }?.let { return it }
        val bajable = (soporte.supportedOnDeviceLanguages + soporte.pendingOnDeviceLanguages).firstOrNull { mismoIdioma(it) }
        if (bajable != null) {
            r.triggerModelDownload(Intent(pedido).putExtra(RecognizerIntent.EXTRA_LANGUAGE, bajable))
            // El sistema pregunta antes de bajarlo -con el tamaño a la vista-:
            // la descarga es suya, no de esta app.
            throw NoEnEsteTelefono(
                "Para transcribir sin internet, el teléfono necesita ese idioma. " +
                    "Si aceptas la descarga del sistema, inténtalo cuando termine.",
            )
        }
        throw NoEnEsteTelefono("Este teléfono no tiene el idioma ${mio.displayLanguage} para transcribir sin internet.")
    }

    @RequiresApi(33)
    private suspend fun escuchar(r: SpeechRecognizer, pcm: ByteArray, idioma: String): String =
        suspendCancellableCoroutine { cont ->
            val (lectura, escritura) = ParcelFileDescriptor.createPipe()
            val partes = StringBuilder()
            fun terminar() {
                if (!cont.isActive) return
                val t = partes.toString().trim()
                if (t.isNotEmpty()) cont.resume(t)
                else cont.resumeWithException(NoEnEsteTelefono("No se entendió nada en el audio."))
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { partes.append(it.trim()).append(' ') }
                }
                override fun onEndOfSegmentedSession() = terminar()
                override fun onResults(b: Bundle) {
                    b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { partes.append(it.trim()) }
                    terminar()
                }
                override fun onError(error: Int) {
                    // Sin habla al final del audio tambien es un "error": si ya
                    // hay texto, es el final normal.
                    if (partes.isNotBlank()) terminar()
                    else if (cont.isActive) cont.resumeWithException(NoEnEsteTelefono("No se pudo transcribir (código $error)."))
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lectura)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TASA)
                // Por segmentos: una nota de voz tiene pausas, y sin esto el
                // reconocedor terminaria en el primer silencio.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            })
            // El audio entra por el tubo desde otro hilo: el tubo tiene un
            // bufer chico y escribir todo de una vez desde aqui trabaria el
            // hilo principal hasta que el reconocedor lea.
            Thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(escritura).use { it.write(pcm) } }
            }.start()
            cont.invokeOnCancellation { runCatching { r.cancel() } }
        }

    /**
     * El audio de la nota como PCM de 16 bits, mono y a 16 kHz.
     *
     * Se decodifica con lo que trae Android (MediaCodec) y se baja de tasa a
     * mano, con interpolacion lineal: para voz alcanza, y evita otra biblioteca.
     */
    internal fun aPcm16kMono(archivo: File): ByteArray {
        val ext = MediaExtractor()
        ext.setDataSource(archivo.absolutePath)
        val pista = (0 until ext.trackCount).firstOrNull {
            ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: throw NoEnEsteTelefono("No hay audio en ese archivo.")
        ext.selectTrack(pista)
        val formato = ext.getTrackFormat(pista)
        val dec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(formato, null, null, 0)
        dec.start()
        var tasa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canales = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val salida = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var finEntrada = false
        try {
            while (true) {
                if (!finEntrada) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ext.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            finEntrada = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    tasa = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    canales = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = dec.getOutputBuffer(o)!!
                    val trozo = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(trozo)
                    salida.write(trozo)
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { dec.stop() }
            dec.release()
            ext.release()
        }
        return remuestrear(salida.toByteArray(), tasa, canales, TASA)
    }

    /** PCM 16 bits little-endian, `canales` intercalados -> mono a `destino` Hz. Pura, con pruebas. */
    fun remuestrear(pcm: ByteArray, tasa: Int, canales: Int, destino: Int): ByteArray {
        val cuadros = pcm.size / (2 * canales)
        if (cuadros == 0 || tasa <= 0) return ByteArray(0)
        val mono = FloatArray(cuadros) { f ->
            var suma = 0f
            for (c in 0 until canales) {
                val k = (f * canales + c) * 2
                suma += ((pcm[k + 1].toInt() shl 8) or (pcm[k].toInt() and 0xFF)).toShort().toFloat()
            }
            suma / canales
        }
        val n = (cuadros.toLong() * destino / tasa).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val pos = i.toDouble() * tasa / destino
            val a = pos.toInt().coerceAtMost(cuadros - 1)
            val b = (a + 1).coerceAtMost(cuadros - 1)
            val v = (mono[a] + (mono[b] - mono[a]) * (pos - a).toFloat()).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

object Traductor {

    fun disponible(): Boolean = Build.VERSION.SDK_INT >= 31

    /**
     * El texto en el idioma del telefono. Lanza [NoEnEsteTelefono] si no se
     * puede hacer sin red.
     */
    suspend fun traducir(ctx: Context, texto: String): String {
        if (Build.VERSION.SDK_INT < 31) {
            throw NoEnEsteTelefono("Este teléfono no traduce sin internet, y wtfuck no manda tus mensajes a ningún traductor.")
        }
        return withContext(Dispatchers.IO) { traducirS(ctx, texto) }
    }

    @RequiresApi(31)
    private suspend fun traducirS(ctx: Context, texto: String): String {
        val tm = ctx.getSystemService(TranslationManager::class.java)
            ?: throw NoEnEsteTelefono("Este teléfono no tiene traductor propio.")
        val destino = ULocale.forLocale(Locale.getDefault())
        // El idioma de origen tambien se adivina en el aparato.
        val origen = ctx.getSystemService(TextClassificationManager::class.java)?.textClassifier
            ?.detectLanguage(TextLanguage.Request.Builder(texto).build())
            ?.takeIf { it.localeHypothesisCount > 0 }?.getLocale(0)
            ?: throw NoEnEsteTelefono("No se pudo saber en qué idioma está.")
        if (origen.language == destino.language) throw NoEnEsteTelefono("Ya está en tu idioma.")

        val capacidad = tm.getOnDeviceTranslationCapabilities(
            TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT,
        ).firstOrNull {
            it.sourceSpec.locale.language == origen.language && it.targetSpec.locale.language == destino.language
        }
        // El nombre en español, que es el idioma de la app; no en el del telefono.
        val nombre = origen.getDisplayLanguage(ULocale("es"))
        when (capacidad?.state) {
            TranslationCapability.STATE_ON_DEVICE -> Unit
            TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD, TranslationCapability.STATE_DOWNLOADING ->
                throw NoEnEsteTelefono(
                    "Para traducir del $nombre sin internet, el teléfono tiene que descargar ese idioma " +
                        "(Ajustes del sistema → Live Translate).",
                )
            else -> throw NoEnEsteTelefono("Este teléfono no traduce del $nombre sin internet.")
        }

        val contexto = TranslationContext.Builder(
            TranslationSpec(capacidad.sourceSpec.locale, TranslationSpec.DATA_FORMAT_TEXT),
            TranslationSpec(capacidad.targetSpec.locale, TranslationSpec.DATA_FORMAT_TEXT),
        ).build()
        val ejecutor = ContextCompat.getMainExecutor(ctx)
        return suspendCancellableCoroutine { cont ->
            tm.createOnDeviceTranslator(contexto, ejecutor) { traductor ->
                if (traductor == null) {
                    cont.resumeWithException(NoEnEsteTelefono("El traductor del teléfono no está disponible."))
                    return@createOnDeviceTranslator
                }
                val pedido = TranslationRequest.Builder()
                    .setTranslationRequestValues(listOf(TranslationRequestValue.forText(texto)))
                    .build()
                val cancelar = CancellationSignal()
                cont.invokeOnCancellation { cancelar.cancel() }
                traductor.translate(pedido, cancelar, ejecutor) { r ->
                    val t = r.translationResponseValues.get(0)?.text?.toString()
                    traductor.destroy()
                    if (cont.isActive) {
                        if (t.isNullOrBlank()) cont.resumeWithException(NoEnEsteTelefono("No se pudo traducir."))
                        else cont.resume(t)
                    }
                }
            }
        }
    }
}
