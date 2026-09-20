package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.ui.theme.*

/** Una opcion de la hoja de adjuntar. */
private data class OpcionAdjunto(
    val etiqueta: String,
    val icono: ImageVector,
    val color: Color,
    val accion: () -> Unit,
)

/**
 * Hoja de adjuntar, al estilo de WhatsApp.
 *
 * Cada clase de archivo tiene su color y su icono porque la persona apunta por
 * forma y no por texto: despues de dos usos ya toca "el circulo cian de la
 * izquierda" sin leer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaAdjuntar(
    onGaleria: () -> Unit,
    onDocumento: () -> Unit,
    onNotaVoz: () -> Unit,
    onSticker: () -> Unit,
    onUbicacion: () -> Unit,
    onContacto: () -> Unit,
    onEncuesta: () -> Unit,
    onEvento: () -> Unit,
    /**
     * Las encuestas y los eventos piden permiso propio, y NO el mismo: el rol
     * `miembro` trae `encuesta.crear` pero no `evento.crear`, porque un evento
     * le pide asistencia a todo el grupo. Por eso son dos banderas y no una.
     */
    puedeEncuesta: Boolean,
    puedeEvento: Boolean,
    onCerrar: () -> Unit,
) {
    val opciones = listOfNotNull(
        OpcionAdjunto("Galeria", Icons.Filled.Image, Cian, onGaleria),
        OpcionAdjunto("Documento", Icons.Filled.InsertDriveFile, Ambar, onDocumento),
        OpcionAdjunto("Nota de voz", Icons.Filled.Mic, Coral, onNotaVoz),
        OpcionAdjunto("Sticker o GIF", Icons.Filled.EmojiEmotions, Cian, onSticker),
        OpcionAdjunto("Ubicacion", Icons.Filled.Place, Cian, onUbicacion),
        OpcionAdjunto("Contacto", Icons.Filled.Person, Ambar, onContacto),
        // Se esconden si no tengo el permiso, pero eso es solo cortesia: quien
        // fuerce la peticion recibe un 403 del servidor igual. Ver
        // `Mensajes.registrar`.
        OpcionAdjunto("Encuesta", Icons.Filled.Poll, Coral, onEncuesta)
            .takeIf { puedeEncuesta },
        OpcionAdjunto("Evento", Icons.Filled.Event, Cian, onEvento)
            .takeIf { puedeEvento },
    )

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Text(
            "Adjuntar",
            style = MaterialTheme.typography.titleMedium,
            color = TextoPrimario,
            modifier = Modifier.padding(start = 22.dp, bottom = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            items(opciones) { o ->
                Column(
                    Modifier
                        .clickable { onCerrar(); o.accion() }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(o.color.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(o.icono, null, tint = o.color, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(o.etiqueta, fontSize = 11.sp, color = TextoSecundario)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ------------------------------------------------------------------
//  Emojis
// ------------------------------------------------------------------

/**
 * Selector de emojis, sin dependencias.
 *
 * El teclado del sistema ya tiene emojis; esto existe para lo que el teclado no
 * da: tocar tres seguidos sin cambiar de modo, y tener a mano los que de verdad
 * se usan. La lista es fija a proposito -una libreria de emojis pesa varios MB
 * y aporta los que nadie manda.
 */
private val GRUPOS_EMOJI: List<Pair<String, List<String>>> = listOf(
    "Caras" to (
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 " +
            "😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 " +
            "😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 " +
            "😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 " +
            "😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👹 👻 👽 🤖"
        ).split(" ")
    ,
    "Gestos" to (
        "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 " +
            "🤝 🙏 ✍️ 💪 🦾 🙌 👏 🫡 🫢 🫣 🤦 🤷 💁 🙋 🙆 🙅 💅 🤳"
        ).split(" ")
    ,
    "Corazones" to (
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ 💫 " +
            "⭐ 🌟 🔥 💥 💯 ✅ ❌ ⚠️ ❓ ❗"
        ).split(" ")
    ,
    "Cosas" to (
        "🎉 🎊 🎁 🎂 🍰 ☕ 🍵 🍺 🍻 🥂 🍕 🍔 🍟 🌮 🍿 🍎 🍌 🍇 🍉 🥑 " +
            "⚽ 🏀 🎮 🎧 🎸 🎤 📱 💻 ⌨️ 🖥️ 📷 🔒 🔑 💰 📈 📉 ⏰ 📌 📎 ✂️ " +
            "🚗 ✈️ 🚀 🏠 🌍 ☀️ 🌙 ☁️ 🌧️ ⛈️ 🌈 ❄️ 🐶 🐱 🐭 🦊 🐻 🐼 🦁 🐷"
        ).split(" ")
    ,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectorEmoji(onElegir: (String) -> Unit, onCerrar: () -> Unit) {
    var grupo by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        ScrollableTabRow(
            selectedTabIndex = grupo,
            containerColor = BgElev,
            contentColor = Cian,
            edgePadding = 12.dp,
        ) {
            GRUPOS_EMOJI.forEachIndexed { i, (nombre, _) ->
                Tab(
                    selected = grupo == i,
                    onClick = { grupo = i },
                    text = {
                        Text(
                            nombre,
                            fontSize = 13.sp,
                            fontWeight = if (grupo == i) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (grupo == i) Cian else TextoSecundario,
                        )
                    },
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 8.dp),
        ) {
            items(GRUPOS_EMOJI[grupo].second) { e ->
                Box(
                    Modifier
                        .padding(2.dp)
                        .clip(CircleShape)
                        // La hoja NO se cierra al elegir: mandar tres emojis
                        // seguidos es lo normal, y cerrarla obligaria a abrirla
                        // otra vez cada vez.
                        .clickable { onElegir(e) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e, fontSize = 25.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}
