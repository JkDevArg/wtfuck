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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
    onVerUnaVez: () -> Unit = {},
) {
    if (m.unaVez) {
        VistaUnaVez(m, sobreAcento, onVerUnaVez)
        return
    }
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
        // Sin `if`: para llegar aqui `local` ya paso la rama de arriba, asi
        // que no puede ser nulo. La comprobacion no protegia de nada y hacia
        // dudar de si podia serlo.
        else -> ({ onAbrir(local) })
    }

    if (m.adjuntoClase == ClaseAdjunto.VIDEO && m.adjuntoForma == FORMA_CIRCULO) {
        VistaVideonota(m, local, onDescargar)
        return
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
    // Un spoiler se destapa tocandolo, y queda destapado mientras dure la
    // pantalla. Lo mio no se tapa: ya se lo que mande.
    var destapado by remember(m.id) { mutableStateOf(m.esMio || !m.spoiler) }

    // Hay archivo y es una imagen: entonces se dibuja el archivo, y lo dibuja
    // Coil. Ver la nota de abajo.
    val hayCompleta = local != null && m.adjuntoClase == ClaseAdjunto.IMAGEN

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
                if (!destapado) {
                    destapado = true
                    return@clickable
                }
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
        if (hayCompleta) {
            /*
             * **Con Coil y no con `BitmapFactory`.**
             *
             * `BitmapFactory.decodeFile` devuelve un `Bitmap`, y un Bitmap es
             * UN fotograma. Un GIF recibido en el chat se veia congelado en el
             * primero, que es exactamente lo que un GIF no es.
             *
             * Es el mismo defecto que se arreglo para los stickers en Y.2.3 y
             * que aqui quedo sin arreglar: se cambio el sitio donde se habia
             * visto y no el otro sitio que tenia el mismo codigo. El envio si
             * estaba bien -`enviarAdjunto` no recomprime un GIF, justamente
             * para no aplanarlo-, asi que los fotogramas llegaban enteros y se
             * tiraban al dibujar.
             *
             * De paso arregla algo que el comentario anterior admitia a
             * medias: decodificar a resolucion completa DENTRO de la
             * composicion, aunque fuera una sola vez, bloquea el hilo de
             * interfaz. Coil decodifica fuera y cachea.
             */
            coil3.compose.AsyncImage(
                model = local,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (destapado) Modifier else Modifier.blur(32.dp)),
                contentScale = ContentScale.Crop,
            )
        } else if (mini != null) {
            // La miniatura viene de un sobre AJENO y por eso pasa por
            // `miniaturaAjena` en vez de por Coil. Ver `MiniaturaSegura.kt`.
            Image(
                mini, null,
                modifier = Modifier.fillMaxSize().then(if (destapado) Modifier else Modifier.blur(32.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        if (!destapado) {
            // Antes de Android 12 `blur` no hace nada: ahi se tapa entera.
            val sinDifuminado = android.os.Build.VERSION.SDK_INT < 31
            Box(
                Modifier.fillMaxSize().background(if (sinDifuminado) BgElev else BgBase.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                InsigniaCentral(Icons.Filled.VisibilityOff, "Spoiler · toca para ver", Cian)
            }
            return@Box
        }

        // Un velo sobre la miniatura: marca que lo que se ve no es el archivo
        // todavia, y ademas da contraste al boton.
        if (!hayCompleta) {
            Box(Modifier.fillMaxSize().background(BgBase.copy(alpha = if (mini == null) 0.2f else 0.45f)))
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
            // Coil, por lo mismo que la burbuja: abrir un GIF a pantalla
            // completa y que se quede quieto es peor todavia, porque aqui la
            // persona vino a mirarlo.
            coil3.compose.SubcomposeAsyncImage(
                model = archivo,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
                // `SubcomposeAsyncImage` y no `AsyncImage` solo por esto: el
                // hueco de error acepta contenido componible, y un visor que
                // no puede abrir el archivo tiene que decirlo en vez de
                // quedarse negro.
                error = { Text("No se pudo abrir la imagen", color = TextoSecundario) },
            )
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
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        when {
            // **Con Coil y no con `BitmapFactory`.** Decodificar a mano
            // devuelve un `Bitmap`, y un Bitmap es UN fotograma: un sticker
            // animado se veia congelado en el primero. El `ImageLoader` de la
            // app ya trae `AnimatedImageDecoder` -para los GIF- y ese mismo
            // decodificador cubre el WebP animado, asi que basta con no
            // esquivarlo.
            local != null -> coil3.compose.AsyncImage(
                model = local,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            // Mientras no ha bajado, la miniatura. Es un fotograma y esta
            // bien que lo sea: es un anticipo, no el sticker.
            m.adjuntoMiniatura.isNotBlank() -> {
                val img = remember(m.adjuntoMiniatura) {
                    Media.deBase64(m.adjuntoMiniatura)?.let {
                        runCatching {
                            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                }
                if (img != null) {
                    Image(img, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Icon(Icons.Filled.EmojiEmotions, null, tint = Slate, modifier = Modifier.size(52.dp))
                }
            }

            else -> Icon(
                Icons.Filled.EmojiEmotions, null,
                tint = Slate, modifier = Modifier.size(52.dp),
            )
        }
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
                // La que midio quien grabo, si la mando. Ver `Onda`.
                forma = m.adjuntoOnda,
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
 * ## De donde sale la figura
 *
 * De quien grabo, cuando la mando. El microfono le da los niveles gratis
 * mientras graba, asi que viajan dentro del sobre en cuarenta caracteres —
 * menos que el nombre del archivo. Ver [com.wtfuck.protocol.Onda].
 *
 * Sacarla del audio al dibujar no era una opcion: habria que decodificar el
 * archivo entero **en cada burbuja de la lista**, y una conversacion con
 * veinte notas haria eso veinte veces por cada scroll.
 *
 * ## Cuando no hay figura
 *
 * Las notas de antes de esto, las que manda una version vieja, y cualquier
 * cadena que no se entienda. Ahi se dibuja la de siempre: derivada del id, que
 * da una forma estable —la misma nota se ve igual cada vez— y distinta entre
 * mensajes.
 *
 * Se prefiere eso a no dibujar nada porque el hueco no seria mas honesto: una
 * barra vacia se lee como un audio roto, y el audio esta perfecto. Lo que
 * falta es su retrato.
 */
@Composable
private fun OndaSonido(
    forma: String,
    semilla: String,
    avance: Float,
    activo: Color,
    inactivo: Color,
    alSaltar: ((Float) -> Unit)? = null,
) {
    val barras = remember(forma, semilla) {
        com.wtfuck.protocol.Onda.decodificar(forma)
            // Un piso para las barras: a cero se dibujan como una linea de un
            // pixel y los silencios parecen un fallo del dibujo en vez de
            // silencio. Con 0.12 se ven bajitas, que es lo que son.
            ?.map { 0.12f + it * 0.88f }
            ?: java.util.Random(semilla.hashCode().toLong()).let { r ->
                List(34) { 0.25f + r.nextFloat() * 0.75f }
            }
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
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}


// ------------------------------------------------------------------
//  Ver una vez
// ------------------------------------------------------------------

/**
 * La burbuja de un "ver una vez". Nunca muestra la foto: ni miniatura ni
 * archivo, en ninguno de los dos lados.
 */
@Composable
private fun VistaUnaVez(m: MensajeEnt, sobreAcento: Boolean, onVer: () -> Unit) {
    val esVideo = m.adjuntoClase == ClaseAdjunto.VIDEO
    val que = if (esVideo) "Video" else "Foto"
    val abrible = !m.esMio && !m.unaVezAbierta && m.estado != EstadoEnvio.FALLIDO.name
    val bajando = m.adjuntoEstado == "DESCARGANDO"
    val texto = if (sobreAcento) TextoSobreAcento else TextoPrimario
    val (titulo, detalle) = when {
        m.esMio -> "$que · ver una vez" to when {
            m.estado == EstadoEnvio.PENDIENTE.name -> "enviando..."
            m.estado == EstadoEnvio.FALLIDO.name -> "no se envió"
            m.unaVezAbierta -> "abierta"
            else -> "enviada · ya no está en este teléfono"
        }
        m.unaVezAbierta -> "Abierta" to "$que de ver una vez"
        bajando -> que to "descargando..."
        else -> que to "toca para verla una vez"
    }
    Row(
        Modifier
            .widthIn(min = 200.dp)
            .then(if (abrible && !bajando) Modifier.clickable(onClick = onVer) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .border(2.dp, if (abrible) Coral else texto.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("1", color = if (abrible) Coral else texto.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(titulo, color = texto, fontWeight = FontWeight.Medium)
            Text(detalle, color = texto.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

/**
 * El visor de un "ver una vez".
 *
 * Con `FLAG_SECURE`: el sistema no deja capturar ni grabar la pantalla
 * mientras esta abierto, y en "recientes" la ventana sale en negro. Lo que NO
 * puede impedir, y se dice en pantalla, es una foto a la pantalla con otro
 * telefono.
 *
 * El video se reproduce aqui adentro y no en el reproductor del sistema, como
 * el resto de los videos: abrirlo afuera seria entregarle el archivo a otra
 * app, que podria guardarlo.
 */
@Composable
fun VisorUnaVez(archivo: File, clase: String, onCerrar: () -> Unit) {
    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (clase == ClaseAdjunto.VIDEO) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoPath(archivo.absolutePath)
                            setOnPreparedListener { it.start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize().align(Alignment.Center),
                )
            } else {
                val imagen = remember(archivo) {
                    // Con muestreo: una foto de 50 MP decodificada entera no
                    // cabe en memoria, y aqui no hay segunda oportunidad.
                    val lim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(archivo.absolutePath, lim)
                    var muestra = 1
                    while (maxOf(lim.outWidth, lim.outHeight) / (muestra * 2) >= 2048) muestra *= 2
                    BitmapFactory.decodeFile(
                        archivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = muestra },
                    )?.asImageBitmap()
                }
                if (imagen != null) {
                    Image(
                        imagen, null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().align(Alignment.Center),
                    )
                } else {
                    Text("No se pudo mostrar.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(
                Modifier.fillMaxWidth().systemBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCerrar) { Icon(Icons.Filled.Close, "Cerrar", tint = Color.White) }
                Text("Ver una vez", color = Color.White, fontWeight = FontWeight.Medium)
            }
            Text(
                "Al cerrar se borra de este teléfono. Las capturas de pantalla están " +
                    "bloqueadas, pero nadie puede impedir una foto a la pantalla con otro teléfono.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
