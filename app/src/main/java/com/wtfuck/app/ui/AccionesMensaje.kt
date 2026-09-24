package com.wtfuck.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.datos.MensajeEnt
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.wtfuck.app.datos.Media
import com.wtfuck.protocol.ClaseHistoria
import com.wtfuck.app.ui.theme.*

/** Reacciones rapidas. Seis: mas que eso ya no se elige, se busca. */
val REACCIONES_RAPIDAS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Menu que sale al mantener pulsado un mensaje.
 *
 * Las opciones visibles dependen del permiso, pero eso es solo cortesia: quien
 * fuerce la peticion recibe un 403 del servidor igual. Ocultar el boton nunca
 * es la medida de seguridad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaAccionesMensaje(
    mensaje: MensajeEnt,
    puedeFijar: Boolean,
    puedeBorrarAjeno: Boolean,
    onCerrar: () -> Unit,
    /**
     * El emoji con el que YO ya reaccione, si hay alguno.
     *
     * Hace falta para dos cosas: marcarlo, y que tocarlo otra vez lo quite en
     * vez de volver a ponerlo. Sin esto la hoja no tenia forma de saber que ya
     * habia reaccionado y siempre mandaba "poner".
     */
    miReaccion: String?,
    onReaccionar: (String, Boolean) -> Unit,
    onResponder: () -> Unit,
    onEditar: () -> Unit,
    onCopiar: () -> Unit,
    onReenviar: () -> Unit,
    /**
     * Guardar en mi coleccion un sticker que me mandaron.
     *
     * Es lo que hace que la funcion sirva para dos personas. Sin esto cada
     * quien solo puede usar los que recorto, y un sticker que llega es un
     * callejon sin salida: se ve una vez y no se puede devolver.
     */
    onGuardarSticker: () -> Unit,
    onFijar: () -> Unit,
    onBorrarParaMi: () -> Unit,
    onReintentar: () -> Unit,
    onRetirar: () -> Unit,
    onDenunciar: () -> Unit,
) {
    var confirmandoBorrado by remember { mutableStateOf(false) }
    val puedeRetirar = mensaje.esMio || puedeBorrarAjeno

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {

            // Fila de reacciones rapidas, arriba: es lo que mas se usa.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // Si ya reaccione con uno de estos, se muestra marcado. La
                // marca no es decoracion: es lo que explica que tocarlo otra
                // vez lo quite.
                (REACCIONES_RAPIDAS + listOfNotNull(
                    miReaccion?.takeIf { it !in REACCIONES_RAPIDAS }
                )).forEach { emoji ->
                    val mia = emoji == miReaccion
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (mia) Cian.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onReaccionar(emoji, !mia) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 26.sp)
                    }
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 6.dp))

            // Un mensaje que no salio: primero lo que la persona quiere hacer,
            // que es volver a intentarlo.
            if (mensaje.estado == "FALLIDO") {
                Opcion("Reintentar envio", Icons.Filled.Refresh, Cian, onReintentar)
                HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 6.dp))
            }

            if (!mensaje.retirado) {
                Opcion("Responder", Icons.AutoMirrored.Filled.Reply, onClick = onResponder)
                Opcion("Copiar", Icons.Filled.ContentCopy, onClick = onCopiar)
                Opcion("Reenviar", Icons.AutoMirrored.Filled.Send, onClick = onReenviar)

                // Solo si es un sticker Y ya esta descargado: guardar uno que
                // todavia no bajo copiaria un archivo que no existe.
                if (mensaje.adjuntoClase == com.wtfuck.protocol.ClaseAdjunto.STICKER &&
                    !mensaje.rutaLocal.isNullOrBlank()
                ) {
                    Opcion(
                        "Guardar en mis stickers",
                        Icons.Filled.AddCircleOutline,
                        onClick = onGuardarSticker,
                    )
                }

                if (mensaje.esMio) {
                    Opcion("Editar", Icons.Filled.Edit, onClick = onEditar)
                }
                if (puedeFijar) {
                    Opcion(
                        if (mensaje.fijado) "Dejar de fijar" else "Fijar",
                        Icons.Filled.PushPin,
                        onClick = onFijar,
                    )
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 6.dp))

            // Denunciar solo aparece en mensajes ajenos: denunciar el propio
            // no significa nada, y el servidor lo rechaza igual.
            if (!mensaje.esMio && !mensaje.retirado) {
                Opcion("Denunciar", Icons.Filled.Flag, Coral, onDenunciar)
            }

            Opcion("Eliminar para mi", Icons.Filled.DeleteOutline, Coral, onBorrarParaMi)
            if (puedeRetirar && !mensaje.retirado) {
                Opcion("Eliminar para todos", Icons.Filled.DeleteOutline, Coral) {
                    confirmandoBorrado = true
                }
            }
        }
    }

    if (confirmandoBorrado) {
        AlertDialog(
            onDismissRequest = { confirmandoBorrado = false },
            containerColor = BgElev,
            title = { Text("Eliminar para todos", color = TextoPrimario) },
            text = {
                Text(
                    if (mensaje.esMio)
                        "El mensaje desaparece para todos los participantes. Quedara el hueco indicando que fue eliminado."
                    else
                        "Vas a eliminar un mensaje de @${mensaje.autor}. Queda registrado en el historial de moderacion.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmandoBorrado = false; onRetirar() }) {
                    Text("Eliminar", color = Coral, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmandoBorrado = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }
}

@Composable
private fun Opcion(
    texto: String,
    icono: ImageVector,
    color: Color = TextoPrimario,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(18.dp))
        Text(texto, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/**
 * Cita dentro de una burbuja.
 *
 * La barra lateral usa el color del autor citado, el mismo que su avatar: sirve
 * para reconocer de un vistazo a quien se le responde.
 */
@Composable
fun CitaMensaje(autor: String, texto: String, sobreAcento: Boolean) {
    val color = if (sobreAcento) TextoSobreAcento else colorDeNombre(autor)
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (sobreAcento) TextoSobreAcento.copy(alpha = 0.12f) else BgBase.copy(alpha = 0.5f))
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "@$autor",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                texto,
                color = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.8f) else TextoSecundario,
                fontSize = 13.sp,
                maxLines = 2,
            )
        }
    }
}

