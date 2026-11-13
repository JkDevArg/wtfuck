package com.wtfuck.app.datos

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R

/**
 * Mantiene viva una grabacion de nota de voz cuando la pantalla se apaga.
 *
 * ## Que problema resuelve
 *
 * La nota de voz se graba en modo manos libres: se toca y queda grabando hasta
 * enviar. Si mientras tanto la pantalla se apaga, la app pasa a segundo plano y
 * Android, desde la 11, **silencia el microfono** de las apps en segundo plano:
 * el `MediaRecorder` sigue escribiendo, pero en silencio. La barra de "pantalla
 * encendida" ([ChatPantalla]) ya cubre el apagado por inactividad; esto cubre el
 * apagado a proposito y que la app pase a segundo plano por otra cosa.
 *
 * ## Por que un servicio en primer plano de tipo microphone
 *
 * Es la unica forma que Android acepta de seguir leyendo el microfono en segundo
 * plano: un servicio en primer plano declarado `microphone`, con su notificacion
 * visible. No graba el audio el mismo -lo sigue haciendo la `Grabadora` en el
 * proceso de la app-; lo que hace es el contrato que le dice al sistema "esta
 * app esta usando el microfono a la vista del usuario", y asi no lo corta ni
 * mata el proceso.
 *
 * Se arranca al empezar a grabar (con la app en primer plano, que es cuando
 * Android permite arrancarlo) y se detiene al terminar o cancelar.
 */
class ServicioGrabacionFg : Service() {

    companion object {
        private const val ID_AVISO = 4343

        /** Arranca el servicio, o no hace nada si Android no lo permite. */
        fun arrancar(ctx: Context) {
            val i = Intent(ctx, ServicioGrabacionFg::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun detener(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioGrabacionFg::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        alPrimerPlano()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Quien llama a startForegroundService DEBE llamar a startForeground en
        // unos segundos o el sistema mata la app; por eso tambien aqui.
        alPrimerPlano()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun alPrimerPlano() {
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        // Sin runCatching: si falla hay que enterarse. Tragar la excepcion solo
        // cambia el sintoma por uno peor (ForegroundServiceDidNotStartInTime).
        try {
            ServiceCompat.startForeground(this, ID_AVISO, aviso(), tipo)
        } catch (ex: Exception) {
            android.util.Log.e("GrabacionFg", "No se pudo pasar a primer plano: $ex", ex)
            stopSelf()
        }
    }

    private fun aviso(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notificaciones.CANAL_GRABACION)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle("Grabando una nota de voz")
            .setContentText("Tocá para volver al chat")
            .setContentIntent(abrir)
            .build()
    }
}
