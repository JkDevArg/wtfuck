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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.datos.Stickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.font.FontWeight
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
fun PanelGifs(onElegirGif: (String) -> Unit, modifier: Modifier = Modifier) {
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

    Column(modifier) {
        // El buscador arriba, que es donde esta en las dos apps de referencia:
        // un GIF **siempre** se busca -no hay coleccion propia que recorrer- y
        // por eso el campo va antes que la rejilla y no escondido detras de
        // una lupa.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = TextoTerciario, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = consulta,
                onValueChange = { consulta = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextoPrimario),
                cursorBrush = SolidColor(Cian),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(vertical = 9.dp),
                decorationBox = { interior ->
                    if (consulta.isEmpty()) {
                        Text(
                            "Buscar GIF",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoTerciario,
                        )
                    }
                    interior()
                },
            )
            if (consulta.isNotEmpty()) {
                IconButton(onClick = { consulta = "" }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close, "Borrar la busqueda",
                        tint = TextoTerciario, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                cargando && resultados.isEmpty() ->
                    CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)

                aviso.isNotBlank() -> EstadoVacio(Icons.Filled.CloudOff, aviso)

                resultados.isEmpty() ->
                    EstadoVacio(Icons.Filled.SearchOff, "No hay resultados para \"$consulta\".")

                else -> LazyVerticalGrid(
                    // Dos columnas y no tres: un GIF es apaisado y ancho, y en
                    // tres columnas no se distingue que pasa dentro.
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(resultados, key = { it.id }) { g ->
                        PreviaGif(g) { onElegirGif(g.id) }
                    }
                }
            }
        }

        // Dos notas en una fila, y las dos tienen que estar.
        //
        // La de privacidad va a la vista y no solo al codigo: quien manda un
        // GIF tiene derecho a saber que ese pedido salio de la red
        // institucional.
        //
        // Y la atribucion **la exigen los terminos de GIPHY**. No es
        // decoracion ni cortesia: usar su API sin decir que el contenido es
        // suyo incumple la licencia con la que se nos permite usarla. Estaba
        // faltando desde que se construyo el buscador, y no se habia notado
        // porque sin clave configurada no se veia ningun GIF.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Shield, null, tint = Slate, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Las busquedas pasan por el servidor de wtfuck, no por el proveedor.",
                fontSize = 10.5.sp,
                color = TextoTerciario,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Vía GIPHY",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = TextoTerciario,
            )
        }
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
