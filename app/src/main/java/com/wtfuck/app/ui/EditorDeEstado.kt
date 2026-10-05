package com.wtfuck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.wtfuck.app.datos.AUDIO_ESTADO_MAX_MS
import com.wtfuck.app.datos.Capa
import com.wtfuck.app.datos.EdicionFoto
import com.wtfuck.app.datos.Encuadre
import com.wtfuck.app.datos.FiltroFoto
import com.wtfuck.app.datos.Lienzo
import com.wtfuck.app.datos.Proporcion
import com.wtfuck.app.datos.RenderEstado
import com.wtfuck.app.datos.Stickers
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Lo que el editor entrega para publicar. */
data class EstadoNuevo(
    val texto: String,
    val fondo: String,
    val medio: Uri?,
    /** La foto ya salio renderizada a 1080x1920: no se vuelve a reducir. */
    val renderizado: Boolean = false,
)

private enum class ModoEditor(val etiqueta: String, val icono: ImageVector) {
    TEXTO("Texto", Icons.Filled.TextFields),
    FOTO("Foto", Icons.Filled.Image),
    VIDEO("Video", Icons.Filled.Videocam),
    AUDIO("Audio", Icons.Filled.Mic),
}

/**
 * El editor de un estado nuevo, a pantalla completa.
 *
 * ## Lo que se pidio
 *
 * "Que se puedan agregar efectos, recortar, girar; siempre vertical y con
 * buena resolución; audio, video, foto, texto, stickers en las fotos; algo
 * como Instagram, WhatsApp, Telegram". Esto es eso, con un limite dicho:
 *
 *  - **Foto**: encuadre vertical con zoom y arrastre (el recorte), giro,
 *    ocho filtros, textos y stickers que se mueven, agrandan y giran con dos
 *    dedos. Sale a 1080x1920. Lo que se ve es exactamente lo que se publica:
 *    la vista previa y el archivo los pinta la misma funcion
 *    (`RenderEstado.dibujar`).
 *  - **Texto**: sobre un color, como hasta ahora.
 *  - **Video**: se publica tal cual, con pie. **Sin filtros ni recorte**:
 *    procesar video exige recodificarlo en el telefono, y eso no se promete
 *    hasta poder probarlo en aparatos de verdad.
 *  - **Audio**: se graba aqui mismo, hasta un minuto, sobre un color.
 *
 * El editor no se cierra al publicar: lo cierra quien lo abrio cuando sale
 * bien. Un fallo de red no puede costar la edicion entera.
 */
