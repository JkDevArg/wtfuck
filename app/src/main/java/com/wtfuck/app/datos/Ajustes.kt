package com.wtfuck.app.datos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wtfuck.app.ui.theme.Tema
import com.wtfuck.protocol.ClaseAdjunto

/** Calidad con la que se envian las fotos. */
enum class CalidadImagen(val etiqueta: String, val ladoMax: Int, val calidad: Int) {
    /** Tal cual salio de la camara. Fiel y caro. */
    ORIGINAL("Original", 0, 100),
    ALTA("Alta", 2560, 88),
    MEDIA("Media (ahorra datos)", 1600, 78),
}

/**
 * Ajustes de multimedia y almacenamiento (modulo D.7).
 *
 * Los valores por defecto no son arbitrarios: descargar fotos solo y ademas
 * solo con WiFi es lo que evita que un grupo activo consuma el plan de datos
 * de alguien sin que se entere. Los videos y los documentos se bajan cuando la
 * persona los toca, que es cuando de verdad le importan.
 */
class Ajustes(private val ctx: Context) {

    private val p = ctx.getSharedPreferences("wtfuck_ajustes", Context.MODE_PRIVATE)

    var autoImagenes: Boolean
        get() = p.getBoolean("auto_imagenes", true)
        set(v) = p.edit().putBoolean("auto_imagenes", v).apply()

    var autoAudio: Boolean
        get() = p.getBoolean("auto_audio", true)
        set(v) = p.edit().putBoolean("auto_audio", v).apply()

    var autoVideo: Boolean
        get() = p.getBoolean("auto_video", false)
        set(v) = p.edit().putBoolean("auto_video", v).apply()

    var autoDocumentos: Boolean
        get() = p.getBoolean("auto_documentos", false)
        set(v) = p.edit().putBoolean("auto_documentos", v).apply()

    var soloWifi: Boolean
        get() = p.getBoolean("solo_wifi", true)
        set(v) = p.edit().putBoolean("solo_wifi", v).apply()

    /**
     * Bloquear capturas de pantalla y grabacion. Apagado por defecto.
     *
     * ## Por que es un ajuste y no siempre
     *
     * `FLAG_SECURE` tapa la miniatura en la lista de recientes, que es un
     * agujero real del bloqueo de la app. Pero de paso **prohibe toda captura
     * dentro de la app**, y eso es otra decision: hay motivos legitimos para
     * capturar una conversacion propia -guardar una direccion, enviar una
     * prueba a alguien-. Forzarlo seria decidir por la persona algo que no
     * pidio.
     *
     * ## El hueco que cierra
     *
     * `setRecentsScreenshotEnabled` -lo que se usa hoy para la miniatura- solo
     * existe desde Android 13. Por debajo **no hay proteccion de la miniatura
     * ni con el bloqueo activo**, y esa lista se ve sin desbloquear nada. Con
     * este ajuste encendido si la hay, en cualquier version.
     *
     * Ese es el trato, y la pantalla lo dice en vez de esconderlo: en Android
     * antiguo, tapar la miniatura cuesta las capturas.
     */
    var bloquearCapturas: Boolean
        get() = p.getBoolean("bloquear_capturas", false)
        set(v) = p.edit().putBoolean("bloquear_capturas", v).apply()

    /**
     * Cuando se hizo la ultima copia de seguridad (epoch ms). 0 = nunca.
     *
     * La copia es manual y se olvida; una copia que nadie hace no protege
     * nada. Esto deja saber cuanto hace de la ultima para poder recordarlo.
     */
    var ultimaCopia: Long
        get() = p.getLong("ultima_copia", 0)
        set(v) = p.edit().putLong("ultima_copia", v).apply()

    var calidadImagen: CalidadImagen
        get() = runCatching { CalidadImagen.valueOf(p.getString("calidad", null) ?: "") }
            .getOrDefault(CalidadImagen.ALTA)
        set(v) = p.edit().putString("calidad", v.name).apply()

    /**
     * Si un adjunto que acaba de llegar se descarga solo.
     *
     * Los stickers y las notas de voz se bajan siempre: son chicos y se
     * consumen al instante. Preguntar por cada uno seria una molestia sin
     * ahorro real.
     */
    fun descargaSola(clase: String, bytes: Long): Boolean {
        if (clase == ClaseAdjunto.STICKER) return true
        if (soloWifi && !enWifi()) return false
        return when (clase) {
            ClaseAdjunto.IMAGEN -> autoImagenes
            ClaseAdjunto.NOTA_VOZ, ClaseAdjunto.AUDIO -> autoAudio
            ClaseAdjunto.VIDEO -> autoVideo && bytes <= 16L * 1024 * 1024
            else -> autoDocumentos && bytes <= 8L * 1024 * 1024
        }
    }

    /**
     * N.2 · Tema claro, oscuro o el del sistema.
     *
     * Vive en el APARATO y no en la cuenta, por lo mismo que los avisos: el
     * tema lo mira un telefono, no una persona, y con multi-dispositivo querer
     * la tablet en claro y el telefono en oscuro es lo normal.
     *
     * Se expone como `mutableStateOf` y no solo como getter porque cambiarlo
     * tiene que repintar la app entera en el acto; un getter sobre
     * SharedPreferences no le avisa a Compose de nada.
     */
    /**
     * Velocidad de las notas de voz, recordada entre notas.
     *
     * Quien escucha a 2x no lo hace para UNA nota: lo hace porque prefiere
     * escuchar asi. Volver a 1x en cada burbuja obliga a repetir el gesto una
     * y otra vez, que es exactamente la clase de detalle que separa una
     * funcion de una funcion usable.
     */
    /**
     * Si este aparato puede pedirle baldosas a un servidor de mapas.
     *
     * ## Por que existe, si ya lo decide quien comparte
     *
     * Porque son dos costos distintos y los pagan personas distintas.
     *
     * Quien comparte decide si su POSICION puede llegar a un tercero
     * (`Carga.UbicacionEnVivo.conMapa`), y eso viaja en la carga. Pero la
     * peticion la hace este telefono: es SU direccion IP la que queda del
     * otro lado, junto con "esta mirando este lugar a esta hora". Esa parte
     * no la puede consentir nadie mas.
     *
     * ## Por que arranca apagado
     *
     * Para que la primera peticion a un tercero no ocurra nunca sin que
     * alguien la haya pedido. Se enciende de dos maneras, las dos explicitas
     * y las dos donde el costo esta escrito: tocando "Mostrar el mapa" en una
     * burbuja, o eligiendo el modo visible al compartir.
     *
     * Apagado no rompe nada: la burbuja dibuja la estela igual, que es la
     * informacion —por donde paso y hacia donde va—; lo que falta es el
     * decorado de las calles.
     */
    var mapaDeTerceros by mutableStateOf(p.getBoolean("mapa_terceros", false))
        private set

    fun fijarMapaDeTerceros(v: Boolean) {
        mapaDeTerceros = v
        p.edit().putBoolean("mapa_terceros", v).apply()
    }

    var velocidadAudio: Float
        get() = p.getFloat("velocidad_audio", 1f)
        set(v) = p.edit().putFloat("velocidad_audio", v).apply()

    var tema by mutableStateOf(
        runCatching { Tema.valueOf(p.getString("tema", null) ?: "") }
            .getOrDefault(Tema.OSCURO)
    )
        private set

    fun fijarTema(t: Tema) {
        tema = t
        p.edit().putString("tema", t.name).apply()
    }

    fun enWifi(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
