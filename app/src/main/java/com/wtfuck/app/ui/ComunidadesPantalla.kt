package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ComunidadDetalle
import com.wtfuck.protocol.ComunidadResumen
import kotlinx.coroutines.launch

/**
 * Módulo AD · Comunidades.
 *
 * ## Qué es una comunidad, y qué la distingue de una carpeta
 *
 * Un conjunto de grupos bajo un nombre, **más un canal de anuncios**. Lo
 * segundo es lo único que la hace una comunidad: sin él, agrupar chats es una
 * carpeta, y una carpeta se resuelve en el teléfono sin que el servidor se
 * entere. El canal de anuncios es un sitio donde quien administra alcanza a
 * todos de una vez.
 *
 * ## Por qué no hay un botón de "unirme"
 *
 * Porque **la pertenencia se deriva**: sos de la comunidad si sos de alguno de
 * sus grupos. Un botón de unirse crearía una segunda forma de pertenecer, y
 * dos listas que dicen lo mismo se separan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComunidadesPantalla(
    onAtras: () -> Unit,
    onAbrirConversacion: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    /** `null` = todavía no se pudo leer. Ver el módulo Z.5. */
    var lista by remember { mutableStateOf<List<ComunidadResumen>?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var creando by remember { mutableStateOf(false) }
    var abierta by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        lista = app.repo.misComunidades()
        cargando = false
    }
    LaunchedEffect(Unit) { recargar() }

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
                title = { Text("Comunidades", color = TextoPrimario) },
                actions = {
                    IconButton(onClick = { creando = true }) {
                        Icon(Icons.Filled.Add, "Nueva comunidad", tint = Cian)
                    }
                },
            )
        },
    ) { pad ->
        val actual = lista
        when {
            cargando -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
            }

            actual == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                EstadoDeError(
                    titulo = "No se pudieron cargar",
                    detalle = "Esto NO quiere decir que no tengas comunidades.",
                    onReintentar = { cargando = true; ambito.launch { recargar() } },
                )
            }

            actual.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Campaign, null, tint = Slate, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("Todavía no tenés comunidades.", color = TextoSecundario, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                // Un vacío que explica de qué se trata: "no tenés ninguna" no
                // dice para qué sirve, y esta es una función que casi nadie usó
                // antes en esta app.
                Text(
                    "Una comunidad junta varios grupos bajo un nombre y les da un " +
                        "canal de anuncios común.",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(actual, key = { it.id }) { k ->
                    FilaComunidad(k) { abierta = k.id }
                    HorizontalDivider(color = Slate.copy(alpha = 0.18f))
                }
            }
        }
    }

    if (creando) {
        HojaNuevaComunidad(
            onCerrar = { creando = false },
            onCreada = {
                creando = false
                cargando = true
                ambito.launch { recargar() }
            },
        )
    }

    abierta?.let { id ->
        HojaComunidad(
            comunidadId = id,
            onCerrar = { abierta = null; ambito.launch { recargar() } },
            onAbrirConversacion = { abierta = null; onAbrirConversacion(it) },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            text = { Text(msg, color = TextoPrimario) },
            confirmButton = {
                TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) }
            },
        )
    }
}

