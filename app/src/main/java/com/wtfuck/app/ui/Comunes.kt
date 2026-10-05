package com.wtfuck.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.EstadoEnvio
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Que clase de cosa dibuja un avatar de conversacion.
 *
 * Un `enum` sin valor por defecto, y ahi esta todo el cambio. Antes eran dos
 * banderas opcionales —`esGrupo` y `esCanal`—, y la razon escrita era que
 * "anadirlo como opcional no obliga a tocar los once sitios restantes". Esa
 * economia salio cara: `esGrupo = false` significa **"si no dices nada, es una
 * persona"**, y un sitio nuevo no dice nada.
 *
 * Se dibujo una cosa como si fuera otra tres veces:
 *
 *  - modulo AE, un canal con el icono de grupo en "Mis canales";
 *  - modulo AJ, una llamada de grupo con el icono de una persona en el
 *    historial;
 *  - y la pantalla de llamada, que dibujaba "@Equipo seguridad" con iniciales.
 *
 * A la tercera el problema deja de ser el sitio.
 */
enum class ClaseDeChat { DIRECTA, GRUPO, CANAL, NOTAS }

/** El `tipo` que manda el servidor, como clase. */
fun claseDeTipo(tipo: String?): ClaseDeChat = when (tipo) {
    "grupo" -> ClaseDeChat.GRUPO
    "canal" -> ClaseDeChat.CANAL
    "notas" -> ClaseDeChat.NOTAS
    // Una directa es el unico caso donde el avatar es una PERSONA, y por eso
    // es el que se puede caer aqui sin hacer dano: si el tipo llega vacio o
    // desconocido, dibujar iniciales de un nombre es lo menos equivocado.
    else -> ClaseDeChat.DIRECTA
}

/**
 * El avatar de una CONVERSACION.
 *
 * Aparte de [Avatar] a proposito: el que dibuja una persona no necesita
 * decidir nada, y el que dibuja una conversacion **no puede olvidarse**,
 * porque `clase` no tiene valor por defecto.
 */
@Composable
fun AvatarDeChat(
    nombre: String,
    url: String?,
    clase: ClaseDeChat,
    tamano: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    AvatarBase(
        nombre = nombre,
        url = url,
        tamano = tamano,
        icono = when (clase) {
            ClaseDeChat.GRUPO -> Icons.Filled.Group
            ClaseDeChat.CANAL -> Icons.Filled.Campaign
            // Un marcador y no mi foto: con mi foto, la nota se confundia con
            // una conversacion con alguien que se llama como yo.
            ClaseDeChat.NOTAS -> Icons.Filled.Bookmark
            // Una directa cae a las iniciales de la persona, que es lo que
            // `Avatar` hace para todo el mundo.
            ClaseDeChat.DIRECTA -> null
        },
        modifier = modifier,
    )
}

/**
 * Foto de perfil de una PERSONA, con las iniciales como respaldo.
 *
 * **No tiene banderas.** Para una conversacion va [AvatarDeChat], que obliga a
 * decir de que clase es porque `clase` no tiene valor por defecto.
 *
 * Marcarlas `internal` no habria alcanzado: toda la app es un solo modulo, asi
 * que `internal` no impide nada desde dentro. La unica forma de que el defecto
 * no vuelva es que la firma no lo permita.
 */
@Composable
fun Avatar(
    nombre: String,
    url: String?,
    tamano: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    AvatarBase(nombre = nombre, url = url, tamano = tamano, icono = null, modifier = modifier)
}

/**
 * El circulo, con una foto, un icono o las iniciales. Privado.
 *
 * `icono` en vez de dos banderas: quien dibuja ya decidio QUE es, y aqui solo
 * queda pintarlo. Un tercer tipo de conversacion se agrega en [ClaseDeChat] y
 * el compilador obliga a mapearlo —el `when` es exhaustivo—, en vez de
 * aparecer como una tercera bandera opcional que nadie pasa.
 */
