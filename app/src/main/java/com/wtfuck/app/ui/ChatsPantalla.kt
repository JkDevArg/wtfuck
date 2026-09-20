package com.wtfuck.app.ui

import com.wtfuck.protocol.VistasDeHistoria
import com.wtfuck.app.datos.Media
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.datos.EstadoConexion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.EstadoEnvio
import com.wtfuck.protocol.PreferenciasChat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsPantalla(
    /**
     * Abrir una conversacion. Recibe el TIPO porque un canal no se abre como
     * un chat: tiene su propia pantalla, donde publicar y leer son papeles
     * distintos. Decidirlo aqui evita que la pantalla de chat tenga que
     * disfrazarse de canal.
     */
    onAbrir: (String, String) -> Unit,
    /**
     * Ir al perfil. Sigue aqui porque tocar tu propia foto es el gesto que la
     * gente ya trae aprendido, aunque ahora el perfil tambien sea una pestaña.
     */
    onPerfil: () -> Unit,
    onCanales: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    // Modulo O. La fila de historias.
    val historias by app.repo.historias().collectAsState(initial = emptyList())
    var abiertoDe by remember { mutableStateOf<String?>(null) }
    var componiendo by remember { mutableStateOf(false) }
    var vistasDe by remember { mutableStateOf<VistasDeHistoria?>(null) }

    // Se sincroniza al entrar y cada vez que llega un sobre: el metadato vive
    // en el servidor y el contenido en el buzon, y la fila necesita los dos.
    LaunchedEffect(Unit) { app.repo.sincronizarHistorias() }
    LaunchedEffect(Unit) {
        app.repo.avisos.collect { app.repo.sincronizarHistorias() }
    }

    var verArchivados by rememberSaveable { mutableStateOf(false) }
    var filtro by rememberSaveable { mutableStateOf(Filtro.TODOS) }

    val activos by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val archivados by app.repo.archivadas.collectAsStateWithLifecycle(emptyList())
    val nArchivados by app.repo.cuantosArchivados.collectAsStateWithLifecycle(0)
    val chats = if (verArchivados) archivados else activos
    val conexion by app.repo.estadoConexion.collectAsStateWithLifecycle()
    val enCola by app.repo.tamanoCola.collectAsStateWithLifecycle(0)
    val fallidos by app.repo.tamanoFallidos.collectAsStateWithLifecycle(0)
    val perfil by app.repo.miPerfil.collectAsStateWithLifecycle()

    var mostrarNueva by remember { mutableStateOf(false) }
    var menuAbierto by remember { mutableStateOf(false) }
    var accionesDe by remember { mutableStateOf<ChatFila?>(null) }
    var arrancarEnGrupo by remember { mutableStateOf(false) }
    var busqueda by rememberSaveable { mutableStateOf("") }
    var errorDialogo by remember { mutableStateOf<String?>(null) }
    var denunciando by remember { mutableStateOf<ChatFila?>(null) }
    var enviandoDenuncia by remember { mutableStateOf(false) }
    var denunciaHecha by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.repo.cerrarChat()
        app.repo.cargarMiPerfil()
        app.repo.sincronizar()
    }

    // El filtro decide QUE lista se mira; el buscador, que parte de esa lista.
    // En ese orden: buscar dentro de "No leidos" es util, filtrar un resultado
    // de busqueda no.
    val filtrados = remember(chats, filtro, verArchivados) {
        if (verArchivados) chats else when (filtro) {
            Filtro.TODOS -> chats
            Filtro.SIN_LEER -> chats.filter { it.sinLeer }
            Filtro.GRUPOS -> chats.filter { it.tipo == "grupo" }
        }
    }

    val visibles = remember(filtrados, busqueda) {
        if (busqueda.isBlank()) filtrados
        else filtrados.filter {
            it.titulo.contains(busqueda, true) ||
                it.nombre.contains(busqueda, true) ||
                it.ultimoTexto.orEmpty().contains(busqueda, true)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = BgBase,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                    navigationIcon = {
                        if (verArchivados) {
                            IconButton(onClick = { verArchivados = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack, "Volver",
                                    tint = TextoPrimario,
                                )
                            }
                        }
                    },
                    title = {
                        if (verArchivados) {
                            Text("Archivados", color = TextoPrimario, fontSize = 20.sp)
                        } else {
                            Text("wtfuck", fontWeight = FontWeight.Bold, color = Cian, fontSize = 21.sp)
                        }
                    },
                    actions = {
                        // Tocar tu propia foto abre tu perfil. Es el gesto que la
                        // gente ya conoce de otras apps de mensajeria.
                        Box(Modifier.padding(end = 4.dp).clickable(onClick = onPerfil)) {
                            Avatar(
                                nombre = perfil?.nombreMostrado?.ifBlank { null }
                                    ?: app.sesion.username.orEmpty(),
                                url = ApiCliente.urlImagen(
                                    app.sesion.username.orEmpty(), "avatar",
                                    perfil?.avatarVersion ?: 0L,
                                ),
                                tamano = 38.dp,
                            )
                        }

                        // Menu de tres puntos. Cerrar sesion NO esta aqui: vive
                        // dentro del perfil, para que no se toque por accidente.
                        IconButton(onClick = { menuAbierto = true }) {
                            Icon(Icons.Filled.MoreVert, "Mas opciones", tint = TextoSecundario)
                        }
                        DropdownMenu(
                            expanded = menuAbierto,
                            onDismissRequest = { menuAbierto = false },
                            containerColor = BgElev,
                        ) {
                            OpcionMenu("Nuevo grupo", Icons.Filled.Group) {
                                menuAbierto = false; arrancarEnGrupo = true; mostrarNueva = true
                            }
                            OpcionMenu("Nueva conversacion", Icons.Filled.PersonAdd) {
                                menuAbierto = false; arrancarEnGrupo = false; mostrarNueva = true
                            }
                            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                            // "Canales" y "Mi perfil" ya no estan aqui: son
                            // pestañas. Dos caminos al mismo sitio solo obligan
                            // a decidir cual es el bueno.
                            OpcionMenu("Actualizar", Icons.Filled.Refresh) {
                                menuAbierto = false
                                ambito.launch { app.repo.sincronizar(); app.repo.despachar() }
                            }
                        }
                    },
                )
                BarraEstado(conexion, enCola, fallidos)
                BuscadorChats(busqueda) { busqueda = it }
                // Dentro de Archivados no hay filtros: es ya una lista aparte.
                if (!verArchivados) {
                    PestanasFiltro(
                        filtro = filtro,
                        onCambio = { filtro = it },
                        sinLeer = chats.count { it.sinLeer },
                        grupos = chats.count { it.tipo == "grupo" },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { mostrarNueva = true },
                containerColor = Cian,
                contentColor = TextoSobreAcento,
            ) { Icon(Icons.Filled.Add, "Nueva conversacion") }
        },
    ) { pad ->
      // El padding del Scaffold va UNA sola vez, aqui. Aplicarlo tambien
      // dentro deja la lista corrida: el hueco de la barra inferior se
      // reservaria dos veces y los chats se irian al fondo de la pantalla.
      Column(Modifier.fillMaxSize().padding(pad)) {
        // Va FUERA del `when` a proposito: dentro desapareceria justo cuando la
        // lista de chats esta vacia, que es cuando alguien recien empieza y mas
        // falta hace ver que esto existe.
        FilaHistorias(
            historias = historias,
            miUsuario = app.sesion.username.orEmpty(),
            onAbrir = { abiertoDe = it },
            onPublicar = { componiendo = true },
        )
        HorizontalDivider(color = Slate.copy(alpha = 0.18f))

        when {
            chats.isEmpty() -> Vacio(
                "Todavia no tienes conversaciones",
                "Toca + y escribe el usuario de alguien.",
            )
            busqueda.isNotBlank() && visibles.isEmpty() -> Vacio(
                "Sin resultados",
                "Nada coincide con \"$busqueda\".",
            )
            // Un filtro vacio no es un error: decir "no hay sin leer" es una
            // respuesta, y es distinta de "no tienes conversaciones".
            visibles.isEmpty() -> Vacio(
                when (filtro) {
                    Filtro.SIN_LEER -> "Todo leido"
                    Filtro.GRUPOS -> "Ningun grupo"
                    Filtro.TODOS -> "Nada por aqui"
                },
                when (filtro) {
                    Filtro.SIN_LEER -> "No te queda nada pendiente."
                    Filtro.GRUPOS -> "Crea uno desde el boton +."
                    Filtro.TODOS -> "Toca + para empezar."
                },
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (!verArchivados && nArchivados > 0) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { verArchivados = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Archive, null, tint = TextoSecundario, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("Archivados", style = MaterialTheme.typography.bodyLarge, color = TextoSecundario)
                            Spacer(Modifier.weight(1f))
                            Text("$nArchivados", style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
                        }
                        HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                    }
                }
                items(visibles, key = { it.id }) { c ->
                    FilaChat(
                        c,
                        app.sesion.username.orEmpty(),
                        onClick = { onAbrir(c.id, c.tipo) },
                        onMantener = { accionesDe = c },
                    )
                    HorizontalDivider(
                        color = Slate.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 78.dp),
                    )
                }
            }
        }
      }
    }

    // --- modulo O: el visor, el compositor y quien la vio ---------------

    abiertoDe?.let { autor ->
        // El mismo filtro que la fila: si no se anuncia, tampoco se abre. Si
        // no, tocar un anillo llevaria a barras que solo dicen "no se pudo
        // descifrar", que es lo que se vino a evitar.
        val suyas = historias.filter { it.autor == autor && it.conContenido }
        VisorHistorias(
            delAutor = autor,
            historias = suyas,
            miUsuario = app.sesion.username.orEmpty(),
            onVista = { id -> ambito.launch { app.repo.verHistoria(id) } },
            onRetirar = { id ->
                ambito.launch {
                    app.repo.retirarHistoria(id)
                    // Si era la ultima suya, el visor se queda sin nada que
                    // mostrar: se cierra en vez de dejar una pantalla vacia.
                    if (suyas.size <= 1) abiertoDe = null
                }
            },
            onVerQuienes = { id ->
                ambito.launch { vistasDe = app.repo.vistasDeHistoria(id) }
            },
            // El archivo se pide al abrirla y no al recibirla: lo que llego en
            // el sobre es la miniatura, que ya dibuja la historia.
            onDescargar = { id -> ambito.launch { app.repo.descargarArchivoDeHistoria(id) } },
            // Responder cierra el visor: la respuesta se fue a un chat, y
            // dejar la historia corriendo encima esconde donde acabo.
            onResponder = { id, texto ->
                abiertoDe = null
                ambito.launch {
                    app.repo.responderHistoria(id, texto)
                        // El servidor puede decir que no -quien publica para
                        // todos puede aceptar mensajes solo de conocidos- y
                        // entonces hay que DECIRLO. Un boton de responder que
                        // falla en silencio es peor que uno que no esta.
                        .onFailure { errorDialogo = it.message ?: "No se pudo responder." }
                }
            },
            onCerrar = { abiertoDe = null },
        )
    }

    if (componiendo) {
        HojaPublicarHistoria(
            onPublicar = { texto, fondo, medio ->
                componiendo = false
                ambito.launch {
                    app.repo.publicarHistoria(texto, fondo, medio)
                        .onFailure { errorDialogo = it.message }
                }
            },
            onCerrar = { componiendo = false },
        )
    }

    vistasDe?.let { v ->
        HojaVistasHistoria(vistas = v, onCerrar = { vistasDe = null })
    }

    accionesDe?.let { chat ->
        HojaAccionesChat(
            chat = chat,
            onCerrar = { accionesDe = null },
            onSilenciar = { min ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.preferencias(chat.id, PreferenciasChat(silenciarMinutos = min)) }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onNoLeida = {
                accionesDe = null
                // Local y nada mas: el servidor no tiene por que saber que
                // dejaste un chat pendiente de leer, y con multi-dispositivo
                // la marca es de ESTE aparato -es donde la vas a ver-.
                ambito.launch { app.repo.marcarNoLeida(chat.id) }
            },
            onArchivar = { v ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.preferencias(chat.id, PreferenciasChat(archivado = v)) }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onFijar = { v ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.preferencias(chat.id, PreferenciasChat(fijado = v)) }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onBloquear = {
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.bloquear(chat.nombre) }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onEliminar = {
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.eliminarChat(chat.id) }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onDenunciar = { accionesDe = null; denunciando = chat },
        )
    }

    denunciando?.let { chat ->
        val esGrupo = chat.tipo == "grupo"
        DenunciarHoja(
            queSeDenuncia = if (esGrupo) "\"${chat.titulo}\"" else "@${chat.nombre}",
            // Denunciar a una persona o un grupo NO entrega texto: no hay
            // mensaje concreto que entregar. Mostrar el aviso igual seria
            // alarmar de gratis.
            entregaTexto = false,
            enviando = enviandoDenuncia,
            onCerrar = { denunciando = null },
            onEnviar = { motivo, detalle, _ ->
                enviandoDenuncia = true
                ambito.launch {
                    val r = if (esGrupo) {
                        app.repo.denunciar("grupo", motivo, detalle, convId = chat.id)
                    } else {
                        app.repo.denunciar("usuario", motivo, detalle, username = chat.nombre)
                    }
                    enviandoDenuncia = false
                    denunciando = null
                    r.onSuccess { denunciaHecha = true }
                        .onFailure { errorDialogo = it.message }
                }
            },
        )
    }

    if (denunciaHecha) {
        AlertDialog(
            onDismissRequest = { denunciaHecha = false },
            containerColor = BgElev,
            title = { Text("Denuncia enviada", color = TextoPrimario) },
            text = {
                Text(
                    "Un moderador la va a revisar. No vas a recibir aviso del resultado: " +
                        "lo que se decida sobre otra cuenta no es informacion tuya.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = { denunciaHecha = false }) { Text("Entendido", color = Cian) }
            },
        )
    }

    if (mostrarNueva) {
        DialogoNueva(
            grupoInicial = arrancarEnGrupo,
            onCerrar = { mostrarNueva = false },
            onDirecta = { usuario ->
                ambito.launch {
                    runCatching { app.repo.nuevaDirecta(usuario) }
                        .onSuccess { mostrarNueva = false; onAbrir(it, "directa") }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onGrupo = { nombre, usuarios ->
                ambito.launch {
                    runCatching { app.repo.nuevoGrupo(nombre, usuarios) }
                        .onSuccess { mostrarNueva = false; onAbrir(it, "grupo") }
                        .onFailure { errorDialogo = it.message }
                }
            },
        )
    }

    errorDialogo?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorDialogo = null },
            containerColor = BgElev,
            title = { Text("No se pudo crear", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = { errorDialogo = null }) { Text("Entendido", color = Cian) }
            },
        )
    }
}

