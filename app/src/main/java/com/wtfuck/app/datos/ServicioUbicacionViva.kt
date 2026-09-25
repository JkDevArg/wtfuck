package com.wtfuck.app.datos

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Módulo AM · Compartir la ubicación en tiempo real.
 *
 * ## Por qué un servicio en primer plano y no una corrutina
 *
 * Por lo mismo que las llamadas (K.8): no es dónde corre el código, es lo que
 * Android **promete**. Un compartido de ocho horas que vive en la Activity se
 * muere al salir de la app, que es justo cuando la persona está yendo a algún
 * sitio. Con el servicio y su notificación visible, el proceso es candidato de
 * última instancia para el asesino de memoria.
 *
 * Y la notificación no es burocracia: es **el contrato**. La app está leyendo
 * dónde estás; que eso no se pueda hacer en silencio es la mitad del diseño.
 * Por eso la notificación es `ongoing` —no se puede descartar de un manotazo—
 * y lleva su botón de cortar.
 *
 * ## Por qué NO se pide `ACCESS_BACKGROUND_LOCATION`
 *
 * El manifiesto decía, desde el módulo M: *«se usa una vez, cuando la persona
 * toca compartir ubicación, y nunca en segundo plano: no se pide
 * ACCESS_BACKGROUND_LOCATION porque esta app no sigue a nadie»*. Esto cambia
 * la primera mitad y **no la segunda**, y la diferencia importa.
 *
 * Un servicio en primer plano de tipo `location` puede leer la posición
 * mientras corre **sin** ese permiso, siempre que se arranque con la app
 * delante — que es exactamente el caso: la persona tocó el botón. Lo que
 * `ACCESS_BACKGROUND_LOCATION` permitiría es leer la ubicación **sin que la
 * persona haya pedido nada**, y eso sigue sin hacerse.
 *
 * ## El ritmo, y lo que cuesta
 *
 * Una posición cada 30 s durante 24 h serían 2.880 sobres por destinatario.
 * Así que hay dos filtros:
 *
 *  - el sistema sólo avisa si pasaron **30 s** y se movieron **25 m**;
 *  - y aun así se manda como mucho una cada 30 s.
 *
 * Quien está quieto no gasta casi nada, que es el caso más común de un
 * compartido largo: se comparte "hasta que llegue", y llegar incluye estar
 * parado en una reunión. El precio es que una parada larga deja la última
 * posición sin refrescar; por eso la burbuja muestra **cuándo** se actualizó y
 * no sólo dónde.
 */
class ServicioUbicacionViva : Service() {

    private val app get() = application as WtfuckApp
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var conversacionId: String = ""
    private var mensajeId: String = ""
    private var hasta: Long = 0L
    private var secuencia: Int = 0
    private var ultimoEnvio: Long = 0L

    private var lm: LocationManager? = null
    private var oyente: LocationListener? = null