@Composable
private fun FilaComunidad(k: ComunidadResumen, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(k.nombre)
                    append(", ")
                    append(if (k.grupos == 1) "1 grupo" else "${k.grupos} grupos")
                    if (k.soyAdmin) append(", la administras")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Cian.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Campaign, null, tint = Cian, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(k.nombre, color = TextoPrimario, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(if (k.grupos == 1) "1 grupo" else "${k.grupos} grupos")
                    if (k.soyAdmin) append(" · administras")
                },
                color = TextoTerciario,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Crear una comunidad, eligiendo con qué grupos nace.
 *
 * Se eligen al crear y no después porque una comunidad vacía no le sirve a
 * nadie, y obligar a crearla y volver a entrar es un paso de más para el caso
 * normal. Sólo se ofrecen los grupos que administro: los demás los rechazaría
 * el servidor, y ofrecer una casilla que va a dar error es peor que no
 * ofrecerla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaNuevaComunidad(onCerrar: () -> Unit, onCreada: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var nombre by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var elegidos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var guardando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val grupos = remember(chats) { chats.filter { it.tipo == "grupo" } }

    ModalBottomSheet(
        // Entera: el botón de confirmar va al final y a media altura queda
        // fuera. Ver el módulo W.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Nueva comunidad", color = TextoPrimario, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Junta varios grupos bajo un nombre y les da un canal de anuncios común.",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it.take(64) },
                label = { Text("Nombre", color = TextoTerciario) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it.take(512) },
                label = { Text("De qué se trata", color = TextoTerciario) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
            )

            Spacer(Modifier.height(16.dp))
            Text("Grupos", color = TextoPrimario, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Su gente entra al canal de anuncios de la comunidad.",
                color = TextoTerciario,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(8.dp))

            if (grupos.isEmpty()) {
                Text(
                    "No tenés grupos todavía. Podés crear la comunidad y agregarlos después.",
                    color = TextoTerciario,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                grupos.forEach { g ->
                    val marcado = g.id in elegidos
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                elegidos = if (marcado) elegidos - g.id else elegidos + g.id
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = marcado,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Cian),
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Filled.Group, null, tint = TextoTerciario, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(g.titulo, color = TextoPrimario, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    guardando = true
                    ambito.launch {
                        app.repo.crearComunidad(nombre.trim(), descripcion.trim(), elegidos.toList())
                            .onSuccess { d ->
                                // Los rechazados se cuentan: crear cinco y que
                                // entren tres sin decir nada deja a alguien
                                // creyendo que estan los cinco.
                                if (d.rechazados.isNotEmpty()) {
                                    aviso = d.rechazados.joinToString("\n") { it.motivo }
                                } else {
                                    onCreada()
                                }
                            }
                            .onFailure { aviso = it.message }
                        guardando = false
                    }
                },
                enabled = nombre.isNotBlank() && !guardando,
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (guardando) {
                    CircularProgressIndicator(
                        color = TextoSobreAcento, strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Crear la comunidad")
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null; onCreada() },
            containerColor = BgElev,
            title = { Text("Algunos grupos no entraron", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = { aviso = null; onCreada() }) { Text("Entendido", color = Cian) }
            },
        )
    }
}

/** Una comunidad por dentro: sus anuncios, sus grupos y, si administro, editarla. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaComunidad(
    comunidadId: String,
    onCerrar: () -> Unit,
    onAbrirConversacion: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var detalle by remember { mutableStateOf<ComunidadDetalle?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        detalle = app.repo.comunidad(comunidadId)
        cargando = false
    }
    LaunchedEffect(comunidadId) { recargar() }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val d = detalle
            when {
                cargando -> Box(Modifier.fillMaxWidth().height(140.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
                }

                d == null -> EstadoDeError(
                    titulo = "No se pudo cargar",
                    detalle = "Esto NO quiere decir que la comunidad no exista.",
                    onReintentar = { cargando = true; ambito.launch { recargar() } },
                )

                else -> {
                    Text(
                        d.comunidad.nombre,
                        color = TextoPrimario, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (d.comunidad.descripcion.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(d.comunidad.descripcion, color = TextoSecundario, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))

                    // El canal de anuncios primero: es lo que hace que esto sea
                    // una comunidad y no una carpeta.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgElev)
                            .clickable { onAbrirConversacion(d.comunidad.anunciosId) }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Campaign, null, tint = Cian, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Anuncios", color = TextoPrimario, fontSize = 14.5.sp)
                            Text(
                                "Lo que se publica aquí lo ve toda la comunidad.",
                                color = TextoTerciario, fontSize = 11.5.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (d.grupos.size == 1) "1 grupo" else "${d.grupos.size} grupos",
                        color = TextoPrimario, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(d.grupos, key = { it.conversacionId }) { g ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    // Sólo se abre el que es mío. Ofrecer abrir
                                    // un grupo del que no soy parte lleva a un
                                    // 404 con forma de fallo.
                                    .clickable(enabled = g.estoy) {
                                        onAbrirConversacion(g.conversacionId)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Group, null,
                                    tint = if (g.estoy) Cian else Slate,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.nombre.ifBlank { "Grupo" },
                                        color = if (g.estoy) TextoPrimario else TextoSecundario,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        buildString {
                                            append(if (g.miembros == 1) "1 miembro" else "${g.miembros} miembros")
                                            if (!g.estoy) append(" · no estás")
                                        },
                                        color = TextoTerciario, fontSize = 11.sp,
                                    )
                                }
                                if (d.comunidad.soyAdmin) {
                                    IconButton(onClick = {
                                        ambito.launch {
                                            app.repo.quitarGrupoDeComunidad(comunidadId, g.conversacionId)
                                                .onSuccess { cargando = true; recargar() }
                                                .onFailure { aviso = it.message }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.Close, "Quitar de la comunidad",
                                            tint = TextoTerciario, modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!d.comunidad.soyAdmin) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Sos parte de esta comunidad porque estás en alguno de sus grupos.",
                            color = TextoTerciario, fontSize = 11.5.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            text = { Text(msg, color = TextoPrimario) },
            confirmButton = {
                TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) }
            },
        )
    }
}
