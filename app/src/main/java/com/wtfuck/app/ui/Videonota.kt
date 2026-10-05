package com.wtfuck.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/** La forma de una videonota en `CargaAdjunto.forma`. */
const val FORMA_CIRCULO = "circulo"

/** Lo que dura como mucho, como en Telegram: es un mensaje, no una pelicula. */
private const val MAX_SEGUNDOS = 60

/**
 * Grabar una videonota: la camara frontal en un circulo, tocar para empezar y
 * para terminar.
 *
 * En calidad SD a proposito: se ve en un circulo de unos 220 dp, y un minuto
 * en alta definicion serian decenas de megas cifrados y subidos para nada.
 */
@SuppressLint("MissingPermission")
@Composable
fun GrabadorVideonota(onListo: (File) -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val duenio = LocalLifecycleOwner.current
    fun tienePermisos() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    var permisos by remember { mutableStateOf(tienePermisos()) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permisos = tienePermisos()
        if (!permisos) {
            Toast.makeText(ctx, "Hace falta la cámara y el micrófono para grabar.", Toast.LENGTH_LONG).show()
            onCerrar()
        }
    }
    LaunchedEffect(Unit) {
        if (!permisos) pedir.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    var captura by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var grabacion by remember { mutableStateOf<Recording?>(null) }
    var segundos by remember { mutableIntStateOf(0) }
    var hecha by remember { mutableStateOf<File?>(null) }

    fun parar() { grabacion?.stop(); grabacion = null }

    // El reloj, y el corte al llegar al tope.
    LaunchedEffect(grabacion) {
        // Solo al EMPEZAR: al parar, el efecto vuelve a correr con la
        // grabacion en null, y ponerlo en cero ahi decia "Lista: 0:00".
        if (grabacion == null) return@LaunchedEffect
        segundos = 0
        while (grabacion != null) {
            delay(1000)
            segundos++
            if (segundos >= MAX_SEGUNDOS) parar()
        }
    }
    // Si se cierra a medio grabar, la grabacion se corta y el archivo se tira.
    DisposableEffect(Unit) { onDispose { grabacion?.stop() } }

    fun empezar() {
        val vc = captura ?: return
        hecha?.delete()
        hecha = null
        val destino = File(File(ctx.cacheDir, "subiendo").apply { mkdirs() }, "videonota-${UUID.randomUUID()}.mp4")
        grabacion = vc.output
            .prepareRecording(ctx, FileOutputOptions.Builder(destino).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                        destino.delete()
                        Toast.makeText(ctx, "No se pudo grabar.", Toast.LENGTH_SHORT).show()
                    } else {
                        hecha = destino
                    }
                }
            }
    }

    Dialog(
        onDismissRequest = { parar(); hecha?.delete(); onCerrar() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            IconButton(onClick = { parar(); hecha?.delete(); onCerrar() }, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Filled.Close, "Cerrar", tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(280.dp)
                        .clip(CircleShape)
                        .border(3.dp, if (grabacion != null) Coral else Color.White.copy(alpha = 0.3f), CircleShape),
                ) {
                    if (permisos) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { c ->
                                // COMPATIBLE (un TextureView) y no el modo por
                                // defecto: un SurfaceView no respeta el recorte
                                // en circulo y se veria cuadrado.
                                val vista = PreviewView(c).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                                val futuro = ProcessCameraProvider.getInstance(c)
                                futuro.addListener({
                                    val proveedor = futuro.get()
                                    val preview = Preview.Builder().build().also { it.surfaceProvider = vista.surfaceProvider }
                                    val recorder = Recorder.Builder()
                                        .setQualitySelector(
                                            QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                                        )
                                        .build()
                                    // Espejada como la vista previa: quien graba
                                    // se ve como en un espejo, y lo que recibe
                                    // el otro tiene que coincidir con eso.
                                    val vc = VideoCapture.Builder(recorder)
                                        .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                                        .build()
                                    // La frontal si hay, y si no la de atras: hay
                                    // tablets y telefonos sin frontal -y el
                                    // emulador de pruebas era uno-, y ahi la
                                    // pantalla se quedaba negra.
                                    val camara = if (runCatching { proveedor.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else CameraSelector.DEFAULT_BACK_CAMERA
                                    runCatching {
                                        proveedor.unbindAll()
                                        proveedor.bindToLifecycle(duenio, camara, preview, vc)
                                        captura = vc
                                    }.onFailure {
                                        Toast.makeText(c, "No se pudo abrir la cámara.", Toast.LENGTH_SHORT).show()
                                    }
                                }, ContextCompat.getMainExecutor(c))
                                vista
                            },
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    when {
                        grabacion != null -> "%d:%02d / 1:00".format(segundos / 60, segundos % 60)
                        hecha != null -> "Lista: %d:%02d".format(segundos / 60, segundos % 60)
                        else -> "Toca para grabar"
                    },
                    color = Color.White, fontSize = 15.sp,
                )
                Spacer(Modifier.height(18.dp))
                if (hecha == null) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .clickable(enabled = captura != null) { if (grabacion == null) empezar() else parar() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (grabacion != null) 28.dp else 54.dp)
                                .clip(if (grabacion != null) MaterialTheme.shapes.small else CircleShape)
                                .background(Coral),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { hecha?.delete(); hecha = null }) { Text("Repetir", color = Color.White) }
                        Button(
                            onClick = { hecha?.let { onListo(it) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                        ) { Text("Enviar") }
                    }
                }
            }
        }
    }
}

