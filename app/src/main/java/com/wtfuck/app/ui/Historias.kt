package com.wtfuck.app.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Mic
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wtfuck.app.datos.HistoriaEnt
import com.wtfuck.app.datos.Media
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ClaseHistoria
import com.wtfuck.protocol.MotivoSinVistas
import com.wtfuck.protocol.VistasDeHistoria
import kotlinx.coroutines.delay
import java.io.File

/**
 * Modulo O · La interfaz de las historias.
 *
 * Tres piezas: la fila de anillos arriba de la lista de chats, el visor a
 * pantalla completa, y el compositor.
 */

/** Cuanto dura una historia de texto en pantalla antes de pasar sola. */
private const val DURACION_MS = 5_000L

// ---------------------------------------------------------------------------
//  La fila de anillos
// ---------------------------------------------------------------------------

/**
 * Las entradas de la fila: un par (autor, sus historias), ya ordenado.
 *
 * Esta fuera del composable para poder probarla. La regla que implementa —que
 * lo que no se puede abrir no se anuncia— es facil de romper sin darse cuenta
 * al tocar el orden, y es justo la que deja de prometer historias que no estan.
 */
fun autoresConHistorias(
    historias: List<HistoriaEnt>,
    miUsuario: String,
): List<Pair<String, List<HistoriaEnt>>> =
    historias
        .filter { it.conContenido }
        .groupBy { it.autor }
        .toList()
        .sortedWith(
            compareByDescending<Pair<String, List<HistoriaEnt>>> { it.first == miUsuario }
                .thenByDescending { par -> par.second.any { !it.vista } }
                .thenBy { it.first }
        )

/**
 * La fila de arriba de la lista de chats.
 *
 * ## Por que se agrupa por autor
 *
 * Alguien que publica cinco cosas seguidas es **una** entrada, no cinco. La
 * fila no es una lista de historias: es una lista de personas que tienen algo
 * que contar, y abrir una abre todas las suyas en orden. Sin agrupar, una
 * persona activa empuja al resto fuera de la pantalla.
 *
 * ## El anillo
 *
 * Lleno mientras quede algo sin ver, apagado cuando ya se vio todo. Es la unica
 * senal que distingue "hay algo nuevo" de "ya lo viste", y por eso el orden
 * tambien pone primero lo no visto: sin eso, lo nuevo se pierde al final de una
 * fila larga.
 *
 * ## Lo que no se puede abrir no se anuncia
 *
 * Una historia cuyo sobre no llego no se puede dibujar: la pantalla solo puede
 * decir "no se pudo descifrar". Anunciarla en la fila es prometer algo que al
 * tocarlo no esta, y encima dura 24 horas.
 *
 * Asi que un autor entra en la fila solo si tiene **al menos una** historia con
 * contenido. Se elige ocultar y no mostrar en gris porque el caso normal no es
 * un fallo permanente: el sobre y el metadato llegan por caminos distintos y
 * cualquiera puede llegar primero, asi que lo habitual es que falte medio
 * segundo. Encender la entrada cuando llega el sobre se ve bien; apagarla
 * despues de haberla anunciado, no.
 */
