package com.wtfuck.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.CLASE_ENLACE
import androidx.compose.ui.graphics.asImageBitmap
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.ui.theme.BgBase
import com.wtfuck.app.ui.theme.BgElev
import com.wtfuck.app.ui.theme.BgSurface
import com.wtfuck.app.ui.theme.Cian
import com.wtfuck.app.ui.theme.TextoPrimario
import com.wtfuck.app.ui.theme.TextoSecundario
import com.wtfuck.app.ui.theme.TextoTerciario
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.protocol.ClaseContenido

/**
 * Cómo se llama y cómo se dibuja cada clase de cosa compartida.
 *
 * Vive en un solo sitio porque el nombre aparece en dos pantallas —la fila del
 * perfil y el título de la galería— y son **el mismo nombre**: si en una
 * dijera "Archivos" y en la otra "Documentos", tocar una y llegar a la otra
 * haría dudar de haber llegado a donde se quería.
 */
private data class Pinta(val icono: ImageVector, val uno: String, val varios: String)

private fun pinta(clase: String): Pinta = when (clase) {
    ClaseAdjunto.IMAGEN -> Pinta(Icons.Filled.Image, "foto", "fotos")
    ClaseAdjunto.VIDEO -> Pinta(Icons.Filled.Videocam, "video", "videos")
    ClaseAdjunto.DOCUMENTO -> Pinta(Icons.AutoMirrored.Filled.InsertDriveFile, "archivo", "archivos")
    ClaseAdjunto.AUDIO -> Pinta(Icons.Filled.Audiotrack, "audio", "audios")
    ClaseAdjunto.NOTA_VOZ -> Pinta(Icons.Filled.Mic, "mensaje de voz", "mensajes de voz")
    ClaseAdjunto.STICKER -> Pinta(Icons.Filled.EmojiEmotions, "sticker", "stickers")
    ClaseContenido.UBICACION -> Pinta(Icons.Filled.Place, "ubicación", "ubicaciones")
    ClaseContenido.CONTACTO -> Pinta(Icons.Filled.Person, "contacto", "contactos")
    ClaseContenido.ENCUESTA -> Pinta(Icons.Filled.Poll, "encuesta", "encuestas")
    CLASE_ENLACE -> Pinta(Icons.Filled.Link, "enlace", "enlaces")
    else -> Pinta(Icons.AutoMirrored.Filled.InsertDriveFile, "archivo", "archivos")
}

/** "93 fotos", "1 foto". El singular importa: "1 fotos" se lee como un error. */
fun etiquetaCompartido(clase: String, cuantos: Int): String {
    val p = pinta(clase)
    return "$cuantos ${if (cuantos == 1) p.uno else p.varios}"
}

/** Una línea del recuento, en el perfil. */
@Composable
fun FilaCompartido(clase: String, cuantos: Int, onClick: () -> Unit) {
    val p = pinta(clase)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(p.icono, null, tint = TextoSecundario, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(etiquetaCompartido(clase, cuantos), color = TextoPrimario, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = TextoTerciario, modifier = Modifier.size(18.dp))
    }
}

