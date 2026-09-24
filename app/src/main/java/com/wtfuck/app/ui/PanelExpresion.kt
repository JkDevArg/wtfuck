package com.wtfuck.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.PaintCompat
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.Emoji
import com.wtfuck.app.datos.GRUPOS_EMOJI
import com.wtfuck.app.datos.SOPORTAN_TONO
import com.wtfuck.app.datos.StickerEnt
import com.wtfuck.app.datos.TONOS
import com.wtfuck.app.datos.TONO_POR_DEFECTO
import com.wtfuck.app.datos.aplicarTono
import com.wtfuck.app.datos.buscarEmojis
import com.wtfuck.app.datos.catalogo
import com.wtfuck.app.datos.quitarTono
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

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
 * Módulo Z.1/Z.2/Z.3 · La rejilla de emojis.
 *
 * ## Lo que le faltaba, y por qué importa cada cosa
 *
 * Eran cuatro grupos fijos y nada más. Tres huecos, en orden de cuánto
 * estorban:
 *
 *  - **No había Recientes.** Es la primera pestaña de cualquier selector de
 *    emojis que exista, y no por costumbre: casi todo el mundo usa los mismos
 *    diez. Sin ella, mandar 😂 por vigésima vez cuesta lo mismo que la primera.
 *  - **No se podía buscar.** Con 250 emojis, encontrar 🥑 es recorrer cuatro
 *    grupos con el pulgar. Buscar es lo que convierte un catálogo en algo
 *    usable, y es exactamente lo que se rompe cuando el glifo y sus palabras
 *    viven en archivos distintos. Por eso están juntos, en `Emojis.kt`.
 *  - **No había tono de piel.** 👍 sale amarillo y no hay forma de cambiarlo.
 *    Es la única parte de esta pantalla donde el valor por defecto le queda
 *    mal a mucha gente a propósito, y arreglarlo es mantener pulsado.
 *
 * ## Por qué el tono se elige una vez y vale para todos
 *
 * Porque nadie tiene una mano de cada color. Elegirlo emoji por emoji sería
 * pedir la misma respuesta treinta y seis veces. Se pregunta al mantener
 * pulsado cualquiera que lo admita y se aplica a todos.
 */
@Composable
private fun RejillaEmojis(onElegir: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var grupo by rememberSaveable { mutableIntStateOf(0) }
    var consulta by rememberSaveable { mutableStateOf("") }
    var eligiendoTono by remember { mutableStateOf<String?>(null) }

    val recientes by app.repo.emojisRecientes.collectAsStateWithLifecycle(emptyList())
    val tono by app.repo.tonoDePiel.collectAsStateWithLifecycle(TONO_POR_DEFECTO)

    /** Elegir = ponerlo en el texto y anotarlo. Con el tono ya aplicado. */
    fun elegir(glifo: String) {
        val conTono = aplicarTono(glifo, tono)
        onElegir(conTono)
        ambito.launch { app.repo.usarEmoji(conTono) }
    }

    /**
     * Las pestañas visibles.
     *
     * Recientes va **primero y sólo si hay algo**. Una pestaña vacía en la
     * primera posición es lo que hacía que la bandeja de stickers abriera en
     * "todavía no mandaste ninguno" teniendo stickers; el mismo error no se
     * repite aquí.
     */
    val pestanas = remember(recientes) {
        buildList {
            if (recientes.isNotEmpty()) add("Recientes" to recientes.map { Emoji(it, emptyList()) })
            addAll(GRUPOS_EMOJI)
        }
    }
    // Si Recientes aparece o desaparece, el índice guardado apunta a otro
    // grupo. Se acota en vez de reventar.
    val idx = grupo.coerceIn(0, (pestanas.size - 1).coerceAtLeast(0))

    val resultados = remember(consulta) {
        if (consulta.isBlank()) emptyList() else buscarEmojis(consulta, catalogo())
    }
    val buscando = consulta.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        // --- buscar -------------------------------------------------
        OutlinedTextField(
            value = consulta,
            onValueChange = { consulta = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Buscar emoji", fontSize = 13.sp, color = TextoTerciario) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoTerciario, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (buscando) IconButton(onClick = { consulta = "" }) {
                    Icon(Icons.Filled.Close, "Borrar la búsqueda", tint = TextoTerciario, modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian,
                unfocusedBorderColor = Slate.copy(alpha = 0.4f),
                focusedTextColor = TextoPrimario,
                unfocusedTextColor = TextoPrimario,
            ),
        )

        if (buscando) {
            if (resultados.isEmpty()) {
                // Un vacío que dice qué pasó. "No hay resultados" a secas deja
                // a la persona sin saber si buscó mal o si el emoji no está.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Ningún emoji se llama \"$consulta\"",
                        color = TextoTerciario, fontSize = 13.sp,
                    )
                }
            } else {
                CeldasEmoji(resultados.map { it.glifo }, tono, ::elegir) { eligiendoTono = it }
            }
        } else {
            ScrollableTabRow(
                selectedTabIndex = idx,
                containerColor = BgElev,
                contentColor = Cian,
                edgePadding = 12.dp,
            ) {
                pestanas.forEachIndexed { i, (nombre, _) ->
                    Tab(
                        selected = idx == i,
                        onClick = { grupo = i },
                        text = {
                            Text(
                                nombre,
                                fontSize = 12.5.sp,
                                fontWeight = if (idx == i) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (idx == i) Cian else TextoSecundario,
                            )
                        },
                    )
                }
            }
            CeldasEmoji(pestanas[idx].second.map { it.glifo }, tono, ::elegir) { eligiendoTono = it }
        }
    }

    eligiendoTono?.let { glifo ->
        HojaTonoDePiel(
            glifo = glifo,
            elegido = tono,
            onElegir = { t ->
                ambito.launch { app.repo.ponerTonoDePiel(t) }
                eligiendoTono = null
            },
            onCerrar = { eligiendoTono = null },
        )
    }
}