@Composable
fun FilaHistorias(
    historias: List<HistoriaEnt>,
    miUsuario: String,
    onAbrir: (String) -> Unit,
    onPublicar: () -> Unit,
) {
    val porAutor = remember(historias, miUsuario) { autoresConHistorias(historias, miUsuario) }

    // **Sin historias, una linea. Con historias, la franja de circulos.**
    //
    // La franja mide 90 dp de alto y cuando no hay ninguna historia contiene
    // un solo circulo con un "+", con el resto del ancho vacio. Eso es lo
    // primero que se ve al abrir la app: una banda grande, casi toda hueca,
    // por encima de las conversaciones. Se lee como un hueco de maquetado, no
    // como una invitacion.
    //
    // No se esconde del todo, y esa fue la tentacion: esconderla dejaria las
    // historias sin ninguna puerta visible -el boton "Nuevo" no las ofrece a
    // proposito, para no duplicar caminos-. Una fila de una linea ocupa un
    // tercio, sigue diciendo lo que hay detras, y desaparece sola en cuanto
    // alguien publica algo.
    if (porAutor.isEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPublicar)
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Publicar una historia"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, Cian.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, null, tint = Cian, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Publicar una historia",
                color = TextoSecundario,
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                // "dura 24 h" y no "24 h" a secas.
                //
                // Esta alineado a la derecha, que en las filas de abajo es la
                // columna de la HORA del ultimo mensaje -"Ayer", "Martes"-.
                // Un "24 h" ahi se lee como una marca de tiempo. La palabra
                // sobra en cualquier otro sitio y aqui es la que desambigua.
                "dura 24 h",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
        }
        return
    }

    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // Publicar vive en la fila y no en un menu: es la accion que hace
            // que la fila exista, y esconderla la deja vacia para siempre.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(62.dp).clickable(onClick = onPublicar),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(BgElev)
                        .border(1.dp, Cian.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, null, tint = Cian, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Publicar",
                    color = TextoTerciario,
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }

        items(porAutor, key = { it.first }) { (autor, suyas) ->
            val sinVer = suyas.any { !it.vista }
            val esMia = autor == miUsuario

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(62.dp)
                    .clickable { onAbrir(autor) }
                    .semantics {
                        contentDescription =
                            if (esMia) "Tu historia" else "Historias de $autor"
                    },
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        // El anillo: lleno mientras quede algo sin ver.
                        .border(
                            width = if (sinVer) 2.dp else 1.dp,
                            color = if (sinVer) Cian else Slate.copy(alpha = 0.55f),
                            shape = CircleShape,
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(nombre = autor, url = null, tamano = 50.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (esMia) "Tu historia" else autor,
                    color = if (sinVer) TextoPrimario else TextoTerciario,
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  El visor
// ---------------------------------------------------------------------------

@Composable
fun VisorHistorias(
    delAutor: String,
    historias: List<HistoriaEnt>,
    miUsuario: String,
    onVista: (String) -> Unit,
    onRetirar: (String) -> Unit,
    onVerQuienes: (String) -> Unit,
    onDescargar: (String) -> Unit,
    onResponder: (String, String) -> Unit,
    onCerrar: () -> Unit,
) {
    if (historias.isEmpty()) {
        onCerrar()
        return
    }

    var indice by remember(delAutor) { mutableIntStateOf(0) }
    var pulsando by remember { mutableStateOf(false) }
    var escribiendo by remember { mutableStateOf(false) }
    var respuesta by remember(delAutor) { mutableStateOf("") }

    // Escribir tambien pausa. Sin esto, la historia avanza sola mientras se
    // teclea la respuesta y se termina contestando a otra cosa.
    val pausado = pulsando || escribiendo

    val actual = historias.getOrNull(indice) ?: historias.last()
    val esMia = actual.autor == miUsuario

    // Marcar vista al mostrarla, no al cerrar: lo que cuenta es haberla visto.
    LaunchedEffect(actual.id) { onVista(actual.id) }

    // El archivo se pide al ABRIRLA, no al recibirla. Lo que llego en el sobre
    // es la miniatura, que ya dibuja algo mientras tanto.
    LaunchedEffect(actual.id, actual.adjuntoEstado) {
        if (actual.adjuntoId.isNotBlank() && !archivoListo(actual) &&
            actual.adjuntoEstado != "DESCARGANDO" && actual.adjuntoEstado != "FALLIDO"
        ) {
            onDescargar(actual.id)
        }
    }

    // Cuanto se queda en pantalla. Un video dura lo que dura el video: cortarlo
    // a los cinco segundos seria no dejarlo ver. El tope existe igual, porque
    // la duracion la declara quien lo subio y no se comprueba contra nada.
    val duracion = duracionEnPantalla(actual)

    // El avance. Se reinicia con cada historia y se congela al pausar.
    var avance by remember(actual.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(actual.id, pausado, duracion) {
        if (pausado) return@LaunchedEffect
        val paso = 50L
        while (avance < 1f) {
            delay(paso)
            avance += paso.toFloat() / duracion
        }
        if (indice < historias.lastIndex) indice++ else onCerrar()
    }

    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // `decorFitsSystemWindows = false` es lo que hace que el teclado se
            // pueda manejar con `imePadding` dentro del dialogo. Con el valor
            // por defecto el sistema EMPUJA la ventana entera hacia arriba, y
            // la cabecera de la historia se iba fuera de la pantalla mientras
            // el campo de responder quedaba flotando en mitad de la foto.
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Con foto o video el fondo es negro y no el color elegido: lo
                // que se mira es la imagen, y un color fuerte alrededor le pelea
                // la atencion y le cambia el aspecto.
                // Un audio se dibuja sobre SU color: no hay foto que llene el negro.
                .background(
                    if (esDeMedio(actual) && actual.clase != ClaseHistoria.AUDIO) Color.Black
                    else colorDeFondo(actual.fondo)
                )
                .pointerInput(actual.id, historias.size) {
                    detectTapGestures(
                        // Mantener pulsado pausa. Hace falta de verdad cuando
                        // el texto es largo y cinco segundos no alcanzan.
                        //
                        // `tryAwaitRelease` es lo que hace que la pausa termine
                        // tambien si el gesto se cancela -un dedo que sale de
                        // la pantalla-, en vez de dejar la historia congelada.
                        onPress = {
                            pulsando = true
                            tryAwaitRelease()
                            pulsando = false
                        },
                        // Izquierda atras, derecha adelante: lo que la gente ya
                        // tiene aprendido. Como el gesto es invisible, la unica
                        // opcion razonable es la que ya conocen.
                        onTap = { pos ->
                            if (pos.x < size.width / 3f) {
                                if (indice > 0) indice-- else onCerrar()
                            } else {
                                if (indice < historias.lastIndex) indice++ else onCerrar()
                            }
                        },
                    )
                },
        ) {
            // El medio va DETRAS de la cabecera y las barras, a sangre: una
            // foto con margenes dentro de una pantalla negra se ve como un
            // error, no como una decision.
            if (esDeMedio(actual)) {
                MedioDeHistoria(actual, pausado, Modifier.fillMaxSize())

                // Un velo oscuro arriba, solo donde va la cabecera.
                //
                // Sin esto el nombre y la hora en blanco caen sobre lo que
                // haya: sobre una foto clara no se leen. Un velo plano sobre
                // toda la pantalla apagaria la foto entera, que es justo lo que
                // se vino a ver; el degradado se come el fondo donde estorba y
                // desaparece donde no.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                            )
                        )
                )
            }

            // `imePadding` va en la COLUMNA y no solo en la barra de abajo.
            // Puesto en la barra, el teclado la empujaba hacia arriba mientras
            // el resto seguia midiendo la pantalla entera, y el campo acababa
            // flotando en mitad de la foto.
            //
            // `statusBarsPadding` y no un margen fijo: el medio se dibuja de
            // borde a borde -tiene que-, pero la cabecera debajo del reloj del
            // sistema es ilegible, y la altura de esa barra la decide el
            // aparato, no este codigo.
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .padding(top = 14.dp)
            ) {
                // Las barras
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    historias.forEachIndexed { i, _ ->
                        val lleno = when {
                            i < indice -> 1f
                            i == indice -> avance.coerceIn(0f, 1f)
                            else -> 0f
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.3f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(lleno)
                                    .fillMaxHeight()
                                    .background(Color.White),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(nombre = actual.autor, url = null, tamano = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (esMia) "Tu historia" else actual.autor,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                        Text(
                            horaCorta(actual.creadaEn),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                        )
                    }
                    if (esMia) {
                        IconButton(onClick = { onRetirar(actual.id) }) {
                            Icon(Icons.Filled.Delete, "Quitar esta historia", tint = Color.White)
                        }
                    }
                    IconButton(onClick = onCerrar) {
                        Icon(Icons.Filled.Close, "Cerrar", tint = Color.White)
                    }
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                    // Con foto, el texto es un pie y se apoya abajo; sin foto
                    // es LA historia y va al centro.
                    if (esDeMedio(actual)) Alignment.BottomCenter else Alignment.Center,
                ) {
                    when {
                        // Se DICE lo que pasa en vez de dejar un hueco: una
                        // historia sin sobre no es una historia vacia, es una
                        // que este aparato no pudo abrir.
                        !actual.conContenido -> Text(
                            "No se pudo descifrar esta historia.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                        actual.texto.isBlank() -> Unit
                        else -> Text(
                            actual.texto,
                            color = Color.White,
                            // Un pie sobre una foto compite con la foto: mas
                            // chico, y con una caja propia para que se lea
                            // igual sobre claro que sobre oscuro.
                            fontSize = if (esDeMedio(actual)) 16.sp else 24.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = if (!esDeMedio(actual)) Modifier else Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                if (esMia) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onVerQuienes(actual.id) }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Visibility, null,
                            tint = Color.White, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (actual.vistas) {
                                0 -> "Todavía no la vio nadie"
                                1 -> "La vio 1 persona"
                                else -> "La vieron ${actual.vistas} personas"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    // Las reacciones rapidas van ENCIMA del campo, no dentro de
                    // un menu: son un toque, y meterlas a dos toques las
                    // convierte en lo mismo que escribir, que ya esta abajo.
                    //
                    // Una reaccion **es** una respuesta cuyo texto es un emoji:
                    // no hay ruta nueva ni tabla nueva, y llega al chat citando
                    // la historia como cualquier otra respuesta. Eso es lo que
                    // hace falta que pase, porque quien publico tiene que poder
                    // contestar a la reaccion.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        REACCIONES_HISTORIA.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onResponder(actual.id, emoji) }
                                    .minimumInteractiveComponentSize()
                                    .padding(6.dp)
                                    .semantics { contentDescription = "Reaccionar con $emoji" },
                            )
                        }
                    }

                    // Contestar una historia es mandarle un mensaje directo a
                    // quien la publico. El campo va AQUI y no en un dialogo
                    // aparte: hay que poder escribir mirando la historia, que
                    // es de lo que se habla.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = respuesta,
                            onValueChange = { respuesta = it.take(TOPE_RESPUESTA_HISTORIA) },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { escribiendo = it.isFocused },
                            placeholder = {
                                Text(
                                    "Responder a ${actual.autor}",
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                                cursorColor = Color.White,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                onResponder(actual.id, respuesta.trim())
                                respuesta = ""
                                escribiendo = false
                            },
                            enabled = respuesta.isNotBlank(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, "Enviar la respuesta",
                                tint = if (respuesta.isBlank()) Color.White.copy(alpha = 0.4f)
                                       else Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Foto y video dentro del visor
// ---------------------------------------------------------------------------

/** Si esta historia trae archivo. La clase la puso quien publico. */
private fun esDeMedio(h: HistoriaEnt): Boolean =
    h.clase == ClaseHistoria.IMAGEN || h.clase == ClaseHistoria.VIDEO || h.clase == ClaseHistoria.AUDIO

/** Si el archivo ya esta descargado y descifrado en este telefono. */
private fun archivoListo(h: HistoriaEnt): Boolean =
    h.rutaLocal.isNotBlank() && File(h.rutaLocal).exists()

/**
 * Cuanto se queda una historia en pantalla.
 *
 * Un video dura lo que dura el video: pasarlo a los cinco segundos seria no
 * dejarlo ver. Pero la duracion **la declara quien lo subio** y nadie la
 * comprueba contra el archivo, asi que va acotada por los dos lados. Sin el
 * tope de arriba, un numero inventado deja la pantalla clavada en una historia
 * que no avanza y sin ninguna pista de por que.
 */
private fun duracionEnPantalla(h: HistoriaEnt): Float {
    // Un audio, igual que un video, dura lo que dura: cortarlo a los cinco
    // segundos seria no dejarlo escuchar. Con su propio tope.
    val tope = when (h.clase) {
        ClaseHistoria.VIDEO -> TOPE_VIDEO_MS
        ClaseHistoria.AUDIO -> com.wtfuck.app.datos.AUDIO_ESTADO_MAX_MS
        else -> return DURACION_MS.toFloat()
    }
    if (h.adjuntoDuracionMs !in 1..tope) return DURACION_MS.toFloat()
    return h.adjuntoDuracionMs.toFloat().coerceAtLeast(DURACION_MS.toFloat())
}

/** Lo maximo que se le cree a la duracion declarada de un video. */
private const val TOPE_VIDEO_MS = 90_000

/**
 * La foto o el video, a pantalla completa.
 *
 * ## Los tres estados, y por que se dibujan los tres
 *
 *  1. **Solo miniatura**: llego en el sobre, se ve al instante. Borrosa, pero
 *     es la historia: un rectangulo gris mientras baja seria peor.
 *  2. **Bajando**: la miniatura con un indicador encima.
 *  3. **Listo**: el archivo de verdad.
 *
 * Y un cuarto que no es un estado sino un fallo: si el archivo no se pudo
 * bajar se DICE. Quedarse en la miniatura para siempre y en silencio es lo que
 * hace que una app parezca rota.
 */
@Composable
private fun MedioDeHistoria(h: HistoriaEnt, pausado: Boolean, modifier: Modifier = Modifier) {
    val archivo = remember(h.rutaLocal, h.adjuntoEstado) {
        h.rutaLocal.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
    }

    // La miniatura viene de un sobre ajeno: se decodifica con `miniaturaAjena`,
    // que mira las dimensiones ANTES de reservar memoria. Ver MiniaturaSegura.
    val mini = remember(h.miniatura) {
        Media.deBase64(h.miniatura)?.let { Media.miniaturaAjena(it)?.asImageBitmap() }
    }

    // La imagen completa se decodifica una vez: hacerlo en cada recomposicion
    // con el avance corriendo a 20 fotogramas por segundo es inviable.
    val completa = remember(archivo?.path, h.clase) {
        if (archivo != null && h.clase == ClaseHistoria.IMAGEN) {
            runCatching { BitmapFactory.decodeFile(archivo.path)?.asImageBitmap() }.getOrNull()
        } else null
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            h.clase == ClaseHistoria.AUDIO ->
                AudioDeHistoria(archivo, pausado)

            h.clase == ClaseHistoria.VIDEO && archivo != null ->
                VideoDeHistoria(archivo, pausado)

            completa != null -> Image(
                completa, null,
                modifier = Modifier.fillMaxSize(),
                // `Fit` y no `Crop`: una historia vertical recortada a la
                // fuerza pierde justo lo que la persona quiso mostrar.
                contentScale = ContentScale.Fit,
            )

            mini != null -> Image(
                mini, null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        when {
            h.adjuntoEstado == "DESCARGANDO" ->
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)

            h.adjuntoEstado == "FALLIDO" -> Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Coral, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("No se pudo bajar", color = Color.White, fontSize = 12.sp)
            }

            // Video sin archivo todavia: la miniatura con el simbolo de play,
            // para que se entienda que eso se mueve y que falta traerlo.
            h.clase == ClaseHistoria.VIDEO && archivo == null -> Icon(
                Icons.Filled.PlayArrow, "Video",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/**
 * Un estado de audio: el micrófono grande que late mientras suena.
 *
 * Con `MediaPlayer` y no con el reproductor de notas de voz del chat: aquí no
 * hay controles —los controles de un estado son los toques de la pantalla— y
 * hace falta exactamente esto: sonar al aparecer, pausar mientras se mantiene
 * pulsado y callarse al pasar al siguiente.
 */
@Composable
private fun AudioDeHistoria(archivo: File?, pausado: Boolean) {
    var reproductor by remember(archivo?.path) { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(archivo?.path) {
        val mp = archivo?.let { f ->
            runCatching {
                android.media.MediaPlayer().apply {
                    setDataSource(f.path)
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }.getOrNull()
        }
        reproductor = mp
        onDispose { runCatching { mp?.release() } }
    }
    LaunchedEffect(pausado, reproductor) {
        val mp = reproductor ?: return@LaunchedEffect
        runCatching { if (pausado) mp.pause() else if (!mp.isPlaying) mp.start() }
    }

    val latido = rememberInfiniteTransition(label = "latido")
    val escala by latido.animateFloat(
        initialValue = 1f,
        targetValue = if (archivo != null && !pausado) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "escala",
    )
    Box(
        Modifier
            .size(132.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, "Audio", tint = Color.White, modifier = Modifier.size(64.dp))
    }
}

/**
 * El video, con `VideoView`.
 *
 * Se elige `VideoView` y no una dependencia de reproduccion entera porque aqui
 * hace falta exactamente esto: un archivo local, en bucle, sin controles —los
 * controles de una historia son los toques de la pantalla, que ya existen—.
 *
 * Mantener pulsado pausa la historia, y **tambien el video**: si el video
 * siguiera corriendo, soltar devolveria una barra de avance que ya no dice
 * nada de lo que se esta viendo.
 */
@Composable
private fun VideoDeHistoria(archivo: File, pausado: Boolean) {
    var vista by remember(archivo.path) { mutableStateOf<VideoView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(archivo))
                setOnPreparedListener { mp ->
                    // En bucle: una historia corta que se queda en negro al
                    // terminar parece que fallo.
                    mp.isLooping = true
                    start()
                }
                // Un archivo que no se puede reproducir no tira la pantalla
                // abajo: se lo traga y queda la miniatura.
                setOnErrorListener { _, _, _ -> true }
                vista = this
            }
        },
        onRelease = { it.stopPlayback() },
    )

    LaunchedEffect(pausado, vista) {
        val v = vista ?: return@LaunchedEffect
        if (pausado) v.pause() else v.start()
    }
}

/**
 * El color de fondo de una historia de texto.
 *
 * Viene del sobre, asi que lo eligio otra persona: se valida antes de usarlo.
 * Un color que no se entiende cae al de la app en vez de reventar o de dejar la
 * pantalla en negro sobre negro.
 */
fun colorDeFondo(hex: String): Color {
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return BgBase
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(BgBase)
}

/** Los fondos que se ofrecen al publicar. */
val FONDOS_HISTORIA = listOf("#0F1717", "#0B6E6D", "#8A5300", "#B3301A", "#3C4A4A")

// El compositor de antes (HojaPublicarHistoria) se reemplazo por el editor a
// pantalla completa: ver `EditorDeEstado`.

/**
 * Los emojis de reaccion rapida.
 *
 * Seis y no una rejilla entera: esto es para contestar sin pensar, y una
 * pantalla de cien emojis obliga a elegir, que es justo lo que se viene a
 * evitar. Quien quiera otro lo escribe en el campo de abajo, que acepta
 * cualquier cosa.
 */
val REACCIONES_HISTORIA = listOf("❤️", "😂", "😮", "😢", "👏", "🔥")

/** Lo que cabe en una historia de texto. */
const val TOPE_TEXTO_HISTORIA = 280

/**
 * Lo que cabe en la respuesta rapida del visor.
 *
 * Mas corto que un mensaje normal a proposito: esto es una reaccion a algo que
 * se esta mirando, y para una parrafada esta el chat, que es donde va a acabar
 * igual.
 */
const val TOPE_RESPUESTA_HISTORIA = 500

// ---------------------------------------------------------------------------
//  Quién la vio
// ---------------------------------------------------------------------------

/**
 * La lista de quién vio una historia.
 *
 * Cuando quien pregunta tiene apagadas las confirmaciones de lectura, la lista
 * viene vacía **con un motivo**, y eso se dice. Un vacío sin explicación se lee
 * como "no le interesó a nadie", que es otra cosa y es falsa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaVistasHistoria(vistas: VistasDeHistoria, onCerrar: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            Text(
                "Quién la vio",
                color = TextoPrimario,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))

            when {
                // Ver una historia es leer un mensaje: si alguien tiene
                // apagadas las confirmaciones, no registra vista y por
                // reciprocidad tampoco ve las suyas. Se dice POR QUÉ.
                vistas.motivo == MotivoSinVistas.SIN_LECTURA -> Text(
                    "Tienes apagadas las confirmaciones de lectura, así que no se " +
                        "registra quién ve tus historias —ni las tuyas cuentan para " +
                        "los demás—. Se puede cambiar en Privacidad.",
                    color = TextoSecundario,
                    fontSize = 13.sp,
                )

                vistas.vistas.isEmpty() -> Text(
                    "Todavía no la vio nadie.",
                    color = TextoTerciario,
                    fontSize = 13.sp,
                )

                else -> vistas.vistas.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(nombre = v.username, url = null, tamano = 36.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.nombreMostrado.ifBlank { v.username },
                                color = TextoPrimario,
                                fontSize = 14.sp,
                            )
                            Text("@${v.username}", color = TextoTerciario, fontSize = 12.sp)
                        }
                        Text(horaCorta(v.vistaEn), color = TextoTerciario, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
