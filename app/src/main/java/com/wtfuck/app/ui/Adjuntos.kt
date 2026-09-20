package com.wtfuck.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.WtfuckApp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.protocol.EstadoEnvio
import java.io.File

/**
 * El adjunto dentro de la burbuja.
 *
 * Todo pasa por los tres estados del archivo y hay que distinguirlos, porque
 * significan cosas distintas para quien mira:
 *
 *   ESPERA       llego la referencia y la miniatura; el archivo no esta aqui.
 *   DESCARGANDO  se esta trayendo.
 *   LISTO        esta en el telefono y se puede abrir.
 *
 * La miniatura se dibuja en los tres: una foto borrosa con un boton de descarga
 * dice mucho mas que un rectangulo gris.
 */
@Composable
fun ContenidoAdjunto(
    m: MensajeEnt,
    sobreAcento: Boolean,
    onDescargar: () -> Unit,
    onAbrir: (File) -> Unit,
    onReintentar: () -> Unit,
) {
    val local = remember(m.rutaLocal) { m.rutaLocal?.let { File(it) }?.takeIf { it.exists() } }

    /**
     * Que hace un toque sobre el adjunto.
     *
     * El orden NO es casual. Si fallo, reintentar gana sobre todo lo demas:
     * el archivo propio esta en el telefono, asi que sin esta rama el toque
     * abriria el visor y el envio fallido no tendria como reintentarse.
     */
    val alTocar: () -> Unit = when {
        m.estado == EstadoEnvio.FALLIDO.name -> onReintentar
        local == null -> onDescargar
        else -> ({ if (local != null) onAbrir(local) })
    }

    when (m.adjuntoClase) {
        ClaseAdjunto.IMAGEN, ClaseAdjunto.VIDEO ->
            VistaImagen(m, local, alTocar)

        ClaseAdjunto.STICKER ->
            VistaSticker(m, local)

        ClaseAdjunto.NOTA_VOZ, ClaseAdjunto.AUDIO ->
            VistaAudio(m, local, sobreAcento, alTocar)

        else ->
            VistaDocumento(m, local, sobreAcento, alTocar)
    }
}

// ------------------------------------------------------------------
//  Imagen y video
// ------------------------------------------------------------------

