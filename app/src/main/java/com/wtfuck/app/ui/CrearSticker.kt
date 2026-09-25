package com.wtfuck.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.datos.Recorte
import com.wtfuck.app.datos.Stickers
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Módulo Y · Recortar una foto para convertirla en sticker.
 *
 * ## Por qué hay un editor y no un recorte automático
 *
 * Porque el cuadrado centrado acierta con una foto de producto y falla con
 * cualquier foto de gente: la cara suele estar arriba, y un recorte centrado
 * de una vertical se lleva el torso. Un sticker en el que no se ve lo que uno
 * quería no se manda.
 *
 * El gesto es arrastrar y pellizcar, sobre una ventana cuadrada fija. Es el
 * modelo que ya conoce cualquiera que haya recortado una foto de perfil: se
 * mueve la imagen, no el marco.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaCrearSticker(
    uri: Uri,
    /** El pack al que va. Vacio = suelto. Es la pestaña abierta al crearlo. */
    packDestino: String = "",
    onListo: () -> Unit,
    onCerrar: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as com.wtfuck.app.WtfuckApp
    val ambito = rememberCoroutineScope()

    var origen by remember { mutableStateOf<Bitmap?>(null) }
    var fallo by remember { mutableStateOf(false) }
    var guardando by remember { mutableStateOf(false) }

    /**
     * Si el archivo elegido tiene movimiento.
     *
     * Se mira **antes** de decodificar nada: un animado no se recorta -Android
     * no trae codificador de WebP animado ni de GIF, asi que recortarlo
     * significaria aplanarlo a un fotograma- y por tanto tampoco hace falta
     * cargarlo para editar. Ver `Stickers.copiarAnimado`.
     */
    val animado = remember(uri) { Stickers.esAnimado(ctx, uri) }

    // Cuánto se ha acercado y movido la imagen dentro de la ventana.
    var zoom by remember { mutableFloatStateOf(1f) }
    var desX by remember { mutableFloatStateOf(0f) }
    var desY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri, animado) {
        if (animado) return@LaunchedEffect
        origen = withContext(Dispatchers.IO) { Stickers.cargarParaEditar(ctx, uri) }
        fallo = origen == null
    }

    ModalBottomSheet(
        // Entera, como las otras cinco hojas de formulario: el botón de
        // confirmar va al final y a media altura queda fuera. Ver el módulo W.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nuevo sticker",
                color = TextoPrimario,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Start),
            )
            Text(
                if (animado) {
                    "Tiene movimiento, así que se agrega tal cual: recortarlo " +
                        "obligaría a dejarlo en un solo fotograma."
                } else {
                    "Arrastra y pellizca para elegir el cuadrado. Si la foto tiene " +
                        "fondo transparente, se conserva."
                },
                color = TextoTerciario,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(14.dp))

            val bmp = origen
            when {
                // Un animado no pasa por el editor: se previsualiza tal cual
                // -con Coil, que es lo que sabe dibujar los fotogramas- y se
                // copia. Ver `Stickers.copiarAnimado`.
                animado && !fallo -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BgElev),
                        contentAlignment = Alignment.Center,
                    ) {
                        coil3.compose.AsyncImage(
                            model = uri,
                            contentDescription = "Vista previa del sticker con movimiento",
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            guardando = true
                            ambito.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val destino = Stickers.nuevoAnimado(
                                        ctx, Stickers.extensionDe(ctx, uri),
                                    )
                                    // El tope de la clase `sticker` es 2 MB, y
                                    // se comprueba AQUI y no al enviar: un
                                    // animado de 5 MB copiado y registrado
                                    // fallaria recien al mandarlo, con el
                                    // sticker ya en la coleccion.
                                    val cabe = Stickers.copiarAnimado(
                                        ctx, uri, destino, 2L * 1024 * 1024,
                                    )
                                    if (cabe) {
                                        app.repo.registrarSticker(destino, true, packDestino)
                                        true
                                    } else false
                                }
                                guardando = false
                                if (ok) onListo() else fallo = true
                            }
                        },
                        enabled = !guardando,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cian, contentColor = TextoSobreAcento,
                        ),
                    ) {
                        if (guardando) {
                            CircularProgressIndicator(
                                color = TextoSobreAcento,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (guardando) "Agregando…" else "Agregar a mis stickers")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Máximo 2 MB. Si pesa más no entra como sticker.",
                        color = TextoTerciario,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                fallo -> EstadoDeError(
                    titulo = "No se pudo abrir la imagen",
                    detalle = "Prueba con otra foto de la galería.",
                )

                bmp == null -> Box(
                    Modifier.fillMaxWidth().height(300.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Cian) }

                else -> {
                    // La ventana cuadrada. El fondo va a cuadros claros/oscuros
                    // para que se vea QUÉ es transparente: sobre un fondo liso,
                    // un recorte con alfa y uno con fondo del mismo color se
                    // ven idénticos hasta que se manda.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BgElev)
                            .pointerInput(bmp) {
                                detectTransformGestures { _, arrastre, escala, _ ->
                                    // Entre 1x y 4x: por debajo de 1 aparecerían
                                    // bordes vacíos dentro del cuadrado, y por
                                    // encima de 4 sobre una imagen de 1024 px no
                                    // queda resolución para 512.
                                    zoom = (zoom * escala).coerceIn(1f, 4f)
                                    desX += arrastre.x
                                    desY += arrastre.y
                                }
                            }
                            .semantics {
                                contentDescription =
                                    "Vista previa del sticker. Arrastra para mover, " +
                                        "pellizca para acercar."
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bmp.asImageBitmap(),
                            null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoom, scaleY = zoom,
                                    translationX = desX, translationY = desY,
                                ),
                            // `Crop` y no `Fit`: la ventana es lo que va a ser
                            // el sticker, así que lo que se ve es exactamente
                            // lo que sale. Con `Fit` quedarían franjas vacías
                            // que el resultado no tiene.
                            contentScale = ContentScale.Crop,
                        )
                    }

                    if (zoom != 1f || desX != 0f || desY != 0f) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { zoom = 1f; desX = 0f; desY = 0f }) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Volver al centro", fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            guardando = true
                            ambito.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val destino = Stickers.nuevo(ctx)
                                    val r = recorteVisible(bmp, zoom, desX, desY)
                                    if (Stickers.escribir(bmp, r, destino)) {
                                        app.repo.registrarSticker(destino, false, packDestino)
                                        true
                                    } else false
                                }
                                guardando = false
                                if (ok) onListo() else fallo = true
                            }
                        },
                        enabled = !guardando,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cian, contentColor = TextoSobreAcento,
                        ),
                    ) {
                        if (guardando) {
                            CircularProgressIndicator(
                                color = TextoSobreAcento,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (guardando) "Creando…" else "Crear sticker")
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Queda guardado en tus stickers para volver a usarlo.",
                        color = TextoTerciario,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Traduce el gesto a un recorte en píxeles de la imagen.
 *
 * ## Por qué esta cuenta no es trivial
 *
 * La ventana es cuadrada y la imagen se dibuja con `ContentScale.Crop`, que
 * ya la escala para cubrirla: el lado corto llena el cuadrado y del largo
 * sobra. Sobre eso se aplica el zoom y el desplazamiento del dedo, que están
 * en píxeles de **pantalla**, no de la imagen.
 *
 * Así que hay que deshacer los dos pasos: dividir por el zoom para saber qué
 * porción de la imagen cabe en la ventana, y convertir el desplazamiento de
 * pantalla a desplazamiento de imagen con la misma escala.
 *
 * El resultado se acota a la imagen. Un gesto rápido puede empujar el recorte
 * fuera, y un recorte fuera no es un error visible: es un sticker recortado de
 * la nada.
 */
internal fun recorteVisible(bmp: Bitmap, zoom: Float, desX: Float, desY: Float): Recorte {
    val base = minOf(bmp.width, bmp.height)
    val lado = (base / zoom).roundToInt().coerceIn(1, base)

    // `Crop` escala la imagen para cubrir el cuadrado: el factor es el lado
    // corto sobre el lado de la ventana. Como la ventana se mide en píxeles de
    // pantalla y el desplazamiento también, lo que hace falta es la proporción
    // entre píxeles de imagen y píxeles de ventana, que es `base / ventana`.
    // Se usa `lado` como aproximación de la ventana visible ya escalada, que
    // es exacta cuando zoom = 1 y suficientemente buena al acercar.
    val porPixel = lado.toFloat() / base
    val centroX = bmp.width / 2f - desX * porPixel
    val centroY = bmp.height / 2f - desY * porPixel

    val izq = (centroX - lado / 2f).roundToInt().coerceIn(0, bmp.width - lado)
    val arriba = (centroY - lado / 2f).roundToInt().coerceIn(0, bmp.height - lado)
    return Recorte(izq, arriba, izq + lado, arriba + lado)
}
