package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
 * Foto de perfil, con las iniciales como respaldo.
 *
 * El respaldo no es gris: el color sale del propio nombre, asi que cada persona
 * tiene siempre el mismo tono y la lista se lee de un vistazo aunque nadie haya
 * subido foto.
 */
@Composable
fun Avatar(
    nombre: String,
    url: String?,
    tamano: Dp = 48.dp,
    esGrupo: Boolean = false,
    modifier: Modifier = Modifier,
) {
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
        } else if (esGrupo) {
            Icon(Icons.Filled.Group, null, tint = fondo, modifier = Modifier.size(tamano * 0.5f))
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
private val fmtCompleta = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

/** Hoy muestra la hora; antes de hoy, la fecha. Como en cualquier mensajeria. */
fun horaCorta(ms: Long): String {
    if (ms <= 0) return ""
    val hoy = Calendar.getInstance()
    val ese = Calendar.getInstance().apply { timeInMillis = ms }
    val mismoDia = hoy.get(Calendar.YEAR) == ese.get(Calendar.YEAR) &&
        hoy.get(Calendar.DAY_OF_YEAR) == ese.get(Calendar.DAY_OF_YEAR)
    return if (mismoDia) fmtHora.format(Date(ms)) else fmtDia.format(Date(ms))
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
        contentDescription = "Codigo QR",
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