@Composable
private fun AvatarBase(
    nombre: String,
    url: String?,
    tamano: Dp,
    icono: androidx.compose.ui.graphics.vector.ImageVector?,
    modifier: Modifier = Modifier,
) {
    // El respaldo no es gris: el color sale del propio nombre, asi que cada
    // persona tiene siempre el mismo tono y la lista se lee de un vistazo
    // aunque nadie haya subido foto.
    val fondo = colorDeNombre(nombre)
    Box(
        modifier
            .size(tamano)
            .clip(CircleShape)
            .background(fondo.copy(alpha = 0.22f))
            .border(1.dp, fondo.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else if (icono != null) {
            Icon(icono, null, tint = fondo, modifier = Modifier.size(tamano * 0.51f))
        } else {
            Text(
                iniciales(nombre),
                color = fondo,
                fontWeight = FontWeight.Medium,
                fontSize = (tamano.value * 0.34f).sp,
            )
        }
    }
}

fun iniciales(nombre: String): String {
    val partes = nombre.trim().removePrefix("@").split(" ", "_", ".").filter { it.isNotBlank() }
    return when {
        partes.isEmpty() -> "?"
        partes.size == 1 -> partes[0].take(2).uppercase()
        else -> (partes[0].take(1) + partes[1].take(1)).uppercase()
    }
}

/**
 * Color estable derivado del nombre.
 *
 * Se eligen solo tonos que ya pasaron contraste sobre el fondo oscuro; no se
 * genera un HSL al azar, que es como se terminan colando colores ilegibles.
 */
private val paletaAvatares = listOf(
    Cian,
    Ambar,
    Coral,
    Color(0xFF9BE8A8),
    Color(0xFFB8A7F0),
    Color(0xFF8FD0F5),
)

fun colorDeNombre(nombre: String): Color {
    if (nombre.isEmpty()) return Slate
    val h = nombre.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return paletaAvatares[h % paletaAvatares.size]
}

/** Icono y color del estado de envio. Siempre van juntos: el color solo no basta. */
fun iconoEstado(estado: EstadoEnvio): Pair<ImageVector, Color> = when (estado) {
    EstadoEnvio.PENDIENTE -> Icons.Filled.Schedule to Ambar
    EstadoEnvio.FALLIDO -> Icons.Filled.ErrorOutline to Coral
    EstadoEnvio.ENVIADO -> Icons.Filled.Check to TextoTerciario
    EstadoEnvio.ENTREGADO -> Icons.Filled.DoneAll to TextoTerciario
    EstadoEnvio.LEIDO -> Icons.Filled.DoneAll to Cian
}

fun estadoDe(s: String?): EstadoEnvio =
    runCatching { EstadoEnvio.valueOf(s.orEmpty()) }.getOrDefault(EstadoEnvio.PENDIENTE)

private val fmtHora = SimpleDateFormat("HH:mm", Locale.getDefault())
private val fmtDia = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
/**
 * Solo el dia de la semana: "lunes", "martes"...
 *
 * **Locale fijo en español, no `getDefault()`.** Toda la app esta escrita en
 * español a mano —"Publicar una historia", "Sin mensajes todavia"— asi que
 * sacar el dia del idioma del telefono produce filas mezcladas: en un aparato
 * en ingles la lista decia **"Sunday"** entre textos en español. El idioma de
 * la interfaz lo decide la interfaz, no el sistema, mientras no haya
 * traducciones de verdad.
 *
 * `dd/MM/yy` y `HH:mm` se quedan con el locale del sistema a proposito: ahi no
 * hay palabras, y el orden de dia y mes o el reloj de 12/24 horas SI son
 * preferencias legitimas del aparato.
 */
private val fmtDiaSemana = SimpleDateFormat("EEEE", Locale("es"))
private val fmtCompleta = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

/** Hoy muestra la hora; antes de hoy, la fecha. Como en cualquier mensajeria. */
/**
 * La marca de tiempo de una fila de la lista de chats.
 *
 * ## Por que no es "hora si es hoy, fecha si no"
 *
 * Porque esa version, que es la que habia, escribia **"21/09/26"** para un
 * mensaje de ayer. Una fecha completa obliga a hacer una cuenta —¿que dia es
 * hoy?— para responder algo que se pregunta de un vistazo: *¿esto es reciente?*
 * Y en una lista donde casi todo es de los ultimos dias, casi todas las filas
 * salian con una fecha larga y ninguna decia nada.
 *
 * Cuatro tramos, de mas a menos preciso segun se aleja:
 *
 * | Cuando | Que dice |
 * |---|---|
 * | hoy | `17:12` |
 * | ayer | `Ayer` |
 * | esta semana | `lunes` |
 * | antes | `21/09/26` |
 *
 * La regla de la semana es **por dias de calendario y no por 24 horas**: un
 * mensaje del lunes a las 23:00 sigue siendo "lunes" el martes a las 08:00,
 * aunque no hayan pasado ni doce horas. Contar horas daria "ayer" a algo de
 * hace dos dias segun la hora, que es justo la confusion que esto evita.
 *
 * @param ahora inyectable para poder probarlo; por defecto, el reloj.
 */
fun horaCorta(ms: Long, ahora: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val dias = diasDeDiferencia(ms, ahora)
    return when {
        // Negativo = en el futuro. Pasa con un reloj mal puesto en el otro
        // aparato, y "mañana" en una lista de mensajes recibidos es absurdo:
        // se trata como hoy, que es lo menos raro que se puede decir.
        dias <= 0 -> fmtHora.format(Date(ms))
        dias == 1 -> "Ayer"
        dias < 7 -> fmtDiaSemana.format(Date(ms)).replaceFirstChar { it.uppercase() }
        else -> fmtDia.format(Date(ms))
    }
}

/**
 * Cuantos dias de CALENDARIO hay entre dos instantes.
 *
 * Se compara normalizando a medianoche y no restando milisegundos, porque lo
 * que interesa es el cambio de dia, no el paso de 24 horas.
 */
private fun diasDeDiferencia(ms: Long, ahora: Long): Int {
    fun aMedianoche(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diff = aMedianoche(ahora) - aMedianoche(ms)
    // Se divide sobre el dia normalizado: entre dos medianoches el resultado
    // es exacto salvo por los cambios de horario de verano, y el redondeo
    // los absorbe.
    return Math.round(diff / 86_400_000.0).toInt()
}

fun hora(ms: Long): String = fmtHora.format(Date(ms))

/**
 * Fecha y hora completas.
 *
 * Aqui NO se abrevia como en la lista de chats. En moderacion y en los accesos
 * de la cuenta, "13:04" a secas es inutil: la pregunta siempre es de QUE dia,
 * y un usuario que revisa si entraron a su cuenta necesita el dato entero.
 */
fun fechaLarga(ms: Long): String = if (ms <= 0) "" else fmtCompleta.format(Date(ms))

/** Una entrada del menu de tres puntos. Icono + texto, como en cualquier app. */
@Composable
fun OpcionMenu(
    texto: String,
    icono: ImageVector,
    color: Color = TextoPrimario,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(texto, color = color) },
        leadingIcon = { Icon(icono, null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/**
 * "en linea", "ult. vez hoy 14:05", o nada.
 *
 * Devuelve cadena vacia cuando no hay dato, y ese caso tapa DOS cosas
 * distintas a proposito: que la persona nunca se conecto, y que oculto su
 * ultima conexion. Si la interfaz las distinguiera, "esta persona oculta su
 * presencia" seria en si mismo un dato deducible, que es media filtracion del
 * ajuste que se acaba de respetar.
 */
fun presencia(enLinea: Boolean, ultimaVez: Long): String = when {
    enLinea -> "en linea"
    ultimaVez > 0 -> "ult. vez " + horaCorta(ultimaVez)
    else -> ""
}

/**
 * Un QR, generado en el momento.
 *
 * Vive aqui y no en la pantalla de la huella porque ahora hay dos sitios que
 * lo necesitan -la huella de verificacion y el codigo de vinculacion-, y dos
 * copias del mismo generador es dos sitios donde arreglar el mismo detalle.
 *
 * No se cachea: es un dato derivado y guardarlo solo crearia la posibilidad de
 * mostrar uno viejo, que en un codigo que vive cinco minutos es un fallo de
 * verdad.
 */
@Composable
fun CodigoQr(
    contenido: String,
    tamano: Dp = 190.dp,
    modifier: Modifier = Modifier,
) {
    if (contenido.isBlank()) return
    val bitmap = remember(contenido) {
        runCatching {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                com.google.zxing.EncodeHintType.MARGIN to 1,
            )
            val matriz = com.google.zxing.qrcode.QRCodeWriter()
                .encode(contenido, com.google.zxing.BarcodeFormat.QR_CODE, 420, 420, hints)
            android.graphics.Bitmap.createBitmap(
                matriz.width, matriz.height, android.graphics.Bitmap.Config.ARGB_8888,
            ).apply {
                for (x in 0 until matriz.width) {
                    for (y in 0 until matriz.height) {
                        setPixel(
                            x, y,
                            if (matriz.get(x, y)) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE,
                        )
                    }
                }
            }
        }.getOrNull()
    } ?: return

    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Código QR",
        modifier = modifier
            .size(tamano)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(8.dp),
    )
}

// ============================================================
//  Listas de ajustes agrupadas
// ============================================================

/**
 * Un grupo de ajustes en UNA tarjeta, con titulo encima.
 *
 * ## Por que agrupadas y no una tarjeta por fila
 *
 * El perfil llego a tener siete tarjetas flotando con 12 dp de aire entre cada
 * una. Siete rectangulos iguales separados por el mismo hueco no dicen nada
 * sobre que va con que: la separacion, que deberia ser la que agrupa, estaba
 * repartida en partes iguales entre cosas relacionadas y cosas que no.
 *
 * Con grupos, el aire vuelve a significar algo. "Privacidad" al lado de
 * "Notificaciones" dentro de la misma tarjeta dice que las dos son sobre lo
 * mismo -que sale de tu telefono y hacia quien-, y el hueco siguiente dice que
 * lo de abajo es otro tema.
 *
 * El titulo es opcional porque el ultimo grupo -cerrar sesion- no necesita uno:
 * una sola fila roja no es una categoria.
 */
@Composable
fun SeccionAjustes(
    titulo: String? = null,
    modifier: Modifier = Modifier,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        titulo?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
            )
        }
        Surface(color = BgSurface, shape = RoundedCornerShape(16.dp)) {
            Column(content = contenido)
        }
    }
}

