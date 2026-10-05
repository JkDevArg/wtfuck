package com.wtfuck.app

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /**
     * El cazador de cierres inesperados.
     *
     * Se instala LO PRIMERO en `onCreate`, antes de que nada mas pueda
     * reventar: el arranque -abrir SQLCipher, leer el Keystore- es justo donde
     * un fallo deja a la persona sin poder entrar Y sin poder contar por que.
     */
    val fallos: CazadorDeFallos by lazy {
        CazadorDeFallos(
            carpeta = CazadorDeFallos.carpetaDe(this),
            version = BuildConfig.VERSION_NAME,
            modelo = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
            android = android.os.Build.VERSION.SDK_INT.toString(),
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Antes que NADA: lo que se instale despues no cubre lo que pase
        // mientras tanto.
        fallos.instalar()

        // Y lo que el SISTEMA sepa de la muerte anterior. Cubre justo lo que
        // el manejador no puede: fallos nativos, ANR y muertes a manos del
        // sistema, donde la app desaparece sin llegar a escribir nada.
        fallos.revisarMuerteAnterior(this)

        Notificaciones.crearCanales(this)

        // Antes que `repo.iniciar()`: lo que el socket baje en este arranque
        // -el que dispara un push, por ejemplo- tiene que encontrar a alguien
        // escuchando. Ver `escucharAvisos`.
        escucharAvisos()
        vigilarAtajosYWidget()

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

    /**
     * Convierte los avisos del servidor en notificaciones del sistema.
     *
     * Fuera del Repositorio a proposito: notificar es cosa de la capa de
     * Android, y asi el Repositorio se puede probar sin framework.
     *
     * ## Por que en la Application y no en la Activity
     *
     * Vivia en `MainActivity`, con `lifecycleScope`. Y la notificacion de un
     * mensaje que llega con la app CERRADA pasa asi: el push despierta el
     * proceso, `ServicioPush` abre el socket, baja el sobre, lo descifra y
     * emite en `notificables`... y no habia nadie escuchando, porque sin
     * interfaz no existe ninguna Activity. El mensaje se guardaba y la
     * notificacion se perdia: justo el caso para el que existe el push.
     *
     * Aqui vive lo que vive el proceso. `notificables` no tiene replay, asi
     * que el colector tiene que estar puesto ANTES de que algo pueda emitir:
     * por eso se llama al principio de `onCreate`.
     */
    /**
     * Mantiene al dia lo que se ve fuera de la app: los atajos de chats
     * recientes y el numero del widget. Solo cuando cambia lo que muestran:
     * el sistema limita cuantas veces por dia se pueden tocar los atajos.
     */
    private fun vigilarAtajosYWidget() {
        ambito.launch {
            repo.conversaciones
                .map { lista -> lista.sumOf { it.noLeidos } }
                .distinctUntilChanged()
                .collect { com.wtfuck.app.datos.WidgetWtfuck.actualizar(this@WtfuckApp, it) }
        }
        ambito.launch {
            repo.conversaciones
                .map { lista -> com.wtfuck.app.datos.Atajos.elegidos(lista).map { it.id to it.titulo } to lista }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, lista) ->
                    com.wtfuck.app.datos.Atajos.publicar(this@WtfuckApp, lista, bloqueo.espera.activo)
                }
        }
    }

    private fun escucharAvisos() {
        val app = this
        ambito.launch {
            run {
                app.repo.avisos.collect { ev ->
                    when (ev.tipo) {
                        "agregado_grupo" -> Notificaciones.agregadoAGrupo(
                            app, ev.actor, ev.nombreConversacion, ev.conversacionId,
                        )
                        // Una advertencia que el usuario no ve no sirve de
                        // nada: el punto de advertir es que haya oportunidad
                        // de corregir.
                        "advertencia" -> Notificaciones.moderacion(app, false)
                        "sancion" -> Notificaciones.moderacion(app, true)
                    }
                }
            }
        }

        // Una notificacion de llamada entrante es "ongoing": no se va sola.
        // Si nadie la borra, queda una llamada fantasma en la bandeja despues
        // de colgar, con sus botones de contestar incluidos.
        //
        // Se borra al DEJAR DE SONAR, no al colgar. Antes solo se limpiaba
        // cuando el estado pasaba a null —o sea al terminar la llamada— y el
        // cartel de "Toca para contestar" se quedaba encima durante toda la
        // conversacion, con sus botones de Contestar y Rechazar puestos.
        //
        // Tapaba ademas la ventanita de la camara propia, que vive justo
        // debajo en la esquina de arriba: parecia que la camara no arrancaba.
        // Dos sintomas que no se parecian entre si, y una sola causa.
        ambito.launch {
            run {
                var ultima: String? = null
                app.repo.llamadas.estado.collect { e ->
                    val sonando = e != null &&
                        e.fase == com.wtfuck.app.datos.EstadoLlamada.Fase.SONANDO &&
                        !e.saliente
                    if (sonando) {
                        ultima = e!!.conversacionId
                    } else {
                        ultima?.let {
                            Notificaciones.quitarLlamada(app, it)
                            ultima = null
                        }
                    }
                }
            }
        }

        // L.6. El Repositorio dice QUE paso; aqui se decide COMO se muestra, y
        // los ajustes por categoria los mira `Notificaciones`. Va en un
        // colector aparte del de eventos porque son dos fuentes distintas: una
        // son avisos del servidor y la otra, cosas que ya pasaron localmente.
        ambito.launch {
            run {
                app.repo.notificables.collect { n ->
                    when (n.tipo) {
                        "mensaje" -> Notificaciones.mensaje(
                            app, n.autor, n.titulo, n.conversacionId, n.esGrupo,
                            silencioso = n.silencioso,
                            mencionado = n.mencionado,
                            protegido = n.protegido,
                        )
                        "canal" -> Notificaciones.canal(
                            app, n.titulo, n.conversacionId,
                        )
                        "llamada" -> Notificaciones.llamada(
                            app, n.autor, n.conVideo, n.conversacionId,
                        )
                    }
                }
            }
        }
    }

    override fun onTerminate() {
        ambito.cancel()
        super.onTerminate()
    }
}
