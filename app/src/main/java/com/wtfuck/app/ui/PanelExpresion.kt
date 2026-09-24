package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.datos.StickerEnt
import com.wtfuck.app.ui.theme.*

/**
 * Módulo Y.3 · Un solo panel con emojis, GIFs y stickers.
 *
 * ## Qué estaba mal
 *
 * Los GIFs y los stickers compartían hoja —el botón se llamaba "Sticker o
 * GIF"— y los emojis vivían en otra distinta, abierta desde otro botón. Son
 * **tres cosas del mismo gesto**: "quiero poner algo que no es texto". Que
 * cambiar de emojis a stickers obligue a cerrar una hoja y abrir otra convierte
 * una decisión en dos.
 *
 * Y meter GIFs y stickers juntos era peor que arbitrario: un GIF viene de un
 * buscador de fuera y un sticker es de la colección propia. Se buscan de forma
 * distinta y no se mezclan en ninguna cabeza.
 *
 * ## La forma
 *
 * Tres pestañas en una **barra de abajo**, que es donde las ponen las apps que
 * el usuario tomó de referencia y donde cae el pulgar. El contenido vive
 * arriba, con alto fijo: un panel que cambia de altura al cambiar de pestaña
 * mueve el chat entero por debajo.
 *
 * El panel **no se cierra al elegir**. Mandar tres emojis seguidos, o dos
 * stickers, es lo normal; cerrarse obligaría a reabrirlo cada vez.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelExpresion(
    /** Con qué pestaña abre. La de adjuntar entra por stickers; el botón de cara, por emojis. */
    inicial: String = PANEL_EMOJIS,
    onEmoji: (String) -> Unit,
    onGif: (String) -> Unit,
    onSticker: (StickerEnt) -> Unit,
    onCerrar: () -> Unit,
) {
    var pestana by rememberSaveable { mutableStateOf(inicial) }

    ModalBottomSheet(
        // Entero: el panel tiene alto propio y a media altura se corta la
        // barra de pestañas, que es lo único con lo que se navega. Ver el
        // módulo W.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            // --- el contenido, con alto fijo -------------------------
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                when (pestana) {
                    PANEL_GIFS -> PanelGifs(
                        onElegirGif = { onCerrar(); onGif(it) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    PANEL_STICKERS -> BandejaStickers(
                        onEnviar = { onCerrar(); onSticker(it) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> RejillaEmojis(onElegir = onEmoji)
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.22f))

            // --- las tres pestañas, abajo ----------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BgSurface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PestanaPanel(
                    "Emojis", pestana == PANEL_EMOJIS, Modifier.weight(1f),
                    icono = Icons.Filled.EmojiEmotions,
                ) { pestana = PANEL_EMOJIS }
                PestanaPanel(
                    "GIFs", pestana == PANEL_GIFS, Modifier.weight(1f),
                    icono = Icons.Filled.Gif,
                ) { pestana = PANEL_GIFS }
                PestanaPanel(
                    "Stickers", pestana == PANEL_STICKERS, Modifier.weight(1f),
                    icono = Icons.Filled.EmojiEmotions,
                ) { pestana = PANEL_STICKERS }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

const val PANEL_EMOJIS = "emojis"
const val PANEL_GIFS = "gifs"
const val PANEL_STICKERS = "stickers"

/**
 * Una de las tres pestañas de abajo.
 *
 * Icono **y** texto, no sólo icono: "GIF" y "sticker" no tienen un símbolo que
 * todo el mundo reconozca —el de emoji sí—, y dos caritas distintas al lado no
 * distinguen nada. La palabra ocupa poco y no se puede confundir.
 */
@Composable
private fun PestanaPanel(
    texto: String,
    activa: Boolean,
    modifier: Modifier = Modifier,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = texto
                selected = activa
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icono, null,
            tint = if (activa) Cian else TextoTerciario,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            texto,
            color = if (activa) Cian else TextoTerciario,
            fontSize = 11.sp,
            fontWeight = if (activa) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * Los emojis, ahora dentro del panel en vez de en su propia hoja.
 *
 * Es el mismo contenido que tenía `SelectorEmoji`: cambia de dónde cuelga, no
 * lo que hace.
 */
@Composable
private fun RejillaEmojis(onElegir: (String) -> Unit) {
    var grupo by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
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
                            fontSize = 12.5.sp,
                            fontWeight = if (grupo == i) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (grupo == i) Cian else TextoSecundario,
                        )
                    },
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            items(GRUPOS_EMOJI[grupo].second) { e ->
                Box(
                    Modifier
                        .padding(2.dp)
                        .clip(CircleShape)
                        // No se cierra al elegir: mandar tres emojis seguidos
                        // es lo normal.
                        .clickable { onElegir(e) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e, fontSize = 25.sp)
                }
            }
        }
    }
}

/**
 * Los emojis, por grupos.
 *
 * Vive aqui y ya no en `HojaAdjuntar`: la hoja que los usaba se fue, absorbida
 * por el panel de tres pestañas, y una lista de datos sin nadie que la lea es
 * lo primero que alguien borra creyendo que sobra. De hecho casi pasa: al
 * quitar `SelectorEmoji` se fue con el.
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
