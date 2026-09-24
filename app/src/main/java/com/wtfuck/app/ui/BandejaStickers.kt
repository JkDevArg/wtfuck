package com.wtfuck.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.PackEnt
import com.wtfuck.app.datos.StickerEnt
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Módulo Y.2 · La bandeja de stickers propios.
 *
 * ## Qué faltaba
 *
 * La primera versión listaba los archivos de una carpeta en una tira. Eso
 * alcanza para diez stickers y deja de servir en cuanto hay treinta: no había
 * forma de agrupar, ni de marcar los que se usan siempre, ni de encontrar uno
 * sin recorrerlos todos.
 *
 * Lo que hay ahora son las cuatro cosas que hacen usable una colección, y
 * ninguna es decorativa:
 *
 *  - **Recientes**, automático. Es de dónde sale el 90 % de los envíos: casi
 *    nadie manda un sticker una sola vez.
 *  - **Favoritos**, manual. Los que uno quiere tener a mano aunque hace una
 *    semana que no los usa — y por eso no puede ser lo mismo que "recientes".
 *  - **Packs**, para agrupar. Un sticker no tiene nombre, así que lo único que
 *    lo ubica es de dónde salió.
 *  - **Emoji**, para buscar. Es la única etiqueta que se le puede poner a algo
 *    que no tiene nombre.
 *
 * ## Por qué las pestañas son iconos y no texto
 *
 * Porque una fila de pestañas con "Recientes · Favoritos · Los del viaje ·
 * Oficina · Perros" no cabe, y truncada no se lee. El icono fija las dos
 * primeras —reloj y estrella, que no hace falta explicar— y deja el ancho para
 * los nombres de los packs, que sí son texto porque son de quien los puso.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BandejaStickers(
    onEnviar: (StickerEnt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    val todos by app.repo.stickers.collectAsStateWithLifecycle(emptyList())
    val packs by app.repo.packsDeStickers.collectAsStateWithLifecycle(emptyList())
    val recientes by app.repo.stickersRecientes.collectAsStateWithLifecycle(emptyList())

    // Adopta los archivos de la versión anterior. Idempotente y una sola vez
    // por apertura: sin esto, quien ya tenía stickers vería la bandeja vacía.
    LaunchedEffect(Unit) { app.repo.adoptarStickersSueltos() }

    /**
     * La pestaña abierta.
     *
     * Arranca en **Todos** y no en Recientes, y eso se decidió probándolo: con
     * Recientes por defecto, quien acaba de crear su primer sticker abre la
     * bandeja y ve "todavía no mandaste ninguno" **teniendo uno**. La primera
     * pantalla no puede estar vacía cuando hay contenido.
     */
    var pestana by rememberSaveable { mutableStateOf(TAB_TODOS) }
    var creando by remember { mutableStateOf<Uri?>(null) }
    var acciones by remember { mutableStateOf<StickerEnt?>(null) }
    var nuevoPack by remember { mutableStateOf(false) }
    var busqueda by remember { mutableStateOf("") }

    val elegirFoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) creando = uri }

    // Qué se ve, según la pestaña y lo buscado.
    val visibles = remember(todos, recientes, pestana, busqueda) {
        val base = when (pestana) {
            TAB_TODOS -> todos
            TAB_RECIENTES -> recientes
            TAB_FAVORITOS -> todos.filter { it.favorito }
            else -> todos.filter { it.packId == pestana }
        }
        if (busqueda.isBlank()) base
        else todos.filter { it.emoji.isNotBlank() && it.emoji.contains(busqueda) }
    }

    Column(modifier) {
        // --- pestañas ------------------------------------------------
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // **Todos existe por necesidad, no por completismo.** Un
                // sticker sin pack y sin usar no aparecía en ninguna pestaña:
                // ni en recientes -nunca se mandó-, ni en favoritos -no se
                // marcó-, ni en ningún pack -no está en ninguno-. Lo descubrí
                // creando un pack y viendo desaparecer el único que había.
                PestanaIcono(
                    Icons.Filled.GridView, "Todos",
                    pestana == TAB_TODOS,
                ) { pestana = TAB_TODOS }
            }
            item {
                PestanaIcono(
                    Icons.Filled.History, "Recientes",
                    pestana == TAB_RECIENTES,
                ) { pestana = TAB_RECIENTES }
            }
            item {
                PestanaIcono(
                    Icons.Filled.Star, "Favoritos",
                    pestana == TAB_FAVORITOS,
                ) { pestana = TAB_FAVORITOS }
            }
            items(packs, key = { it.id }) { p ->
                PestanaTexto(p.nombre, pestana == p.id) { pestana = p.id }
            }
            item {
                PestanaIcono(
                    Icons.Filled.Folder, "Nuevo pack", false,
                ) { nuevoPack = true }
            }
        }

        // --- buscar por emoji ----------------------------------------
        if (todos.any { it.emoji.isNotBlank() }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.EmojiEmotions, null,
                    tint = TextoTerciario, modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        todos.mapNotNull { it.emoji.ifBlank { null } }.distinct(),
                        key = { it },
                    ) { e ->
                        Text(
                            e,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (busqueda == e) Cian.copy(alpha = 0.2f) else BgSurface)
                                .clickable { busqueda = if (busqueda == e) "" else e }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .semantics {
                                    contentDescription =
                                        if (busqueda == e) "Quitar el filtro $e" else "Filtrar por $e"
                                },
                        )
                    }
                }
            }
        }

        // --- la rejilla ----------------------------------------------
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            when {
                todos.isEmpty() -> VacioDeStickers(
                    "Todavía no tenés ninguno.",
                    "Elegí una foto y recortala: queda guardada acá para volver a usarla. " +
                        "Si elegís un GIF o un WebP animado, se conserva el movimiento.",
                )

                visibles.isEmpty() && busqueda.isNotBlank() -> VacioDeStickers(
                    "Ninguno con $busqueda",
                    "Mantené pulsado un sticker para ponerle un emoji.",
                )

                visibles.isEmpty() && pestana == TAB_TODOS -> VacioDeStickers(
                    "Todavía no tenés ninguno.",
                    "Creá uno de una foto o guardá los que te manden.",
                )

                visibles.isEmpty() && pestana == TAB_RECIENTES -> VacioDeStickers(
                    "Todavía no mandaste ninguno",
                    "Los que uses van a aparecer acá.",
                )

                visibles.isEmpty() && pestana == TAB_FAVORITOS -> VacioDeStickers(
                    "Sin favoritos",
                    "Mantené pulsado un sticker para marcarlo con la estrella.",
                )

                visibles.isEmpty() -> VacioDeStickers(
                    "Este pack está vacío",
                    "Mantené pulsado un sticker para moverlo acá.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(82.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visibles, key = { it.id }) { s ->
                        CeldaSticker(
                            s = s,
                            onEnviar = {
                                ambito.launch { app.repo.usarSticker(s.id) }
                                onEnviar(s)
                            },
                            onMantener = { acciones = s },
                        )
                    }
                }
            }
        }

        // --- crear ---------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    elegirFoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            ) {
                Icon(Icons.Filled.Add, null, tint = Cian, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Crear de una foto o un GIF", color = Cian, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            if (todos.isNotEmpty()) {
                Text("${todos.size}", color = TextoTerciario, fontSize = 12.sp)
            }
        }
    }

    creando?.let { uri ->
        HojaCrearSticker(
            uri = uri,
            // Se crea dentro del pack abierto, si hay uno. Las tres pestañas
            // virtuales no son packs: crear "dentro de Favoritos" no significa
            // nada, así que el sticker sale suelto.
            packDestino = pestana
                .takeIf { it != TAB_TODOS && it != TAB_RECIENTES && it != TAB_FAVORITOS }
                .orEmpty(),
            onListo = { creando = null },
            onCerrar = { creando = null },
        )
    }

    acciones?.let { s ->
        HojaAccionesSticker(
            s = s,
            packs = packs,
            onCerrar = { acciones = null },
            onFavorito = { ambito.launch { app.repo.favoritoSticker(s.id, !s.favorito) } },
            onEmoji = { e -> ambito.launch { app.repo.emojiSticker(s.id, e) } },
            onMover = { p -> ambito.launch { app.repo.moverSticker(s.id, p) } },
            onBorrar = { ambito.launch { app.repo.borrarSticker(s) } },
        )
    }

    if (nuevoPack) {
        DialogoNombre(
            titulo = "Nuevo pack",
            detalle = "Un nombre para agrupar los que van juntos.",
            onCerrar = { nuevoPack = false },
            onListo = { n ->
                nuevoPack = false
                ambito.launch { pestana = app.repo.crearPack(n) }
            },
        )
    }
}

