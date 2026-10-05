package com.wtfuck.app.datos

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Un proxy para las redes que bloquean la app: SOCKS5 o HTTP, como el que
 * levanta Orbot (Tor) o Psiphon en el propio telefono.
 *
 * ## Lo que cubre y lo que no
 *
 * Cubre TODO lo que la app le pide a la red: la API, el socket de mensajes,
 * los archivos, las vistas previas, las fotos de perfil y el mapa. No cubre:
 *  - las **llamadas**: WebRTC va por UDP y por su propio camino;
 *  - las **notificaciones push**: las entrega el sistema, no la app.
 * La pantalla lo dice.
 *
 * ## Por que el proxy no puede leer nada
 *
 * Porque la conexion con nuestro servidor va cifrada DENTRO del tunel, con el
 * certificado fijado (ver `Pinning`): el proxy ve que hay trafico hacia el
 * servidor, no que dice. Y los mensajes, ademas, van cifrados de punta a punta.
 * Con SOCKS5 el nombre del servidor lo resuelve el proxy, asi que un DNS
 * bloqueado en la red tampoco molesta.
 *
 * Sin usuario ni contraseña de proxy, a proposito: los de Tor y Psiphon no los
 * piden, y guardarlos seria guardar una credencial mas.
 */
object Red {

    enum class Tipo(val etiqueta: String) { SOCKS5("SOCKS5"), HTTP("HTTP") }

    data class ConfigProxy(val tipo: Tipo, val host: String, val puerto: Int) {
        fun aProxy(): Proxy = Proxy(
            if (tipo == Tipo.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            // Sin resolver: con SOCKS5, el nombre lo resuelve el proxy.
            InetSocketAddress.createUnresolved(host, puerto),
        )

        override fun toString() = "${tipo.etiqueta} $host:$puerto"
    }

    /** Por que no sirve lo escrito, o null si sirve. Pura, con pruebas. */
    fun problemaCon(host: String, puerto: String): String? {
        val h = host.trim()
        val p = puerto.trim().toIntOrNull()
        return when {
            h.isEmpty() -> "Falta la dirección del proxy."
            h.any { it.isWhitespace() } || h.contains("://") || h.contains('/') ->
                "Solo la dirección, sin http:// ni rutas (por ejemplo 127.0.0.1)."
            h.length > 253 -> "La dirección es demasiado larga."
            p == null || p !in 1..65535 -> "El puerto va de 1 a 65535."
            else -> null
        }
    }

    @Volatile
    var actual: ConfigProxy? = null
        private set

    private val clientes = mutableListOf<WeakReference<OkHttpClient>>()

    private val _cambios = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Cada cambio de proxy: el momento de reconectar el socket. */
    val cambios = _cambios.asSharedFlow()

    /** Lee lo guardado. Va al arrancar la app, antes de crear ningun cliente. */
    fun cargar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tipo = p.getString("tipo", null)?.let { t -> Tipo.entries.firstOrNull { it.name == t } }
        val host = p.getString("host", null)
        val puerto = p.getInt("puerto", 0)
        actual = if (tipo != null && host != null && problemaCon(host, puerto.toString()) == null) {
            ConfigProxy(tipo, host, puerto)
        } else null
    }

    /**
     * Guarda y aplica. Las conexiones que ya estaban abiertas se cierran: si
     * no, el cliente seguiria usando las de antes -directas- hasta que se
     * cayeran solas, y el proxy pareceria no hacer nada.
     */
    fun fijar(ctx: Context, config: ConfigProxy?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (config == null) clear()
            else putString("tipo", config.tipo.name).putString("host", config.host.trim()).putInt("puerto", config.puerto)
        }.apply()
        actual = config
        synchronized(clientes) {
            clientes.removeAll { it.get() == null }
            clientes.forEach { it.get()?.connectionPool?.evictAll() }
        }
        _cambios.tryEmit(Unit)
    }

    /** Lo que eligen los clientes en cada conexion: el proxy de AHORA. */
    private val selector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(actual?.aProxy() ?: Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    /** Todo cliente de la app se construye por aqui. */
    fun construir(b: OkHttpClient.Builder): OkHttpClient {
        val c = b.proxySelector(selector).build()
        synchronized(clientes) { clientes += WeakReference(c) }
        return c
    }

    /**
     * Prueba un proxy antes de guardarlo: se conecta al servidor a traves de
     * el. Cualquier respuesta del servidor vale -incluso un 404-: lo que se
     * prueba es el camino, no la ruta. Devuelve los milisegundos.
     */
    fun probar(config: ConfigProxy, servidor: String): Result<Long> = runCatching {
        val cliente = Pinning.aplicar(
            OkHttpClient.Builder()
                .proxy(config.aProxy())
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS),
        ).build()
        val inicio = System.currentTimeMillis()
        cliente.newCall(okhttp3.Request.Builder().url("$servidor/v1/hora-de-prueba").build()).execute().use { }
        System.currentTimeMillis() - inicio
    }

    private const val PREFS = "red"
}
