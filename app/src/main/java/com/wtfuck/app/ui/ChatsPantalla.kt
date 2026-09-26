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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
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
import com.wtfuck.app.ui.theme.TextoSobreAcento
import com.wtfuck.protocol.DuracionChat
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Timer

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
     * Abrir un chat SALTANDO a un mensaje. Lo usa el aviso de "no se envio".
     *
     * Aparte de [onAbrir] y no un tercer argumento suyo porque es un gesto
     * distinto: los seis sitios que abren un chat no quieren saltar a nada, y
     * anadirles un parametro vacio seria pedirles que decidan algo que no les
     * toca.
     */
    onAbrirEnMensaje: (conversacionId: String, mensajeId: String) -> Unit = { _, _ -> },
    /**
     * Ir al perfil. Sigue aqui porque tocar tu propia foto es el gesto que la
     * gente ya trae aprendido, aunque ahora el perfil tambien sea una pestaña.
     */
    onPerfil: () -> Unit,
    onCanales: () -> Unit,
    /** Crear un canal se hace en la pestaña Canales; esto la abre pidiendolo. */
    onNuevoCanal: () -> Unit = {},
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
    val convsConFallidos by app.repo.conversacionesConFallidos
        .collectAsStateWithLifecycle(emptyList())

    /**
     * Abre el chat donde quedo un mensaje sin enviar, en el mensaje.
     *
     * Con nombre y no como lambda dentro del argumento: `ambito.launch`
     * devuelve un `Job`, asi que la lambda quedaba de tipo `() -> Job` y hacia
     * falta un `Unit` colgando al final para corregirlo. Un `Unit` suelto es
     * justo lo que el compilador marca como "expresion sin usar", y tenia
     * razon: no es lo que se queria decir.
     */
    fun irAlFallo(c: com.wtfuck.app.datos.ChatFila) {
        ambito.launch {
            val m = app.repo.primerFallidoDe(c.id)
            // Al mensaje si se sabe cual; si no, al chat, que ya es mucho
            // mejor que nada.
            if (m != null) onAbrirEnMensaje(c.id, m) else onAbrir(c.id, c.tipo)
        }
    }
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

    /**
     * Los chats marcados. Vacio = modo normal.
     *
     * Con `listSaver` y no con `remember` a secas: girar el telefono en mitad
     * de una seleccion de doce chats y encontrarsela vacia es de las cosas que
     * mas molestan, y cuesta tres lineas evitarlo.
     */
    var seleccion by rememberSaveable(
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    var confirmarBorrado by remember { mutableStateOf(false) }
    /** El menu de creacion desplegado. Ver [MenuDeCreacion]. */
    var creando by remember { mutableStateOf(false) }
    /** Publicando una historia, y el motivo si no salio. Ver [HojaPublicarHistoria]. */
    var publicandoHistoria by remember { mutableStateOf(false) }
    var errorHistoria by remember { mutableStateOf<String?>(null) }

    // Salir de la seleccion con Atras, antes de que Atras signifique otra cosa.
    // Sin esto, el gesto natural para "me arrepenti" cierra la pestaña.
    BackHandler(enabled = seleccion.isNotEmpty()) { seleccion = emptySet() }
    BackHandler(enabled = seleccion.isEmpty() && creando) { creando = false }

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

    // Los chats marcados, como objetos. Se resuelve una vez y no por boton: la
    // barra contextual necesita saber si TODOS estan fijados, si TODOS estan
    // silenciados, etc., para decidir si ofrece poner o quitar.
    val marcados = remember(visibles, chats, seleccion) {
        chats.filter { it.id in seleccion }
    }
    // Si una seleccion se queda sin chats -se archivaron, se borraron- hay que
    // salir del modo: una barra contextual sobre cero elementos no tiene que
    // hacer, y deja la pantalla sin buscador ni filtros sin motivo.
    LaunchedEffect(marcados.isEmpty(), seleccion.isEmpty()) {
        if (seleccion.isNotEmpty() && marcados.isEmpty()) seleccion = emptySet()
    }

    /** Aplica la misma preferencia a todo lo marcado y sale del modo. */
    fun aTodos(hacer: suspend (ChatFila) -> Unit) {
        val lote = marcados
        seleccion = emptySet()
        ambito.launch {
            // En serie y no en paralelo: son pocas y cada una es una escritura
            // en el servidor. Un `map { async { } }` sobre cincuenta chats
            // abriria cincuenta peticiones a la vez para ahorrar medio segundo.
            lote.forEach { c -> runCatching { hacer(c) }.onFailure { errorDialogo = it.message } }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = BgBase,
        topBar = {
            // En modo seleccion la barra normal se sustituye entera: el
            // buscador y los filtros no sirven mientras se opera sobre un lote,
            // y dejarlos invita a tocarlos y perder la seleccion.
            if (seleccion.isNotEmpty()) {
                val lote = loteDe(marcados)
                BarraSeleccion(
                    cuantos = lote.cuantos,
                    todosFijados = lote.todosFijados,
                    todosSilenciados = lote.todosSilenciados,
                    todosLeidos = lote.todosLeidos,
                    enArchivados = verArchivados,
                    unoSolo = lote.unoSolo,
                    onCerrar = { seleccion = emptySet() },
                    onFijar = { v -> aTodos { app.repo.preferencias(it.id, PreferenciasChat(fijado = v)) } },
                    onSilenciar = { v ->
                        aTodos {
                            app.repo.preferencias(
                                it.id, PreferenciasChat(silenciarMinutos = if (v) -1 else 0),
                            )
                        }
                    },
                    onArchivar = { v -> aTodos { app.repo.preferencias(it.id, PreferenciasChat(archivado = v)) } },
                    onLeidas = { leer ->
                        val lote = marcados
                        seleccion = emptySet()
                        ambito.launch {
                            lote.forEach {
                                // `marcarLeidaLocal` y no `abrirChat`: baja
                                // el globo sin mandar acuse de lectura. Nadie
                                // leyo nada, y decir que si es mentirle a la
                                // otra persona sobre el unico dato que esa
                                // confirmacion aporta.
                                if (leer) app.repo.marcarLeidaLocal(it.id)
                                else app.repo.marcarNoLeida(it.id)
                            }
                        }
                    },
                    onEliminar = { confirmarBorrado = true },
                    onMas = { c -> seleccion = emptySet(); accionesDe = c },
                )
            } else {
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
                            Icon(Icons.Filled.MoreVert, "Más opciones", tint = TextoSecundario)
                        }
                        DropdownMenu(
                            expanded = menuAbierto,
                            onDismissRequest = { menuAbierto = false },
                            containerColor = BgElev,
                        ) {
                            // Crear ya no esta aqui: esta en el boton de
                            // abajo, que lo dice con todas las letras. Dos
                            // caminos al mismo sitio solo obligan a decidir
                            // cual es el bueno, y era la misma razon por la
                            // que "Canales" y "Mi perfil" se fueron a las
                            // pestañas.
                            //
                            // Lo que SI hace falta aqui es esto: mantener
                            // pulsado no se descubre solo. Es el gesto que
                            // usan las apps de mensajeria para seleccionar y
                            // no lo anuncia ninguna, asi que quien no lo sabe
                            // no lo encuentra nunca.
                            OpcionMenu("Seleccionar chats", Icons.Filled.DoneAll) {
                                menuAbierto = false
                                visibles.firstOrNull()?.let { seleccion = setOf(it.id) }
                            }
                            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                            OpcionMenu("Actualizar", Icons.Filled.Refresh) {
                                menuAbierto = false
                                ambito.launch { app.repo.sincronizar(); app.repo.despachar() }
                            }
                        }
                    },
                )
                BarraEstado(
                    conexion = conexion,
                    enCola = enCola,
                    fallidos = fallidos,
                    // El nombre del chat, para que el aviso diga DONDE. Se
                    // busca en la lista que ya esta cargada: un chat que no
                    // este ahi no tiene nombre que mostrar y el aviso se queda
                    // generico, que es mejor que inventar uno.
                    dondeFallo = convsConFallidos.singleOrNull()?.let { id ->
                        chats.firstOrNull { it.id == id }?.let { c ->
                            // "a joaquin" y "en Equipo seguridad". Un mensaje
                            // se le manda A una persona y se manda EN un
                            // grupo; la preposicion equivocada se nota al
                            // leer aunque nadie sepa decir por que.
                            val preposicion = if (c.tipo == "directa") "a" else "en"
                            "$preposicion ${c.titulo}"
                        }
                    },
                    // Si el chat no esta en la lista cargada no hay a donde
                    // ir, y el aviso se queda sin toque en vez de llevar a
                    // una pantalla vacia.
                    onIrAlFallo = convsConFallidos.firstOrNull()
                        ?.let { id -> chats.firstOrNull { it.id == id } }
                        ?.let { c -> { irAlFallo(c) } },
                )
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
            }
        },
        floatingActionButton = {
            // El boton dejo de ser un "+" suelto.
            //
            // Un "+" no dice que crea, y aqui se pueden crear tres cosas
            // distintas: una conversacion, un grupo y una historia. El "+"
            // hacia SOLO la primera, y las otras dos vivian escondidas —una en
            // el menu de tres puntos, la otra en un circulo de la fila de
            // historias— asi que la accion mas visible de la pantalla era la
            // que menos falta hacia descubrir.
            //
            // Ahora dice "Nuevo" y despliega las tres. Ver [MenuDeCreacion].
            //
            // No aparece en modo seleccion: crear algo nuevo no tiene nada que
            // ver con operar sobre lo que ya hay, y el boton taparia la lista
            // justo cuando hay que verla entera.
            if (seleccion.isEmpty()) {
                MenuDeCreacion(
                    abierto = creando,
                    onAbrir = { creando = it },
                    onConversacion = { arrancarEnGrupo = false; mostrarNueva = true },
                    onGrupo = { arrancarEnGrupo = true; mostrarNueva = true },
                    onCanal = onNuevoCanal,
                )
            }
        },
    ) { pad ->
      // El padding del Scaffold va UNA sola vez, aqui. Aplicarlo tambien
      // dentro deja la lista corrida: el hueco de la barra inferior se
      // reservaria dos veces y los chats se irian al fondo de la pantalla.
      Column(Modifier.fillMaxSize().padding(pad)) {
        // Va FUERA del `when` a proposito: dentro desapareceria justo cuando la
        // lista de chats esta vacia, que es cuando alguien recien empieza y mas
        // falta hace ver que esto existe.
        // Tampoco en modo seleccion, por lo mismo que el buscador y los
        // filtros: mientras se opera sobre un lote, todo lo que no sea el
        // lote estorba, y tocar una historia por error pierde la seleccion.
        if (seleccion.isEmpty()) {
            FilaHistorias(
                historias = historias,
                miUsuario = app.sesion.username.orEmpty(),
                onAbrir = { abiertoDe = it },
                onPublicar = { componiendo = true },
            )
            HorizontalDivider(color = Slate.copy(alpha = 0.18f))
        }

        when {
            chats.isEmpty() -> Vacio(
                "Todavía no tienes conversaciones",
                "Toca Nuevo, abajo, y escribe el usuario de alguien.",
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
                    Filtro.GRUPOS -> "Ningún grupo"
                    Filtro.TODOS -> "Nada por aquí"
                },
                when (filtro) {
                    Filtro.SIN_LEER -> "No te queda nada pendiente."
                    Filtro.GRUPOS -> "Crea uno con el boton Nuevo."
                    Filtro.TODOS -> "Toca Nuevo para empezar."
                },
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                // Hueco al final para el boton flotante.
                //
                // Sin esto la ultima conversacion queda DEBAJO del boton
                // "Nuevo" y no se puede abrir: la lista termina justo donde el
                // boton empieza. Con pocos chats no se nota porque la lista no
                // llega hasta abajo.
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
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
                        // En modo seleccion, tocar marca y desmarca. Abrir el
                        // chat con un toque mientras se selecciona es la forma
                        // mas rapida de perder una seleccion larga.
                        onClick = {
                            if (seleccion.isEmpty()) onAbrir(c.id, c.tipo)
                            else seleccion = if (c.id in seleccion) seleccion - c.id
                                             else seleccion + c.id
                        },
                        // Mantener pulsado ENTRA en seleccion en vez de abrir
                        // la hoja de acciones. La hoja no se pierde: con un
                        // solo chat marcado, la barra ofrece "Mas".
                        onMantener = { seleccion = seleccion + c.id },
                        marcado = c.id in seleccion,
                        enSeleccion = seleccion.isNotEmpty(),
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
            publicando = publicandoHistoria,
            error = errorHistoria,
            onPublicar = { texto, fondo, medio ->
                // La hoja NO se cierra al empezar, solo al terminar bien.
                //
                // Se cerraba antes de saber el resultado, asi que un fallo de
                // subida se llevaba por delante la foto elegida y el pie
                // escrito: para reintentar habia que volver a abrir, volver a
                // buscar la foto en la galeria y volver a escribir. Un error
                // de red no deberia costar el trabajo de nadie.
                publicandoHistoria = true
                errorHistoria = null
                ambito.launch {
                    app.repo.publicarHistoria(texto, fondo, medio)
                        .onSuccess { componiendo = false }
                        .onFailure { errorHistoria = it.message ?: "No se pudo publicar." }
                    publicandoHistoria = false
                }
            },
            onCerrar = { componiendo = false; errorHistoria = null },
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
                        "lo que se decida sobre otra cuenta no es información tuya.",
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
            onDirecta = { usuario, duracion ->
                ambito.launch {
                    runCatching { app.repo.nuevaDirecta(usuario, duracion) }
                        .onSuccess { mostrarNueva = false; onAbrir(it, "directa") }
                        .onFailure { errorDialogo = it.message }
                }
            },
            onGrupo = { nombre, usuarios, duracion ->
                ambito.launch {
                    runCatching { app.repo.nuevoGrupo(nombre, usuarios, duracion) }
                        .onSuccess { mostrarNueva = false; onAbrir(it, "grupo") }
                        .onFailure { errorDialogo = it.message }
                }
            },
        )
    }

    // Borrar en lote SI pregunta, y las otras cuatro acciones no.
    //
    // La diferencia no es el numero de chats: es que archivar, fijar,
    // silenciar y marcar como leido se deshacen con el mismo boton que los
    // hizo. Borrar no. Preguntar ante todo convierte la pregunta en un tramite
    // que se contesta sin leer, y entonces no protege de nada.
    if (confirmarBorrado) {
        val cuantos = marcados.size
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            containerColor = BgElev,
            title = {
                Text(
                    if (cuantos == 1) "Eliminar la conversación" else "Eliminar $cuantos conversaciones",
                    color = TextoPrimario,
                )
            },
            text = {
                Text(
                    // Se dice que el historial vive solo aqui porque en esta
                    // app es verdad y cambia la decision: no hay copia en un
                    // servidor de la que recuperarlo despues.
                    if (cuantos == 1) {
                        "Se borra de este aparato. El historial no esta en ningún servidor, " +
                            "así que no se puede recuperar."
                    } else {
                        "Se borran de este aparato las $cuantos. El historial no esta en " +
                            "ningún servidor, así que no se puede recuperar."
                    },
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrado = false
                    val lote = marcados
                    seleccion = emptySet()
                    ambito.launch {
                        lote.forEach { c ->
                            runCatching { app.repo.eliminarChat(c.id) }
                                .onFailure { errorDialogo = it.message }
                        }
                    }
                }) { Text("Eliminar", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarBorrado = false }) {
                    Text("Cancelar", color = TextoSecundario)
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
    // Un `OutlinedTextField` con su alto por defecto, borde visible y 8 dp de
    // margen arriba y abajo se comia **96 dp** de la primera pantalla para un
    // campo que casi nunca se usa. Entre el titulo, el buscador y las
    // pestañas, el primer chat empezaba pasada la mitad del telefono.
    //
    // Ahora es un campo relleno, sin borde y con alto propio: la mitad de
    // alto, y se sigue viendo que es un buscador porque lo dice la lupa y la
    // forma de pastilla. Un borde alrededor de algo que ya tiene fondo propio
    // no aporta nada que el fondo no diga.
    Surface(color = BgSurface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BgElev)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search, null,
                tint = TextoTerciario, modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = valor,
                onValueChange = onCambio,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextoPrimario),
                cursorBrush = SolidColor(Cian),
                modifier = Modifier
                    .weight(1f)
                    // 40 dp de alto: por debajo de eso el campo deja de ser
                    // comodo de tocar, y es el minimo que recomienda la guia
                    // de accesibilidad para algo que se toca con el dedo.
                    .heightIn(min = 40.dp)
                    .padding(vertical = 9.dp),
                decorationBox = { interior ->
                    if (valor.isEmpty()) {
                        Text(
                            "Buscar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextoTerciario,
                        )
                    }
                    interior()
                },
            )
            // Borrar lo escrito sin tener que mantener el borrado pulsado.
            // Aparece solo cuando hay algo: un icono que no hace nada es peor
            // que ninguno.
            if (valor.isNotEmpty()) {
                IconButton(onClick = { onCambio("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close, "Borrar la busqueda",
                        tint = TextoTerciario, modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * La barra de estado es donde `msg off` se vuelve visible. Si hay mensajes en
 * cola, el usuario ve en ambar cuantos son y que no se perdieron.
 */
@Composable
private fun BarraEstado(
    conexion: EstadoConexion,
    enCola: Int,
    fallidos: Int,
    /**
     * Donde fallo, ya con preposicion: "a joaquin", "en Equipo seguridad".
     *
     * Llega armado y no como nombre suelto porque la preposicion depende del
     * tipo de conversacion, y eso lo sabe quien tiene la lista, no la barra.
     */
    dondeFallo: String? = null,
    /** Abre ese chat. Nulo cuando no hay ninguno al que ir. */
    onIrAlFallo: (() -> Unit)? = null,
) {
    // Un mensaje FALLIDO no esta "enviandose": no va a salir solo. Mezclarlo
    // con los pendientes dejaba la barra diciendo "Enviando 1..." para siempre.
    // **"Conectado" no se dibuja.** Ocupaba una banda entera de la pantalla,
    // permanentemente, para anunciar el estado NORMAL. Una barra que esta
    // siempre deja de leerse, y entonces tampoco se lee el dia que dice algo
    // importante: lo unico que consigue es ensenar a ignorarla.
    //
    // Ahora aparece solo cuando hay algo que la persona podria querer hacer
    // -reintentar, esperar, buscar senal- y se va sola al arreglarse. Es el
    // mismo criterio que la banda de "sin conexion" de cualquier app: se nota
    // porque no esta siempre.
    if (conexion == EstadoConexion.CONECTADO && enCola == 0 && fallidos == 0) return

    val (color, texto) = when {
        fallidos > 0 && enCola == 0 -> {
            val cuantos = "$fallidos ${if (fallidos == 1) "mensaje no se envió" else "mensajes no se enviaron"}"
            // Con un solo chat se dice cual. Con varios no se enumeran: tres
            // nombres no entran y decir "en 2 chats" ya orienta.
            Coral to when {
                dondeFallo != null -> "$cuantos $dondeFallo · toca para ir"
                onIrAlFallo != null -> "$cuantos · toca para ir"
                else -> cuantos
            }
        }
        enCola > 0 && conexion != EstadoConexion.CONECTADO ->
            Ambar to "Sin conexión - $enCola ${if (enCola == 1) "mensaje" else "mensajes"} en cola"
        conexion == EstadoConexion.CONECTADO && enCola > 0 ->
            Ambar to "Enviando $enCola ${if (enCola == 1) "pendiente" else "pendientes"}..."
        conexion == EstadoConexion.CONECTADO -> Cian to "Conectado"
        conexion == EstadoConexion.CONECTANDO -> Ambar to "Conectando..."
        else -> Coral to "Sin conexión"
    }
    Surface(color = BgElev, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                // Solo se puede tocar cuando hay algo que hacer. Una barra
                // "clicable" que no lleva a ningun lado es peor que una que no
                // lo parece.
                .then(
                    if (onIrAlFallo != null) {
                        Modifier.clickable(onClick = onIrAlFallo)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                texto,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    marcado: Boolean = false,
    enSeleccion: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // El fondo es lo unico que dice "esta marcada", y por eso hay
            // ademas un check sobre el avatar: un tinte de fondo se pierde
            // entero con poco contraste o a pleno sol.
            .background(if (marcado) Cian.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onMantener)
            // §15 · A la vista esta fila es UNA cosa; para un lector de
            // pantalla eran seis paradas -avatar, nombre, quien hablo, el
            // ultimo mensaje, la hora, el globo- y habia que armar la frase
            // uno mismo. Se fusiona y se describe entera. Ver `Accesibilidad`.
            .semantics(mergeDescendants = true) {
                contentDescription = descripcionDeFila(c, miUsuario)
                // En modo seleccion la fila deja de ser "abrir el chat" y pasa
                // a ser una casilla. Sin esto, un lector de pantalla anuncia
                // que se va a abrir la conversacion y lo que pasa es otra cosa.
                if (enSeleccion) {
                    stateDescription = if (marcado) "seleccionada" else "sin seleccionar"
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        // `Top` y no `CenterVertically`. Centrada, la hora flotaba a media
        // altura entre el nombre y la vista previa, sin alinearse con
        // ninguno de los dos: se leia como un numero suelto. Arriba queda a
        // la misma altura que el nombre, que es a lo que pertenece.
        verticalAlignment = Alignment.Top,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            // Un canal llevaba el icono de grupo, asi que en la lista un
            // canal y un grupo eran identicos salvo por una etiqueta que solo
            // tenia uno de los dos. Son cosas distintas -en un canal publican
            // unos pocos y el resto lee- y ahora se ven distintas.
            AvatarDeChat(
                nombre = c.titulo,
                url = ApiCliente.urlImagen(c.avatarUsername, "avatar", c.avatarVersion),
                clase = claseDeTipo(c.tipo),
                tamano = 50.dp,
            )
            if (marcado) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Cian),
                    contentAlignment = Alignment.Center,
                ) {
                    // Sin descripcion: la fila ya se fusiona y dice
                    // "seleccionada" en su `stateDescription`.
                    Icon(
                        Icons.Filled.Check, null,
                        tint = TextoSobreAcento, modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Sin "@". Lo llevaba delante de toda conversacion
                    // directa, y eso convertia la lista en un listado de
                    // identificadores: "@tatiana" y no "Tatiana". Una agenda
                    // de telefono no muestra numeros.
                    //
                    // El titulo ya resuelve que decir -mi alias si la tengo
                    // agendada, el username si no- y en los dos casos el "@"
                    // sobra: delante de un nombre propio es ruido, y delante
                    // de un username no aporta nada que no diga el contexto.
                    c.titulo,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Aqui habia una etiqueta gris que decia "grupo". Se fue:
                // ahora el avatar distingue persona, grupo y canal con tres
                // iconos, y la palabra al lado del nombre era decir dos veces
                // lo mismo, robandole ancho al titulo. Para quien no ve el
                // icono, la palabra sigue en `descripcionDeFila`.
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
                        // El alias tambien aqui: si en un grupo escribe
                        // alguien de mi libreta, la lista lo llama como yo lo
                        // llamo. El color se calcula con el USERNAME, que es
                        // lo estable: dos contactos pueden compartir alias.
                        if (c.ultimoEsMio == true) "Tu: "
                        else "${c.aliasAutor.ifBlank { c.ultimoAutor.orEmpty() }}: ",
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

        // 3.dp de correccion optica: el texto de la hora es mas chico que el
        // del nombre, asi que con la misma coordenada sus lineas de base no
        // coinciden y se ve descolgada hacia arriba.
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.fijado) {
                    Icon(Icons.Filled.PushPin, "Fijado", tint = TextoTerciario, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (c.silenciado) {
                    Icon(Icons.Filled.NotificationsOff, "Silenciado", tint = TextoTerciario, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                // Temporal, y en CIAN y no en gris como los otros dos.
                //
                // Fijado y silenciado son preferencias: se cambian de idea y no
                // pasa nada. Que un chat se borre solo no se deshace, así que
                // no puede verse como un detalle más de la fila.
                if (c.expiraEn > 0) {
                    Icon(
                        Icons.Filled.Timer, "Chat temporal",
                        tint = Cian, modifier = Modifier.size(13.dp),
                    )
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
    onDirecta: (String, Long) -> Unit,
    onGrupo: (String, List<String>, Long) -> Unit,
) {
    var esGrupo by remember { mutableStateOf(grupoInicial) }
    var usuario by remember { mutableStateOf("") }
    var nombreGrupo by remember { mutableStateOf("") }
    /** `0` = para siempre, que es lo normal. */
    var duracion by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(if (esGrupo) "Nuevo grupo" else "Nueva conversación", color = TextoPrimario) },
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
                Spacer(Modifier.height(12.dp))

                // Temporal, y apagado por defecto.
                //
                // Lo normal es un chat que se queda. Ofrecerlo encendido haría
                // que alguien creara sin querer un chat que se borra, y eso no
                // se deshace: cuando se nota, ya no está.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = duracion != 0L,
                        onCheckedChange = { duracion = if (it) DuracionChat.HORAS_24 else 0L },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextoSobreAcento,
                            checkedTrackColor = Cian,
                            uncheckedThumbColor = TextoTerciario,
                            uncheckedTrackColor = BgElev,
                            uncheckedBorderColor = Slate,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Temporal", color = TextoPrimario)
                }

                if (duracion != 0L) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuracionChat.OPCIONES.forEach { ms ->
                            FilterChip(
                                selected = duracion == ms,
                                onClick = { duracion = ms },
                                label = { Text(etiquetaDuracionChat(ms)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cian,
                                    selectedLabelColor = TextoSobreAcento,
                                    labelColor = TextoSecundario,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // Lo que promete y lo que no, donde se decide.
                    //
                    // "Temporal" invita a entender "nadie lo va a poder ver", y
                    // no es eso: lo que hace es que el registro deje de
                    // existir. Una captura de pantalla, o alguien mirando por
                    // encima del hombro, no los para nada — y no decirlo aquí
                    // sería vender algo que la app no puede cumplir.
                    Text(
                        "Al cumplirse el plazo, la conversación y sus mensajes se " +
                            "borran en todos los teléfonos. No impide que alguien " +
                            "haga una captura antes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { esGrupo = !esGrupo }) {
                    Text(if (esGrupo) "Mejor una conversación directa" else "Crear un grupo", color = Cian)
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
                        onGrupo(nombreGrupo.trim(), us, duracion)
                    } else onDirecta(us.first(), duracion)
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
    if (clase.isBlank()) return c.ultimoTexto ?: "Sin mensajes todavía"
    return Media.resumen(clase, c.ultimoTexto.orEmpty(), c.ultimoAdjuntoNombre.orEmpty())
}

// ===========================================================================
//  El boton de crear, y la barra de seleccion
// ===========================================================================

/**
 * Que ofrece la barra contextual para un lote de chats.
 *
 * ## Por que es una funcion y no cuatro `all { }` dentro del Composable
 *
 * Porque es una **regla**, no un detalle de dibujo, y las reglas se prueban.
 * La regla es: *un boton hace lo que le falta al lote*. Con once chats
 * fijados y uno sin fijar, el boton **fija**; no desfija los once ni se queda
 * a medias. Asi una accion sobre un lote mixto deja el lote entero igual, que
 * es lo que alguien quiere decir cuando marca doce cosas y toca un boton.
 *
 * La alternativa —"la mayoria manda"— suena razonable y es peor: con siete de
 * doce fijados, desfijaria; con seis, fijaria. El resultado dependeria de una
 * cuenta que nadie hizo, y dos toques seguidos harian cosas distintas.
 *
 * `todosLeidos` mira `sinLeer`, que ya junta el globo de no leidos y la marca
 * manual de "no leida": son dos formas de estar pendiente y el boton las
 * apaga las dos.
 */
internal data class LoteDeChats(
    val cuantos: Int,
    /** Si todos estan fijados, el boton ofrece quitar. Si no, poner. */
    val todosFijados: Boolean,
    val todosSilenciados: Boolean,
    val todosLeidos: Boolean,
    /** El unico marcado, o null si hay cero o varios. Ver "Mas" en la barra. */
    val unoSolo: ChatFila?,
)

internal fun loteDe(marcados: List<ChatFila>) = LoteDeChats(
    cuantos = marcados.size,
    // Un lote VACIO no puede decir "todos": `all` sobre una lista vacia
    // devuelve true, y la barra ofreceria "quitar de fijados" sobre nada.
    // No llega a dibujarse -la pantalla sale del modo seleccion- pero una
    // funcion que miente en su caso limite acaba usandose en otro sitio.
    todosFijados = marcados.isNotEmpty() && marcados.all { it.fijado },
    // `silenciado` y no `silenciadoHasta != 0`: la columna guarda hasta
    // cuando, y un silencio VENCIDO deja un numero distinto de cero. Mirando
    // la columna en crudo, un chat silenciado hasta ayer ofreceria "quitar
    // silencio" hoy, sobre un silencio que ya no existe.
    todosSilenciados = marcados.isNotEmpty() && marcados.all { it.silenciado },
    todosLeidos = marcados.isNotEmpty() && marcados.none { it.sinLeer },
    unoSolo = marcados.singleOrNull(),
)

/**
 * El boton flotante, que dejo de ser un "+".
 *
 * ## Que estaba mal con el "+"
 *
 * Un "+" no dice que crea, y hacia una sola de las tres cosas que se pueden
 * crear. Un grupo estaba escondido en el menu de tres puntos; un canal ni
 * siquiera se podia empezar desde aqui, habia que ir a otra pestaña y
 * encontrar un icono. O sea que el elemento mas visible de la pantalla
 * resolvia la accion que menos falta hace descubrir.
 *
 * ## Por que NO esta "Historia"
 *
 * Porque ya tiene su sitio: el circulo "Publicar" de la fila de historias,
 * que esta justo encima y en su contexto. Ponerla tambien aqui seria el mismo
 * error que se acaba de corregir en el menu de tres puntos —dos caminos al
 * mismo sitio solo obligan a decidir cual es el bueno—, y esta vez con el
 * agravante de que el camino que ya existe es mejor.
 *
 * ## Por que lleva texto
 *
 * Cerrado dice "Nuevo", no un icono suelto. Un boton flotante con solo un
 * simbolo obliga a tocarlo para saber que hace, y tocar algo para averiguarlo
 * solo es gratis cuando no pasa nada; aqui abre una pantalla.
 *
 * ## Como se cierra
 *
 * Con Atras y al elegir una opcion. Que Atras funcione es lo importante: un
 * menu desplegado que solo se cierra tocando otra vez el boton deja sin salida
 * evidente a quien lo abrio.
 */
@Composable
private fun MenuDeCreacion(
    abierto: Boolean,
    onAbrir: (Boolean) -> Unit,
    onConversacion: () -> Unit,
    onGrupo: () -> Unit,
    onCanal: () -> Unit,
) {
    // El icono gira 45 grados: el mismo "+" se convierte en una X sin cambiar
    // de icono. Lo que cambia no es la forma, es lo que significa.
    val giro by animateFloatAsState(if (abierto) 45f else 0f, label = "giro")

    Column(horizontalAlignment = Alignment.End) {
        if (abierto) {
            OpcionDeCreacion("Canal", Icons.Filled.Campaign) { onAbrir(false); onCanal() }
            OpcionDeCreacion("Grupo", Icons.Filled.Group) { onAbrir(false); onGrupo() }
            OpcionDeCreacion("Conversación", Icons.Filled.PersonAdd) { onAbrir(false); onConversacion() }
            Spacer(Modifier.height(6.dp))
        }
        ExtendedFloatingActionButton(
            onClick = { onAbrir(!abierto) },
            containerColor = Cian,
            contentColor = TextoSobreAcento,
            modifier = Modifier.semantics {
                stateDescription = if (abierto) "desplegado" else "plegado"
            },
        ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.rotate(giro))
            Spacer(Modifier.width(8.dp))
            Text(if (abierto) "Cerrar" else "Nuevo", fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Una opcion del menu de creacion: etiqueta a la izquierda, circulo a la
 * derecha.
 *
 * La etiqueta es parte del boton y no una pastilla suelta al lado, asi el area
 * tocable incluye el texto. Un circulo de 36 dp flotando es un blanco pequeño,
 * y el texto que lo explica se quedaba fuera de el.
 */
@Composable
private fun OpcionDeCreacion(texto: String, icono: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(BgElev)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Nuevo: $texto" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, color = TextoPrimario, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Cian),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icono, null, tint = TextoSobreAcento, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * La barra que sustituye a la de arriba mientras hay chats marcados.
 *
 * ## Por que los botones cambian de significado
 *
 * No hay "fijar" y "desfijar" a la vez: hay un boton que hace lo que falta. Si
 * los doce marcados estan fijados, ofrece quitar; si alguno no lo esta, ofrece
 * poner. Dos botones obligarian a mirar cual de los dos esta activo, y con una
 * seleccion mixta ninguno de los dos seria el correcto.
 *
 * La regla para las mezclas es "lo que falta": con once fijados y uno no, el
 * boton fija. Asi una accion sobre un lote mixto deja el lote entero igual,
 * que es lo que alguien quiere decir al marcar doce cosas.
 *
 * ## Por que existe "Mas"
 *
 * Mantener pulsado ya no abre la hoja de acciones de un chat —ahora
 * selecciona— y esa hoja tiene cosas que no tienen sentido en lote: bloquear a
 * alguien, denunciar, silenciar ocho horas. Con UN solo chat marcado, "Mas" la
 * abre. Asi el gesto cambia de significado sin que se pierda nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarraSeleccion(
    cuantos: Int,
    todosFijados: Boolean,
    todosSilenciados: Boolean,
    todosLeidos: Boolean,
    enArchivados: Boolean,
    unoSolo: ChatFila?,
    onCerrar: () -> Unit,
    onFijar: (Boolean) -> Unit,
    onSilenciar: (Boolean) -> Unit,
    onArchivar: (Boolean) -> Unit,
    onLeidas: (Boolean) -> Unit,
    onEliminar: () -> Unit,
    onMas: (ChatFila) -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgElev),
        navigationIcon = {
            IconButton(onClick = onCerrar) {
                Icon(Icons.Filled.Close, "Salir de la seleccion", tint = TextoPrimario)
            }
        },
        title = {
            Text(
                "$cuantos",
                color = TextoPrimario,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                // El numero solo, leido en voz alta, es un acertijo.
                modifier = Modifier.semantics {
                    contentDescription = if (cuantos == 1) "1 chat seleccionado"
                                         else "$cuantos chats seleccionados"
                },
            )
        },
        actions = {
            IconButton(onClick = { onFijar(!todosFijados) }) {
                Icon(
                    if (todosFijados) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                    if (todosFijados) "Quitar de fijados" else "Fijar arriba",
                    tint = TextoSecundario,
                )
            }
            IconButton(onClick = { onSilenciar(!todosSilenciados) }) {
                Icon(
                    if (todosSilenciados) Icons.Filled.NotificationsActive
                    else Icons.Filled.NotificationsOff,
                    if (todosSilenciados) "Quitar silencio" else "Silenciar",
                    tint = TextoSecundario,
                )
            }
            IconButton(onClick = { onLeidas(!todosLeidos) }) {
                Icon(
                    if (todosLeidos) Icons.Filled.MarkChatUnread else Icons.Filled.DoneAll,
                    if (todosLeidos) "Marcar como no leidas" else "Marcar como leidas",
                    tint = TextoSecundario,
                )
            }
            IconButton(onClick = { onArchivar(!enArchivados) }) {
                Icon(
                    if (enArchivados) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    if (enArchivados) "Sacar del archivo" else "Archivar",
                    tint = TextoSecundario,
                )
            }
            IconButton(onClick = onEliminar) {
                Icon(Icons.Filled.DeleteOutline, "Eliminar", tint = Coral)
            }
            // Solo con uno marcado: lo de dentro no tiene version en lote.
            unoSolo?.let { c ->
                IconButton(onClick = { onMas(c) }) {
                    Icon(Icons.Filled.MoreVert, "Más acciones de este chat", tint = TextoSecundario)
                }
            }
        },
    )
}

/**
 * "1 h", "24 h", "7 días".
 *
 * Aparte de `etiquetaDuracion` —la de la ubicación en vivo— porque las escalas
 * no se tocan: aquella va de 15 minutos a 24 horas y ésta de 1 hora a 7 días.
 * Una sola función para las dos tendría que cubrir un rango donde la mitad de
 * los casos no existen.
 */
internal fun etiquetaDuracionChat(ms: Long): String = when (ms) {
    DuracionChat.HORA_1 -> "1 h"
    DuracionChat.HORAS_8 -> "8 h"
    DuracionChat.HORAS_24 -> "24 h"
    DuracionChat.DIAS_7 -> "7 días"
    else -> "${ms / 3_600_000} h"
}