/**
 * La historia a la que contesta un mensaje.
 *
 * ## Por que no es la misma pieza que [CitaMensaje]
 *
 * Una cita de mensaje se apoya en algo que sigue en el chat; esta se apoya en
 * algo que **ya no existe** —una historia dura 24 horas—. Por eso lleva la
 * miniatura pegada y lo dice con todas las letras ("Respuesta a tu historia"):
 * al dia siguiente esa frase y esa miniatura son lo unico que queda para
 * entender de que hablaba el mensaje.
 *
 * El texto y la miniatura vienen de un sobre ajeno. El texto ya llego recortado
 * y la miniatura se decodifica con `miniaturaAjena`, que mira las dimensiones
 * antes de reservar memoria.
 */
@Composable
fun CitaHistoriaEnBurbuja(m: MensajeEnt, sobreAcento: Boolean) {
    val color = if (sobreAcento) TextoSobreAcento else Cian
    val mini = remember(m.citaHistoriaMiniatura) {
        Media.deBase64(m.citaHistoriaMiniatura)?.let { Media.miniaturaAjena(it)?.asImageBitmap() }
    }

    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (sobreAcento) TextoSobreAcento.copy(alpha = 0.12f) else BgBase.copy(alpha = 0.5f))
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))

        if (mini != null) {
            Image(
                mini, null,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(8.dp))
        }

        Column {
            Text(
                // "Tu historia" cuando la burbuja es de la otra persona: quien
                // lee es el autor. Al reves, es la de quien tiene delante.
                if (m.esMio) "Respuesta a su historia" else "Respuesta a tu historia",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                m.citaHistoriaTexto.ifBlank {
                    when (m.citaHistoriaClase) {
                        ClaseHistoria.IMAGEN -> "Foto"
                        ClaseHistoria.VIDEO -> "Video"
                        else -> ""
                    }
                },
                color = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.8f) else TextoSecundario,
                fontSize = 13.sp,
                maxLines = 2,
            )
        }
    }
}

/** Fila de reacciones bajo la burbuja. Ya vienen contadas del servidor. */
@Composable
fun FilaReacciones(
    reacciones: List<Pair<String, Pair<Int, Boolean>>>,
    onToca: (String, Boolean) -> Unit,
) {
    if (reacciones.isEmpty()) return
    Row(
        Modifier.padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reacciones.forEach { (emoji, datos) ->
            val (total, mia) = datos
            Row(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (mia) Cian.copy(alpha = 0.25f) else BgElev)
                    // §15 · `toggleable` y no `clickable`: el lector dice
                    // "casilla, puesta" en vez de "boton", que es lo que esto
                    // es de verdad. La descripcion lleva la cuenta y si la
                    // propia esta puesta, que a la vista lo dice el fondo cian.
                    .toggleable(
                        value = mia,
                        role = Role.Checkbox,
                        onValueChange = { onToca(emoji, it) },
                    )
                    .semantics(mergeDescendants = true) { reaccion(emoji, total, mia) }
                    // La pastilla se DIBUJA chica —una fila de reacciones de
                    // 48 dp de alto debajo de cada burbuja es un muro— pero el
                    // area que responde al dedo llega a 48. Estaba en 32, y
                    // solo se vio midiendo el volcado del arbol.
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 13.sp)
                if (total > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        total.toString(),
                        fontSize = 11.sp,
                        color = if (mia) Cian else TextoSecundario,
                    )
                }
            }
        }
    }
}