/**
 * Una fila de ajuste: icono en su pastilla, titulo, detalle y flecha.
 *
 * El icono va dentro de un circulo tenue del color del tinte y no suelto. Con
 * el icono suelto, un tinte ambar de aviso se perdia entre los cian: el circulo
 * le da area al color, que es lo que hace que un aviso se note al pasar la
 * vista sin leer.
 *
 * `detalle` es opcional, pero cuando esta dice algo que cambia -"2
 * advertencias", "sin telefono verificado"-, no repite el titulo con otras
 * palabras.
 */
@Composable
fun FilaAjuste(
    icono: ImageVector,
    titulo: String,
    detalle: String? = null,
    tinte: Color = Cian,
    /** La ultima fila de un grupo no lleva divisor debajo. */
    conDivisor: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                // 60 dp de alto util: por encima del minimo de 48 que pide
                // accesibilidad, porque estas filas se tocan con el pulgar en
                // movimiento y no apuntando.
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tinte.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icono, null, tint = tinte, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(titulo, style = MaterialTheme.typography.bodyLarge, color = TextoPrimario)
                detalle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tinte == Ambar || tinte == Coral) tinte else TextoTerciario,
                        fontSize = 12.5.sp,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = Slate,
                modifier = Modifier.size(20.dp),
            )
        }
        // El divisor arranca donde arranca el texto y no en el borde: alineado
        // al texto, la columna de iconos se lee como una sola cosa.
        if (conDivisor) {
            HorizontalDivider(
                color = Slate.copy(alpha = 0.22f),
                modifier = Modifier.padding(start = 61.dp),
            )
        }
    }
}