@Composable
private fun VistaImagen(m: MensajeEnt, local: File?, alTocar: () -> Unit) {
    var visor by remember { mutableStateOf(false) }

    // La imagen completa se decodifica una vez y se recuerda: hacerlo en cada
    // recomposicion de la lista tira la fluidez del scroll al piso.
    val completa = remember(local?.path, m.adjuntoClase) {
        if (local != null && m.adjuntoClase == ClaseAdjunto.IMAGEN) {
            runCatching { BitmapFactory.decodeFile(local.path)?.asImageBitmap() }.getOrNull()
        } else null
    }
    val mini = remember(m.adjuntoMiniatura) {
        // `miniaturaAjena` y no `decodeByteArray` a secas: esos bytes vienen de
        // un sobre ajeno. Ver `MiniaturaSegura.kt`.
        Media.deBase64(m.adjuntoMiniatura)?.let { b ->
            Media.miniaturaAjena(b)?.asImageBitmap()
        }
    }

    // Se respeta la proporcion real para que la burbuja no salte cuando la
    // imagen completa reemplaza a la miniatura.
    val proporcion = if (m.adjuntoAncho > 0 && m.adjuntoAlto > 0) {
        (m.adjuntoAncho.toFloat() / m.adjuntoAlto).coerceIn(0.6f, 1.8f)
    } else 1.3f

    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(BgBase)
            .fillMaxWidth()
            .aspectRatio(proporcion)
            .clickable {
                // Una imagen lista se ve aqui mismo; un video lo abre el
                // reproductor del sistema. Lo demas lo decide alTocar.
                if (local != null && m.adjuntoClase == ClaseAdjunto.IMAGEN &&
                    m.estado != EstadoEnvio.FALLIDO.name
                ) {
                    visor = true
                } else {
                    alTocar()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = completa ?: mini
        if (img != null) {
            Image(
                img, null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Un velo sobre la miniatura: marca que lo que se ve no es el archivo
        // todavia, y ademas da contraste al boton.
        if (completa == null) {
            Box(Modifier.fillMaxSize().background(BgBase.copy(alpha = if (img == null) 0.2f else 0.45f)))
        }

        when (m.adjuntoEstado) {
            "DESCARGANDO" -> CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
            "SUBIENDO" -> InsigniaCentral(Icons.Filled.CloudUpload, "Subiendo", Ambar)
            "FALLIDO" -> InsigniaCentral(Icons.Filled.ErrorOutline, "No se pudo", Coral)
            else -> when {
                local == null -> InsigniaCentral(
                    Icons.Filled.Download,
                    Media.tamanoLegible(m.adjuntoBytes),
                    Cian,
                )
                m.adjuntoClase == ClaseAdjunto.VIDEO -> BotonReproducir(m.adjuntoDuracionMs)
                else -> Unit
            }
        }
    }

    if (visor && local != null) {
        VisorImagen(local, m.texto) { visor = false }
    }
}

@Composable
private fun InsigniaCentral(icono: ImageVector, texto: String, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(BgBase.copy(alpha = 0.82f))
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(17.dp))
        if (texto.isNotBlank()) {
            Spacer(Modifier.width(7.dp))
            Text(texto, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BotonReproducir(duracionMs: Int) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(BgBase.copy(alpha = 0.78f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, "Reproducir", tint = Cian, modifier = Modifier.size(20.dp))
        if (duracionMs > 0) {
            Spacer(Modifier.width(6.dp))
            Text(Media.duracionLegible(duracionMs), color = TextoPrimario, fontSize = 12.sp)
        }
    }
}

/**
 * Visor a pantalla completa.
 *
 * Es un dialogo y no otra pantalla del navegador a proposito: se abre y se
 * cierra sin tocar la pila de navegacion, asi que el boton de atras vuelve al
 * chat en la misma posicion en la que estaba.
 */
@Composable
private fun VisorImagen(archivo: File, pie: String, onCerrar: () -> Unit) {
    val img = remember(archivo.path) {
        runCatching { BitmapFactory.decodeFile(archivo.path)?.asImageBitmap() }.getOrNull()
    }
    Dialog(
        onDismissRequest = onCerrar,
        // `usePlatformDefaultWidth = false` es imprescindible: sin esto el
        // dialogo se queda con el ancho de un cuadro de dialogo normal y un
        // fillMaxSize() adentro solo llena esa caja, dejando el chat visible
        // alrededor. Un visor de fotos a medio abrir no es un visor.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Negro opaco, no casi opaco: con un 3% de transparencia el
                // chat de atras se sigue viendo y el visor parece a medio abrir.
                .background(Color.Black)
                .clickable(onClick = onCerrar),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) {
                Image(img, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            } else {
                Text("No se pudo abrir la imagen", color = TextoSecundario)
            }
            if (pie.isNotBlank()) {
                Text(
                    pie,
                    color = TextoPrimario,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp),
                )
            }
            IconButton(
                onClick = onCerrar,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Icon(Icons.Filled.Close, "Cerrar", tint = TextoPrimario) }
        }
    }
}

// ------------------------------------------------------------------
//  Sticker
// ------------------------------------------------------------------

/** Un sticker no lleva burbuja: se dibuja suelto, como en Telegram. */
@Composable
private fun VistaSticker(m: MensajeEnt, local: File?) {
    val img = remember(local?.path, m.adjuntoMiniatura) {
        val bytes = if (local != null) local.readBytes() else Media.deBase64(m.adjuntoMiniatura)
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Filled.EmojiEmotions, null, tint = Slate, modifier = Modifier.size(52.dp))
    }
}

// ------------------------------------------------------------------
//  Audio y notas de voz
// ------------------------------------------------------------------

@Composable
private fun VistaAudio(m: MensajeEnt, local: File?, sobreAcento: Boolean, alTocar: () -> Unit) {
    val reproductor = LocalReproductor.current
    val sonando = reproductor.sonando == m.id
    val avance = if (sonando) reproductor.avance else 0f
    val acento = if (sobreAcento) TextoSobreAcento else Cian
    val apagado = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.35f) else Slate

    Row(
        Modifier.widthIn(min = 210.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(acento.copy(alpha = if (sobreAcento) 0.18f else 0.16f))
                .clickable {
                    if (local == null || m.estado == EstadoEnvio.FALLIDO.name) alTocar()
                    else if (sonando) reproductor.detener() else reproductor.reproducir(m.id, local)
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                m.adjuntoEstado == "DESCARGANDO" ->
                    CircularProgressIndicator(color = acento, strokeWidth = 2.dp, modifier = Modifier.size(19.dp))
                local == null ->
                    Icon(Icons.Filled.Download, "Descargar", tint = acento, modifier = Modifier.size(20.dp))
                sonando ->
                    Icon(Icons.Filled.Pause, "Pausar", tint = acento, modifier = Modifier.size(22.dp))
                else ->
                    Icon(Icons.Filled.PlayArrow, "Reproducir", tint = acento, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            OndaSonido(
                semilla = m.id,
                avance = avance,
                activo = acento,
                inactivo = apagado,
                // Adelantar solo tiene sentido sobre lo que esta sonando: en
                // una nota parada no hay a donde saltar todavia.
                alSaltar = if (sonando) reproductor::saltarA else null,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Mientras suena se muestra lo que va corriendo, como
                    // cualquier reproductor: la duracion total ya se vio antes
                    // de empezar, y durante la escucha lo util es cuanto falta.
                    when {
                        sonando -> Media.duracionLegible(reproductor.transcurridoMs)
                            .ifBlank { "0:00" }
                        m.adjuntoDuracionMs > 0 -> Media.duracionLegible(m.adjuntoDuracionMs)
                        else -> Media.tamanoLegible(m.adjuntoBytes)
                    },
                    fontSize = 11.sp,
                    color = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else TextoTerciario,
                )
                if (m.adjuntoClase == ClaseAdjunto.NOTA_VOZ && local != null) {
                    Spacer(Modifier.weight(1f))
                    BotonVelocidad(reproductor, sobreAcento, acento)
                }
            }
        }
    }
}

/**
 * El boton que rota la velocidad.
 *
 * Solo aparece en notas de voz. En una cancion o un audio que alguien mando
 * como archivo, cambiar la velocidad no es lo que se quiere; en una nota de voz
 * de tres minutos, si.
 *
 * Es un toque que rota entre 1x, 1.5x y 2x en vez de un menu: un menu cuesta
 * mas gestos que el beneficio que da.
 */
@Composable
private fun BotonVelocidad(reproductor: Reproductor, sobreAcento: Boolean, acento: Color) {
    val ajustes = (LocalContext.current.applicationContext as WtfuckApp).ajustes
    val v = reproductor.velocidad
    // El area tactil y la pildora que se VE son dos cosas distintas, y hay que
    // separarlas. Pintar el fondo sobre los 48 dp del area minima da un
    // recuadro que pesa mas que el boton de reproducir, al lado del cual esta;
    // dejar la pildora de 22 dp sin area minima da un blanco que no se acierta.
    //
    // Asi: la caja de fuera manda en el tacto y no se pinta, la de dentro se
    // pinta y no manda en el tacto.
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(9.dp))
            .clickable {
                val siguiente = siguienteVelocidad(v)
                reproductor.cambiarVelocidad(siguiente)
                ajustes.velocidadAudio = siguiente
            }
            .semantics(mergeDescendants = true) {
                // Sin esto el lector de pantalla lee "1.5x" y nada mas, que no
                // dice ni que es un boton ni que hace.
                contentDescription = "Velocidad de reproduccion, ${velocidadLegible(v)}. " +
                    "Tocar para pasar a ${velocidadLegible(siguienteVelocidad(v))}"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(acento.copy(alpha = if (sobreAcento) 0.20f else 0.13f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                velocidadLegible(v),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = acento,
            )
        }
    }
}

