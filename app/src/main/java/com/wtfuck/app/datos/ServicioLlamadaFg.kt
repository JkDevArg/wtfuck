package com.wtfuck.app.datos

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import android.app.Service
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * K.8 · La llamada vive en un servicio en primer plano.
 *
 * ## El problema que resuelve, en una linea
 *
 * Hasta aqui una llamada vivia mientras viviera la Activity: salir de la app la
 * cortaba, y Android mata procesos en segundo plano sin avisar. Estaba
 * declarado como deuda desde el modulo K y es lo que separa "una llamada de
 * demostracion" de "una llamada".
 *
 * ## Por que un servicio y no una corrutina mas
 *
 * No es cuestion de donde corre el codigo: es lo que Android **promete**. Un
 * proceso con un servicio en primer plano y su notificacion visible es
 * candidato de ultima instancia para el asesino de memoria; sin el, es de los
 * primeros. La notificacion no es un adorno ni un requisito burocratico: es el
 * contrato. El sistema protege el proceso porque la persona puede ver que algo
 * esta pasando y cortarlo de un toque.
 *
 * ## El tipo de servicio no es opcional
 *
 * Desde Android 14 hay que declarar PARA QUE es el servicio, y el sistema
 * comprueba que la app tenga el permiso correspondiente EN ESE MOMENTO. Una
 * llamada de audio es `microphone`; con video, tambien `camera`. Declarar el
 * tipo equivocado -o arrancar sin el permiso- no es una advertencia: es una
 * excepcion y el servicio no arranca.
 *
 * ## Lo que sigue sin poder hacer
 *
 * Una llamada entrante con la app **cerrada** no suena, y esto no lo arregla:
 * hace falta un empujon del sistema (FCM o equivalente) que despierte el
 * proceso, y eso es infraestructura aparte. Lo que arregla es que una llamada
 * en curso sobreviva a salir de la app, que es el caso que se da todo el rato.
 */
class ServicioLlamadaFg : Service() {

    private val app get() = application as WtfuckApp

    /**
     * Ambito propio y no `lifecycleScope`.
     *
     * `LifecycleService` traeria una dependencia entera -`lifecycle-service`-
     * para lo unico que hace falta aqui: un ambito que muera con el servicio.
     * Eso son tres lineas.
     */
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val ID_AVISO = 4242
        const val ACCION_COLGAR = "com.wtfuck.app.COLGAR"
        const val ACCION_CONTESTAR = "com.wtfuck.app.CONTESTAR"
        const val ACCION_RECHAZAR = "com.wtfuck.app.RECHAZAR"