/**
 * Lo que se dibuja cuando algo no se pudo cargar.
 *
 * ## Por qué existe, y por qué es compartido
 *
 * Porque varias pantallas resolvían el fallo con **una línea de texto gris en
 * una pantalla vacía**, sin icono, sin explicación y sin forma de reintentar.
 * Eso no se lee como "no hay conexión": se lee como que la app está rota. Y
 * cuando el fallo es de red, además es mentira que no se pueda hacer nada: se
 * puede volver a intentar.
 *
 * Tres cosas y ninguna sobra:
 *
 *  - **Un icono**, porque un bloque de texto suelto en medio de la nada no se
 *    distingue de un error de maquetado.
 *  - **Qué pasó y qué se puede hacer**, en ese orden. "No se pudo leer el
 *    estado de la cuenta" dice lo primero y nada de lo segundo.
 *  - **Reintentar**, que es la acción que la persona iba a buscar de todos
 *    modos saliendo y volviendo a entrar.
 *
 * Se anuncia como región viva: aparece después de una espera, cuando el foco
 * ya está en otro sitio, y sin esto un lector de pantalla no diría nada.
 */
@Composable
fun EstadoDeError(
    titulo: String,
    detalle: String,
    onReintentar: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$titulo. $detalle"
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CloudOff, null,
            tint = Slate, modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            titulo,
            color = TextoPrimario,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            detalle,
            color = TextoSecundario,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
        )
        onReintentar?.let {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = it,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cian),
            ) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reintentar")
            }
        }
    }
}

// ------------------------------------------------------------------
//  La foto de perfil, abierta
// ------------------------------------------------------------------

/**
 * La foto de alguien, a pantalla completa y con zoom.
 *
 * ## Por que un diálogo y no una pantalla
 *
 * Igual que el visor de los adjuntos: se abre y se cierra sin tocar la pila de
 * navegación, así que el botón de atrás devuelve al perfil en la posición en
 * la que estaba, y no a la pantalla anterior al perfil.
 *
 * ## El zoom no es un adorno
 *
 * Una foto de perfil se dibuja en un círculo de 112 dp y se recorta a
 * cuadrado. Abrirla sirve justamente para ver lo que ese círculo no muestra:
 * el resto de la imagen, y de cerca. Abrirla al tamaño de la pantalla y nada
 * más sería un círculo un poco más grande.
 *
 * Doble toque vuelve al inicio: con solo pellizcar no hay forma de volver
 * exactamente a 1x, y una foto que quedó torcida y no se deja enderezar se
 * siente rota.
 *
 * @param url de dónde sale. Puede ser `null` cuando la persona no tiene foto,
 *   y en ese caso no se abre nada — no hay foto que mirar, y un visor negro
 *   con un aviso es peor que no reaccionar al toque.
 */
