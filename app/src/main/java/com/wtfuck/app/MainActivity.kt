package com.wtfuck.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wtfuck.app.ui.AvisoDeActualizacion
import com.wtfuck.app.ui.InvitacionesPantalla
import com.wtfuck.app.ui.CopiaSeguridadPantalla
import com.wtfuck.app.ui.NovedadesPantalla
import com.wtfuck.app.ui.ComunidadesPantalla
import com.wtfuck.app.ui.ChatPantalla
import com.wtfuck.app.ui.CompartidoPantalla
import com.wtfuck.app.ui.PersonaPantalla
import com.wtfuck.app.ui.DiagnosticoPantalla
import com.wtfuck.app.ui.TipoCuentaPantalla
import com.wtfuck.app.ui.DosPaneles
import com.wtfuck.app.ui.PanelVacio
import com.wtfuck.app.ui.anchoParaDosPaneles
import com.wtfuck.app.ui.GrupoPantalla
import com.wtfuck.app.datos.Notificaciones
import com.wtfuck.app.ui.AlmacenamientoPantalla
import com.wtfuck.app.ui.CanalPantalla
import com.wtfuck.app.ui.HuellaPantalla
import com.wtfuck.app.ui.CuentaPantalla
import com.wtfuck.app.ui.DispositivosPantalla
import com.wtfuck.app.ui.CapaLlamada
import com.wtfuck.app.ui.HistorialLlamadasPantalla
import com.wtfuck.app.ui.BitacoraPantalla
import com.wtfuck.app.ui.ConsolaWebPantalla
import com.wtfuck.app.ui.ExcepcionesPantalla
import com.wtfuck.app.ui.ConversacionesPanelPantalla
import com.wtfuck.app.ui.Inicio
import com.wtfuck.app.ui.LimitesPantalla
import com.wtfuck.app.ui.NotificacionesPantalla
import com.wtfuck.app.ui.ModeracionPantalla
import com.wtfuck.app.ui.PanelPantalla
import com.wtfuck.app.ui.AuthPantalla
import com.wtfuck.app.ui.PrivacidadPantalla
import com.wtfuck.app.ui.PantallaBloqueada
import com.wtfuck.app.ui.theme.WtfuckTheme

