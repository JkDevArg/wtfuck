package com.wtfuck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wtfuck.app.ui.theme.*
import java.util.concurrent.Executors

/**
 * J.6 · Escanear el QR de vinculación.
 *
 * ## Por qué hay QR si el código de ocho caracteres ya funcionaba
 *
 * Porque teclear `K7M2-9QXF` mirando otra pantalla es donde la gente se
 * equivoca, y cada equivocación gasta uno de los cinco intentos del código.
 * El QR no cambia la seguridad —lleva exactamente el mismo código, con su
 * misma vida de cinco minutos y sus mismos cinco intentos—, cambia la tasa de
 * error.
 *
 * El código sigue **visible** al lado del QR, y eso no es redundancia: una
 * cámara tapada, un aparato sin cámara o una pantalla rota dejan el QR
 * inservible, y entonces se teclea.
 *
 * ## Por qué CameraX y el núcleo de zxing, y no una biblioteca de escaneo
 *
 * Las que hay traen su propia pantalla, su tema y su forma de pedir permisos;
 * en una app cuya promesa es que nada sale del aparato, una dependencia que
 * abre cámara por su cuenta es justo lo que no se quiere auditar. ML Kit
 * tampoco: exige Play Services y no funciona sin Google.
 *
 * ## Lo que este escáner NO hace
 *
 * No guarda ni una imagen. El análisis ocurre en memoria, cuadro por cuadro, y
 * lo único que sale de aquí es el texto del código. Tampoco pide permiso de
 * almacenamiento, porque no hay nada que almacenar.
 */
@Composable
fun EscanerQr(
    onCodigo: (String) -> Unit,
    onCerrar: () -> Unit,
    titulo: String = "Escanear el código",
    explicacion: String = "Apunta al QR que muestra el otro aparato.",
    ayuda: String? = "Si no funciona, el código también se puede escribir a mano.",
) {
    val ctx = LocalContext.current
    var permiso by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        permiso = concedido
        // Sin cámara no hay escáner, y quedarse mirando un rectángulo negro no
        // explica nada: se vuelve al código escrito, que siempre funciona.
        if (!concedido) onCerrar()
    }

    LaunchedEffect(Unit) {
        if (!permiso) lanzador.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.QrCodeScanner, null, tint = Cian, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(titulo, color = TextoPrimario)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    explicacion,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(12.dp))
                if (permiso) {
                    VistaCamara(
                        onCodigo = onCodigo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Hace falta permiso de cámara.",
                            color = TextoSecundario,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ayuda?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}

/**
 * La vista previa con el analizador enganchado.
 *
 * El `MultiFormatReader` se limita a QR: sin esa pista intenta todos los
 * formatos en cada cuadro -códigos de barras incluidos- y gasta CPU para
 * buscar cosas que aquí no van a aparecer nunca.
 */
@Composable
private fun VistaCamara(onCodigo: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val duenio = LocalLifecycleOwner.current
    // Un solo hilo para el análisis: dos cuadros analizándose a la vez no
    // aceleran nada -la cámara no da más- y sí duplican el pico de CPU.
    val ejecutor = remember { Executors.newSingleThreadExecutor() }

    // Guarda contra el disparo doble: un QR se lee en varios cuadros seguidos,
    // así que sin esto se consumiría el código dos veces y el segundo intento
    // fallaría diciendo "ya se uso", que es el peor mensaje posible tras un
    // escaneo correcto.
    var yaLeido by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { ejecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { contexto ->
            val vista = PreviewView(contexto).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val futuro = ProcessCameraProvider.getInstance(contexto)
            futuro.addListener({
                val proveedor = futuro.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = vista.surfaceProvider
                }
                val analisis = ImageAnalysis.Builder()
                    // Solo el cuadro más reciente: acumular una cola de
                    // imágenes para decodificar es latencia que se ve como un
                    // escáner que "va atrasado".
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val lector = MultiFormatReader().apply {
                    setHints(
                        mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                    )
                }

                analisis.setAnalyzer(ejecutor) { imagen ->
                    if (!yaLeido) {
                        leerQr(imagen, lector)?.let { texto ->
                            yaLeido = true
                            vista.post { onCodigo(texto) }
                        }
                    }
                    imagen.close()
                }

                runCatching {
                    proveedor.unbindAll()
                    proveedor.bindToLifecycle(
                        duenio, CameraSelector.DEFAULT_BACK_CAMERA, preview, analisis,
                    )
                }
            }, ContextCompat.getMainExecutor(contexto))
            vista
        },
    )
}

/**
 * Decodifica un cuadro. Devuelve null cuando no hay QR, que es lo normal.
 *
 * Se usa el plano Y (luminancia) directamente: convertir a bitmap para
 * decodificar sería asignar un bitmap por cuadro a 30 por segundo, y el
 * decodificador solo mira brillo.
 */
private fun leerQr(imagen: ImageProxy, lector: MultiFormatReader): String? {
    val plano = imagen.planes.firstOrNull() ?: return null
    val buffer = plano.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return decodificarLuminancia(
        bytes = bytes,
        rowStride = plano.rowStride,
        ancho = imagen.width,
        alto = imagen.height,
        lector = lector,
    )
}

/**
 * El decodificador, sin una sola clase de Android.
 *
 * Esta separado de [leerQr] para poder probarlo: con `ImageProxy` en la firma
 * haria falta un dispositivo para comprobar si el QR se lee, y entonces la
 * parte que de verdad puede fallar -el recorte, el `rowStride`, la
 * orientacion- solo se probaria a mano. Asi se prueba en la JVM generando un
 * QR y leyendolo.
 *
 * `rowStride` NO es el ancho: la camara alinea cada fila a un multiplo, asi
 * que una fila puede traer bytes de relleno al final. Pasar el ancho como
 * stride es el error clasico y produce una imagen inclinada que nunca
 * decodifica.
 */
internal fun decodificarLuminancia(
    bytes: ByteArray,
    rowStride: Int,
    ancho: Int,
    alto: Int,
    lector: MultiFormatReader,
): String? {
    val fuente = PlanarYUVLuminanceSource(
        bytes,
        rowStride,
        alto,
        0, 0,
        ancho.coerceAtMost(rowStride),
        alto,
        false,
    )
    return runCatching {
        lector.decodeWithState(BinaryBitmap(HybridBinarizer(fuente))).text
    }.also { lector.reset() }.getOrNull()
}