@Composable
fun VisorDeFoto(url: String?, titulo: String, onCerrar: () -> Unit) {
    // Los sitios que lo abren ya comprueban que haya foto; esto es la red por
    // si alguno se olvida. `LaunchedEffect` y no una llamada directa: cerrar
    // es un efecto, y hacerlo DURANTE la composicion es de los errores que se
    // manifiestan como un parpadeo raro meses despues.
    if (url.isNullOrBlank()) {
        LaunchedEffect(Unit) { onCerrar() }
        return
    }

    // Sin `by`: el delegado de Compose choca aqui con el `getValue` de la
    // biblioteca estandar, ya importado en este archivo. `.floatValue` es lo
    // mismo y no depende de que import gane.
    val zoom = remember { mutableFloatStateOf(1f) }
    val desX = remember { mutableFloatStateOf(0f) }
    val desY = remember { mutableFloatStateOf(0f) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCerrar,
        // Sin esto el diálogo se queda con el ancho de un cuadro de diálogo
        // normal y el `fillMaxSize` de adentro solo llena esa caja: el perfil
        // se sigue viendo alrededor y parece a medio abrir.
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, arrastre, escala, _ ->
                        zoom.floatValue = (zoom.floatValue * escala).coerceIn(1f, 5f)
                        // Arrastrar solo tiene sentido con zoom: a 1x la foto
                        // entra entera y moverla la sacaría de la pantalla sin
                        // mostrar nada nuevo.
                        if (zoom.floatValue > 1f) {
                            desX.floatValue += arrastre.x
                            desY.floatValue += arrastre.y
                        } else {
                            desX.floatValue = 0f; desY.floatValue = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoom.floatValue = 1f; desX.floatValue = 0f; desY.floatValue = 0f
                        },
                        onTap = { if (zoom.floatValue <= 1f) onCerrar() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            coil3.compose.SubcomposeAsyncImage(
                model = url,
                contentDescription = "Foto de $titulo",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom.floatValue, scaleY = zoom.floatValue,
                        translationX = desX.floatValue, translationY = desY.floatValue,
                    ),
                // Un visor que no puede abrir la foto tiene que decirlo en vez
                // de quedarse negro: negro y sin texto se lee como que la app
                // se colgó.
                error = { Text("No se pudo abrir la foto", color = TextoSecundario) },
            )

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCerrar) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Cerrar",
                        tint = TextoPrimario,
                    )
                }
                Text(
                    titulo,
                    color = TextoPrimario,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * El estado de conexion, pero con un respiro antes de anunciar una caida.
 *
 * ## El problema que resuelve
 *
 * Al desbloquear el telefono, Android habia suspendido la app y con ella el
 * WebSocket: el estado real pasa a DESCONECTADO, reconecta en un segundo, y
 * vuelve a CONECTADO. Sin esto, ese segundo pinta la barra de "sin conexion" y
 * el usuario ve un parpadeo rojo cada vez que abre la app, que NO significa
 * nada -no hay nada roto, es el reenganche normal-.
 *
 * ## Como
 *
 * CONECTADO se refleja al instante: en cuanto el cable vuelve, la barra
 * desaparece sin demora. Una caida, en cambio, espera [graciaMs] antes de
 * mostrarse; si reconecta dentro de ese margen -el caso de despertar el
 * telefono-, no se llega a ver. Si de verdad no hay red, pasado el margen la
 * barra aparece igual.
 *
 * Arranca optimista en CONECTADO para que, incluso si la pantalla se recrea
 * estando el socket a medio reenganchar, no parpadee en el primer fotograma.
 */
@Composable
fun estadoConGracia(
    real: com.wtfuck.app.datos.EstadoConexion,
    graciaMs: Long = 2500,
): com.wtfuck.app.datos.EstadoConexion {
    val mostrado = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.wtfuck.app.datos.EstadoConexion.CONECTADO)
    }
    LaunchedEffect(real) {
        if (real == com.wtfuck.app.datos.EstadoConexion.CONECTADO) {
            mostrado.value = real
        } else {
            kotlinx.coroutines.delay(graciaMs)
            mostrado.value = real
        }
    }
    return mostrado.value
}