internal const val TAB_TODOS = "__todos"
internal const val TAB_RECIENTES = "__recientes"
internal const val TAB_FAVORITOS = "__favoritos"

/**
 * Una celda de la rejilla.
 *
 * Se dibuja con Coil y **no con `BitmapFactory`**, y ésa es la diferencia
 * entre un sticker animado y su primer fotograma: el `ImageLoader` de la app
 * ya trae el decodificador de animaciones, y decodificar a mano lo esquiva.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CeldaSticker(s: StickerEnt, onEnviar: () -> Unit, onMantener: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .combinedClickable(onClick = onEnviar, onLongClick = onMantener)
            .semantics {
                contentDescription = buildString {
                    append("Sticker")
                    if (s.emoji.isNotBlank()) append(" ${s.emoji}")
                    if (s.animado) append(", con movimiento")
                    if (s.favorito) append(", favorito")
                    append(". Mantené pulsado para más opciones.")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(File(s.archivo))
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(7.dp),
        )
        // La estrella y el símbolo de movimiento van encima y chiquitos: son
        // marcas sobre el sticker, no información al lado de él.
        if (s.favorito) {
            Icon(
                Icons.Filled.Star, null,
                tint = Ambar,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(13.dp),
            )
        }
        if (s.animado) {
            Icon(
                Icons.Filled.PlayArrow, null,
                tint = TextoTerciario,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(13.dp),
            )
        }
    }
}

@Composable
private fun PestanaIcono(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    etiqueta: String,
    activa: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (activa) Cian.copy(alpha = 0.18f) else BgSurface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = etiqueta },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icono, null,
            tint = if (activa) Cian else TextoSecundario,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun PestanaTexto(nombre: String, activa: Boolean, onClick: () -> Unit) {
    Text(
        nombre,
        color = if (activa) Cian else TextoSecundario,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(19.dp))
            .background(if (activa) Cian.copy(alpha = 0.18f) else BgSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    )
}

@Composable
private fun VacioDeStickers(titulo: String, detalle: String) {
    Column(
        Modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.EmojiEmotions, null, tint = Slate, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(titulo, color = TextoSecundario, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            detalle,
            color = TextoTerciario,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Lo que se puede hacer con un sticker: marcarlo, etiquetarlo, moverlo, tirarlo.
 *
 * Todo detrás de mantener pulsado, y no con iconos sobre cada miniatura: en
 * una rejilla de treinta, un botón por celda son treinta botones que se tocan
 * sin querer justo cuando uno quería mandar el sticker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaAccionesSticker(
    s: StickerEnt,
    packs: List<PackEnt>,
    onCerrar: () -> Unit,
    onFavorito: () -> Unit,
    onEmoji: (String) -> Unit,
    onMover: (String) -> Unit,
    onBorrar: () -> Unit,
) {
    var confirmando by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = File(s.archivo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (s.animado) "Sticker con movimiento" else "Sticker",
                        color = TextoPrimario,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        packs.firstOrNull { it.id == s.packId }?.nombre ?: "Sin pack",
                        color = TextoTerciario,
                        fontSize = 12.sp,
                    )
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f))

            Text(
                "Etiqueta con un emoji para poder buscarlo",
                color = TextoTerciario,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(EMOJIS_DE_STICKER, key = { it }) { e ->
                    Text(
                        e,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (s.emoji == e) Cian.copy(alpha = 0.22f) else BgSurface)
                            .clickable { onEmoji(if (s.emoji == e) "" else e); onCerrar() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .semantics { contentDescription = "Etiquetar con $e" },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Slate.copy(alpha = 0.3f))

            AccionSticker(
                if (s.favorito) "Quitar de favoritos" else "Marcar como favorito",
                if (s.favorito) Icons.Filled.StarBorder else Icons.Filled.Star,
            ) { onFavorito(); onCerrar() }

            if (packs.isNotEmpty()) {
                Text(
                    "Mover a un pack",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                packs.forEach { p ->
                    AccionSticker(
                        p.nombre,
                        Icons.Filled.Folder,
                        activa = p.id == s.packId,
                    ) { onMover(if (p.id == s.packId) "" else p.id); onCerrar() }
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
            AccionSticker("Borrar el sticker", Icons.Filled.DeleteOutline, color = Coral) {
                confirmando = true
            }
        }
    }

    if (confirmando) {
        AlertDialog(
            onDismissRequest = { confirmando = false },
            containerColor = BgElev,
            title = { Text("Borrar el sticker", color = TextoPrimario) },
            text = {
                Text(
                    // Se dice que sólo se va de aquí: los que ya se mandaron
                    // están en la conversación de la otra persona y borrar el
                    // original no los retira.
                    "Se quita de tu colección. Los que ya mandaste siguen en sus chats.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmando = false; onBorrar(); onCerrar() }) {
                    Text("Borrar", color = Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmando = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }
}

@Composable
private fun AccionSticker(
    texto: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color = TextoPrimario,
    activa: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icono, null,
            tint = if (activa) Cian else color,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(texto, color = if (activa) Cian else color, fontSize = 15.sp)
    }
}

/**
 * Los emojis con los que se etiqueta.
 *
 * Una lista corta y no el teclado entero: la etiqueta sirve para **agrupar**,
 * y con mil emojis distintos no agrupa nada — cada sticker acabaría con el
 * suyo y la búsqueda devolvería siempre uno solo.
 */
private val EMOJIS_DE_STICKER = listOf(
    "😂", "❤️", "👍", "😮", "😢",
    "🔥", "🎉", "👏", "🤔", "😴",
    "🙏", "💪",
)

/** Pedir un nombre. Se reusa para crear y para renombrar un pack. */
@Composable
internal fun DialogoNombre(
    titulo: String,
    detalle: String,
    inicial: String = "",
    onCerrar: () -> Unit,
    onListo: (String) -> Unit,
) {
    var texto by remember { mutableStateOf(TextFieldValue(inicial)) }
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(titulo, color = TextoPrimario) },
        text = {
            Column {
                Text(detalle, color = TextoSecundario, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.copy(text = it.text.take(40)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Slate,
                        focusedTextColor = TextoPrimario,
                        unfocusedTextColor = TextoPrimario,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onListo(texto.text.trim()) },
                enabled = texto.text.isNotBlank(),
            ) { Text("Listo", color = Cian) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}