/**
 * Las pestañas de la lista, al estilo de las carpetas de Telegram.
 *
 * Son tres y no seis a proposito: una pestaña que nunca se toca ocupa el mismo
 * ancho que una que si. "No leidos" y "Grupos" son las dos preguntas que la
 * gente le hace de verdad a una lista de chats.
 *
 * El numero va en la pestaña, no en un globo aparte: asi se sabe si vale la
 * pena tocarla ANTES de tocarla.
 */
private enum class Filtro { TODOS, SIN_LEER, GRUPOS }

@Composable
private fun PestanasFiltro(
    filtro: Filtro,
    onCambio: (Filtro) -> Unit,
    sinLeer: Int,
    grupos: Int,
) {
    val etiquetas = listOf(
        Filtro.TODOS to "Todos",
        Filtro.SIN_LEER to if (sinLeer > 0) "No leidos $sinLeer" else "No leidos",
        Filtro.GRUPOS to if (grupos > 0) "Grupos $grupos" else "Grupos",
    )
    TabRow(
        selectedTabIndex = etiquetas.indexOfFirst { it.first == filtro },
        containerColor = BgSurface,
        contentColor = Cian,
        divider = { HorizontalDivider(color = Slate.copy(alpha = 0.25f)) },
        indicator = { tabPositions ->
            val i = etiquetas.indexOfFirst { it.first == filtro }
            if (i in tabPositions.indices) {
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[i]),
                    width = 48.dp,
                    height = 3.dp,
                    color = Cian,
                )
            }
        },
    ) {
        etiquetas.forEach { (f, texto) ->
            Tab(
                selected = filtro == f,
                onClick = { onCambio(f) },
                selectedContentColor = Cian,
                unselectedContentColor = TextoTerciario,
                text = {
                    Text(
                        texto,
                        fontSize = 13.sp,
                        maxLines = 1,
                        fontWeight = if (filtro == f) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun Vacio(titulo: String, detalle: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, color = TextoSecundario)
            Spacer(Modifier.height(6.dp))
            Text(detalle, style = MaterialTheme.typography.bodyMedium, color = TextoTerciario)
        }
    }
}

@Composable
private fun BuscadorChats(valor: String, onCambio: (String) -> Unit) {
    Surface(color = BgSurface, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valor,
            onValueChange = onCambio,
            placeholder = { Text("Buscar", color = TextoTerciario) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoTerciario) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian,
                unfocusedBorderColor = Slate.copy(alpha = 0.6f),
                focusedContainerColor = BgElev,
                unfocusedContainerColor = BgElev,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * La barra de estado es donde `msg off` se vuelve visible. Si hay mensajes en
 * cola, el usuario ve en ambar cuantos son y que no se perdieron.
 */
@Composable
private fun BarraEstado(conexion: EstadoConexion, enCola: Int, fallidos: Int) {
    // Un mensaje FALLIDO no esta "enviandose": no va a salir solo. Mezclarlo
    // con los pendientes dejaba la barra diciendo "Enviando 1..." para siempre.
    val (color, texto) = when {
        fallidos > 0 && enCola == 0 ->
            Coral to "$fallidos ${if (fallidos == 1) "mensaje no se envio" else "mensajes no se enviaron"}"
        enCola > 0 && conexion != EstadoConexion.CONECTADO ->
            Ambar to "Sin conexion - $enCola ${if (enCola == 1) "mensaje" else "mensajes"} en cola"
        conexion == EstadoConexion.CONECTADO && enCola > 0 ->
            Ambar to "Enviando $enCola ${if (enCola == 1) "pendiente" else "pendientes"}..."
        conexion == EstadoConexion.CONECTADO -> Cian to "Conectado"
        conexion == EstadoConexion.CONECTANDO -> Ambar to "Conectando..."
        else -> Coral to "Sin conexion"
    }
    Surface(color = BgElev, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(texto, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaChat(
    c: ChatFila,
    miUsuario: String,
    onClick: () -> Unit,
    onMantener: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMantener)
            // §15 · A la vista esta fila es UNA cosa; para un lector de
            // pantalla eran seis paradas -avatar, nombre, quien hablo, el
            // ultimo mensaje, la hora, el globo- y habia que armar la frase
            // uno mismo. Se fusiona y se describe entera. Ver `Accesibilidad`.
            .semantics(mergeDescendants = true) {
                contentDescription = descripcionDeFila(c, miUsuario)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            nombre = c.titulo,
            url = ApiCliente.urlImagen(c.avatarUsername, "avatar", c.avatarVersion),
            tamano = 50.dp,
            esGrupo = c.tipo != "directa",
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // El "@" es de las personas. Un grupo y un canal tienen
                    // nombre propio, y "@Prueba en vivo" se lee como un
                    // usuario que no existe.
                    if (c.tipo == "directa") "@${c.titulo}" else c.titulo,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.tipo == "grupo") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "grupo",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Slate.copy(alpha = 0.25f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // El check del ultimo mensaje propio, visible SIN entrar al chat.
                if (c.ultimoEsMio == true) {
                    val (icono, tinte) = iconoEstado(estadoDe(c.ultimoEstado))
                    Icon(icono, null, tint = tinte, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                }
                // En grupos, quien hablo. Sin esto no se sabe de quien es el ultimo
                // mensaje hasta abrir la conversacion.
                if (c.tipo == "grupo" && c.ultimoTexto != null) {
                    Text(
                        if (c.ultimoEsMio == true) "Tu: " else "${c.ultimoAutor.orEmpty()}: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorDeNombre(if (c.ultimoEsMio == true) miUsuario else c.ultimoAutor.orEmpty()),
                        maxLines = 1,
                    )
                }
                Text(
                    previaDe(c),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (c.ultimoTexto == null) TextoTerciario else TextoSecundario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.fijado) {
                    Icon(Icons.Filled.PushPin, "Fijado", tint = TextoTerciario, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (c.silenciado) {
                    Icon(Icons.Filled.NotificationsOff, "Silenciado", tint = TextoTerciario, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    horaCorta(c.ultimaFecha ?: 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.sinLeer && !c.silenciado) Cian else TextoTerciario,
                )
            }
            if (c.sinLeer) {
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (c.silenciado) Slate else Cian)
                        // Marcada a mano: un punto, sin numero. Poner "1"
                        // seria decir que hay un mensaje sin leer, y no lo hay.
                        .defaultMinSize(
                            minWidth = if (c.noLeidos > 0) 21.dp else 11.dp,
                            minHeight = if (c.noLeidos > 0) 21.dp else 11.dp,
                        )
                        .padding(horizontal = if (c.noLeidos > 0) 6.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (c.noLeidos > 0) {
                        Text(
                            if (c.noLeidos > 99) "99+" else c.noLeidos.toString(),
                            color = TextoSobreAcento,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogoNueva(
    grupoInicial: Boolean,
    onCerrar: () -> Unit,
    onDirecta: (String) -> Unit,
    onGrupo: (String, List<String>) -> Unit,
) {
    var esGrupo by remember { mutableStateOf(grupoInicial) }
    var usuario by remember { mutableStateOf("") }
    var nombreGrupo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(if (esGrupo) "Nuevo grupo" else "Nueva conversacion", color = TextoPrimario) },
        text = {
            Column {
                if (esGrupo) {
                    OutlinedTextField(
                        value = nombreGrupo,
                        onValueChange = { nombreGrupo = it },
                        label = { Text("Nombre del grupo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it },
                    label = { Text(if (esGrupo) "Usuarios, separados por coma" else "Usuario") },
                    prefix = { Text("@", color = TextoTerciario) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { esGrupo = !esGrupo }) {
                    Text(if (esGrupo) "Mejor una conversacion directa" else "Crear un grupo", color = Cian)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val us = usuario.split(",").map { it.trim().removePrefix("@").lowercase() }
                        .filter { it.isNotEmpty() }
                    if (us.isEmpty()) return@TextButton
                    if (esGrupo) {
                        if (nombreGrupo.isBlank()) return@TextButton
                        onGrupo(nombreGrupo.trim(), us)
                    } else onDirecta(us.first())
                }
            ) { Text("Crear", color = Cian) }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
}


/**
 * La linea de resumen de una conversacion en la lista.
 *
 * Un adjunto no se resume con su pie: una foto sin pie dejaba la linea **en
 * blanco**, y quien mira la lista no veia que habia llegado algo. La clase del
 * adjunto ya venia en la consulta y `Media.resumen` ya estaba escrita para
 * esto; solo faltaba unirlas.
 *
 * El nombre del documento se muestra porque para un documento **es** el
 * resumen: "Documento" a secas no distingue un contrato de un meme. Viene
 * saneado desde la ingesta -ver `nombreDeArchivoSeguro`-, que es lo que permite
 * ponerlo aqui sin pensarlo dos veces.
 */
private fun previaDe(c: ChatFila): String {
    val clase = c.ultimoAdjuntoClase.orEmpty()
    if (clase.isBlank()) return c.ultimoTexto ?: "Sin mensajes todavia"
    return Media.resumen(clase, c.ultimoTexto.orEmpty(), c.ultimoAdjuntoNombre.orEmpty())
}
