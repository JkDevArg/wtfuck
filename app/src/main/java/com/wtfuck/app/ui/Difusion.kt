package com.wtfuck.app.ui

import androidx.compose.foundation.background
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.datos.DifusionEnt
import com.wtfuck.app.datos.Difusiones
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Listas de difusion: un mensaje a varias personas, cada una en su chat
 * conmigo. Ver `DifusionEnt`.
 *
 * ## Solo con quien ya tengo un chat
 *
 * Es la regla de WhatsApp -alli, quien te tiene agendado- y por el mismo
 * motivo: una lista que pudiera escribirle a cualquiera seria la herramienta
 * perfecta para el spam. Aqui ademas evita crear de golpe chats nuevos con
 * gente que nunca hablo conmigo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifusionesPantalla(onAtras: () -> Unit, onAbrir: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val listas by app.repo.difusiones.collectAsStateWithLifecycle(emptyList())
    var creando by remember { mutableStateOf(false) }

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
                title = { Text("Listas de difusión", color = TextoPrimario) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creando = true }, containerColor = Cian, contentColor = TextoSobreAcento,
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Nueva lista")
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Surface(
                    color = BgSurface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        "Un mensaje a varias personas a la vez. Cada una lo recibe en su chat contigo, " +
                            "como un mensaje normal, y nadie ve a quién más le llegó. Solo puedes " +
                            "agregar a personas con las que ya tienes un chat. Las listas viven en este teléfono.",
                        color = TextoSecundario, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            if (listas.isEmpty()) {
                item {
                    Text(
                        "Todavía no tienes ninguna.",
                        color = TextoTerciario, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(listas, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onAbrir(d.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Campaign, null, tint = Cian, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(d.nombre, color = TextoPrimario, fontWeight = FontWeight.Medium)
                        Text(
                            "${d.lista.size} destinatarios", color = TextoTerciario,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    if (creando) {
        HojaEditarDifusion(
            existente = null,
            onListo = { creando = false; onAbrir(it) },
            onCerrar = { creando = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifusionPantalla(id: String, onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val d by app.repo.difusion(id).collectAsStateWithLifecycle(null)
    val envios by app.repo.enviosDifusion(id).collectAsStateWithLifecycle(emptyList())
    val todos = remember(envios) { envios.flatMap { it.ids } }
    val estados by remember(todos) { app.repo.estadosDe(todos) }.collectAsStateWithLifecycle(emptyList())
    val porId = remember(estados) { estados.associate { it.id to it.estado } }

    var texto by remember { mutableStateOf("") }
    var adjuntos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf(false) }
    var borrando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val lista = rememberLazyListState()
    LaunchedEffect(envios.size) { if (envios.isNotEmpty()) lista.animateScrollToItem(envios.lastIndex) }

    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
        if (it.isNotEmpty()) adjuntos = it
    }

    val miembros = d?.lista.orEmpty()

    fun enviar() {
        val t = texto.trim()
        if (t.isEmpty() && adjuntos.isEmpty()) return
        // Cada persona recibe SU copia cifrada, y el servidor deja subir 40
        // archivos cada cinco minutos: mas que eso fallaria a mitad.
        if (adjuntos.size * miembros.size > 40) {
            aviso = "Son demasiados archivos para tantas personas: cada una recibe su propia copia cifrada " +
                "y el servidor deja subir 40 cada 5 minutos. Manda menos fotos, o usa una lista más chica."
            return
        }
        app.repo.difundir(id, t, adjuntos)
        texto = ""
        adjuntos = emptyList()
    }

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
                    Column {
                        Text(d?.nombre.orEmpty(), color = TextoPrimario, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${miembros.size} destinatarios", color = TextoTerciario, fontSize = 12.sp,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, "Más opciones", tint = TextoPrimario)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = BgElev) {
                            DropdownMenuItem(
                                text = { Text("Editar lista", color = TextoPrimario) },
                                onClick = { menu = false; editando = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Borrar lista", color = Coral) },
                                onClick = { menu = false; borrando = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(BgSurface).navigationBarsPadding().imePadding()) {
                if (adjuntos.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (adjuntos.size == 1) "1 foto o video para mandar" else "${adjuntos.size} fotos o videos para mandar",
                            color = TextoSecundario, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { adjuntos = emptyList() }) {
                            Icon(Icons.Filled.Close, "Quitar", tint = TextoSecundario)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Icon(Icons.Filled.AttachFile, "Adjuntar", tint = TextoSecundario) }
                    OutlinedTextField(
                        value = texto, onValueChange = { texto = it },
                        placeholder = { Text(if (adjuntos.isEmpty()) "Mensaje para la lista" else "Pie (opcional)") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                    )
                    IconButton(
                        onClick = { enviar() },
                        enabled = (texto.isNotBlank() || adjuntos.isNotEmpty()) && miembros.isNotEmpty(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Cian) }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = lista) {
            if (envios.isEmpty()) {
                item {
                    Text(
                        "Todavía no mandaste nada a esta lista. Lo que escribas aquí le llega a cada persona " +
                            "en su chat contigo.",
                        color = TextoTerciario, modifier = Modifier.padding(20.dp),
                    )
                }
            }
            items(envios, key = { it.id }) { e ->
                val conteo = Difusiones.contar(e.ids.mapNotNull { porId[it] }, e.ids.size)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    Surface(color = Cian.copy(alpha = 0.16f), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 300.dp)) {
                            if (e.resumen != e.texto) {
                                Text(e.resumen, color = Cian, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                            if (e.texto.isNotBlank()) Text(e.texto, color = TextoPrimario)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                horaCorta(e.creadoEn) + " · " + Difusiones.resumen(conteo),
                                color = TextoTerciario, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (editando) {
        d?.let {
            HojaEditarDifusion(existente = it, onListo = { editando = false }, onCerrar = { editando = false })
        }
    }
    if (borrando) {
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { borrando = false },
            title = { Text("Borrar la lista", color = TextoPrimario) },
            text = { Text("Lo que ya mandaste sigue en cada chat. Solo se borra la lista.", color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = {
                    borrando = false
                    ambito.launch { app.repo.borrarDifusion(id); onAtras() }
                }) { Text("Borrar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { borrando = false }) { Text("Cancelar", color = TextoSecundario) } },
        )
    }
    aviso?.let {
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { aviso = null },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
            text = { Text(it, color = TextoPrimario) },
        )
    }
}

/**
 * Crear o editar una lista: nombre y a quienes. Los candidatos son mis chats
 * directos; los protegidos no se ofrecen, igual que en el buscador: elegirlos
 * aqui seria mostrarlos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaEditarDifusion(existente: DifusionEnt?, onListo: (String) -> Unit, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())
    val candidatos = remember(chats) {
        chats.filter { it.tipo == "directa" && it.soyMiembro && !it.protegido && it.expiraEn == 0L }
            .distinctBy { it.nombre }
            .sortedBy { it.titulo.lowercase() }
    }
    var nombre by remember { mutableStateOf(existente?.nombre.orEmpty()) }
    var elegidos by remember { mutableStateOf(existente?.lista?.toSet() ?: emptySet()) }
    var filtro by remember { mutableStateOf("") }
    val visibles = remember(candidatos, filtro) {
        val f = filtro.trim().lowercase()
        if (f.isEmpty()) candidatos
        else candidatos.filter { f in it.titulo.lowercase() || f in it.nombre.lowercase() }
    }

    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                if (existente == null) "Nueva lista" else "Editar lista",
                color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nombre, onValueChange = { nombre = it.take(60) },
                label = { Text("Nombre de la lista") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filtro, onValueChange = { filtro = it },
                label = { Text("Buscar entre tus chats") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${elegidos.size} elegidas · mínimo 2, máximo ${Difusiones.MAX_MIEMBROS}",
                color = TextoTerciario, style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(visibles, key = { it.id }) { c: ChatFila ->
                    val marcado = c.nombre in elegidos
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            elegidos = if (marcado) elegidos - c.nombre
                            else if (elegidos.size < Difusiones.MAX_MIEMBROS) elegidos + c.nombre else elegidos
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = marcado, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(c.titulo, color = TextoPrimario)
                            if (c.titulo != c.nombre) Text("@" + c.nombre, color = TextoTerciario, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (candidatos.isEmpty()) {
                Text("No tienes chats con nadie todavía.", color = TextoTerciario, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    ambito.launch {
                        val id = app.repo.guardarDifusion(existente?.id, nombre, elegidos.toList())
                        onListo(id)
                    }
                },
                enabled = elegidos.size >= 2,
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar") }
        }
    }
}