/**
 * Una videonota en el chat: un circulo, sin burbuja. Se reproduce ahi mismo,
 * con sonido, al tocarla; antes se ve su miniatura.
 *
 * Con un TextureView y no un VideoView: el VideoView es un SurfaceView y no se
 * deja recortar en circulo.
 */
@Composable
fun VistaVideonota(m: MensajeEnt, local: File?, onDescargar: () -> Unit) {
    var reproduciendo by remember(m.id) { mutableStateOf(false) }
    val mini = remember(m.adjuntoMiniatura) {
        Media.deBase64(m.adjuntoMiniatura)?.let { b -> Media.miniaturaAjena(b)?.asImageBitmap() }
    }
    Box(
        Modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(BgBase)
            .clickable { if (local == null) onDescargar() else reproduciendo = !reproduciendo },
        contentAlignment = Alignment.Center,
    ) {
        if (reproduciendo && local != null) {
            ReproductorCircular(local, onFin = { reproduciendo = false })
        } else {
            if (mini != null) {
                Image(mini, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (local == null && m.adjuntoEstado == "DESCARGANDO") {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Filled.PlayArrow, if (local == null) "Descargar videonota" else "Reproducir", tint = Color.White)
                }
            }
            if (m.adjuntoDuracionMs > 0) {
                val s = m.adjuntoDuracionMs / 1000
                Text(
                    "%d:%02d".format(s / 60, s % 60),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ReproductorCircular(archivo: File, onFin: () -> Unit) {
    val jugador = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { jugador.release() } } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            jugador.setSurface(Surface(st))
                            jugador.setDataSource(archivo.absolutePath)
                            jugador.setOnVideoSizeChangedListener { _, vw, vh ->
                                // Recorte al centro, como `ContentScale.Crop`:
                                // estirado al cuadrado se veria deformado.
                                if (vw > 0 && vh > 0) {
                                    val escala = maxOf(w.toFloat() / vw, h.toFloat() / vh)
                                    setTransform(Matrix().apply {
                                        setScale(vw * escala / w, vh * escala / h, w / 2f, h / 2f)
                                    })
                                }
                            }
                            jugador.setOnCompletionListener { onFin() }
                            jugador.setOnPreparedListener { it.start() }
                            jugador.prepareAsync()
                        }.onFailure { onFin() }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}
