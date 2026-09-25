package com.wtfuck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.EstadoLlamada
import com.wtfuck.app.datos.FabricaWebRtc
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * La pantalla de una llamada: sonando, conectando, en curso.
 *
 * ## Por que es una capa encima de todo y no un destino de navegacion
 *
 * Una llamada entrante tiene que aparecer sobre lo que sea que haya en
 * pantalla, y tiene que irse sin dejar rastro en el historial de navegacion.
 * Como destino, "atras" desde la llamada devolveria a la pantalla anterior -o
 * peor, sacaria de la app- y una llamada entrante mientras se escribe un
 * mensaje pisaria el borrador. Como capa, la llamada aparece y desaparece con
 * su estado y no toca la navegacion de abajo.
 *
 * ## Lo que esta pantalla NO arregla
 *
 * Si Android mata el proceso, la llamada se corta: no hay servicio en primer
 * plano. Esta declarado en [com.wtfuck.app.datos.ServicioLlamadas] y sigue
 * siendo trabajo aparte.
 */
@Composable
fun CapaLlamada() {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val servicio = app.repo.llamadas
    val estado by servicio.estado.collectAsState()
    val videoRemoto by servicio.videoRemoto.collectAsState()
    val videoLocal by servicio.videoLocal.collectAsState()
    val ambito = rememberCoroutineScope()

    val e = estado ?: return

    // Minimizada o completa.
    //
    // La clave es el `llamadaId` y no `Unit`: cada llamada empieza completa.
    // Con `Unit`, minimizar una llamada dejaba la SIGUIENTE entrando en
    // miniatura, y una llamada entrante que aparece como una ventanita de 128
    // dp en una esquina es una llamada que nadie ve.
    var minimizada by remember(e.llamadaId) { mutableStateOf(false) }

    // Mientras suena NO se puede minimizar, y al terminar se vuelve a abrir:
    // las dos son pantallas que existen para decir algo -"contesta" y "te
    // rechazaron"- y en una ventanita de una esquina no se dicen.
    LaunchedEffect(e.fase) {
        if (e.fase == EstadoLlamada.Fase.SONANDO || e.fase == EstadoLlamada.Fase.TERMINADA) {
            minimizada = false
        }
    }

    if (minimizada) {
        VentanaFlotante(
            e = e,
            videoRemoto = videoRemoto,
            onExpandir = { minimizada = false },
            onColgar = { ambito.launch { servicio.colgar() } },
        )
        return
    }

    Surface(color = BgBase, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {

            // El video remoto ocupa el fondo. Si no hay -llamada de audio, o
            // video que aun no llega- queda el fondo liso y el nombre grande,
            // que es lo que se quiere ver mientras suena.
            if (e.conVideo && videoRemoto != null) {
                VistaVideo(
                    track = videoRemoto,
                    espejo = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = 72.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Con video de fondo el texto necesita su propia base oscura o
                // se vuelve ilegible sobre una imagen clara.
                val conFondo = e.conVideo && videoRemoto != null
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (conFondo) {
                        Modifier
                            .background(BgBase.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    } else Modifier,
                ) {
                    if (!conFondo) {
                        Avatar(nombre = e.conQuien, url = null, tamano = 112.dp)
                        Spacer(Modifier.height(20.dp))
                    }
                    Text(
                        "@${e.conQuien}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextoPrimario,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextoDeFase(e)
                }

                Spacer(Modifier.weight(1f))

                when {
                    // Entrante y sin contestar: dos acciones, no una. Colgar y
                    // contestar tienen que estar lejos y de colores distintos,
                    // porque un toque equivocado aqui es irreversible.
                    e.fase == EstadoLlamada.Fase.SONANDO && !e.saliente ->
                        BotonesEntrante(
                            conVideo = e.conVideo,
                            onContestar = { ambito.launch { servicio.contestar() } },
                            onRechazar = { ambito.launch { servicio.rechazar() } },
                        )

                    e.fase == EstadoLlamada.Fase.TERMINADA -> Unit

                    else -> BotonesEnCurso(
                        e = e,
                        onSilenciar = servicio::silenciar,
                        onCamara = servicio::camara,
                        onAltavoz = servicio::altavoz,
                        onColgar = { ambito.launch { servicio.colgar() } },
                    )
                }
            }

            // Minimizar solo aparece cuando hay algo que minimizar: una
            // llamada en curso. Mientras suena, el boton seria una trampa.
            if (e.fase == EstadoLlamada.Fase.EN_CURSO ||
                e.fase == EstadoLlamada.Fase.CONECTANDO
            ) {
                IconButton(
                    onClick = { minimizada = true },
                    // `statusBarsPadding` no es cosmetico: la capa va de borde
                    // a borde, asi que sin esto el boton queda DEBAJO de la
                    // barra de estado, que se traga el toque. Se veia como un
                    // boton que esta ahi y no hace nada.
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.CloseFullscreen,
                        "Minimizar la llamada",
                        tint = TextoPrimario,
                    )
                }
            }

            // El video propio, chico y arriba. Va en espejo porque es lo que
            // la persona espera de su propia camara: un espejo, no una foto.
            if (e.conVideo && videoLocal != null && e.camaraActiva) {
                VistaVideo(
                    track = videoLocal,
                    espejo = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 16.dp)
                        .size(width = 108.dp, height = 168.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/** El texto que dice en que va la llamada. Un estado, una frase. */
@Composable
private fun TextoDeFase(e: EstadoLlamada) {
    val texto = when (e.fase) {
        EstadoLlamada.Fase.SONANDO ->
            if (e.saliente) "Llamando..." else if (e.conVideo) "Videollamada entrante" else "Llamada entrante"
        EstadoLlamada.Fase.CONECTANDO -> "Conectando..."
        EstadoLlamada.Fase.EN_CURSO -> Cronometro(e.conectadaEn)
        EstadoLlamada.Fase.TERMINADA -> when (e.motivoFin) {
            "rechazada" -> "Llamada rechazada"
            "sin_respuesta" -> "Sin respuesta"
            "ocupado" -> "Ocupado"
            "cancelada" -> "Llamada cancelada"
            "fallo_red" -> "Se corto la conexión"
            else -> "Llamada terminada"
        }
    }
    Text(
        texto,
        style = MaterialTheme.typography.bodyLarge,
        color = if (e.fase == EstadoLlamada.Fase.EN_CURSO) Cian else TextoSecundario,
    )
}

/**
 * El cronometro de la llamada.
 *
 * Cuenta desde `conectadaEn` y no desde que se monto la pantalla: si la
 * pantalla se recompone -girar el telefono, por ejemplo- el tiempo tiene que
 * seguir donde estaba, no volver a cero.
 */
@Composable
private fun Cronometro(desde: Long): String {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(desde) {
        while (true) {
            ahora = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (desde == 0L) return "00:00"
    val s = ((ahora - desde) / 1000).coerceAtLeast(0)
    return duracionHabla(s.toInt())
}

/** `m:ss` hasta la hora, `h:mm:ss` despues. */
fun duracionHabla(segundos: Int): String {
    val h = segundos / 3600
    val m = (segundos % 3600) / 60
    val s = segundos % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun BotonesEntrante(conVideo: Boolean, onContestar: () -> Unit, onRechazar: () -> Unit) {
    val ctx = LocalContext.current
    // Contestar necesita el microfono -y la camara si es video- ANTES de
    // contestar. Pedirlo despues dejaria una llamada conectada y muda, que es
    // peor que no haber contestado.
    val necesarios = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (conVideo) add(Manifest.permission.CAMERA)
    }
    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> if (res.values.all { it }) onContestar() else onRechazar() }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BotonGrande(Icons.Filled.CallEnd, "Rechazar", Coral, onRechazar)
        BotonGrande(Icons.Filled.Phone, "Contestar", Cian) {
            val faltan = necesarios.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltan) lanzador.launch(necesarios.toTypedArray()) else onContestar()
        }
    }
}

@Composable
private fun BotonesEnCurso(
    e: EstadoLlamada,
    onSilenciar: () -> Unit,
    onCamara: () -> Unit,
    onAltavoz: () -> Unit,
    onColgar: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BotonChico(
                if (e.silenciado) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (e.silenciado) "Activar microfono" else "Silenciar microfono",
                activo = e.silenciado,
                onClick = onSilenciar,
            )
            BotonChico(
                Icons.Filled.VolumeUp,
                if (e.altavoz) "Quitar altavoz" else "Poner altavoz",
                activo = e.altavoz,
                onClick = onAltavoz,
            )
            if (e.conVideo) {
                BotonChico(
                    if (e.camaraActiva) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    if (e.camaraActiva) "Apagar cámara" else "Encender cámara",
                    activo = !e.camaraActiva,
                    onClick = onCamara,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        BotonGrande(Icons.Filled.CallEnd, "Colgar", Coral, onColgar)
    }
}

@Composable
private fun BotonGrande(icono: ImageVector, desc: String, color: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = color, contentColor = TextoSobreAcento,
        ),
    ) { Icon(icono, desc, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun BotonChico(icono: ImageVector, desc: String, activo: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (activo) Cian else BgElev,
            contentColor = if (activo) TextoSobreAcento else TextoPrimario,
        ),
    ) { Icon(icono, desc, modifier = Modifier.size(24.dp)) }
}

/**
 * Un `SurfaceViewRenderer` de WebRTC dentro de Compose.
 *
 * Los dos detalles que importan:
 *
 *  - El sink se **quita** del track anterior antes de poner el nuevo. Sin eso,
 *    cambiar de track deja dos fuentes pintando en la misma superficie.
 *  - `release()` va en el `onRelease` de la vista, no en un `DisposableEffect`
 *    del composable: liberar un renderer que la vista todavia tiene montada
 *    deja la superficie negra hasta que se recrea.
 */
@Composable
private fun VistaVideo(track: VideoTrack?, espejo: Boolean, modifier: Modifier = Modifier) {
    val egl = FabricaWebRtc.egl ?: return
    var vista by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(espejo)
                vista = this
            }
        },
        onRelease = { v ->
            runCatching { v.release() }
            vista = null
        },
    )

    DisposableEffect(track, vista) {
        val v = vista
        if (v != null && track != null) runCatching { track.addSink(v) }
        onDispose { if (v != null && track != null) runCatching { track.removeSink(v) } }
    }
}

/**
 * La llamada en una ventanita movible, encima de la app.
 *
 * ## Por que existe
 *
 * Una videollamada a pantalla completa secuestra la app: mientras hablas no
 * podes mirar un mensaje, buscar un dato ni copiar una direccion. Es lo que
 * hace WhatsApp con su ventanita, y por eso mismo.
 *
 * ## Por que NO es un `Dialog` ni una ventana del sistema
 *
 * Un `Dialog` de Compose vive en su propia ventana y se traga los toques de
 * todo lo que tiene debajo: con el abierto, la app deja de responder, que es
 * exactamente lo contrario de lo que se busca. Una ventana flotante del
 * sistema -`TYPE_APPLICATION_OVERLAY`- funcionaria incluso fuera de la app,
 * pero exige el permiso `SYSTEM_ALERT_WINDOW`, que es de los que asustan y de
 * los que Google revisa. Asi que es una capa dentro de la propia jerarquia:
 * un `Box` que ocupa toda la pantalla pero **no dibuja nada ni escucha nada**
 * fuera de la ventanita. Compose solo entrega los toques a quien los pide, asi
 * que todo lo de abajo sigue funcionando.
 *
 * Que la llamada no sobreviva a salir de la app es una limitacion conocida y
 * declarada -no hay servicio en primer plano-, y no la arregla esta ventana.
 */
@Composable
private fun VentanaFlotante(
    e: EstadoLlamada,
    videoRemoto: VideoTrack?,
    onExpandir: () -> Unit,
    onColgar: () -> Unit,
) {
    val d = LocalDensity.current
    val conVideo = e.conVideo && videoRemoto != null
    val ancho = if (conVideo) 128.dp else 190.dp
    val alto = if (conVideo) 190.dp else 64.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margen = with(d) { 12.dp.toPx() }
        // Arriba hay barra de estado y abajo botonera del sistema: la ventana
        // arranca lejos de las dos.
        val techo = with(d) { 72.dp.toPx() }
        val piso = with(d) { 96.dp.toPx() }
        val w = with(d) { ancho.toPx() }
        val h = with(d) { alto.toPx() }
        val maxX = (constraints.maxWidth - w - margen).coerceAtLeast(margen)
        val maxY = (constraints.maxHeight - h - piso).coerceAtLeast(techo)

        // Arranca arriba a la derecha, que es donde no tapa el contenido de
        // una lista de chats ni el campo de escribir.
        var pos by remember { mutableStateOf<Offset?>(null) }
        val actual = pos ?: Offset(maxX, techo)

        Box(
            Modifier
                .offset { IntOffset(actual.x.roundToInt(), actual.y.roundToInt()) }
                .size(width = ancho, height = alto)
                .clip(RoundedCornerShape(14.dp))
                .background(BgElev)
                .border(1.dp, Cian.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                // El arrastre se registra ANTES del clic: si fuera al revés,
                // un arrastre que empieza sobre la ventana se leería como un
                // toque y la llamada se abriría a pantalla completa cada vez
                // que se la intenta mover.
                .pointerInput(maxX, maxY) {
                    detectDragGestures { _, delta ->
                        val base = pos ?: Offset(maxX, techo)
                        pos = Offset(
                            (base.x + delta.x).coerceIn(margen, maxX),
                            (base.y + delta.y).coerceIn(techo, maxY),
                        )
                    }
                }
                .clickable(onClick = onExpandir),
        ) {
            if (conVideo) {
                VistaVideo(track = videoRemoto, espejo = false, modifier = Modifier.fillMaxSize())
                // El nombre y el reloj sobre una base oscura: encima de un
                // video cualquiera, un texto suelto no se lee.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(BgBase.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.conQuien,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoPrimario,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        TextoCorto(e),
                        style = MaterialTheme.typography.labelSmall,
                        color = Cian,
                    )
                }
                IconButton(
                    onClick = onColgar,
                    modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        "Colgar",
                        tint = Coral,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // Sin video la ventanita es una barra: avatar, nombre, reloj y
                // colgar. Una miniatura cuadrada con un avatar gigante seria
                // ocupar la mitad de la pantalla para no decir nada mas.
                Row(
                    Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(nombre = e.conQuien, url = null, tamano = 34.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.conQuien,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextoPrimario,
                            maxLines = 1,
                        )
                        Text(
                            TextoCorto(e),
                            style = MaterialTheme.typography.labelSmall,
                            color = Cian,
                        )
                    }
                    IconButton(onClick = onColgar, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.CallEnd,
                            "Colgar",
                            tint = Coral,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Lo mismo que [TextoDeFase] pero para una ventana de 128 dp: el reloj. */
@Composable
private fun TextoCorto(e: EstadoLlamada): String = when (e.fase) {
    EstadoLlamada.Fase.EN_CURSO -> Cronometro(e.conectadaEn)
    EstadoLlamada.Fase.CONECTANDO -> "conectando"
    EstadoLlamada.Fase.SONANDO -> "llamando"
    EstadoLlamada.Fase.TERMINADA -> "fin"
}

/**
 * Empieza una llamada pidiendo antes los permisos.
 *
 * Devuelve la funcion que la interfaz llama al tocar el boton. Existe como
 * helper porque el mismo baile -mirar el permiso, pedirlo si falta, y solo
 * entonces llamar- hace falta desde el chat, desde el historial y desde el
 * perfil, y repetirlo tres veces es garantia de que en alguno se olvide.
 */
@Composable
fun recordarInicioLlamada(onError: (String) -> Unit): (String, String, Boolean) -> Unit {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var pendiente by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }

    fun arrancar(convId: String, conQuien: String, conVideo: Boolean) {
        ambito.launch {
            app.repo.llamadas.llamar(convId, conQuien, conVideo)
                .onFailure { onError(it.message ?: "No se pudo llamar.") }
        }
    }

    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val p = pendiente
        pendiente = null
        when {
            p == null -> Unit
            res.values.all { it } -> arrancar(p.first, p.second, p.third)
            // Sin micro no hay llamada que valga la pena: es mas honesto
            // decirlo que abrir una llamada donde el otro no te oye.
            else -> onError("Sin permiso de microfono no se puede llamar.")
        }
    }

    return { convId, conQuien, conVideo ->
        val necesarios = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (conVideo) add(Manifest.permission.CAMERA)
        }
        val faltan = necesarios.any {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltan) {
            pendiente = Triple(convId, conQuien, conVideo)
            lanzador.launch(necesarios.toTypedArray())
        } else {
            arrancar(convId, conQuien, conVideo)
        }
    }
}
