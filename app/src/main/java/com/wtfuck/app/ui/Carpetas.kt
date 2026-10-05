package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.CarpetaEnt
import com.wtfuck.app.datos.Carpetas
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/** Las carpetas: crear, editar y borrar. Se abre desde ⋮ → Carpetas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaCarpetas(onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val carpetas by app.repo.carpetas.collectAsStateWithLifecycle(emptyList())
    val enCarpetas by app.repo.chatsEnCarpetas.collectAsStateWithLifecycle(emptyList())
    // null = cerrado; "" = una nueva; si no, el id de la que se edita.
    var editando by remember { mutableStateOf<String?>(null) }
    var borrando by remember { mutableStateOf<CarpetaEnt?>(null) }

    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                "Carpetas",
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Solo en este teléfono: el servidor no sabe cómo ordenas tus chats.",
                color = TextoTerciario,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            carpetas.forEach { c ->
                val cuantos = enCarpetas.count { it.carpetaId == c.id }
                Row(
                    Modifier.fillMaxWidth().clickable { editando = c.id }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Cian, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.nombre, color = TextoPrimario)
                        Text(
                            if (cuantos == 1) "1 chat" else "$cuantos chats",
                            color = TextoTerciario, fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = { borrando = c }) {
                        Icon(Icons.Filled.DeleteOutline, "Borrar ${c.nombre}", tint = Coral)
                    }
                }
            }
            if (Carpetas.caben(carpetas.size)) {
                Row(
                    Modifier.fillMaxWidth().clickable { editando = "" }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = Cian, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Nueva carpeta", color = Cian)
                }
            } else {
                Text(
                    "Llegaste a ${Carpetas.MAX} carpetas.",
                    color = TextoTerciario, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    editando?.let { id ->
        EditorCarpeta(carpetaId = id.ifBlank { null }, onCerrar = { editando = null })
    }
    borrando?.let { c ->
        AlertDialog(
            onDismissRequest = { borrando = null },
            containerColor = BgElev,
            title = { Text("¿Borrar \"${c.nombre}\"?", color = TextoPrimario) },
            text = { Text("Los chats no se borran: solo salen de la carpeta.", color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = {
                    ambito.launch { app.repo.borrarCarpeta(c.id) }
                    borrando = null
                }) { Text("Borrar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { borrando = null }) { Text("Cancelar", color = Cian) } },
        )
    }
}

/**
 * Crear o editar una carpeta: el nombre y que chats tiene.
 *
 * [conChat] es para crearla desde el menu de un chat: ese chat ya viene
 * marcado, que es lo que la persona estaba queriendo hacer.
 */
@Composable
fun EditorCarpeta(carpetaId: String?, onCerrar: () -> Unit, conChat: String? = null) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val carpetas by app.repo.carpetas.collectAsStateWithLifecycle(emptyList())
    val enCarpetas by app.repo.chatsEnCarpetas.collectAsStateWithLifecycle(emptyList())
    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val actual = carpetas.firstOrNull { it.id == carpetaId }

    var nombre by remember(actual?.id) { mutableStateOf(actual?.nombre.orEmpty()) }
    var marcados by remember(actual?.id) { mutableStateOf<Set<String>?>(null) }
    // Los de la carpeta se cargan una vez, cuando llegan: despues manda lo que
    // la persona marque.
    LaunchedEffect(actual?.id, enCarpetas.isNotEmpty() || carpetaId == null) {
        if (marcados == null) {
            marcados = if (carpetaId == null) setOfNotNull(conChat)
            else enCarpetas.filter { it.carpetaId == carpetaId }.map { it.conversacionId }.toSet()
        }
    }
    val problema = Carpetas.problemaCon(nombre, carpetas.map { it.nombre }, propio = actual?.nombre)

    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = BgSurface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f)) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    if (actual == null) "Nueva carpeta" else "Editar carpeta",
                    color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it.take(Carpetas.MAX_NOMBRE + 5) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    isError = nombre.isNotEmpty() && problema != null,
                    supportingText = { if (nombre.isNotEmpty() && problema != null) Text(problema) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "Chats",
                    color = TextoSecundario, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(chats, key = { it.id }) { c ->
                        val dentro = c.id in (marcados ?: emptySet())
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val m = marcados ?: emptySet()
                                    marcados = if (dentro) m - c.id else m + c.id
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarDeChat(
                                nombre = c.titulo,
                                url = ApiCliente.urlImagen(c.avatarUsername, "avatar", c.avatarVersion),
                                clase = claseDeTipo(c.tipo),
                                tamano = 36.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.titulo, color = TextoPrimario, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(checked = dentro, onCheckedChange = null)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
                    TextButton(
                        enabled = problema == null,
                        onClick = {
                            val m = marcados ?: emptySet()
                            ambito.launch {
                                if (actual == null) app.repo.crearCarpeta(nombre, m)
                                else app.repo.guardarCarpeta(actual.id, nombre, m)
                                onCerrar()
                            }
                        },
                    ) { Text("Guardar", color = if (problema == null) Cian else TextoTerciario) }
                }
            }
        }
    }
}

/** "Añadir a carpeta" desde el menu de un chat: se marca y desmarca al momento. */
@Composable
fun ElegirCarpetas(conversacionId: String, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val carpetas by app.repo.carpetas.collectAsStateWithLifecycle(emptyList())
    val enCarpetas by app.repo.chatsEnCarpetas.collectAsStateWithLifecycle(emptyList())
    var nueva by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Carpetas", color = TextoPrimario) },
        text = {
            Column {
                if (carpetas.isEmpty()) {
                    Text("Todavía no tienes carpetas.", color = TextoTerciario, fontSize = 13.sp)
                }
                carpetas.forEach { c ->
                    val dentro = enCarpetas.any { it.carpetaId == c.id && it.conversacionId == conversacionId }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { ambito.launch { app.repo.ponerEnCarpeta(c.id, conversacionId, !dentro) } }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = dentro, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(c.nombre, color = TextoPrimario)
                    }
                }
                if (Carpetas.caben(carpetas.size)) {
                    TextButton(onClick = { nueva = true }) { Text("Nueva carpeta", color = Cian) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Listo", color = Cian) } },
    )
    if (nueva) EditorCarpeta(carpetaId = null, onCerrar = { nueva = false }, conChat = conversacionId)
}