@Composable
fun EditorDeEstado(
    publicando: Boolean,
    error: String?,
    onPublicar: (EstadoNuevo) -> Unit,
    onCerrar: () -> Unit,
) {
    var modo by remember { mutableStateOf(ModoEditor.FOTO) }

    // Si ya hay algo hecho, salir pregunta. Atras por reflejo -o el gesto de
    // volver del sistema- se llevaba la foto encuadrada, los filtros y los
    // textos sin avisar: se vio probando el editor.
    var hayTrabajo by remember { mutableStateOf(false) }
    var confirmarSalir by remember { mutableStateOf(false) }
    val intentarCerrar: () -> Unit = { if (hayTrabajo && !publicando) confirmarSalir = true else if (!publicando) onCerrar() }
    val marcar: () -> Unit = { hayTrabajo = true }

    Dialog(
        onDismissRequest = intentarCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = intentarCerrar) { Icon(Icons.Filled.Close, "Cerrar", tint = Color.White) }
                    Text("Nuevo estado", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (modo) {
                        ModoEditor.TEXTO -> EditorTexto(publicando, error, onPublicar, marcar)
                        ModoEditor.FOTO -> EditorFoto(
                            publicando, error, marcar,
                            onListo = { uri, renderizado, pie -> onPublicar(EstadoNuevo(pie, "", uri, renderizado)) },
                        )
                        ModoEditor.VIDEO -> EditorVideo(publicando, error, onPublicar, marcar)
                        ModoEditor.AUDIO -> EditorAudio(publicando, error, onPublicar, marcar)
                    }
                }

                if (confirmarSalir) {
                    AlertDialog(
                        onDismissRequest = { confirmarSalir = false },
                        containerColor = BgElev,
                        title = { Text("¿Descartar el estado?", color = TextoPrimario) },
                        text = { Text("Se pierde lo que editaste.", color = TextoSecundario) },
                        confirmButton = {
                            TextButton(onClick = { confirmarSalir = false; onCerrar() }) {
                                Text("Descartar", color = Coral)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmarSalir = false }) { Text("Seguir editando", color = Cian) }
                        },
                    )
                }

                // El selector de modo, abajo: donde lo pone cualquier camara.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ModoEditor.entries.forEach { m ->
                        val activo = m == modo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !publicando) {
                                    // Cambiar de modo descarta lo del modo anterior.
                                    if (m != modo) hayTrabajo = false
                                    modo = m
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(m.icono, null, tint = if (activo) Cian else Color.White.copy(alpha = 0.6f))
                            Text(
                                m.etiqueta,
                                color = if (activo) Cian else Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                fontWeight = if (activo) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
//  Piezas comunes
// ============================================================

/**
 * El marco 9:16, lo mas grande que entre. Le pasa al contenido su tamano en
 * pixeles, que es el "lienzo" de la vista previa.
 */
@Composable
private fun MarcoVertical(
    modifier: Modifier = Modifier,
    fondo: Color = Color.Black,
    /** Ancho/alto del marco. 9:16 en un estado; la del recorte en el chat. */
    proporcion: Float = Lienzo.PROPORCION,
    contenido: @Composable BoxScope.(ancho: Float, alto: Float) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val porAlto = maxHeight * proporcion
        val ancho = if (porAlto <= maxWidth) porAlto else maxWidth
        val alto = ancho / proporcion
        Box(
            Modifier
                .size(ancho, alto)
                .clip(RoundedCornerShape(14.dp))
                .background(fondo),
        ) {
            val d = androidx.compose.ui.platform.LocalDensity.current
            contenido(with(d) { ancho.toPx() }, with(d) { alto.toPx() })
        }
    }
}

/** El pie, el error si lo hubo y el boton de publicar. */
@Composable
private fun PiePublicar(
    pie: String?,
    onPie: ((String) -> Unit)?,
    error: String?,
    publicando: Boolean,
    habilitado: Boolean,
    etiqueta: String = "Publicar",
    onPublicar: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        if (error != null) {
            Text(error, color = Coral, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pie != null && onPie != null) {
                OutlinedTextField(
                    value = pie,
                    onValueChange = { onPie(it.take(200)) },
                    placeholder = { Text("Añade un pie...", color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Cian,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(
                onClick = onPublicar,
                enabled = habilitado && !publicando,
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
            ) {
                if (publicando) {
                    CircularProgressIndicator(color = TextoSobreAcento, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(etiqueta)
                }
            }
        }
    }
}

/** Los colores de fondo, en fila. */
@Composable
private fun SelectorFondo(fondo: String, onFondo: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        FONDOS_HISTORIA.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colorDeFondo(c))
                    .border(if (c == fondo) 2.dp else 1.dp, if (c == fondo) Cian else Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable { onFondo(c) },
            )
        }
    }
}

/**
 * Un archivo propio, servido por el FileProvider de la app.
 *
 * Hace falta: el repositorio averigua el tipo del archivo con el
 * `ContentResolver`, que para un `file://` devuelve null, y entonces una foto
 * renderizada se trataba como "documento" y se rechazaba.
 */
private fun uriPropia(ctx: android.content.Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", f)

private fun archivoTemporal(ctx: android.content.Context, extension: String): File =
    File(File(ctx.cacheDir, "subiendo").apply { mkdirs() }, "estado-${UUID.randomUUID()}.$extension")

// ============================================================
//  Texto
// ============================================================

@Composable
private fun EditorTexto(publicando: Boolean, error: String?, onPublicar: (EstadoNuevo) -> Unit, onTrabajo: () -> Unit) {
    var texto by remember { mutableStateOf("") }
    var fondo by remember { mutableStateOf(FONDOS_HISTORIA.first()) }

    Column(Modifier.fillMaxSize()) {
        MarcoVertical(Modifier.weight(1f).padding(horizontal = 12.dp), fondo = colorDeFondo(fondo)) { _, _ ->
            BasicTextField(
                value = texto,
                onValueChange = { texto = it.take(TOPE_TEXTO_HISTORIA); if (texto.isNotBlank()) onTrabajo() },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = if (texto.length < 80) 28.sp else 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                decorationBox = { campo ->
                    Box(contentAlignment = Alignment.Center) {
                        if (texto.isEmpty()) {
                            Text(
                                "Escribe algo",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 28.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        campo()
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SelectorFondo(fondo) { fondo = it }
        }
        PiePublicar(
            pie = null, onPie = null, error = error, publicando = publicando,
            habilitado = texto.isNotBlank(),
        ) { onPublicar(EstadoNuevo(texto.trim(), fondo, null)) }
    }
}

// ============================================================
//  Foto
// ============================================================

private val EMOJIS = listOf(
    "😂", "😍", "🥹", "😎", "🤯", "🥳", "😭", "😡", "🙏", "👏", "🔥", "✨",
    "❤️", "💔", "💯", "🎉", "🌙", "☀️", "🌧️", "🌈", "🍕", "☕", "🎧", "📚",
    "⚽", "🏀", "🎮", "💻", "📸", "✈️", "🏖️", "🐶", "🐱", "🌸", "🍀", "🎂",
    "👀", "💪", "🤝", "👋", "🫶", "🤔", "😴", "🤫", "🙃", "💀", "👻", "🚀",
)

private val COLORES_TEXTO = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF26E0E0, 0xFFFFD54F, 0xFFFF7A59, 0xFF7CDB7C, 0xFFFF6FB5, 0xFFB388FF,
)

/** Mueve, escala o gira una capa sin perderla de vista. */
private fun Capa.transformada(dx: Float, dy: Float, zoom: Float, giroGrados: Float): Capa {
    val nx = (x + dx).coerceIn(0f, 1f)
    val ny = (y + dy).coerceIn(0f, 1f)
    val ne = (escala * zoom).coerceIn(0.3f, 6f)
    val ng = giro + giroGrados
    return when (this) {
        is Capa.Texto -> copy(x = nx, y = ny, escala = ne, giro = ng)
        is Capa.Sticker -> copy(x = nx, y = ny, escala = ne, giro = ng)
    }
}

@Composable
internal fun EditorFoto(
    publicando: Boolean,
    error: String?,
    onTrabajo: () -> Unit,
    /**
     * Cuando esta lista: la foto a mandar, si es la renderizada, y el pie.
     * Sin cambios y con `renderizarSiempre = false` llega la ORIGINAL: no se
     * recodifica una foto que nadie toco.
     */
    onListo: (Uri, Boolean, String) -> Unit,
    /** La foto con la que arranca. Null = se elige en la propia pantalla. */
    fotoInicial: Uri? = null,
    proporciones: List<Proporcion> = listOf(Proporcion.VERTICAL),
    etiqueta: String = "Publicar",
    pieInicial: String = "",
    /** Un estado siempre se renderiza: tiene que salir a 1080x1920 en vertical. */
    renderizarSiempre: Boolean = true,
    /** El lado mayor del archivo final. */
    ladoSalida: Int = Lienzo.ALTO,
) {
    val ctx = LocalContext.current
    val ambito = rememberCoroutineScope()
    var proporcion by remember { mutableStateOf(proporciones.first()) }
    var origen by remember { mutableStateOf(fotoInicial) }

    var foto by remember { mutableStateOf<Bitmap?>(null) }
    var miniatura by remember { mutableStateOf<Bitmap?>(null) }
    var edicion by remember { mutableStateOf(EdicionFoto()) }
    var stickers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var seleccion by remember { mutableStateOf<Long?>(null) }
    var pie by remember { mutableStateOf(pieInicial) }
    var cargando by remember { mutableStateOf(false) }
    var renderizando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var editandoTexto by remember { mutableStateOf<Capa.Texto?>(null) }
    var nuevoTexto by remember { mutableStateOf(false) }
    var eligiendoSticker by remember { mutableStateOf(false) }
    var verFiltros by remember { mutableStateOf(false) }

    fun abrir(uri: Uri) {
        origen = uri
        cargando = true
        ambito.launch {
            val b = withContext(Dispatchers.IO) { RenderEstado.cargar(ctx, uri) }
            if (b == null) {
                aviso = "No se pudo abrir esa foto."
            } else {
                foto = b
                onTrabajo()
                // Una miniatura para los botones de filtro: aplicar ocho
                // matrices a la foto entera en cada fotograma seria gastar.
                miniatura = withContext(Dispatchers.Default) {
                    val lado = 160f / maxOf(b.width, b.height)
                    Bitmap.createScaledBitmap(b, (b.width * lado).toInt().coerceAtLeast(1), (b.height * lado).toInt().coerceAtLeast(1), true)
                }
                edicion = EdicionFoto()
            }
            cargando = false
        }
    }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) abrir(uri)
    }
    LaunchedEffect(fotoInicial) { fotoInicial?.let { abrir(it) } }
    var destinoCamara by remember { mutableStateOf<Uri?>(null) }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = destinoCamara
        if (ok && u != null) abrir(u)
    }
    fun lanzarCamara() {
        val f = archivoTemporal(ctx, "jpg")
        val u = uriPropia(ctx, f)
        destinoCamara = u
        runCatching { camara.launch(u) }.onFailure { aviso = "No hay una app de cámara disponible." }
    }
    // La camara exige el permiso porque la app lo declara (lo usan las
    // videollamadas): sin pedirlo, lanzar la captura falla con un error.
    val permisoCamara = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) lanzarCamara() else aviso = "Sin permiso de cámara no se puede sacar la foto. Puedes elegir una de la galería."
    }

    val b = foto
    if (b == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (cargando) {
                CircularProgressIndicator(color = Cian)
            } else {
                Text(
                    "Una foto, en vertical y con buena resolución. Después puedes " +
                        "encuadrarla, girarla, ponerle filtros, textos y stickers.",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            lanzarCamara()
                        } else {
                            permisoCamara.launch(Manifest.permission.CAMERA)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Icon(Icons.Filled.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sacar una foto")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Icon(Icons.Filled.Image, null, tint = Cian)
                    Spacer(Modifier.width(8.dp))
                    Text("Elegir de la galería", color = Cian)
                }
            }
            aviso?.let { Text(it, color = Coral, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp)) }
        }
        return
    }

    val prop = proporcion.efectiva(b.width, b.height, edicion.giro)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MarcoVertical(Modifier.fillMaxSize().padding(horizontal = 12.dp), proporcion = prop) { ancho, alto ->
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(b) {
                            // Un solo detector para todo, y la decision al
                            // apoyar el dedo: si cae sobre una capa, se mueve
                            // la capa; si no, se mueve la foto (el recorte).
                            awaitEachGesture {
                                val abajo = awaitFirstDown()
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val objetivo = RenderEstado.capaEn(edicion, abajo.position.x, abajo.position.y, w, h, stickers)
                                seleccion = objetivo?.id
                                var movio = false
                                do {
                                    val ev = awaitPointerEvent()
                                    val pan = ev.calculatePan()
                                    val zoom = ev.calculateZoom()
                                    val giro = ev.calculateRotation()
                                    if (pan != Offset.Zero || zoom != 1f || giro != 0f) {
                                        movio = true
                                        if (objetivo != null) {
                                            edicion = edicion.copy(capas = edicion.capas.map {
                                                if (it.id == objetivo.id) it.transformada(pan.x / w, pan.y / h, zoom, giro) else it
                                            })
                                        } else {
                                            edicion = Encuadre.acotar(
                                                b.width, b.height,
                                                edicion.copy(
                                                    zoom = edicion.zoom * zoom,
                                                    desplX = edicion.desplX + pan.x / w,
                                                    desplY = edicion.desplY + pan.y / h,
                                                ),
                                                w, h,
                                            )
                                        }
                                        ev.changes.forEach { it.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                                // Un toque sin mover sobre un texto lo edita.
                                if (!movio && objetivo is Capa.Texto) {
                                    editandoTexto = edicion.capas.firstOrNull { it.id == objetivo.id } as? Capa.Texto
                                }
                            }
                        },
                ) {
                    drawIntoCanvas { c ->
                        RenderEstado.dibujar(c.nativeCanvas, b, edicion, ancho, alto, stickers)
                    }
                }
            }

            // Las herramientas, en columna a la derecha: donde las pone cualquier
            // editor de estados, al alcance del pulgar y sin tapar el centro.
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Herramienta(Icons.Filled.Rotate90DegreesCw, "Girar") {
                    val girada = edicion.copy(giro = edicion.giro + 90, desplX = 0f, desplY = 0f)
                    val p = proporcion.efectiva(b.width, b.height, girada.giro)
                    edicion = Encuadre.acotar(b.width, b.height, girada, 1000f, 1000f / p)
                }
                Herramienta(Icons.Filled.TextFields, "Texto") { nuevoTexto = true }
                Herramienta(Icons.Filled.EmojiEmotions, "Sticker") { eligiendoSticker = true }
                Herramienta(Icons.Filled.Image, "Cambiar foto") {
                    galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                val sel = seleccion
                if (sel != null && edicion.capas.any { it.id == sel }) {
                    Herramienta(Icons.Filled.Delete, "Quitar", color = Coral) {
                        edicion = edicion.copy(capas = edicion.capas.filterNot { it.id == sel })
                        seleccion = null
                    }
                }
            }
        }

        // La forma del recorte, si hay para elegir. Un estado no la ofrece:
        // es siempre 9:16.
        if (proporciones.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                items(proporciones) { pr ->
                    val activa = pr == proporcion
                    Text(
                        pr.etiqueta,
                        color = if (activa) TextoSobreAcento else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (activa) Cian else Color.White.copy(alpha = 0.12f))
                            .clickable {
                                proporcion = pr
                                // Al cambiar la forma se vuelve a encuadrar desde el centro.
                                val p = pr.efectiva(b.width, b.height, edicion.giro)
                                edicion = Encuadre.acotar(
                                    b.width, b.height, edicion.copy(zoom = 1f, desplX = 0f, desplY = 0f), 1000f, 1000f / p,
                                )
                                onTrabajo()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Los filtros, con la propia foto como muestra.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { verFiltros = !verFiltros }) {
                Text(if (verFiltros) "Ocultar filtros" else "Filtros · ${edicion.filtro.etiqueta}", color = Cian)
            }
            Spacer(Modifier.weight(1f))
            Text("Pellizca para acercar · arrastra para encuadrar", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        if (verFiltros) {
            val mini = miniatura
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(FiltroFoto.entries) { f ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { edicion = edicion.copy(filtro = f) },
                    ) {
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    if (f == edicion.filtro) 2.dp else 0.dp,
                                    if (f == edicion.filtro) Cian else Color.Transparent,
                                    RoundedCornerShape(10.dp),
                                ),
                        ) {
                            if (mini != null) {
                                Image(
                                    mini.asImageBitmap(), f.etiqueta,
                                    contentScale = ContentScale.Crop,
                                    colorFilter = if (f == FiltroFoto.ORIGINAL) null else ColorFilter.colorMatrix(ColorMatrix(f.matriz)),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Text(f.etiqueta, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }
        }

        PiePublicar(
            pie = pie, onPie = { pie = it }, error = error ?: aviso,
            publicando = publicando || renderizando, habilitado = true, etiqueta = etiqueta,
        ) {
            val original = origen
            // Sin tocar nada, la original tal cual: recodificar una foto que
            // nadie edito solo le quitaria calidad.
            if (!renderizarSiempre && original != null &&
                edicion == EdicionFoto() && proporcion == Proporcion.ORIGINAL
            ) {
                onListo(original, false, pie.trim())
                return@PiePublicar
            }
            renderizando = true
            ambito.launch {
                val destino = archivoTemporal(ctx, "jpg")
                // Sin agrandar: el lado mayor no pasa de los pixeles REALES
                // que caben en el recorte. Ver `Proporcion.ladoSinAgrandar`.
                val lado = minOf(
                    ladoSalida,
                    Proporcion.ladoSinAgrandar(prop, b.width, b.height, edicion.giro, edicion.zoom),
                ).coerceAtLeast(480)
                val (w, h) = if (renderizarSiempre) Proporcion.salida(prop, ladoSalida) else Proporcion.salida(prop, lado)
                val ok = withContext(Dispatchers.Default) { RenderEstado.renderizar(b, edicion, stickers, destino, w, h) }
                renderizando = false
                if (ok) onListo(uriPropia(ctx, destino), true, pie.trim())
                else aviso = "No se pudo preparar la foto."
            }
        }
    }

    if (nuevoTexto || editandoTexto != null) {
        DialogoCapaTexto(
            inicial = editandoTexto,
            onCerrar = { nuevoTexto = false; editandoTexto = null },
            onListo = { texto, color, conFondo ->
                val previo = editandoTexto
                edicion = if (previo == null) {
                    val id = System.nanoTime()
                    seleccion = id
                    edicion.copy(capas = edicion.capas + Capa.Texto(id = id, texto = texto, color = color, conFondo = conFondo))
                } else {
                    edicion.copy(capas = edicion.capas.map {
                        if (it.id == previo.id) previo.copy(texto = texto, color = color, conFondo = conFondo) else it
                    })
                }
                nuevoTexto = false
                editandoTexto = null
            },
            onQuitar = editandoTexto?.let { t ->
                {
                    edicion = edicion.copy(capas = edicion.capas.filterNot { it.id == t.id })
                    editandoTexto = null
                    seleccion = null
                }
            },
        )
    }

    if (eligiendoSticker) {
        HojaStickersDeEstado(
            onCerrar = { eligiendoSticker = false },
            onEmoji = { e ->
                val id = System.nanoTime()
                edicion = edicion.copy(capas = edicion.capas + Capa.Sticker(id = id, emoji = e))
                seleccion = id
                eligiendoSticker = false
            },
            onPropio = { archivo ->
                ambito.launch {
                    val bmp = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(archivo.path) }.getOrNull() }
                    if (bmp != null) {
                        stickers = stickers + (archivo.path to bmp)
                        val id = System.nanoTime()
                        edicion = edicion.copy(capas = edicion.capas + Capa.Sticker(id = id, ruta = archivo.path))
                        seleccion = id
                    }
                    eligiendoSticker = false
                }
            },
        )
    }
}

@Composable
private fun Herramienta(icono: ImageVector, desc: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icono, desc, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DialogoCapaTexto(
    inicial: Capa.Texto?,
    onCerrar: () -> Unit,
    onListo: (String, Long, Boolean) -> Unit,
    onQuitar: (() -> Unit)?,
) {
    var texto by remember { mutableStateOf(inicial?.texto.orEmpty()) }
    var color by remember { mutableStateOf(inicial?.color ?: COLORES_TEXTO.first()) }
    var conFondo by remember { mutableStateOf(inicial?.conFondo ?: true) }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(if (inicial == null) "Agregar texto" else "Editar texto", color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.take(160) },
                    placeholder = { Text("Escribe algo", color = TextoTerciario) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        focusedTextColor = TextoPrimario,
                        unfocusedTextColor = TextoPrimario,
                        cursorColor = Cian,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLORES_TEXTO.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == color) 2.dp else 1.dp, if (c == color) Cian else Slate, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { conFondo = !conFondo }) {
                    Checkbox(checked = conFondo, onCheckedChange = { conFondo = it })
                    Text("Con caja oscura detrás (se lee sobre cualquier foto)", color = TextoSecundario, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onListo(texto.trim(), color, conFondo) }, enabled = texto.isNotBlank()) {
                Text("Listo", color = Cian)
            }
        },
        dismissButton = {
            Row {
                if (onQuitar != null) TextButton(onClick = onQuitar) { Text("Quitar", color = Coral) }
                TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaStickersDeEstado(onCerrar: () -> Unit, onEmoji: (String) -> Unit, onPropio: (File) -> Unit) {
    val ctx = LocalContext.current
    val mios = remember { runCatching { Stickers.mios(ctx) }.getOrDefault(emptyList()) }
    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 20.dp)) {
            if (mios.isNotEmpty()) {
                Text("Mis stickers", color = TextoSecundario, fontSize = 13.sp)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    items(mios) { f ->
                        AsyncImage(
                            model = f, contentDescription = "Sticker",
                            modifier = Modifier.size(64.dp).clickable { onPropio(f) },
                        )
                    }
                }
            }
            Text("Emojis", color = TextoSecundario, fontSize = 13.sp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(52.dp),
                modifier = Modifier.heightIn(max = 340.dp).padding(top = 6.dp),
            ) {
                items(EMOJIS) { e ->
                    Box(
                        Modifier.size(52.dp).clickable { onEmoji(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 30.sp) }
                }
            }
        }
    }
}

// ============================================================
//  Video
// ============================================================

@Composable
private fun EditorVideo(publicando: Boolean, error: String?, onPublicar: (EstadoNuevo) -> Unit, onTrabajo: () -> Unit) {
    var video by remember { mutableStateOf<Uri?>(null) }
    var pie by remember { mutableStateOf("") }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { video = uri; onTrabajo() }
    }

    val v = video
    if (v == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Un video de tu galería, con pie. Los filtros y el recorte son solo para fotos.",
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
            ) {
                Icon(Icons.Filled.Videocam, null)
                Spacer(Modifier.width(8.dp))
                Text("Elegir un video")
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        MarcoVertical(Modifier.weight(1f).padding(horizontal = 12.dp)) { _, _ ->
            AndroidView(
                modifier = Modifier.align(Alignment.Center),
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(v)
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                        setOnErrorListener { _, _, _ -> true }
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Text("Cambiar video", color = Cian)
            }
        }
        PiePublicar(pie = pie, onPie = { pie = it }, error = error, publicando = publicando, habilitado = true) {
            onPublicar(EstadoNuevo(pie.trim(), "", v))
        }
    }
}

// ============================================================
//  Audio
// ============================================================

@Composable
private fun EditorAudio(publicando: Boolean, error: String?, onPublicar: (EstadoNuevo) -> Unit, onTrabajo: () -> Unit) {
    val ctx = LocalContext.current
    val ambito = rememberCoroutineScope()
    val grabadora = remember { Grabadora(ctx) }
    var grabando by remember { mutableStateOf(false) }
    var grabado by remember { mutableStateOf<File?>(null) }
    var ms by remember { mutableLongStateOf(0L) }
    var nivel by remember { mutableFloatStateOf(0f) }
    var fondo by remember { mutableStateOf(FONDOS_HISTORIA[1]) }
    var pie by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf<String?>(null) }
    var reproduciendo by remember { mutableStateOf(false) }
    var reproductor by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { if (grabadora.grabando) grabadora.cancelar() }
            runCatching { reproductor?.release() }
        }
    }

    fun detener() {
        grabando = false
        grabado = grabadora.terminar()
        if (grabado == null) aviso = "La grabación salió vacía. Prueba otra vez." else onTrabajo()
    }

    fun empezar() {
        runCatching { reproductor?.release() }
        reproductor = null
        reproduciendo = false
        grabado = null
        aviso = null
        if (!grabadora.iniciar()) {
            aviso = "No se pudo usar el micrófono."
            return
        }
        grabando = true
        ambito.launch {
            // El tope del minuto lo pone esta pantalla: la grabadora es la de
            // las notas de voz, que dejan grabar mucho mas.
            while (grabando) {
                ms = grabadora.milisegundos()
                nivel = grabadora.nivel()
                if (ms >= AUDIO_ESTADO_MAX_MS) { detener(); break }
                delay(80)
            }
        }
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) empezar() else aviso = "Sin permiso de micrófono no se puede grabar."
    }

    Column(Modifier.fillMaxSize()) {
        MarcoVertical(Modifier.weight(1f).padding(horizontal = 12.dp), fondo = colorDeFondo(fondo)) { _, _ ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tamano = 120.dp + (40.dp * (if (grabando) nivel else 0f))
                Box(
                    Modifier
                        .size(tamano)
                        .clip(CircleShape)
                        .background(if (grabando) Coral.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = !publicando) {
                            when {
                                grabando -> detener()
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED -> empezar()
                                else -> permiso.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (grabando) Icons.Filled.Stop else Icons.Filled.Mic,
                        if (grabando) "Detener" else "Grabar",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                val seg = ms / 1000
                Text(
                    when {
                        grabando -> "Grabando · %d:%02d / 1:00".format(seg / 60, seg % 60)
                        grabado != null -> "Listo · %d:%02d".format(seg / 60, seg % 60)
                        else -> "Toca para grabar · hasta 1 minuto"
                    },
                    color = Color.White,
                    fontSize = 15.sp,
                )
                val f = grabado
                if (f != null && !grabando) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = {
                            val mp = reproductor ?: runCatching {
                                android.media.MediaPlayer().apply {
                                    setDataSource(f.path)
                                    setOnCompletionListener { reproduciendo = false }
                                    prepare()
                                }
                            }.getOrNull().also { reproductor = it }
                            if (mp != null) {
                                if (reproduciendo) { mp.pause(); reproduciendo = false } else { mp.start(); reproduciendo = true }
                            }
                        }) {
                            Icon(if (reproduciendo) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(if (reproduciendo) "Pausar" else "Escuchar", color = Color.White)
                        }
                        TextButton(onClick = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) empezar() else permiso.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text("Grabar otra vez", color = Color.White) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SelectorFondo(fondo) { fondo = it }
        }
        PiePublicar(
            pie = pie, onPie = { pie = it }, error = error ?: aviso, publicando = publicando,
            habilitado = grabado != null && !grabando,
        ) {
            val f = grabado ?: return@PiePublicar
            ambito.launch {
                // A la carpeta que sirve el FileProvider: ver `uriPropia`.
                val destino = archivoTemporal(ctx, "m4a")
                val ok = withContext(Dispatchers.IO) { runCatching { f.copyTo(destino, overwrite = true); true }.getOrDefault(false) }
                if (ok) onPublicar(EstadoNuevo(pie.trim(), fondo, uriPropia(ctx, destino)))
                else aviso = "No se pudo preparar el audio."
            }
        }
    }
}


/**
 * El editor de fotos del chat: el mismo de los estados, con otra salida.
 *
 * Se abre al elegir una foto para mandar. Recorta con las formas de siempre
 * -la de la foto, 1:1, 4:5, 9:16, 16:9-, gira, filtra y acepta textos y
 * stickers. Lo que sale pasa despues por el mismo camino que cualquier foto
 * del chat, con el ajuste de calidad de siempre.
 *
 * Si no se toca nada, manda la foto ORIGINAL: abrir el editor no puede costar
 * calidad a quien solo queria mandar la foto.
 */
@Composable
fun EditorFotoChat(
    foto: Uri,
    pieInicial: String,
    onEnviar: (Uri, String) -> Unit,
    onCerrar: () -> Unit,
) {
    var hayTrabajo by remember { mutableStateOf(false) }
    var confirmarSalir by remember { mutableStateOf(false) }
    val intentarCerrar: () -> Unit = { if (hayTrabajo) confirmarSalir = true else onCerrar() }
    Dialog(
        onDismissRequest = intentarCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = intentarCerrar) { Icon(Icons.Filled.Close, "Cerrar", tint = Color.White) }
                    Text("Editar foto", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EditorFoto(
                        publicando = false,
                        error = null,
                        onTrabajo = { hayTrabajo = true },
                        onListo = { uri, _, pie -> onEnviar(uri, pie) },
                        fotoInicial = foto,
                        proporciones = Proporcion.entries,
                        etiqueta = "Enviar",
                        pieInicial = pieInicial,
                        renderizarSiempre = false,
                        ladoSalida = 2560,
                    )
                }
                if (confirmarSalir) {
                    AlertDialog(
                        onDismissRequest = { confirmarSalir = false },
                        containerColor = BgElev,
                        title = { Text("¿Descartar los cambios?", color = TextoPrimario) },
                        text = { Text("La foto no se envía.", color = TextoSecundario) },
                        confirmButton = {
                            TextButton(onClick = { confirmarSalir = false; onCerrar() }) { Text("Descartar", color = Coral) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmarSalir = false }) { Text("Seguir editando", color = Cian) }
                        },
                    )
                }
            }
        }
    }
}
