package com.wtfuck.app.datos

import android.util.Log
import com.wtfuck.app.BuildConfig
import com.wtfuck.protocol.Bajada
import com.wtfuck.protocol.RUTA_WS
import com.wtfuck.protocol.Subida
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

enum class EstadoConexion { DESCONECTADO, CONECTANDO, CONECTADO }

/**
 * El transporte por internet. Vale igual para wifi, 3G, 4G o 5G: es TCP, la app
 * no distingue la portadora. Lo que si importa en movil es que la conexion se
 * cae seguido, asi que aqui lo central es la reconexion, no el envio.
 */
class Socket(private val ambito: CoroutineScope) {

    private val TAG = "Socket"

    private val http = Pinning.aplicar(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // El servidor manda ping cada 20s. 40s de lectura detecta la caida
            // sin castigar una red lenta.
            .readTimeout(40, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
    ).build()

    private val _estado = MutableStateFlow(EstadoConexion.DESCONECTADO)
    val estado = _estado.asStateFlow()

    private val _entrantes = MutableSharedFlow<Bajada>(extraBufferCapacity = 256)
    val entrantes = _entrantes.asSharedFlow()

    /** Se dispara en cada (re)conexion: el momento de vaciar la cola. */
    private val _conectado = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val conectado = _conectado.asSharedFlow()

    private var ws: WebSocket? = null
    private var token: String? = null
    private var intentos = 0
    private var reconectando: Job? = null
    private var queremosEstar = false

    /**
     * Aviso de que el servidor rechazo el token en el handshake.
     *
     * El socket no conoce la Sesion -no debe-, asi que lo grita y el
     * Repositorio decide. Sin esto, un token revocado dejaba el backoff
     * girando cada 30 s hasta que alguien cerraba la app.
     */
    var alRechazarToken: ((String) -> Unit)? = null

    /**
     * Abre la conexion, o no hace nada si ya esta abierta.
     *
     * Ser idempotente NO es un lujo aqui: `conectar` se llama en cada vuelta
     * de la app al primer plano. Sin este guard cada llamada abria OTRO
     * WebSocket sin cerrar el anterior, el servidor empujaba a todos, y cada
     * mensaje llegaba tantas veces como conexiones hubiera abiertas.
     */
    fun conectar(token: String) {
        val mismoToken = this.token == token
        this.token = token
        queremosEstar = true
        if (mismoToken && _estado.value != EstadoConexion.DESCONECTADO) return
        intentos = 0
        abrir()
    }

    fun desconectar() {
        queremosEstar = false
        reconectando?.cancel()
        ws?.close(1000, "salida")
        ws = null
        _estado.value = EstadoConexion.DESCONECTADO
    }

    fun enviar(msg: Subida): Boolean {
        val s = ws ?: return false
        if (_estado.value != EstadoConexion.CONECTADO) return false
        return s.send(jsonApp.encodeToString(Subida.serializer(), msg))
    }

    private fun abrir() {
        val t = token ?: return
        // Se cierra la anterior antes de abrir otra. Dejarla viva era una fuga
        // de conexion y una fuente de mensajes duplicados.
        ws?.let { anterior ->
            ws = null
            runCatching { anterior.cancel() }
        }
        _estado.value = EstadoConexion.CONECTANDO

        val req = Request.Builder()
            .url("${BuildConfig.SERVIDOR_WS}$RUTA_WS?token=$t")
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                intentos = 0
                _estado.value = EstadoConexion.CONECTADO
                _conectado.tryEmit(Unit)
                Log.i(TAG, "conectado")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching {
                    jsonApp.decodeFromString(Bajada.serializer(), text)
                }.getOrNull() ?: return
                _entrantes.tryEmit(msg)
            }

            override fun onFailure(webSocket: WebSocket, t2: Throwable, response: Response?) {
                Log.w(TAG, "caida: ${t2.message}")
                _estado.value = EstadoConexion.DESCONECTADO
                // Un 401 en el handshake no es una caida de red: es un token
                // que ya no vale. Reintentar es inutil por definicion.
                if (response?.code == 401) {
                    Log.w(TAG, "token rechazado por el servidor")
                    queremosEstar = false
                    token = null
                    alRechazarToken?.invoke("Se cerro la sesion de este aparato.")
                    return
                }
                programarReconexion()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _estado.value = EstadoConexion.DESCONECTADO
                if (queremosEstar) programarReconexion()
            }
        })
    }

    /**
     * Backoff exponencial con techo de 30s. Sin techo, una caida larga deja la
     * app dormida horas; sin backoff, un servidor caido recibe una tormenta.
     */
    private fun programarReconexion() {
        if (!queremosEstar) return
        if (reconectando?.isActive == true) return
        reconectando = ambito.launch {
            val espera = min(30_000.0, 1000.0 * 2.0.pow(intentos.toDouble())).toLong()
            intentos = (intentos + 1).coerceAtMost(6)
            Log.i(TAG, "reintentando en ${espera}ms")
            delay(espera)
            if (queremosEstar) abrir()
        }
    }
}
