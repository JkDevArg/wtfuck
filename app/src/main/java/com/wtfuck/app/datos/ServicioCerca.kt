package com.wtfuck.app.datos

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wtfuck.app.MainActivity
import com.wtfuck.app.R
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * El modo cerca en primer plano, mientras esté encendido.
 *
 * ## Por qué hace falta
 *
 * Sin esto el diálogo tenía que decir "deja esta pantalla abierta en los dos
 * teléfonos": con la pantalla apagada o la app en segundo plano, Android corta
 * el proceso -y el enlace con él- en minutos. Con un servicio de tipo
 * `connectedDevice` el enlace sigue, y la notificación dice que la radio está
 * escuchando, que es algo que la persona tiene derecho a ver.
 *
 * ## Lo que NO puede hacer, porque Android no lo deja
 *
 * Encenderse solo. Un servicio en primer plano no puede arrancar desde
 * segundo plano: el modo cerca lo enciende la persona, desde el diálogo, y no
 * puede activarse por su cuenta cuando se va la señal con la app cerrada.
 */
class ServicioCerca : Service() {

    private val app get() = application as WtfuckApp
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observando = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_APAGAR) {
            app.repo.cerca.apagar()
            parar()
            return START_NOT_STICKY
        }
        // Primero el primer plano, pase lo que pase: quien llama a
        // `startForegroundService` tiene unos segundos para esto o el sistema
        // mata la app entera.
        mostrar(null, TransporteCerca.Estado.ESCUCHANDO)
        // Una sola observacion aunque lo arranquen dos veces.
        if (observando) return START_NOT_STICKY
        observando = true
        ambito.launch {
            combine(app.repo.cerca.estado, app.repo.cerca.conQuien) { e, q -> e to q }.collect { (e, q) ->
                if (e == TransporteCerca.Estado.APAGADO) parar() else mostrar(q, e)
            }
        }
        // NOT_STICKY: si el sistema mata el proceso, el modo cerca NO revive
        // solo. Volver a escuchar conexiones sin que nadie lo pidiera es justo
        // lo que el apagado por defecto quiere evitar.
        return START_NOT_STICKY
    }

    private fun mostrar(conQuien: String?, estado: TransporteCerca.Estado) {
        runCatching {
            ServiceCompat.startForeground(
                this, ID_AVISO, aviso(conQuien, estado),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.onFailure { Log.w(TAG, "No se pudo pasar a primer plano: ${it.message}") }
    }

    private fun aviso(conQuien: String?, estado: TransporteCerca.Estado): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val apagar = PendingIntent.getService(
            this, 1,
            Intent(this, ServicioCerca::class.java).setAction(ACCION_APAGAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val texto = if (estado == TransporteCerca.Estado.ENLAZADO && conQuien != null) {
            "Conectado con @$conQuien. Lo que le escribas sale por Bluetooth."
        } else {
            "Buscando a alguien cerca. Se apaga solo tras media hora sin nadie."
        }
        return NotificationCompat.Builder(this, Notificaciones.CANAL_CERCA)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Modo cerca encendido")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(0, "Apagar", apagar)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun parar() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ServicioCerca"
        private const val ID_AVISO = 4545
        private const val ACCION_APAGAR = "com.wtfuck.app.CERCA_APAGAR"

        fun iniciar(ctx: Context) {
            runCatching {
                val i = Intent(ctx, ServicioCerca::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "No se pudo arrancar: ${it.message}") }
        }

        fun detener(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServicioCerca::class.java)) }
        }
    }
}
