package com.wtfuck.app.datos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Mensajes programados: escritos ahora, enviados a la hora que se eligio.
 *
 * ## Por que viven en el telefono
 *
 * Porque el servidor no puede mandar nada en nombre de nadie: no tiene el
 * contenido, solo sobres cifrados. Para que el servidor lo hiciera habria que
 * dejarle el sobre ya cifrado y una hora, y eso es contarle cuando alguien
 * planea escribir y a quien. Telegram lo hace en el servidor porque ahi el
 * servidor lee los chats; Signal no lo tiene por lo mismo.
 *
 * El costo, que se dice en pantalla: sale desde ESTE telefono. Si a esa hora
 * esta apagado o sin red, sale cuando vuelva.
 *
 * ## Como
 *
 * El mensaje se guarda como cualquier otro pero con estado `PROGRAMADO` y
 * `oculto`: la cola solo toma `PENDIENTE`, asi que no sale, y `oculto` lo saca
 * del chat, de la lista y del buscador sin tocar ninguna consulta. A la hora,
 * pasa a `PENDIENTE` y lo demas lo hace la maquinaria de siempre: cifrado,
 * reintentos, cola en segundo plano.
 *
 * Lo despierta una alarma que el sistema respeta en reposo (`AllowWhileIdle`),
 * que puede llegar con unos minutos de diferencia, y por las dudas un trabajo
 * de WorkManager a la misma hora: las alarmas se pierden al reiniciar el
 * telefono y los trabajos no. Las dos cosas hacen lo mismo y lo segundo que
 * llegue no encuentra nada que hacer.
 */
object Programados {

    const val ESTADO = "PROGRAMADO"

    private const val TAG = "Programados"
    private const val TRABAJO = "wtfuck-programados"

    /** Deja la alarma y el trabajo puestos para [proximo], o los quita si es null. */
    fun armar(ctx: Context, proximo: Long?) {
        val alarmas = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, Receptor::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trabajos = runCatching { WorkManager.getInstance(ctx) }.getOrNull()
        if (proximo == null) {
            alarmas?.cancel(pi)
            trabajos?.cancelUniqueWork(TRABAJO)
            return
        }
        // Inexacta a proposito: la exacta pide un permiso que Android 14 ya no
        // da por defecto, y un mensaje que sale cinco minutos tarde no justifica
        // mandar a la persona a los ajustes del sistema.
        alarmas?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, proximo, pi)
        val espera = (proximo - System.currentTimeMillis()).coerceAtLeast(0)
        trabajos?.enqueueUniqueWork(
            TRABAJO, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Trabajo>().setInitialDelay(espera, TimeUnit.MILLISECONDS).build(),
        )
    }

    /** Lo que despierta la alarma. Sin exportar: solo la usa el PendingIntent de arriba. */
    class Receptor : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext as? WtfuckApp ?: return
            val pendiente = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (app.sesion.hayS) app.repo.liberarProgramados()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron liberar: ${e.message}")
                } finally {
                    pendiente.finish()
                }
            }
        }
    }

    /** El respaldo de la alarma: sobrevive a un reinicio. */
    class Trabajo(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as? WtfuckApp ?: return Result.success()
            if (app.sesion.hayS) runCatching { app.repo.liberarProgramados() }
            return Result.success()
        }
    }
}

/**
 * Las partes puras de elegir cuando: los atajos y como se dice una hora.
 * Aparte para poder probarlas sin un telefono.
 */
object MomentoProgramado {

    data class Opcion(val etiqueta: String, val cuando: Long)

    /**
     * Los atajos de siempre: en una hora, esta noche si todavia no es de noche,
     * y mañana temprano. Todos redondeados a minuto: "a las 20:00:37" no es una
     * hora que alguien elija.
     */
    fun atajos(ahora: ZonedDateTime): List<Opcion> {
        val enUnaHora = ahora.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        val estaNoche = ahora.with(LocalTime.of(20, 0)).truncatedTo(ChronoUnit.MINUTES)
        val manana = ahora.plusDays(1).with(LocalTime.of(8, 0)).truncatedTo(ChronoUnit.MINUTES)
        return buildList {
            add(Opcion("En 1 hora", enUnaHora.toInstant().toEpochMilli()))
            // Solo si falta al menos media hora: a las 19:50, "esta noche a las
            // 20:00" es casi lo mismo que mandarlo ya.
            if (estaNoche.isAfter(ahora.plusMinutes(30))) {
                add(Opcion("Esta noche, 20:00", estaNoche.toInstant().toEpochMilli()))
            }
            add(Opcion("Mañana, 08:00", manana.toInstant().toEpochMilli()))
        }
    }

    /** "hoy 20:00", "mañana 08:00" o "lun 12 oct, 09:30". */
    fun etiqueta(cuando: Long, ahora: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(cuando).atZone(ahora.zone)
        val hora = t.format(DateTimeFormatter.ofPattern("HH:mm"))
        val dias = ChronoUnit.DAYS.between(ahora.toLocalDate(), t.toLocalDate())
        return when (dias) {
            0L -> "hoy $hora"
            1L -> "mañana $hora"
            else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-PE"))) +
                ", $hora"
        }
    }

    /** Si una hora elegida a mano sirve: en el futuro y a no mas de un año. */
    fun valido(cuando: Long, ahora: Long): Boolean =
        cuando > ahora + 30_000 && cuando < ahora + 366L * 24 * 3600 * 1000

    fun ahora(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
}
