package com.wtfuck.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.Media
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.GifResumen
import kotlinx.coroutines.delay

/**
 * Selector de GIFs.
 *
 * Las vistas previas y los GIF completos los trae NUESTRO servidor, no el
 * proveedor: ver protocol/Gifs.kt para el razonamiento. Aqui eso se nota en
 * que cada miniatura se pide a la API propia y se guarda en memoria mientras la
 * hoja esta abierta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaStickers(onElegirGif: (String) -> Unit, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var consulta by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<GifResumen>>(emptyList()) }
    var aviso by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(true) }

    // Espera antes de buscar: sin esto cada letra dispara una peticion, y
    // escribir "gracias" serian siete busquedas para ver una.
    LaunchedEffect(consulta) {
        cargando = true
        if (consulta.isNotBlank()) delay(400)
        val r = app.repo.buscarGifs(consulta)
        resultados = r.resultados
        aviso = r.aviso
        cargando = false
    }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        OutlinedTextField(
            value = consulta,
            onValueChange = { consulta = it },
            placeholder = { Text("Buscar GIF", color = TextoTerciario) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoSecundario) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian,
                unfocusedBorderColor = Slate,
                focusedContainerColor = BgSurface,
                unfocusedContainerColor = BgSurface,
            ),
        )

        Box(
            Modifier.fillMaxWidth().height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cargando && resultados.isEmpty() ->
                    CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)

                aviso.isNotBlank() -> EstadoVacio(Icons.Filled.CloudOff, aviso)

                resultados.isEmpty() ->
                    EstadoVacio(Icons.Filled.SearchOff, "No hay resultados para \"$consulta\".")

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    items(resultados, key = { it.id }) { g ->
                        PreviaGif(g) { onCerrar(); onElegirGif(g.id) }
                    }
                }
            }
        }

        // La nota va a la vista y no solo al codigo: quien manda un GIF tiene
        // derecho a saber que ese pedido salio de la red institucional.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Shield, null, tint = Slate, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "Las busquedas pasan por el servidor de wtfuck, no por el proveedor.",
                fontSize = 11.sp,
                color = TextoTerciario,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun PreviaGif(g: GifResumen, onElegir: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var img by remember(g.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(g.id) {
        val bytes = app.repo.bytesGifPrevia(g.id)
        if (bytes != null) {
            img = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        Modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BgBase)
            .aspectRatio(1f)
            .clickable(onClick = onElegir),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = img
        if (bmp != null) {
            // La previa es un fotograma quieto a proposito: 24 GIF animados en
            // una rejilla dejan el selector inservible en un telefono modesto.
            Image(bmp, g.titulo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            CircularProgressIndicator(color = Slate, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EstadoVacio(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String) {
    Column(
        Modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icono, null, tint = Slate, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(texto, color = TextoSecundario, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------
//  Grabacion de nota de voz
// ------------------------------------------------------------------

/**
 * Barra que reemplaza al campo de texto mientras se graba.
 *
 * Se eligio tocar-para-empezar y tocar-para-enviar en vez de mantener
 * apretado. Mantener es mas comodo cuando funciona, pero deja a la persona sin
 * salida si se le resbala el dedo, y no hay forma de revisar antes de mandar.
 * Asi hay tres acciones claras y visibles: grabar, descartar, enviar.
 */
@Composable
fun BarraGrabando(
    segundos: Int,
    nivel: Float,
    onCancelar: () -> Unit,
    onEnviar: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancelar) {
            Icon(Icons.Filled.Delete, "Descartar", tint = Coral)
        }

        Box(
            Modifier
                .size(11.dp)
                .clip(CircleShape)
                // El punto crece con el nivel de entrada: es la senal de que el
                // microfono esta tomando algo y no grabando silencio.
                .background(Coral.copy(alpha = 0.45f + nivel * 0.55f))
        )
        Spacer(Modifier.width(10.dp))

        Text(
            Media.duracionLegible(segundos * 1000),
            color = TextoPrimario,
            fontSize = 15.sp,
            modifier = Modifier.width(52.dp),
        )
        Text("Grabando...", color = TextoSecundario, fontSize = 13.sp, modifier = Modifier.weight(1f))

        FilledIconButton(
            onClick = onEnviar,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Cian,
                contentColor = TextoSobreAcento,
            ),
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar nota de voz") }
    }
}