    companion object {
        private const val TAG = "UbicacionViva"
        private const val ID_AVISO = 4343
        const val ACCION_CORTAR = "com.wtfuck.app.UBICACION_CORTAR"

        private const val EXTRA_CONV = "conv"
        private const val EXTRA_MSG = "msg"
        private const val EXTRA_HASTA = "hasta"

        /** Cada cuánto, como mucho, sale un sobre. */
        const val CADA_MS = 30_000L

        /** Cuánto hay que moverse para que el sistema avise. */
        const val CADA_METROS = 25f

        fun arrancar(ctx: Context, convId: String, mensajeId: String, hasta: Long) {
            val i = Intent(ctx, ServicioUbicacionViva::class.java)
                .putExtra(EXTRA_CONV, convId)
                .putExtra(EXTRA_MSG, mensajeId)
                .putExtra(EXTRA_HASTA, hasta)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "No se pudo arrancar el servicio: ${it.message}") }
        }

        fun detener(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioUbicacionViva::class.java)) }
        }

        /**
         * Corta desde la BURBUJA, si el servicio esta siguiendo ese compartido.
         *
         * Hace falta porque cortar desde la burbuja solo llamaba al
         * repositorio: marcaba el compartido terminado y avisaba al otro
         * lado, y el servicio seguia corriendo con su notificacion puesta.
         * O sea que la barra decia "Compartiendo tu ubicacion" sin compartir
         * nada, y el telefono seguia leyendo el GPS. La notificacion es el
         * contrato de este servicio; dejarla mintiendo lo rompe entero.
         *
         * Lleva el id y el servicio compara: cortar desde la burbuja de un
         * compartido VIEJO no puede apagar el que esta corriendo ahora.
         */
        fun cortarSiEs(ctx: Context, mensajeId: String) {
            val i = Intent(ctx, ServicioUbicacionViva::class.java)
                .setAction(ACCION_CORTAR)
                .putExtra(EXTRA_MSG, mensajeId)
            // `startService` y no `startForegroundService`: si el servicio no
            // esta corriendo no hay nada que cortar, y arrancarlo para
            // apagarlo obligaria a mostrar una notificacion por un instante.
            runCatching { ctx.startService(i) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_CORTAR) {
            // Sin id viene del boton de la notificacion, que solo puede
            // referirse al compartido en curso. Con id viene de una burbuja, y
            // puede ser la de uno viejo: entonces no se toca nada.
            val cual = intent.getStringExtra(EXTRA_MSG)
            if (cual == null || cual == mensajeId) cortar()
            return START_NOT_STICKY
        }

        val convNuevo = intent?.getStringExtra(EXTRA_CONV).orEmpty()
        val msgNuevo = intent?.getStringExtra(EXTRA_MSG).orEmpty()
        val hastaNuevo = intent?.getLongExtra(EXTRA_HASTA, 0L) ?: 0L

        // Se VALIDA antes de tocar nada.
        //
        // Antes el orden era al reves: se escribian los campos y recien
        // despues se miraba si servian. Un arranque sin extras —un intent
        // vacio, una redeliver rara— borraba la conversacion, el id y la
        // fecha de un compartido que estaba andando bien, y lo dejaba sin
        // seguimiento con la fila todavia "viva" en la base: del otro lado,
        // un punto congelado presentado como si fuera de ahora. Es el unico
        // modo en que esta funcion puede mentir de verdad.
        //
        // Ahora un arranque inutil no se lleva puesto al que funciona: si ya
        // hay uno en curso se lo deja en paz, y sino se apaga como antes.
        if (convNuevo.isBlank() || msgNuevo.isBlank() ||
            hastaNuevo <= System.currentTimeMillis()
        ) {
            if (mensajeId.isNotBlank() && hasta > System.currentTimeMillis()) {
                Log.w(TAG, "Arranque sin datos utiles: se ignora, hay uno en curso")
                alPrimerPlano()
                return START_REDELIVER_INTENT
            }
            Log.w(TAG, "Arranque sin datos utiles: se apaga")
            alPrimerPlano()
            apagar()
            return START_NOT_STICKY
        }

        // UN compartido a la vez, y el anterior se cierra de verdad.
        //
        // Sin esto, empezar un segundo compartido pisaba las variables del
        // servicio y el primero quedaba huerfano: nadie volvia a mandar su
        // posicion y nadie avisaba que habia terminado, asi que del otro lado
        // se veia "en vivo" con un punto congelado hasta que venciera su
        // plazo — que podian ser 24 horas.
        //
        // Se vio en el emulador con tres burbujas a la vez. Cerrar el anterior
        // es mas honesto que llevar varios: "estoy compartiendo mi ubicacion"
        // es un estado, no una lista, y la notificacion —que es el contrato
        // con la persona— tambien es una sola.
        if (mensajeId.isNotBlank() && mensajeId != msgNuevo) {
            val convViejo = conversacionId
            val msgViejo = mensajeId
            ambito.launch {
                runCatching { app.repo.terminarUbicacionEnVivo(convViejo, msgViejo) }
                    .onFailure { Log.w(TAG, "No se pudo cerrar el anterior: ${it.message}") }
            }
            runCatching { oyente?.let { lm?.removeUpdates(it) } }
            oyente = null
            secuencia = 0
            ultimoEnvio = 0L
        }

        conversacionId = convNuevo
        mensajeId = msgNuevo
        hasta = hastaNuevo

        // Primero el primer plano, pase lo que pase. Quien llama a
        // `startForegroundService` DEBE llamar a `startForeground` en unos
        // segundos o el sistema mata la app entera; comprobar los datos antes
        // de eso sería ordenarlo al revés.
        alPrimerPlano()

        engancharUbicacion()
        vigilarElFinal()

        // START_REDELIVER_INTENT y no START_STICKY: si el sistema mata el
        // proceso y lo revive, tiene que devolver el MISMO intent con su
        // conversación y su fecha. Con STICKY volvería con un intent nulo y el
        // servicio se apagaría solo, que es peor que no revivir.
        return START_REDELIVER_INTENT
    }

    /**
     * Se engancha a las posiciones del sistema.
     *
     * Sin servicios de Google, igual que el compartido de una sola posición
     * del módulo M: la app no depende de ellos en ningún otro sitio y atar la
     * función más sensible a un componente propietario sería la peor elección
     * posible para hacerlo.
     */
    private fun engancharUbicacion() {
        val permiso = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!permiso) {
            Log.w(TAG, "Sin permiso de ubicacion: se apaga")
            apagar()
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        if (manager == null) {
            apagar()
            return
        }
        lm = manager

        val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (proveedores.isEmpty()) {
            Log.w(TAG, "Ubicacion del telefono apagada: se apaga")
            apagar()
            return
        }

        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = alLlegarPosicion(loc)

            @Deprecated("Requerido en API < 30")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        oyente = l

        // Se escuchan TODOS los proveedores disponibles y no sólo el mejor: el
        // GPS no engancha bajo techo y la red no engancha en el campo, y un
        // compartido de ocho horas pasa por los dos.
        for (p in proveedores) {
            runCatching {
                manager.requestLocationUpdates(p, CADA_MS, CADA_METROS, l, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "No se pudo escuchar $p: ${it.message}") }
        }
    }

    private fun alLlegarPosicion(loc: Location) {
        val ahora = System.currentTimeMillis()
        if (ahora >= hasta) {
            cortar()
            return
        }
        // El filtro del sistema es por proveedor: con GPS y red enganchados a
        // la vez llegan dos series y se doblarían los sobres. Este es el que
        // cuenta de verdad.
        if (ahora - ultimoEnvio < CADA_MS) return
        ultimoEnvio = ahora
        secuencia += 1

        ambito.launch {
            runCatching {
                app.repo.actualizarUbicacionEnVivo(
                    convId = conversacionId,
                    mensajeId = mensajeId,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    precisionM = loc.accuracy.toInt(),
                    secuencia = secuencia,
                )
            }.onFailure { Log.w(TAG, "No se pudo mandar la posicion: ${it.message}") }
            alPrimerPlano()
        }
    }

    /**
     * El reloj que apaga esto solo.
     *
     * Hace falta aparte de la comprobación en `alLlegarPosicion` porque quien
     * está quieto no genera posiciones: sin este, un compartido de quince
     * minutos hecho desde un sillón seguiría vivo a la mañana siguiente.
     */
    private fun vigilarElFinal() {
        ambito.launch {
            while (true) {
                val queda = hasta - System.currentTimeMillis()
                if (queda <= 0) {
                    cortar()
                    return@launch
                }
                // Se despierta cada minuto como mucho: el texto de la
                // notificación cuenta los minutos que quedan y con menos
                // frecuencia se quedaría atrás.
                delay(minOf(queda, 60_000L))
                alPrimerPlano()
            }
        }
    }

    /** Cortar de verdad: avisa al otro lado y se apaga. */
    private fun cortar() {
        val conv = conversacionId
        val msg = mensajeId
        ambito.launch {
            if (conv.isNotBlank() && msg.isNotBlank()) {
                runCatching { app.repo.terminarUbicacionEnVivo(conv, msg) }
                    .onFailure { Log.w(TAG, "No se pudo avisar el fin: ${it.message}") }
            }
            apagar()
        }
    }

    private fun alPrimerPlano() {
        runCatching {
            ServiceCompat.startForeground(
                this, ID_AVISO, aviso(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        }.onFailure { Log.w(TAG, "No se pudo pasar a primer plano: ${it.message}") }
    }

    private fun aviso(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cortar = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioUbicacionViva::class.java).setAction(ACCION_CORTAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val queda = restanteCorto(hasta - System.currentTimeMillis())
        return NotificationCompat.Builder(this, Notificaciones.CANAL_UBICACION)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Compartiendo tu ubicación")
            .setContentText(if (queda.isBlank()) "Terminando…" else "Termina en $queda")
            .setContentIntent(abrir)
            // `ongoing`: no se puede descartar de un manotazo. La app está
            // leyendo dónde estás y eso no puede quedar sin rastro en la barra.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Dejar de compartir", cortar)
            .build()
    }

    private fun apagar() {
        runCatching { oyente?.let { lm?.removeUpdates(it) } }
        oyente = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { oyente?.let { lm?.removeUpdates(it) } }
        ambito.cancel()
        super.onDestroy()
    }
}

/**
 * Lo que queda, corto, para la notificación.
 *
 * Aparte del de la burbuja porque aquí el espacio es de una línea y la frase
 * completa —"quedan 7 h 43 min"— no entra junto al título.
 */
internal fun restanteCorto(ms: Long): String {
    if (ms <= 0) return ""
    val minutos = ((ms + 59_999) / 60_000).toInt()
    if (minutos < 60) return "$minutos min"
    val horas = minutos / 60
    val resto = minutos % 60
    return if (resto == 0) "$horas h" else "$horas h $resto min"
}