/**
 * `FragmentActivity` y no `ComponentActivity` **sólo** por el módulo U:
 * `BiometricPrompt` lo exige, porque se apoya en un fragmento invisible para
 * sobrevivir a los cambios de configuración mientras el diálogo del sistema
 * está abierto. `FragmentActivity` extiende `ComponentActivity`, así que
 * Compose y todo lo demás siguen igual.
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * Si la app está tapada por la pantalla de bloqueo ahora mismo.
     *
     * Vive en la Activity y no dentro de `Raiz()` porque lo decide el ciclo de
     * vida —irse a segundo plano y volver—, que es algo que la Activity ve y
     * un Composable no.
     */
    private val bloqueada = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        escucharAvisos()
        val app = application as WtfuckApp

        // Arranque en frío: se decide ANTES de dibujar nada. Si se decidiera
        // después, la app se vería un instante antes de taparse, y ese
        // instante es justo lo que el bloqueo existe para evitar.
        bloqueada.value = app.bloqueo.bloqueadoAhora(android.os.SystemClock.elapsedRealtime())
        aplicarPrivacidadDeRecientes(app)

        setContent {
            WtfuckTheme(tema = app.ajustes.tema) {
                PedirPermisoNotificaciones()
                Surface(modifier = Modifier.fillMaxSize()) {
                    Raiz()
                    // ENCIMA de Raiz y dentro del mismo Surface: tapa lo que
                    // haya, incluido un chat abierto o una llamada en curso, y
                    // Atras no la puede quitar porque no es un destino.
                    if (bloqueada.value) {
                        PantallaBloqueada(onDesbloquear = { desbloquear(app) })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as WtfuckApp
        if (app.sesion.hayS) app.repo.iniciar()
        // Tambien aqui y no solo en `onCreate`: el ajuste se cambia sin
        // recrear la Activity, y si solo se aplicara al crearla, activar el
        // bloqueo dejaria la miniatura de recientes a la vista hasta el
        // siguiente arranque en frio.
        aplicarPrivacidadDeRecientes(app)
        // Al volver de segundo plano. `onStart` y no `onResume`: `onResume` se
        // dispara tambien al cerrarse un dialogo del sistema —el propio
        // BiometricPrompt, un permiso, el selector de archivos— y volveria a
        // bloquear a mitad de cualquiera de esas cosas.
        if (app.bloqueo.bloqueadoAhora(android.os.SystemClock.elapsedRealtime())) {
            bloqueada.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as WtfuckApp
        // Se anota CUANDO se dejo de usar la app, que es desde donde cuenta la
        // espera. No se anota si ya estaba bloqueada: si no, salir y volver
        // reiniciaria el contador y abriria la app sin autenticar nada.
        if (!bloqueada.value && app.bloqueo.espera.activo) {
            app.bloqueo.ultimoDesbloqueo = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun desbloquear(app: WtfuckApp) {
        app.bloqueo.ultimoDesbloqueo = android.os.SystemClock.elapsedRealtime()
        bloqueada.value = false
    }

    /**
     * Oculta el contenido en el conmutador de aplicaciones cuando el bloqueo
     * esta activo.
     *
     * Sin esto, el bloqueo tiene un agujero evidente: la miniatura de la app en
     * la lista de recientes muestra el ultimo chat abierto, y esa lista se ve
     * **sin desbloquear nada**.
     *
     * Se usa `setRecentsScreenshotEnabled` y NO `FLAG_SECURE`. `FLAG_SECURE`
     * tambien taparia la miniatura, pero de paso prohibe toda captura de
     * pantalla dentro de la app, y eso es una decision distinta que nadie
     * pidio: hay motivos legitimos para capturar una conversacion propia.
     */
    private fun aplicarPrivacidadDeRecientes(app: WtfuckApp) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!app.bloqueo.espera.activo)
        }
    }

    /**
     * Convierte los avisos del servidor en notificaciones del sistema.
     *
     * Vive en la Activity y no en el Repositorio a proposito: notificar es cosa
     * de la capa de Android, y asi el Repositorio se puede probar sin framework.
     */
    private fun escucharAvisos() {
        val app = application as WtfuckApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.repo.avisos.collect { ev ->
                    when (ev.tipo) {
                        "agregado_grupo" -> Notificaciones.agregadoAGrupo(
                            this@MainActivity, ev.actor, ev.nombreConversacion, ev.conversacionId,
                        )
                        // Una advertencia que el usuario no ve no sirve de
                        // nada: el punto de advertir es que haya oportunidad
                        // de corregir.
                        "advertencia" -> Notificaciones.moderacion(this@MainActivity, false)
                        "sancion" -> Notificaciones.moderacion(this@MainActivity, true)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                var ultima: String? = null
                app.repo.llamadas.estado.collect { e ->
                    val sonando = e != null &&
                        e.fase == com.wtfuck.app.datos.EstadoLlamada.Fase.SONANDO &&
                        !e.saliente
                    if (sonando) {
                        ultima = e!!.conversacionId
                    } else {
                        ultima?.let {
                            Notificaciones.quitarLlamada(this@MainActivity, it)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.repo.notificables.collect { n ->
                    when (n.tipo) {
                        "mensaje" -> Notificaciones.mensaje(
                            this@MainActivity, n.autor, n.titulo, n.conversacionId, n.esGrupo,
                        )
                        "canal" -> Notificaciones.canal(
                            this@MainActivity, n.titulo, n.conversacionId,
                        )
                        "llamada" -> Notificaciones.llamada(
                            this@MainActivity, n.autor, n.conVideo, n.conversacionId,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Android 13+ exige permiso para notificar. Se pide una vez, al entrar; si el
 * usuario dice que no, la app funciona igual y solo se pierde el aviso del
 * sistema: el mensaje de "te agregaron" igual queda dentro del chat.
 */
@Composable
private fun PedirPermisoNotificaciones() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        val ya = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
        if (ya != PackageManager.PERMISSION_GRANTED) {
            lanzador.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Raiz() {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val nav = rememberNavController()
    val inicio = if (app.sesion.hayS) "chats" else "auth"

    // Un cierre remoto tiene que sacar de la app, no solo fallar por dentro.
    //
    // Antes de esto, revocar la sesion desde otro aparato dejaba a este
    // navegando por sus propios chats: la lista seguia en la base local, se
    // podia abrir un chat y escribir, y el mensaje moria en la cola con
    // "Sesion invalida o expirada". La persona veia "sesion terminada" sin
    // saber por que ni como arreglarlo, porque la app no ofrecia ninguna
    // salida. El estado autoritativo de "estoy dentro" es el token, asi que
    // se observa el token y no la pantalla.
    val viva by app.sesion.viva.collectAsState()
    LaunchedEffect(viva) {
        if (!viva && nav.currentDestination?.route != "auth") {
            nav.navigate("auth") { popUpTo(0) { inclusive = true } }
        }
    }

    // La llamada se pinta ENCIMA de la navegacion, no dentro.
    //
    // Es una capa y no un destino porque una llamada entrante tiene que
    // aparecer sobre lo que sea que haya en pantalla -incluido un borrador a
    // medio escribir, que no debe perderse- y desaparecer sin dejar nada en el
    // historial de navegacion. Ver `CapaLlamada`.
    Box(Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = inicio) {

        composable("auth") {
            AuthPantalla(
                onListo = {
                    app.repo.iniciar()
                    // Modulo N: recien con sesion se puede pedir la config de
                    // push y registrar el token. Antes del login no hay con
                    // que autenticar, y el token quedaria sin cuenta a la que
                    // asociarlo.
                    app.ambito.launch { app.push.poner() }
                    nav.navigate("chats") { popUpTo("auth") { inclusive = true } }
                }
            )
        }

        // Las tres pestañas viven en un solo destino de navegacion, no en
        // tres. Cambiar de pestaña no es navegar: no apila historial, y atras
        // desde Canales devuelve a Chats en vez de sacarte de la app.
        composable("chats") {
            // N.3 · En pantalla ancha, lista y conversacion a la vez.
            //
            // La eleccion vive AQUI y no en el historial de navegacion: con dos
            // paneles, tocar un chat no hace desaparecer la lista, asi que el
            // "atras" del sistema no tendria nada que deshacer. Ver
            // `DosPaneles`.
            val ancho = anchoParaDosPaneles()
            var elegido by rememberSaveable { mutableStateOf<String?>(null) }

            // Al angostarse -girar la tablet, cerrar un plegable- la eleccion
            // se suelta: si no, la lista quedaria escondida detras de un chat
            // que nadie pidio abrir a pantalla completa.
            LaunchedEffect(ancho) { if (!ancho) elegido = null }

            // Va aqui y no en cada panel: con dos paneles se dibujan los dos,
            // y el aviso saldria duplicado.
            AvisoDeActualizacion()

            val lista = @Composable {
                Inicio(
                    onAbrirChat = { id -> if (ancho) elegido = id else nav.navigate("chat/$id") },
                    onAbrirCanal = { id -> nav.navigate("canal/$id") },
                    onPrivacidad = { nav.navigate("privacidad") },
                    onComunidades = { nav.navigate("comunidades") },
                    onAlmacenamiento = { nav.navigate("almacenamiento") },
                    onMiCuenta = { nav.navigate("mi-cuenta") },
                    onPanel = { nav.navigate("panel") },
                    onNovedades = { nav.navigate("novedades") },
                    onCopiaSeguridad = { nav.navigate("copia-seguridad") },
                    onSeguridad = { nav.navigate("seguridad") },
                    onAbrirEnMensaje = { id, m -> nav.navigate("chat/$id?m=$m") },
                    onLlamadas = { nav.navigate("llamadas") },
                    onNotificaciones = { nav.navigate("notificaciones") },
                onTipoCuenta = { nav.navigate("tipo-cuenta") },
                onDiagnostico = { nav.navigate("diagnostico") },
                    onCerrarSesion = {
                        nav.navigate("auth") { popUpTo(0) { inclusive = true } }
                    },
                )
            }

            if (!ancho) {
                lista()
            } else {
                DosPaneles(
                    lista = lista,
                    detalle = {
                        val id = elegido
                        if (id == null) {
                            PanelVacio()
                        } else {
                            ChatPantalla(
                                conversacionId = id,
                                onInfoGrupo = { nav.navigate("grupo/$id") },
                                onInfoPersona = { nav.navigate("persona/$id") },
                                onVerificarCifrado = { nav.navigate("huella/$id") },
                                onAbrirChatCon = { otro -> elegido = otro },
                                // Con dos paneles, "atras" es cerrar el panel
                                // derecho y no salir de ningun sitio.
                                onAtras = { elegido = null },
                            )
                        }
                    },
                )
            }
        }

        // Modulo P. La pantalla existe siempre; quien no esta en la beta ni
        // siquiera ve la fila que lleva aqui, y las rutas del servidor le
        // responden 404 de todas formas.
        composable("tipo-cuenta") {
            TipoCuentaPantalla(onAtras = { nav.popBackStack() })
        }

        composable("diagnostico") {
            DiagnosticoPantalla(onAtras = { nav.popBackStack() })
        }

        composable("almacenamiento") {
            AlmacenamientoPantalla(onAtras = { nav.popBackStack() })
        }

        composable("privacidad") {
            PrivacidadPantalla(
                onAtras = { nav.popBackStack() },
                onExcepciones = { nav.navigate("excepciones") },
            )
        }

        composable("excepciones") {
            ExcepcionesPantalla(onAtras = { nav.popBackStack() })
        }

        composable("comunidades") {
            ComunidadesPantalla(
                onAtras = { nav.popBackStack() },
                // Cada cosa a su pantalla: una comunidad no tiene
                // conversaciones propias, agrupa las que ya hay, pero el canal
                // de anuncios es un CANAL y la pantalla de chat no tiene muro.
                onAbrirCanal = { id -> nav.navigate("canal/$id") },
                onAbrirChat = { id -> nav.navigate("chat/$id") },
            )
        }

        composable("mi-cuenta") {
            ModeracionPantalla(onAtras = { nav.popBackStack() })
        }

        composable("dispositivos") {
            DispositivosPantalla(onAtras = { nav.popBackStack() })
        }

        composable("seguridad") {
            CuentaPantalla(
                onAtras = { nav.popBackStack() },
                onDispositivos = { nav.navigate("dispositivos") },
                // Pedir la eliminacion cierra las sesiones en el servidor, asi
                // que la app tiene que volver al ingreso: quedarse dentro con
                // un token muerto solo produciria errores sin explicacion.
                onCerrarSesion = {
                    nav.navigate("auth") { popUpTo(0) { inclusive = true } }
                },
            )
        }

        // El panel es una pantalla normal y no una pestaña: casi nadie es
        // staff, y una pestaña que la mayoria no puede usar solo estorba.
        composable("panel") {
            PanelPantalla(
                onAtras = { nav.popBackStack() },
                onLimites = { nav.navigate("limites") },
                onBitacora = { nav.navigate("bitacora") },
                onConversaciones = { nav.navigate("panel-conversaciones") },
                onConsolaWeb = { nav.navigate("consola-web") },
                onInvitaciones = { nav.navigate("invitaciones") },
            )
        }

        composable("invitaciones") {
            InvitacionesPantalla(onAtras = { nav.popBackStack() })
        }

        composable("novedades") {
            NovedadesPantalla(onAtras = { nav.popBackStack() })
        }

        composable("copia-seguridad") {
            CopiaSeguridadPantalla(onAtras = { nav.popBackStack() })
        }

        composable("limites") {
            LimitesPantalla(onAtras = { nav.popBackStack() })
        }

        composable("consola-web") {
            ConsolaWebPantalla(onAtras = { nav.popBackStack() })
        }

        composable("panel-conversaciones") {
            ConversacionesPanelPantalla(onAtras = { nav.popBackStack() })
        }

        composable("bitacora") {
            BitacoraPantalla(onAtras = { nav.popBackStack() })
        }

        composable("canal/{id}") { entry ->
            CanalPantalla(
                conversacionId = entry.arguments?.getString("id").orEmpty(),
                onAtras = { nav.popBackStack() },
            )
        }

        composable("huella/{id}") { entry ->
            HuellaPantalla(
                conversacionId = entry.arguments?.getString("id").orEmpty(),
                onAtras = { nav.popBackStack() },
            )
        }

        composable("grupo/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            GrupoPantalla(
                conversacionId = id,
                onAtras = { nav.popBackStack() },
                // Al salir del grupo no tiene sentido volver a su chat: se
                // vuelve a la lista.
                onSalio = { nav.navigate("chats") { popUpTo("chats") { inclusive = true } } },
                onVerificarCifrado = { nav.navigate("huella/$id") },
                // Con arroba: la ficha se abre sin crear la conversacion. Ver
                // `PersonaPantalla.clave`.
                onVerPersona = { u -> nav.navigate("persona/@$u") },
            )
        }

        composable("persona/{clave}") { entry ->
            val clave = entry.arguments?.getString("clave").orEmpty()
            PersonaPantalla(
                clave = clave,
                onAtras = { nav.popBackStack() },
                // Desde el chat, "Mensaje" es VOLVER y no apilar otra copia de
                // la misma conversacion. Desde un grupo o desde contactos no
                // hay chat detras, asi que hay que navegar. Se distingue por
                // como se llego: con `@username` no habia conversacion.
                onMensaje = { conv ->
                    if (clave.startsWith("@")) nav.navigate("chat/$conv")
                    else nav.popBackStack()
                },
                onVerificarCifrado = { conv -> nav.navigate("huella/$conv") },
                onCompartido = { conv, clase -> nav.navigate("compartido/$conv/$clase") },
                onDenunciar = { nav.popBackStack() },
            )
        }

        composable("compartido/{id}/{clase}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val clase = entry.arguments?.getString("clase").orEmpty()
            CompartidoPantalla(
                conversacionId = id,
                clase = clase,
                onAtras = { nav.popBackStack() },
                onVerEnElChat = { msg: String -> nav.navigate("chat/$id?m=$msg") },
            )
        }

        composable("notificaciones") {
            NotificacionesPantalla(onAtras = { nav.popBackStack() })
        }

        composable("llamadas") {
            HistorialLlamadasPantalla(
                onAtras = { nav.popBackStack() },
                // Desde una llamada del historial se abre el chat, no otra
                // llamada: devolverla es el boton de al lado, y confundir las
                // dos acciones hace sonar el telefono de alguien sin querer.
                onAbrirChat = { id -> nav.navigate("chat/$id") },
            )
        }

        // El argumento `m` va DECLARADO con su valor por defecto. Sin eso la
        // ruta `chat/$id` a secas —los seis sitios que ya existian— deja de
        // coincidir, y el argumento tampoco llega cuando si viene.
        composable(
            route = "chat/{id}?m={m}",
            arguments = listOf(navArgument("m") { defaultValue = "" }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ChatPantalla(
                conversacionId = id,
                // Opcional y con nombre: `chat/{id}` a secas sigue valiendo, y
                // los seis sitios que ya navegaban asi no se tocan.
                irAMensaje = entry.arguments?.getString("m").orEmpty(),
                onInfoGrupo = { nav.navigate("grupo/$id") },
                onInfoPersona = { nav.navigate("persona/$id") },
                onVerificarCifrado = { nav.navigate("huella/$id") },
                onAbrirChatCon = { otro -> nav.navigate("chat/$otro") },
                onAtras = { nav.popBackStack() },
            )
        }
    }

    // Solo con sesion viva: sin token no hay llamada que mostrar, y la capa
    // usa el Repositorio.
    if (viva) CapaLlamada()
    }
}