/**
 * Todo lo de una clase que se compartió en un chat.
 *
 * ## Por qué esto existe y no es sólo un número
 *
 * Un recuento que no se puede abrir es decoración: dice "hay 93 fotos" y deja
 * a la persona haciendo scroll por el chat para encontrar una. La razón de
 * contar es poder volver.
 *
 * ## Dos formas de listar, no una
 *
 * Las fotos, los videos y los stickers van en **rejilla**: lo que identifica
 * una imagen es la imagen. Los archivos, los audios y los enlaces van en
 * **lista**: lo que identifica un documento es su nombre, y una rejilla de
 * iconos iguales no distingue nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompartidoPantalla(
    conversacionId: String,
    clase: String,
    onAtras: () -> Unit,
    onVerEnElChat: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ctx = LocalContext.current
    var filas by remember { mutableStateOf<List<MensajeEnt>?>(null) }

    LaunchedEffect(conversacionId, clase) {
        filas = runCatching { app.repo.compartidoDe(conversacionId, clase) }.getOrElse { emptyList() }
    }

    val lista = filas
    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                    }
                },
                title = {
                    Text(
                        if (lista == null) pinta(clase).varios.replaceFirstChar { it.uppercase() }
                        else etiquetaCompartido(clase, lista.size)
                            .replaceFirstChar { it.uppercase() },
                        color = TextoPrimario,
                    )
                },
            )
        },
    ) { pad ->
        if (lista == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }
        if (lista.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No hay nada todavía", color = TextoTerciario)
            }
            return@Scaffold
        }

        val enRejilla = clase == ClaseAdjunto.IMAGEN ||
            clase == ClaseAdjunto.VIDEO ||
            clase == ClaseAdjunto.STICKER

        if (enRejilla) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lista, key = { it.id }) { m ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(BgElev)
                            .clickable { onVerEnElChat(m.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // La miniatura ya está en la base, descifrada al
                        // guardar. Se ve sin descargar nada y sin red — que es
                        // justo lo que una galería tiene que hacer.
                        //
                        // Se decodifica por `miniaturaAjena` y NO con un
                        // cargador de imagenes cualquiera: esos bytes vienen
                        // de un sobre de otra persona, y ese es justamente el
                        // decodificador endurecido del modulo D. Pasarlos por
                        // Coil habria saltado la unica comprobacion que hay.
                        val mini = remember(m.adjuntoMiniatura) {
                            Media.deBase64(m.adjuntoMiniatura)
                                ?.let { b -> Media.miniaturaAjena(b)?.asImageBitmap() }
                        }
                        if (mini != null) {
                            Image(
                                bitmap = mini,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                pinta(clase).icono,
                                null,
                                tint = TextoTerciario,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        if (clase == ClaseAdjunto.VIDEO) {
                            Icon(
                                Icons.Filled.Videocam,
                                null,
                                tint = TextoPrimario,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(lista, key = { it.id }) { m ->
                    val enlace = if (clase == CLASE_ENLACE) primerEnlace(m.texto) else null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Un enlace se abre; todo lo demás lleva a su
                                // sitio en el chat, que es donde está el
                                // contexto de quién lo mandó y por qué.
                                if (enlace != null) {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enlace)))
                                    }
                                } else {
                                    onVerEnElChat(m.id)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(BgElev),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(pinta(clase).icono, null, tint = Cian, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                enlace ?: m.adjuntoNombre.ifBlank { m.texto.ifBlank { "Sin nombre" } },
                                color = TextoPrimario,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                detalleDe(m, clase),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextoTerciario,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** La segunda línea: quién y cuándo, más lo que distinga a esa clase. */
private fun detalleDe(m: MensajeEnt, clase: String): String {
    val quien = if (m.esMio) "Vos" else m.autor
    val cuando = horaCorta(m.creadoEn)
    return when {
        clase == CLASE_ENLACE -> "$quien · $cuando"
        m.adjuntoDuracionMs > 0 ->
            "$quien · ${duracionHabla(m.adjuntoDuracionMs / 1000)} · $cuando"
        m.adjuntoBytes > 0 -> "$quien · ${Media.tamanoLegible(m.adjuntoBytes)} · $cuando"
        else -> "$quien · $cuando"
    }
}

/**
 * La primera dirección de un texto, o `null`.
 *
 * Se corta en el primer espacio y se quita la puntuación final: "mirá
 * https://x.com." termina con un punto que no es parte de la dirección, y
 * abrirlo con el punto da un 404.
 */
fun primerEnlace(texto: String): String? {
    val i = texto.indexOf("http")
    if (i < 0) return null
    val resto = texto.substring(i).substringBefore(' ').substringBefore('\n')
    if (!resto.startsWith("http://") && !resto.startsWith("https://")) return null
    return resto.trimEnd('.', ',', ')', ']', '»', '"', '\'', ';', ':').ifBlank { null }
}

/**
 * El recuento en un orden estable, de lo más común a lo más raro.
 *
 * Es el orden que tiene el contenido en la cabeza de quien lo busca —fotos
 * antes que encuestas— y no cambia nunca. Lo que no está en la lista va al
 * final, para que una clase nueva aparezca aunque nadie se acuerde de
 * agregarla acá.
 */
fun ordenCompartido(resumen: Map<String, Int>): List<Pair<String, Int>> {
    val orden = listOf(
        ClaseAdjunto.IMAGEN,
        ClaseAdjunto.VIDEO,
        ClaseAdjunto.DOCUMENTO,
        ClaseAdjunto.AUDIO,
        ClaseAdjunto.NOTA_VOZ,
        ClaseAdjunto.STICKER,
        CLASE_ENLACE,
        ClaseContenido.UBICACION,
        ClaseContenido.CONTACTO,
        ClaseContenido.ENCUESTA,
    )
    val conocidas = orden.mapNotNull { c -> resumen[c]?.let { c to it } }
    val resto = resumen.filterKeys { it !in orden }.toList().sortedBy { it.first }
    return conocidas + resto
}
