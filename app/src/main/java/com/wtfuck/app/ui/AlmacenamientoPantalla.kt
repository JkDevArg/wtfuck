package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.CalidadImagen
import com.wtfuck.app.datos.Media
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.UsoAlmacenamiento
import kotlinx.coroutines.launch

/**
 * Almacenamiento y datos (modulo D.7).
 *
 * Se muestran DOS ocupaciones distintas y separadas, porque son cosas
 * diferentes y confundirlas hace tomar malas decisiones:
 *
 *   - En este telefono: archivos ya descargados. Vaciarlo libera espacio y no
 *     pierde nada, porque lo que sigue en el servidor se vuelve a bajar.
 *   - En el servidor: la cuota de la cuenta. Eso NO se libera vaciando el
 *     cache; ahi lo que ocupa son los archivos que uno envio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlmacenamientoPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val ajustes = app.ajustes

    var archivos by remember { mutableIntStateOf(0) }
    var bytesLocales by remember { mutableLongStateOf(0L) }
    var uso by remember { mutableStateOf<UsoAlmacenamiento?>(null) }
    var confirmarVaciado by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    // Estado local de cada opcion: SharedPreferences no emite cambios, asi que
    // la pantalla guarda su propia copia y la escribe al tocar.
    var autoImagenes by remember { mutableStateOf(ajustes.autoImagenes) }
    var autoAudio by remember { mutableStateOf(ajustes.autoAudio) }
    var autoVideo by remember { mutableStateOf(ajustes.autoVideo) }
    var autoDocumentos by remember { mutableStateOf(ajustes.autoDocumentos) }
    var soloWifi by remember { mutableStateOf(ajustes.soloWifi) }
    var calidad by remember { mutableStateOf(ajustes.calidadImagen) }
    var eligiendoCalidad by remember { mutableStateOf(false) }

    suspend fun recontar() {
        val (n, b) = app.repo.usoAlmacenLocal()
        archivos = n
        bytesLocales = b
        uso = app.repo.usoAlmacenServidor()
    }

    LaunchedEffect(Unit) { recontar() }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                    }
                },
                title = { Text("Almacenamiento y datos", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // --- ocupacion ------------------------------------------------
            Spacer(Modifier.height(12.dp))
            Tarjeta {
                FilaOcupacion(
                    icono = Icons.Filled.PhoneAndroid,
                    titulo = "En este teléfono",
                    valor = Media.tamanoLegible(bytesLocales).ifBlank { "0 B" },
                    detalle = if (archivos == 1) "1 archivo descargado" else "$archivos archivos descargados",
                    color = Cian,
                )
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                val u = uso
                FilaOcupacion(
                    icono = Icons.Filled.CloudQueue,
                    titulo = "En el servidor",
                    valor = if (u == null) "—" else Media.tamanoLegible(u.bytes).ifBlank { "0 B" },
                    detalle = if (u == null) "sin conexión" else
                        "de ${Media.tamanoLegible(u.cuotaBytes)} de cuota · ${u.archivos} enviados",
                    color = Ambar,
                )
            }

            if (uso != null) {
                val u = uso!!
                val fraccion = if (u.cuotaBytes > 0) (u.bytes.toFloat() / u.cuotaBytes).coerceIn(0f, 1f) else 0f
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraccion },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (fraccion > 0.9f) Coral else Cian,
                    trackColor = Slate.copy(alpha = 0.3f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(BgSurface)
                    .clickable(enabled = archivos > 0) { confirmarVaciado = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DeleteSweep, null,
                    tint = if (archivos > 0) Coral else Slate,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        "Liberar espacio en el teléfono",
                        color = if (archivos > 0) TextoPrimario else TextoTerciario,
                        fontSize = 15.sp,
                    )
                    Text(
                        "Los mensajes no se borran. Lo que siga disponible se vuelve a descargar.",
                        color = TextoTerciario,
                        fontSize = 12.sp,
                    )
                }
            }

            // --- descarga automatica --------------------------------------
            Encabezado("Descarga automatica")
            Tarjeta {
                FilaInterruptor("Fotos", "Se ven sin tocar nada", autoImagenes) {
                    autoImagenes = it; ajustes.autoImagenes = it
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                FilaInterruptor("Notas de voz y audio", "Suelen pesar poco", autoAudio) {
                    autoAudio = it; ajustes.autoAudio = it
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                FilaInterruptor("Videos", "Solo los de menos de 16 MB", autoVideo) {
                    autoVideo = it; ajustes.autoVideo = it
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                FilaInterruptor("Documentos", "Solo los de menos de 8 MB", autoDocumentos) {
                    autoDocumentos = it; ajustes.autoDocumentos = it
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                FilaInterruptor(
                    "Solo con WiFi",
                    "Con datos moviles nada se descarga solo",
                    soloWifi,
                ) { soloWifi = it; ajustes.soloWifi = it }
            }

            // --- calidad de envio -----------------------------------------
            Encabezado("Calidad al enviar fotos")
            Tarjeta {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { eligiendoCalidad = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.HighQuality, null, tint = Cian, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Calidad", color = TextoPrimario, fontSize = 15.sp)
                        Text(calidad.etiqueta, color = Cian, fontSize = 13.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = TextoTerciario)
                }
            }
            Text(
                "Una foto de cámara pesa varios MB y en pantalla de teléfono no se " +
                    "distingue de la misma reducida. Bajarla antes de cifrar ahorra datos " +
                    "de quien envia, de quien recibe y cuota, a la vez.",
                color = TextoTerciario,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(28.dp))
        }
    }

    if (eligiendoCalidad) {
        AlertDialog(
            onDismissRequest = { eligiendoCalidad = false },
            containerColor = BgElev,
            title = { Text("Calidad al enviar fotos", color = TextoPrimario) },
            text = {
                Column {
                    CalidadImagen.entries.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    calidad = c; ajustes.calidadImagen = c; eligiendoCalidad = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = calidad == c,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Cian, unselectedColor = Slate),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.etiqueta, color = TextoPrimario, fontSize = 15.sp)
                                Text(
                                    if (c == CalidadImagen.ORIGINAL) "Sin reducir. Fiel y caro."
                                    else "Hasta ${c.ladoMax} px",
                                    color = TextoTerciario,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { eligiendoCalidad = false }) { Text("Cerrar", color = Cian) }
            },
        )
    }

    if (confirmarVaciado) {
        AlertDialog(
            onDismissRequest = { confirmarVaciado = false },
            containerColor = BgElev,
            title = { Text("Liberar espacio", color = TextoPrimario) },
            text = {
                Text(
                    "Se van a borrar $archivos archivos de este teléfono " +
                        "(${Media.tamanoLegible(bytesLocales)}).\n\n" +
                        "Los mensajes quedan. Lo que siga disponible en el servidor se " +
                        "vuelve a descargar cuando lo abras.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmarVaciado = false
                    ambito.launch {
                        val n = app.repo.vaciarMediaLocal()
                        recontar()
                        aviso = if (n == 1) "Se libero 1 archivo." else "Se liberaron $n archivos."
                    }
                }) { Text("Liberar", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarVaciado = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            text = { Text(msg, color = TextoPrimario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

// ------------------------------------------------------------------
//  Piezas
// ------------------------------------------------------------------

@Composable
private fun Tarjeta(contenido: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(BgSurface),
        content = contenido,
    )
}

@Composable
private fun Encabezado(texto: String) {
    Text(
        texto.uppercase(),
        color = TextoTerciario,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun FilaOcupacion(
    icono: ImageVector,
    titulo: String,
    valor: String,
    detalle: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = TextoPrimario, fontSize = 15.sp)
            Text(detalle, color = TextoTerciario, fontSize = 12.sp)
        }
        Text(valor, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilaInterruptor(
    titulo: String,
    detalle: String,
    valor: Boolean,
    onCambio: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCambio(!valor) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = TextoPrimario, fontSize = 15.sp)
            Text(detalle, color = TextoTerciario, fontSize = 12.sp)
        }
        Switch(
            checked = valor,
            onCheckedChange = onCambio,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextoSobreAcento,
                checkedTrackColor = Cian,
                uncheckedThumbColor = TextoSecundario,
                uncheckedTrackColor = BgElev,
                uncheckedBorderColor = Slate,
            ),
        )
    }
}
