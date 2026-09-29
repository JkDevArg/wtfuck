package com.wtfuck.app.datos

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wtfuck.app.WtfuckApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Vaciar la cola de salida aunque la app este cerrada.
 *
 * ## El defecto que arregla
 *
 * La cola de salida es persistente -filas `PENDIENTE` en la base- y eso estaba
 * bien: un mensaje escrito sin red **no se pierde** aunque se mate el proceso.
 * Lo que no habia era quien la vaciara despues.
 *
 * `despachar()` se llamaba al reconectar el socket, al mandar otro mensaje, o
 * al volver la app al primer plano. Los tres tienen algo en comun: **necesitan
 * que la app este viva**. Asi que el caso normal de "escribo en el ascensor,
 * bloqueo el telefono y me olvido" terminaba con el mensaje ahi parado hasta
 * que la persona volviera a abrir la app — a veces horas, y sin ninguna
 * senal, porque en su pantalla el mensaje ya estaba escrito.
 *
 * ## Por que WorkManager y no un hilo propio
 *
 * Porque lo que hace falta es justo lo que un hilo no puede dar: sobrevivir a
 * que el sistema mate el proceso, esperar a que **vuelva la red** sin gastar
 * bateria sondeando, y aguantar un reinicio del telefono. Eso en Android lo
 * hace el planificador del sistema o no lo hace nadie.
 *
 * Doze tambien entra aqui: un `delay()` dentro de una corrutina no corre con
 * el telefono dormido, y una alarma exacta para mandar un mensaje de chat
 * seria abusar de un permiso que existe para despertadores.
 */
object ColaEnSegundoPlano {

    private const val TAG = "ColaFondo"

    /**
     * Nombre unico del trabajo.
     *
     * Unico y con `KEEP`: sin esto, cada mensaje que no sale encolaria otro
     * trabajo, y al volver la red se despertarian veinte a la vez para hacer
     * lo mismo. Uno basta — vacia la cola entera.
     */
    private const val TRABAJO = "wtfuck-cola-salida"

    /** Que hacer despues de intentar despachar. */
    enum class Decision { PROGRAMAR, CANCELAR }

    /**
     * La regla, aparte para poder probarla.
     *
     * Trivial a proposito: si quedan mensajes por salir hace falta que alguien
     * lo reintente; si no quedan, hay que **cancelar**. Lo segundo importa mas
     * de lo que parece: un trabajo que sigue programado cuando ya no hay nada
     * despierta el telefono para no hacer nada, y eso es de las cosas que
     * hacen que una app salga en la lista de las que gastan bateria.
     */
    fun decidir(quedanPendientes: Boolean): Decision =
        if (quedanPendientes) Decision.PROGRAMAR else Decision.CANCELAR

    /** Aplica la decision. Lo llama el repositorio al final de `despachar`. */
    fun ajustar(ctx: Context, quedanPendientes: Boolean) {
        when (decidir(quedanPendientes)) {
            Decision.PROGRAMAR -> programar(ctx)
            Decision.CANCELAR -> cancelar(ctx)
        }
    }

    private fun programar(ctx: Context) {
        val peticion = OneTimeWorkRequestBuilder<Repartidor>()
            .setConstraints(
                Constraints.Builder()
                    // Sin red no hay nada que intentar, y despertar para
                    // comprobarlo es gastar bateria por gusto. Que el sistema
                    // avise cuando la haya es justo para lo que esta.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // KEEP y no REPLACE: si ya hay uno esperando, reemplazarlo REINICIA su
        // espera. Con un mensaje nuevo cada pocos minutos, el trabajo se
        // pospondria una y otra vez y no llegaria a correr nunca — el clasico
        // temporizador que se reinicia solo.
        runCatching {
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(TRABAJO, ExistingWorkPolicy.KEEP, peticion)
        }.onFailure { Log.w(TAG, "no se pudo programar: ${it.message}") }
    }

    private fun cancelar(ctx: Context) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(TRABAJO) }
    }

    /**
     * Cuanto se espera a que el socket abra antes de rendirse por esta vuelta.
     *
     * Veinte segundos: una conexion movil lenta entra de sobra, y no se agota
     * el presupuesto que el sistema le da a un trabajo en segundo plano.
     * Rendirse aqui no pierde nada — el mensaje sigue en la cola y habra otra
     * vuelta.
     */
    private const val ESPERA_SOCKET_MS = 20_000L

    /**
     * El trabajo: despertar, **conectar**, vaciar la cola y decir si hay que
     * volver.
     *
     * ## Por que hay que conectar a mano, y no es un detalle
     *
     * El transporte principal solo se declara disponible con el socket
     * `CONECTADO` (`socket.estado.value == EstadoConexion.CONECTADO`). Con la
     * app cerrada el socket **no existe**: no lo ha abierto nadie.
     *
     * Asi que un worker que llamara a `despachar()` a secas no encontraria
     * transporte, volveria en el acto sin mandar nada, pediria reintento y
     * repetiria la nada con espera creciente. La funcion entera habria sido un
     * no-op silencioso — lo peor posible, porque parece hecha.
     *
     * Por eso arranca el transporte y **espera** a que abra antes de despachar.
     */
    class Repartidor(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

        override suspend fun doWork(): Result {
            val app = applicationContext as? WtfuckApp ?: return Result.success()

            // Sin sesion no hay nada que mandar, y tocar el repositorio seria
            // abrir la base cifrada para nada.
            if (!app.sesion.hayS) return Result.success()

            return runCatching {
                // Y si ya no queda nada -la app se abrio y despacho sola entre
                // que esto se programo y corrio- se sale sin conectar.
                if (!app.repo.hayPendientes()) {
                    Log.i(TAG, "la cola ya estaba vacia")
                    return@runCatching Result.success()
                }

                app.repo.iniciar()
                val conectado = withTimeoutOrNull(ESPERA_SOCKET_MS) {
                    app.repo.estadoConexion.first { it == EstadoConexion.CONECTADO }
                } != null

                if (!conectado) {
                    // Hay red -el sistema lo garantizo con la restriccion- pero
                    // el servidor no responde. Reintentar con espera creciente
                    // es exactamente lo correcto.
                    Log.i(TAG, "no se pudo conectar; se reintentara")
                    return@runCatching Result.retry()
                }

                app.repo.despachar()

                if (app.repo.hayPendientes()) {
                    // `retry` y no `failure`: que no saliera puede ser un
                    // servidor caido, o mensajes que esperan una sesion de
                    // cifrado que todavia no existe.
                    Log.i(TAG, "quedan mensajes en cola, se reintentara")
                    Result.retry()
                } else {
                    Log.i(TAG, "cola vaciada")
                    Result.success()
                }
            }.getOrElse {
                Log.w(TAG, "fallo el despacho en segundo plano: ${it.message}")
                Result.retry()
            }
        }
    }
}