        /**
         * Los tres intents que puede disparar una notificacion de llamada.
         *
         * Viven aqui y no en `Notificaciones` porque el destinatario es este
         * servicio: la notificacion entrante que publica `Notificaciones`
         * -la que se ve cuando la app esta en segundo plano y el servicio no
         * pudo arrancar- tiene que poder contestar y rechazar igual que la del
         * servicio, o serian dos notificaciones de llamada con capacidades
         * distintas segun donde estuviera la app, que es inexplicable.
         */
        fun intentDeAccion(ctx: Context, accion: String, codigo: Int): PendingIntent =
            PendingIntent.getService(
                ctx, codigo,
                Intent(ctx, ServicioLlamadaFg::class.java).setAction(accion),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Arranca el servicio, o no hace nada si Android no lo permite.
         *
         * `runCatching` no es pereza: desde Android 12 arrancar un servicio en
         * primer plano **desde el fondo** lanza
         * `ForegroundServiceStartNotAllowedException`, y ese caso existe de
         * verdad -una oferta de llamada que llega con la app en segundo plano-.
         * Cuando pasa, la llamada sigue funcionando como antes y la
         * notificacion normal es la que avisa; lo que se pierde es la promesa
         * de que el proceso no muera, no la llamada.
         */
        fun arrancar(ctx: Context) {
            val i = Intent(ctx, ServicioLlamadaFg::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun detener(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioLlamadaFg::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Primero el primer plano, y solo despues cualquier otra cosa.
        //
        // El contrato de Android es estricto: quien llama a
        // `startForegroundService` DEBE llamar a `startForeground` en unos
        // segundos, y si el servicio se detiene antes de hacerlo, el sistema
        // mata la app entera. Por eso esto no espera a que llegue nada.
        alPrimerPlano(app.repo.llamadas.estado.value)

        // Y se apaga solo cuando la llamada termina. Que lo decida el estado y
        // no quien lo arranco evita el caso clasico: colgar desde la pantalla y
        // quedarse con una notificacion de llamada eterna.
        ambito.launch {
            // `huboLlamada` es la guarda que falto la primera vez: un
            // `StateFlow` entrega su valor ACTUAL al suscribirse, y al arrancar
            // ese valor puede ser null porque la llamada todavia se esta
            // creando. Sin esta bandera, el servicio se detenia a si mismo en
            // el primer instante y Android mataba la app.
            var huboLlamada = false
            app.repo.llamadas.estado.collect { e ->
                if (e != null) {
                    huboLlamada = true
                    alPrimerPlano(e)
                } else if (huboLlamada) {
                    apagar()
                }
            }
        }

        // Red de seguridad: si la llamada nunca llega a existir -la peticion
        // fallo, el otro lado no contesto el handshake-, el servicio no puede
        // quedarse con una notificacion de "Llamada" para siempre.
        ambito.launch {
            kotlinx.coroutines.delay(20_000)
            if (app.repo.llamadas.estado.value == null) apagar()
        }
    }

    private fun apagar() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_COLGAR -> ambito.launch { app.repo.llamadas.colgar() }
            ACCION_CONTESTAR -> ambito.launch {
                // Contestar desde la notificacion arranca la llamada sin abrir
                // la app. Se aprovecha el permiso temporal que da Android al
                // tocar una accion de notificacion para pasar a primer plano.
                app.repo.llamadas.contestar()
            }
            ACCION_RECHAZAR -> ambito.launch {
                app.repo.llamadas.rechazar()
                apagar()
            }
            else -> {
                // Pasar a primer plano tiene que ocurrir en los primeros
                // segundos o el sistema mata el servicio con su propio ANR. Si
                // todavia no hay estado se muestra algo neutro, y el colector
                // de arriba lo corrige en cuanto llegue.
                alPrimerPlano(app.repo.llamadas.estado.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun alPrimerPlano(e: EstadoLlamada?) {
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (e?.conVideo == true) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            t
        } else {
            0
        }
        // Sin runCatching a proposito: si esto falla hay que ENTERARSE. Con
        // la excepcion tragada, el sintoma es que Android mata la app entera
        // unos segundos despues con `ForegroundServiceDidNotStartInTime`, un
        // error que no dice nada de la causa real.
        try {
            ServiceCompat.startForeground(this, ID_AVISO, aviso(e), tipo)
        } catch (ex: Exception) {
            android.util.Log.e("LlamadaFg", "No se pudo pasar a primer plano: " + ex, ex)
            stopSelf()
        }
    }

    /**
     * La notificacion de la llamada.
     *
     * Con `CallStyle` (Android 12+) el sistema la trata como lo que es: la pone
     * arriba de todo, la deja visible mientras dure y le da botones grandes. No
     * es estetica: una notificacion de llamada perdida entre las demas es una
     * llamada que se pierde.
     */
    private fun aviso(e: EstadoLlamada?): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val colgar = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioLlamadaFg::class.java).setAction(ACCION_COLGAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val b = NotificationCompat.Builder(this, Notificaciones.CANAL_LLAMADAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(abrir)
            // El texto NO dice de que se habla, porque no se puede saber: la
            // llamada va cifrada de punta a punta. Dice con quien y en que va.
            .setContentTitle(e?.let { "@" + it.conQuien } ?: "Llamada")
            .setContentText(
                when (e?.fase) {
                    EstadoLlamada.Fase.SONANDO ->
                        if (e.saliente) "Llamando..." else "Llamada entrante"
                    EstadoLlamada.Fase.CONECTANDO -> "Conectando..."
                    EstadoLlamada.Fase.EN_CURSO ->
                        if (e.conVideo) "Videollamada en curso" else "En llamada"
                    else -> "Llamada"
                }
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e != null) {
            val quien = Person.Builder().setName("@" + e.conQuien).setImportant(true).build()
            b.setStyle(
                if (e.fase == EstadoLlamada.Fase.SONANDO && !e.saliente) {
                    val contestar = PendingIntent.getService(
                        this, 2,
                        Intent(this, ServicioLlamadaFg::class.java).setAction(ACCION_CONTESTAR),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    NotificationCompat.CallStyle.forIncomingCall(quien, colgar, contestar)
                } else {
                    NotificationCompat.CallStyle.forOngoingCall(quien, colgar)
                }
            )
        } else {
            // Antes de Android 12 no hay CallStyle: un boton normal y listo.
            b.addAction(R.drawable.ic_notificacion, "Colgar", colgar)
        }

        // Mientras suena, pantalla completa: es lo que hace que una llamada
        // entrante se vea aunque el telefono este bloqueado. El permiso
        // `USE_FULL_SCREEN_INTENT` esta declarado para eso, y esta ES una app
        // de llamadas, que es el caso para el que existe.
        if (e?.fase == EstadoLlamada.Fase.SONANDO && !e.saliente) {
            b.setFullScreenIntent(abrir, true)
        }

        return b.build()
    }
}