/**
 * Forma de onda.
 *
 * No son las amplitudes reales del audio: dibujarlas exigiria decodificar el
 * archivo entero para cada burbuja de la lista. Se derivan del id del mensaje,
 * que da una figura estable -la misma nota se ve siempre igual- y distinta
 * entre mensajes, que es todo lo que esta forma tiene que comunicar.
 */
@Composable
private fun OndaSonido(
    semilla: String,
    avance: Float,
    activo: Color,
    inactivo: Color,
    alSaltar: ((Float) -> Unit)? = null,
) {
    val barras = remember(semilla) {
        val r = java.util.Random(semilla.hashCode().toLong())
        List(34) { 0.25f + r.nextFloat() * 0.75f }
    }
    var ancho by remember { mutableIntStateOf(0) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { ancho = it.width }
            // Tocar y arrastrar sobre la onda salta a ese punto. Van los dos
            // gestos: el toque es lo que se intenta primero, y el arrastre es
            // lo que se espera despues de descubrir que el toque funciona.
            .then(
                if (alSaltar == null) Modifier else Modifier
                    .pointerInput(semilla, ancho) {
                        detectTapGestures { pos ->
                            if (ancho > 0) alSaltar(pos.x / ancho)
                        }
                    }
                    .pointerInput(semilla, ancho) {
                        detectHorizontalDragGestures { cambio, _ ->
                            if (ancho > 0) alSaltar(cambio.position.x / ancho)
                        }
                    }
                    .semantics {
                        contentDescription = "Barra de avance del audio. " +
                            "Tocar en un punto para adelantar o retroceder"
                    }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        barras.forEachIndexed { i, alto ->
            val pasada = i.toFloat() / barras.size <= avance
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(alto)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (pasada) activo else inactivo)
            )
        }
    }
}

