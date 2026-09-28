package com.wtfuck.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wtfuck.app.datos.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Contenedor de dependencias, a mano.
 *
 * No hay Hilt a proposito: son seis objetos con un grafo lineal. Un framework de
 * inyeccion aqui agrega procesamiento de anotaciones y un modo mas de fallar en
 * el build, sin quitar ninguna linea real.
 */
class WtfuckApp : Application(), SingletonImageLoader.Factory {

    val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sesion by lazy { Sesion(this) }
    val api by lazy { ApiCliente(sesion) }

    /** Avisa de que hay una version nueva y la deja lista para instalar. */
    val actualizador by lazy { Actualizador(this, api) }
    val base by lazy { BaseLocal.crear(this) }
    val socket by lazy { Socket(ambito) }
    val archivos by lazy { ArchivosLocales(this) }
    val ajustes by lazy { Ajustes(this) }

    /** Modulo U. Bloqueo de la app con huella o el PIN del telefono. */
    val bloqueo by lazy { AjustesBloqueo(this) }

    /**
     * Modulo N. Vive en el contenedor y no dentro del Repositorio porque el
     * SERVICIO de mensajeria lo necesita sin que la interfaz exista: cuando un
     * aviso despierta el proceso, `Application.onCreate` corrio y nada mas.
     */
    val push by lazy { Push(this, api, sesion) }

    /**
     * El cifrador real. Modulo E.
     *
     * Aqui se ve que la abstraccion valio: cambiar esta linea es TODO lo que
     * hizo falta del lado del contenedor. Ni el despachador, ni la cola, ni la
     * UI saben que ahora hay Double Ratchet detras.
     *
     * [CifradorPlano] sigue existiendo y se puede volver a enchufar aqui para
     * depurar el transporte mirando los cuerpos en la base del servidor.
     */
    val almacenSignal by lazy { AlmacenSignal(base.signalDao()) }

    val cifrador: Cifrador by lazy { CifradorSignal(almacenSignal, base.signalDao(), api, sesion) }

    val repo by lazy {
        Repositorio(
            contexto = this,
            api = api,
            dao = base.chatDao(),
            socket = socket,
            sesion = sesion,
            cifrador = cifrador,
            signalDao = base.signalDao(),
            archivos = archivos,
            ajustes = ajustes,
            ambito = ambito,
        )
    }

    /**
     * Cargador de imagenes.
     *
     * Las fotos de perfil estan detras de autenticacion, asi que el cliente de
     * Coil tiene que mandar el token igual que el resto de la app. El header se
     * agrega SOLO a nuestro servidor: un `addInterceptor` global mandaria el
     * token a cualquier host que apareciera en una URL.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val servidor = BuildConfig.SERVIDOR.toHttpUrlOrNull()

        val cliente = OkHttpClient.Builder()
            .addInterceptor { cadena ->
                val url = cadena.request().url
                val esNuestro = servidor != null && url.host == servidor.host && url.port == servidor.port
                val token = sesion.token
                val req = if (esNuestro && token != null) {
                    cadena.request().newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    cadena.request()
                }
                cadena.proceed(req)
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { cliente }))
                // Decodificador de GIF animado. Sin esto un GIF se ve como su
                // primer fotograma, que es exactamente lo que un GIF no es.
                if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("imagenes").toOkioPath())
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Notificaciones.crearCanales(this)

        // Firebase se inicializa con lo que haya en disco, ANTES de pedirle
        // nada al servidor: un aviso puede llegar en el mismo segundo del
        // arranque, y esperar la red para inicializar perderia el primero de
        // cada arranque. Ver `Push.inicializarSiSePuede`.
        push.inicializarSiSePuede()

        if (sesion.hayS) {
            repo.iniciar()
            // Y en segundo plano se refresca la configuracion y el token. Si
            // el servidor apago el push, esto lo detecta y se da de baja.
            ambito.launch { push.poner() }
        }
    }

    override fun onTerminate() {
        ambito.cancel()
        super.onTerminate()
    }
}