/**
 * Módulo Z.3 · Si **esta** fuente sabe dibujar el emoji con tono.
 *
 * ## Por qué no alcanza con la lista de Unicode
 *
 * Porque son dos preguntas distintas y yo las confundí. Unicode define qué
 * secuencias existen; la fuente del aparato decide cuáles sabe dibujar. Una
 * lista escrita a mano contesta la primera y **aparenta** contestar las dos.
 *
 * Puse tres caras en la lista de los que admiten tono y el resultado fue una
 * carita amarilla con un rectángulo de color suelto debajo. El código hacía lo
 * que le pedí; lo que estaba mal era lo que le pedí. Eso se arregla borrando
 * las tres, y el error de fondo —confiar en una lista escrita a mano— se
 * arregla preguntándole a quien sabe.
 *
 * `hasGlyph` responde por la secuencia completa, así que si la fuente no la
 * tiene no se ofrece el tono y nunca se dibuja algo roto. Cachea porque se
 * llama por celda y por fotograma.
 */
private val pinturaDePrueba = android.graphics.Paint()
private val cacheTono = HashMap<String, Boolean>()

private fun admiteTonoAqui(glifo: String): Boolean {
    val base = quitarTono(glifo)
    if (base !in SOPORTAN_TONO) return false
    return cacheTono.getOrPut(base) {
        // Se exigen TODOS: ofrecer seis opciones de las que dos no se dibujan
        // es peor que no ofrecer ninguna.
        TONOS.all { t ->
            runCatching {
                PaintCompat.hasGlyph(pinturaDePrueba, aplicarTono(base, t))
            }.getOrDefault(false)
        }
    }
}

/**
 * La cuadrícula.
 *
 * Los de Recientes **ya vienen con su tono** —se guardaron así— y volver a
 * aplicárselo no los cambia: `aplicarTono` quita el que hubiera antes de poner
 * el nuevo, así que es idempotente. Sin eso, cambiar de tono dejaría en
 * Recientes glifos con dos modificadores pegados.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CeldasEmoji(
    glifos: List<String>,
    tono: String,
    onElegir: (String) -> Unit,
    onPedirTono: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(46.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
    ) {
        items(glifos, key = { it }) { e ->
            val conTono = aplicarTono(e, tono)
            val admite = admiteTonoAqui(e)
            Box(
                Modifier
                    .padding(2.dp)
                    .clip(CircleShape)
                    // No se cierra al elegir: mandar tres emojis seguidos
                    // es lo normal.
                    .combinedClickable(
                        onClick = { onElegir(e) },
                        // Mantener pulsado sólo hace algo donde hay algo que
                        // elegir. Un menú que se abre vacío enseña a no usarlo.
                        onLongClick = if (admite) ({ onPedirTono(e) }) else null,
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(conTono, fontSize = 25.sp)
            }
        }
    }
}

/**
 * Las seis opciones de tono.
 *
 * Se muestran sobre el emoji que se mantuvo pulsado y no sobre un 👍 genérico:
 * así se ve cómo va a quedar el que se estaba por mandar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaTonoDePiel(
    glifo: String,
    elegido: String,
    onElegir: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Tono de piel", color = TextoPrimario, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Se aplica a todos los emojis que lo admiten.",
                color = TextoTerciario, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (listOf(TONO_POR_DEFECTO) + TONOS).forEach { t ->
                    val activo = t == elegido
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (activo) Cian.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onElegir(t) }
                            .semantics {
                                contentDescription =
                                    if (t.isEmpty()) "Tono por defecto" else "Tono de piel"
                                selected = activo
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(aplicarTono(glifo, t), fontSize = 26.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