// ------------------------------------------------------------------
//  Documentos
// ------------------------------------------------------------------

@Composable
private fun VistaDocumento(m: MensajeEnt, local: File?, sobreAcento: Boolean, alTocar: () -> Unit) {
    val acento = if (sobreAcento) TextoSobreAcento else Cian
    val secundario = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else TextoTerciario

    Row(
        Modifier
            .widthIn(min = 200.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (sobreAcento) TextoSobreAcento.copy(alpha = 0.08f) else BgBase)
            .clickable(onClick = alTocar)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            when {
                m.adjuntoEstado == "DESCARGANDO" ->
                    CircularProgressIndicator(color = acento, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                m.adjuntoEstado == "SUBIENDO" ->
                    Icon(Icons.Filled.CloudUpload, null, tint = Ambar, modifier = Modifier.size(24.dp))
                local == null ->
                    Icon(Icons.Filled.Download, "Descargar", tint = acento, modifier = Modifier.size(24.dp))
                else ->
                    Icon(iconoDeArchivo(m.adjuntoMime, m.adjuntoNombre), null, tint = acento, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.adjuntoNombre.ifBlank { "Documento" },
                color = if (sobreAcento) TextoSobreAcento else TextoPrimario,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    Media.tamanoLegible(m.adjuntoBytes).ifBlank { null },
                    m.adjuntoNombre.substringAfterLast('.', "").uppercase().ifBlank { null },
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = secundario,
            )
        }
    }
}

/** Icono por tipo. Ayuda a reconocer el archivo antes de abrirlo. */
private fun iconoDeArchivo(mime: String, nombre: String): ImageVector {
    val ext = nombre.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || ext == "pdf" -> Icons.Filled.PictureAsPdf
        ext in setOf("doc", "docx", "odt", "rtf", "txt") -> Icons.Filled.Description
        ext in setOf("xls", "xlsx", "csv", "ods") -> Icons.Filled.TableChart
        ext in setOf("ppt", "pptx", "odp") -> Icons.Filled.Slideshow
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Filled.FolderZip
        ext in setOf("apk") -> Icons.Filled.Android
        else -> Icons.Filled.InsertDriveFile
    }
}
