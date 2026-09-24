package com.wtfuck.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.EstadoConexion
import com.wtfuck.app.datos.jsonApp
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.protocol.ClaseContenido
import com.wtfuck.protocol.EstadoEnvio
import com.wtfuck.protocol.FichaEmpresa
import com.wtfuck.protocol.ReaccionAgrupada
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.wtfuck.protocol.UsuarioPublico

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPantalla(
    conversacionId: String,
    onInfoGrupo: () -> Unit,
    onVerificarCifrado: () -> Unit,
    /** Abrir la conversacion con alguien: lo pide la tarjeta de contacto. */
    onAbrirChatCon: (String) -> Unit,
    onAtras: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    val mensajes by app.repo.mensajes(conversacionId).collectAsStateWithLifecycle(emptyList())
    val fijados by app.repo.fijados(conversacionId).collectAsStateWithLifecycle(emptyList())
    val conexion by app.repo.estadoConexion.collectAsStateWithLifecycle()
    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val chat = chats.firstOrNull { it.id == conversacionId }

    /**
     * Lo escrito, con el cursor.
     *
     * `TextFieldValue` y no `String` porque el selector de menciones necesita
     * saber DONDE esta el cursor: "lo que hay antes del cursor" es lo unico
     * que distingue estar escribiendo `@ta` de haber escrito `@tatiana hola`
     * y estar corrigiendo el principio de la frase. Con un String solo se
     * puede mirar el final del texto, y entonces el selector no aparece al
     * editar por el medio.
     */
    var texto by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    /**
     * A quien se puede mencionar aqui: los participantes, con MI nombre para
     * cada uno.
     *
     * Se arma una vez por conversacion y no en cada tecla. Lo que se inserta
     * es siempre el username -es lo unico que el servidor resuelve- pero lo
     * que se busca y se muestra es el nombre, porque quien escribe piensa en
     * la persona que tiene delante y no en su identificador.
     */
    var candidatosMencion by remember { mutableStateOf<List<CandidatoMencion>>(emptyList()) }

    /**
     * Username -> como lo llamo yo. Lo usan el selector de menciones y la
     * etiqueta de autor de cada burbuja de grupo, que son la misma pregunta.
     */
    var nombresDeGente by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(chat?.participantes) {
        val gente = chat?.participantes.orEmpty().split(",").filter { it.isNotBlank() }
        val nombres = if (gente.isEmpty()) emptyMap() else app.repo.nombresDeLibreta(gente)
        nombresDeGente = nombres
        candidatosMencion = gente.map { CandidatoMencion(it, nombres[it] ?: it) }
    }
    var menuAbierto by remember { mutableStateOf(false) }
    var accionesDe by remember { mutableStateOf<MensajeEnt?>(null) }
    var denunciando by remember { mutableStateOf<MensajeEnt?>(null) }
    var enviandoDenuncia by remember { mutableStateOf(false) }
    var denunciaHecha by remember { mutableStateOf(false) }
    var respondiendoA by remember { mutableStateOf<MensajeEnt?>(null) }
    var editando by remember { mutableStateOf<MensajeEnt?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val portapapeles = LocalClipboardManager.current
    var mostrarInfo by remember { mutableStateOf(false) }
    /**
     * La ficha de empresa de la otra persona, si tiene.
     *
     * Se pide al abrir el contacto y no al abrir el chat: es un dato que casi
     * nadie va a mirar, y pedirlo siempre seria una peticion de red por cada
     * conversacion que se abre para dibujar algo que esta detras de un menu.
     */
    var fichaDelOtro by remember { mutableStateOf<FichaEmpresa?>(null) }

    // L.1 · Quien escribe. Se apaga solo: ver `Repositorio.escribiendo`.
    val escribiendoTodos by app.repo.escribiendo.collectAsStateWithLifecycle()
    var quienEscribe by remember(conversacionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(escribiendoTodos, conversacionId) {
        val marca = escribiendoTodos[conversacionId]
        if (marca == null) {
            quienEscribe = null
            return@LaunchedEffect
        }
        val resto = 6_000 - (System.currentTimeMillis() - marca.second)
        if (resto <= 0) {
            quienEscribe = null
        } else {
            quienEscribe = marca.first
            // Seis segundos sin otro aviso y se apaga. El cliente manda uno
            // cada cuatro, asi que mientras alguien escriba de verdad esto no
            // llega a cumplirse.
            kotlinx.coroutines.delay(resto)
            quienEscribe = null
        }
    }

    // L.1 · Presencia de la otra persona.
    //
    // Antes la cabecera decia "conectado" SIEMPRE, para cualquiera, sin ningun
    // dato detras: un adorno con forma de informacion. Peor que no decir nada,
    // porque la gente lo lee y decide cosas con eso.
    //
    // Se pide al servidor -que aplica la privacidad del otro y la
    // reciprocidad- y se refresca cada 45 s mientras el chat este abierto. No
    // por socket: un canal de presencia en vivo para todos los contactos es
    // trafico constante para un dato que se mira de refilon.
    var presenciaOtro by remember(conversacionId) { mutableStateOf<UsuarioPublico?>(null) }
    LaunchedEffect(conversacionId, chat?.tipo) {
        if (chat?.tipo != "directa") return@LaunchedEffect
        while (true) {
            presenciaOtro = app.repo.perfilDe(chat.nombre)
            kotlinx.coroutines.delay(45_000)
        }
    }

    // Modulo K. El permiso de microfono se pide aqui y no dentro del servicio
    // porque pedir permisos necesita una Activity, y el servicio de llamadas a
    // proposito no sabe nada de la capa de Android.
    val llamar = recordarInicioLlamada { aviso = it }
    var confirmarSalir by remember { mutableStateOf(false) }
    val lista = rememberLazyListState()
    val contexto = LocalContext.current

    // --- modulo M: buscador dentro de la conversacion ---
    //
    // El texto solo existe en claro en este telefono, asi que buscar es
    // necesariamente local. Es la misma razon por la que el panel de
    // administracion no puede buscar mensajes de nadie.
    var buscando by remember { mutableStateOf(false) }
    var consulta by rememberSaveable { mutableStateOf("") }
    var hallazgos by remember { mutableStateOf<List<MensajeEnt>>(emptyList()) }
    var cualHallazgo by remember { mutableIntStateOf(0) }

    // --- modulo D: adjuntos ---
    var hojaAdjuntar by remember { mutableStateOf(false) }
    var hojaUbicacion by remember { mutableStateOf(false) }
    var hojaContacto by remember { mutableStateOf(false) }
    var hojaEncuesta by remember { mutableStateOf(false) }
    var hojaEvento by remember { mutableStateOf(false) }
    var hojaEmoji by remember { mutableStateOf(false) }
    var hojaSticker by remember { mutableStateOf(false) }
    /**
     * Un aviso de que algo salio BIEN.
     *
     * Aparte de `aviso`, que es el de los errores y se titula "No se pudo
     * completar". Son dos cosas distintas y meterlas en el mismo sitio hace
     * que una confirmacion se lea como un fallo.
     */
    var confirmacion by remember { mutableStateOf<String?>(null) }

    // Un solo reproductor para toda la pantalla: dos notas de voz sonando a la
    // vez no es un caso a soportar, es un defecto.
    // La velocidad se recupera al crear la pantalla: quien escucha a 2x lo
    // hace porque prefiere escuchar asi, no para una nota suelta.
    val reproductor = remember {
        Reproductor(ambito).also { it.recordarVelocidad(app.ajustes.velocidadAudio) }
    }
    val grabadora = remember { Grabadora(contexto) }
    var grabando by remember { mutableStateOf(false) }
    var segundosGrabados by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            reproductor.detener()
            // Salir del chat mientras se graba cancela: el archivo a medias no
            // le sirve a nadie y ocuparia cache para siempre.
            grabadora.cancelar()
        }
    }


    // E.6: si la clave de seguridad de la otra persona cambio, hay que
    // decirlo DENTRO del chat, no en una pantalla que nadie abre.
    var claveCambiada by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conversacionId) {
        claveCambiada = runCatching { app.repo.claveCambiada(conversacionId) }.getOrNull()
    }

    /** Envia un archivo usando el texto escrito como pie de foto. */
    fun mandarArchivo(uri: android.net.Uri, clase: String) {
        val pie = texto.text.trim()
        texto = TextFieldValue("")
        ambito.launch {
            runCatching { app.repo.enviarAdjunto(conversacionId, uri, clase, pie) }
                .onFailure { aviso = it.message }
        }
    }

    // El selector de fotos del sistema no necesita permiso de almacenamiento:
    // devuelve solo lo que la persona eligio. Pedir READ_MEDIA_IMAGES para esto
    // seria pedir acceso a la galeria entera sin motivo.
    val elegirMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val mime = contexto.contentResolver.getType(it).orEmpty()
            mandarArchivo(it, Media.claseDe(mime))
        }
    }

    val elegirDocumento = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { mandarArchivo(it, ClaseAdjunto.DOCUMENTO) } }

    /**
     * El reloj de la barra de grabacion.
     *
     * Vive aqui abajo y no arriba del todo porque necesita `mandarArchivo`:
     * cuando el sistema corta por tope, la nota se cierra y se manda **sola**.
     * Dejar la barra contando segundos sobre un archivo que ya no crece seria
     * mentir, y que alguien suelte para descubrir que se corto hace diez
     * minutos, peor.
     */
    LaunchedEffect(grabando) {
        segundosGrabados = 0
        while (grabando) {
            delay(500)
            segundosGrabados = (grabadora.milisegundos() / 1000).toInt()
            if (grabadora.topeAlcanzado) {
                grabando = false
                val f = grabadora.terminar()
                if (f != null) {
                    aviso = "La nota llego al maximo y se envio."
                    mandarArchivo(android.net.Uri.fromFile(f), ClaseAdjunto.NOTA_VOZ)
                }
                break
            }
        }
    }

    fun arrancarGrabacion() {
        if (grabadora.iniciar()) grabando = true
        else aviso = "No se pudo acceder al microfono."
    }

    val pedirMicro = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) arrancarGrabacion()
        else aviso = "Sin permiso de microfono no se puede grabar."
    }

    fun grabarNotaVoz() {
        val tienePermiso = androidx.core.content.ContextCompat.checkSelfPermission(
            contexto, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (tienePermiso) arrancarGrabacion()
        else pedirMicro.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // Mientras este chat esta abierto, lo que llegue aqui no suma no leidos.
    DisposableEffect(conversacionId) {
        app.repo.abrirChat(conversacionId)
        onDispose { app.repo.cerrarChat() }
    }

    // Los temporales vencidos se borran al abrir el chat: no hace falta un job
    // en segundo plano para algo que solo importa cuando se mira.
    LaunchedEffect(conversacionId) { app.repo.limpiarVencidos() }

    // Si el servidor rechaza un envio (silenciado, expulsado), se dice por que.
    LaunchedEffect(Unit) {
        app.repo.rechazos.collect { aviso = it }
    }

    // Se busca con un retardo corto: escribir "reunion" son siete consultas a
    // la base si se dispara en cada letra, y las ultimas seis no se leen.
    LaunchedEffect(consulta, buscando) {
        if (!buscando || consulta.trim().length < 2) {
            hallazgos = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        hallazgos = app.repo.buscarEnChat(conversacionId, consulta)
        cualHallazgo = 0
        // Al primer resultado se salta solo: buscar y despues tener que tocar
        // "siguiente" para ver algo es un paso de mas.
        //
        // El salto va en runCatching: la posicion sale de otra consulta, y un
        // scroll que falla no debe tirar la pantalla con el resultado ya
        // encontrado en la mano.
        hallazgos.firstOrNull()?.let { m ->
            runCatching { lista.scrollToItem(app.repo.posicionDe(conversacionId, m)) }
        }
    }

    LaunchedEffect(mensajes.size, buscando) {
        // Mientras se busca NO se salta al final: el buscador acaba de mover la
        // lista al resultado, y un mensaje nuevo que llegue en ese momento
        // arrastraria la pantalla lejos de lo que la persona estaba leyendo.
        if (mensajes.isNotEmpty() && !buscando) lista.animateScrollToItem(mensajes.lastIndex)
    }

    // §15 · Atajos de teclado, que en esta pantalla son dos y no diez.
    //
    // Llegan aqui porque en un aparato con teclado -tablet con funda, plegable
    // abierto, ChromeOS- son los dos gestos que de otro modo hay que hacer con
    // el dedo cruzando la pantalla: abrir el buscador y cerrarlo.
    //
    // No se inventan mas: un atajo que nadie descubre es codigo muerto, y estos
    // dos son los que cualquiera prueba sin que se los digan porque son los de
    // todos los programas. `Esc` cierra y `Ctrl+F` busca.
    //
    // `onPreviewKeyEvent` y no `onKeyEvent`: si esperara el turno normal, el
    // campo de texto se come el Escape antes de que llegue aqui.
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }

    CompositionLocalProvider(LocalReproductor provides reproductor) {
    Scaffold(
        modifier = Modifier
            .focusRequester(foco)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.key == Key.Escape && buscando -> {
                        buscando = false; consulta = ""; hallazgos = emptyList(); true
                    }
                    ev.key == Key.F && ev.isCtrlPressed && !buscando -> {
                        buscando = true; true
                    }
                    else -> false
                }
            },
        containerColor = BgBase,
        topBar = {
            if (buscando) {
                BarraBusquedaChat(
                    consulta = consulta,
                    onConsulta = { consulta = it },
                    total = hallazgos.size,
                    cual = cualHallazgo,
                    onMover = { paso ->
                        if (hallazgos.isNotEmpty()) {
                            // Envuelve en los dos sentidos: llegar al ultimo y
                            // seguir tocando "siguiente" sin que pase nada
                            // parece que la app se colgo.
                            cualHallazgo =
                                (cualHallazgo + paso + hallazgos.size) % hallazgos.size
                            ambito.launch {
                                runCatching {
                                    lista.animateScrollToItem(
                                        app.repo.posicionDe(conversacionId, hallazgos[cualHallazgo])
                                    )
                                }
                            }
                        }
                    },
                    onCerrar = {
                        buscando = false
                        consulta = ""
                        hallazgos = emptyList()
                    },
                )
                return@Scaffold
            }
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            nombre = chat?.titulo.orEmpty(),
                            url = ApiCliente.urlImagen(
                                chat?.avatarUsername.orEmpty(), "avatar", chat?.avatarVersion ?: 0L,
                            ),
                            tamano = 38.dp,
                            esGrupo = chat?.tipo == "grupo",
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                // Sin "@", igual que en la lista: el titulo
                                // ya es mi nombre para esa persona, o su
                                // username si no la tengo agendada.
                                chat?.titulo ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextoPrimario,
                            )
                            val sub = when {
                                // Si ya no pertenezco, el recuento local esta
                                // viejo: decir "2 miembros" es peor que no
                                // decir nada.
                                chat != null && !chat.soyMiembro -> "ya no eres miembro"
                                conexion != EstadoConexion.CONECTADO -> "sin conexion"
                                // Escribiendo manda sobre todo lo demas: es lo
                                // unico que esta pasando AHORA.
                                quienEscribe != null && chat?.tipo != "directa" ->
                                    "@$quienEscribe esta escribiendo..."
                                quienEscribe != null -> "escribiendo..."
                                chat?.tipo == "grupo" ->
                                    "${chat.participantes.split(",").filter { it.isNotBlank() }.size + 1} miembros"
                                chat?.tipo == "canal" -> "canal"
                                // Sin dato de presencia no se escribe nada.
                                // Inventar "conectado" era exactamente el
                                // defecto que L.1 vino a arreglar.
                                else -> presenciaOtro?.let {
                                    presencia(it.enLinea, it.ultimaVez)
                                }.orEmpty()
                            }
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    chat != null && !chat.soyMiembro -> Coral
                                    conexion != EstadoConexion.CONECTADO -> Ambar
                                    // Cian solo para "en linea". Que "ult. vez
                                    // ayer" se pintara igual que "en linea"
                                    // haria que el color dejara de significar
                                    // algo.
                                    quienEscribe != null -> Cian
                                    sub == "en linea" -> Cian
                                    else -> TextoTerciario
                                },
                            )
                        }
                    }
                },
                actions = {
                    // Llamar solo en una directa donde sigo siendo miembro. En
                    // un grupo la llamada en malla esta limitada a 4 y no hay
                    // interfaz para elegir a quien, asi que ofrecer el boton
                    // seria prometer algo que la pantalla no cumple.
                    if (chat?.tipo == "directa" && chat.soyMiembro) {
                        IconButton(onClick = { llamar(conversacionId, chat.titulo, false) }) {
                            Icon(Icons.Filled.Phone, "Llamar", tint = TextoSecundario)
                        }
                        IconButton(onClick = { llamar(conversacionId, chat.titulo, true) }) {
                            Icon(Icons.Filled.Videocam, "Videollamada", tint = TextoSecundario)
                        }
                    }
                    IconButton(onClick = { menuAbierto = true }) {
                        Icon(Icons.Filled.MoreVert, "Mas opciones", tint = TextoSecundario)
                    }
                    DropdownMenu(
                        expanded = menuAbierto,
                        onDismissRequest = { menuAbierto = false },
                        containerColor = BgElev,
                    ) {
                        OpcionMenu(
                            if (chat?.tipo == "grupo") "Info del grupo" else "Ver contacto",
                            Icons.Filled.Info,
                        ) {
                            menuAbierto = false
                            // Un grupo tiene administracion; una directa solo
                            // una tarjeta con los datos del contacto.
                            if (chat?.tipo == "grupo") {
                                onInfoGrupo()
                            } else {
                                mostrarInfo = true
                                // El perfil publico se pide aqui. Si falla, la
                                // tarjeta simplemente no aparece: el contacto
                                // se abre igual, porque una ficha de empresa no
                                // es lo que se vino a ver.
                                val quien = chat?.titulo
                                if (quien != null) {
                                    ambito.launch {
                                        fichaDelOtro =
                                            runCatching { app.repo.perfilDe(quien)?.empresa }
                                                .getOrNull()
                                    }
                                }
                            }
                        }

                        OpcionMenu("Buscar en el chat", Icons.Filled.Search) {
                            menuAbierto = false; buscando = true
                        }

                        OpcionMenu("Vaciar chat", Icons.Filled.DeleteSweep) {
                            menuAbierto = false
                            ambito.launch { app.repo.vaciarChat(conversacionId) }
                        }

                        OpcionMenu("Verificar cifrado", Icons.Filled.Lock, Cian) {
                            menuAbierto = false; onVerificarCifrado()
                        }

                        // Si ya te expulsaron no tiene sentido "salir": la
                        // opcion que queda es eliminar el chat.
                        if (chat?.tipo == "grupo" && chat.soyMiembro) {
                            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                            OpcionMenu("Salir del grupo", Icons.AutoMirrored.Filled.Logout, Coral) {
                                menuAbierto = false; confirmarSalir = true
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Ya no soy miembro: no hay campo de texto. Dejarlo ahi solo
            // consigue que la persona escriba y vea el mensaje fallar.
            if (chat != null && !chat.soyMiembro) {
                Surface(color = BgSurface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Block, null, tint = TextoTerciario, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Ya no eres miembro de este grupo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoTerciario,
                        )
                    }
                }
                return@Scaffold
            }

            Surface(color = BgSurface) {
                Column {

                // Cabecera de "respondiendo a" o "editando": sin esto no se ve
                // que el proximo envio no es un mensaje nuevo.
                (respondiendoA ?: editando)?.let { ctx ->
                    val esEdicion = editando != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(BgElev)
                            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (esEdicion) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply,
                            null,
                            tint = Cian,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (esEdicion) "Editando" else "Respondiendo a @${ctx.autor}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Cian,
                            )
                            Text(
                                ctx.texto,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextoSecundario,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            respondiendoA = null
                            if (editando != null) { editando = null; texto = TextFieldValue("") }
                        }) {
                            Icon(Icons.Filled.Close, "Cancelar", tint = TextoSecundario)
                        }
                    }
                    HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                }

                if (grabando) {
                    BarraGrabando(
                        segundos = segundosGrabados,
                        nivel = grabadora.nivel(),
                        onCancelar = { grabando = false; grabadora.cancelar() },
                        onEnviar = {
                            grabando = false
                            val f = grabadora.terminar()
                            if (f == null) aviso = "Nota demasiado corta."
                            else mandarArchivo(android.net.Uri.fromFile(f), ClaseAdjunto.NOTA_VOZ)
                        },
                    )
                    return@Column
                }

                Column(Modifier.navigationBarsPadding().imePadding()) {
                // El selector de menciones, encima del campo. Solo en grupos y
                // canales: en una directa hay una sola persona al otro lado y
                // ofrecer una lista de uno es ruido.
                //
                // Se calcula en cada tecla y es barato: `mencionEnCurso`
                // devuelve null en cuanto no hay un "@" abierto antes del
                // cursor, que es casi siempre.
                val enCurso = if (chat?.tipo == "directa") null
                              else mencionEnCurso(texto.text, texto.selection.start)
                if (enCurso != null) {
                    TiraDeMenciones(
                        candidatos = candidatosDeMencion(enCurso, candidatosMencion),
                        onElegir = { c ->
                            val (t, cur) = insertarMencion(
                                texto.text, texto.selection.start, c.username,
                            )
                            texto = TextFieldValue(t, TextRange(cur))
                        },
                    )
                }
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = { hojaEmoji = true }) {
                        Icon(Icons.Filled.EmojiEmotions, "Emojis", tint = TextoSecundario)
                    }
                    IconButton(onClick = { hojaAdjuntar = true }) {
                        Icon(Icons.Filled.AttachFile, "Adjuntar", tint = TextoSecundario)
                    }
                    OutlinedTextField(
                        value = texto,
                        onValueChange = {
                            texto = it
                            // El freno esta en el Repositorio: aqui se avisa en
                            // cada tecla y alli se decide si toca mandarlo.
                            if (it.text.isNotEmpty()) app.repo.avisarQueEscribo(conversacionId)
                        },
                        placeholder = { Text("Mensaje", color = TextoTerciario) },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                            focusedContainerColor = BgElev,
                            unfocusedContainerColor = BgElev,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Sin texto el boton graba, con texto envia. Es el gesto
                    // que ya conoce cualquiera que use un mensajero: el mismo
                    // lugar sirve para las dos cosas y nunca esta apagado.
                    val hayTexto = texto.text.isNotBlank()
                    FilledIconButton(
                        onClick = {
                            if (!hayTexto) {
                                grabarNotaVoz()
                                return@FilledIconButton
                            }
                            val t = texto.text
                            val cita = respondiendoA
                            val edit = editando
                            texto = TextFieldValue("")
                            respondiendoA = null
                            editando = null
                            ambito.launch {
                                runCatching {
                                    if (edit != null) app.repo.editarMensaje(edit.id, t)
                                    else app.repo.enviarTexto(conversacionId, t, respondeA = cita)
                                }.onFailure { aviso = it.message }
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Cian,
                            contentColor = TextoSobreAcento,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        if (hayTexto) Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                        else Icon(Icons.Filled.Mic, "Grabar nota de voz")
                    }
                }
                }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

        if (fijados.isNotEmpty()) {
            val fijado = fijados.first()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BgElev)
                    .clickable {
                        val i = mensajes.indexOfFirst { it.id == fijado.id }
                        if (i >= 0) ambito.launch { lista.animateScrollToItem(i) }
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PushPin, null, tint = Cian, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (fijados.size > 1) "Mensajes fijados (${fijados.size})" else "Mensaje fijado",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cian,
                    )
                    Text(
                        fijado.texto,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
        }

        claveCambiada?.let { quien ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Coral.copy(alpha = 0.16f))
                    .clickable(onClick = onVerificarCifrado)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Coral, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "La clave de seguridad de @$quien cambio",
                        color = Coral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Toca para verificar",
                        color = TextoSecundario,
                        fontSize = 11.sp,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Coral, modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(
            state = lista,
            modifier = Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            items(mensajes, key = { it.id }) { m ->
                if (m.esSistema) {
                    LineaSistema(m.texto)
                } else {
                    Burbuja(
                        m = m,
                        esGrupo = chat?.tipo == "grupo",
                        miUsuario = app.sesion.username.orEmpty(),
                        nombreDe = { u -> nombresDeGente[u] ?: u },
                        resaltado = buscando && hallazgos.getOrNull(cualHallazgo)?.id == m.id,
                        onReintentar = {
                            ambito.launch {
                                // Un adjunto fallido se reintenta desde el
                                // archivo local: hay que volver a subirlo, no
                                // solo reenviar el sobre.
                                if (m.adjuntoClase.isNotBlank() && m.adjuntoId == null) {
                                    app.repo.reintentarAdjunto(m.id)
                                } else {
                                    app.repo.reintentar(m.id)
                                }
                            }
                        },
                        onDescargar = { ambito.launch { app.repo.descargarAdjunto(m.id) } },
                        onAbrir = { archivo ->
                            runCatching {
                                contexto.startActivity(app.archivos.intentVer(archivo, m.adjuntoMime))
                            }.onFailure { aviso = "No hay ninguna app que pueda abrir este archivo." }
                        },
                        onMantener = { if (!m.retirado) accionesDe = m },
                        onReaccion = { emoji, poner ->
                            ambito.launch {
                                runCatching { app.repo.reaccionar(m.id, emoji, poner) }
                                    .onFailure { aviso = it.message }
                            }
                        },
                        onAbrirContacto = { quien ->
                            ambito.launch {
                                runCatching { app.repo.nuevaDirecta(quien) }
                                    .onSuccess { onAbrirChatCon(it) }
                                    // Puede fallar legitimamente: esa persona
                                    // quizas no acepta mensajes de desconocidos.
                                    // El motivo lo da el servidor.
                                    .onFailure { aviso = it.message }
                            }
                        },
                        // Deslizar la burbuja hacia la derecha: el mismo
                        // destino que el "Responder" del menu, por otro camino.
                        onResponder = { respondiendoA = m },
                    )
                }
            }
        }
        }
    }

    if (hojaAdjuntar) {
        HojaAdjuntar(
            onGaleria = {
                elegirMedia.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            },
            onDocumento = { elegirDocumento.launch(arrayOf("*/*")) },
            onNotaVoz = { grabarNotaVoz() },
            onSticker = { hojaSticker = true },
            onUbicacion = { hojaUbicacion = true },
            onContacto = { hojaContacto = true },
            onEncuesta = { hojaEncuesta = true },
            onEvento = { hojaEvento = true },
            // El permiso real lo resuelve el servidor al registrar el mensaje.
            // Esto solo evita ofrecer una puerta que va a dar 403.
            //
            // Los umbrales siguen el reparto de V4: `encuesta.crear` la tiene
            // el rol miembro, `evento.crear` empieza en moderador (50).
            puedeEncuesta = chat?.soyMiembro == true && chat.tipo != "canal",
            puedeEvento = (chat?.miJerarquia ?: 0) >= 50 && chat?.tipo != "canal",
            onCerrar = { hojaAdjuntar = false },
        )
    }

    if (hojaUbicacion) {
        HojaUbicacion(
            onEnviar = { lat, lon, precision, etiqueta ->
                hojaUbicacion = false
                ambito.launch {
                    runCatching {
                        app.repo.enviarUbicacion(conversacionId, lat, lon, precision, etiqueta)
                    }.onFailure { aviso = it.message }
                }
            },
            onCerrar = { hojaUbicacion = false },
        )
    }

    if (hojaContacto) {
        HojaContacto(
            onEnviar = { username, nombre ->
                hojaContacto = false
                ambito.launch {
                    runCatching { app.repo.enviarContacto(conversacionId, username, nombre) }
                        .onFailure { aviso = it.message }
                }
            },
            onCerrar = { hojaContacto = false },
        )
    }

    if (hojaEncuesta) {
        HojaEncuesta(
            onCrear = { pregunta, opciones, multiple ->
                hojaEncuesta = false
                ambito.launch {
                    runCatching {
                        app.repo.crearEncuesta(conversacionId, pregunta, opciones, multiple)
                    }.onFailure { aviso = it.message }
                }
            },
            onCerrar = { hojaEncuesta = false },
        )
    }

    if (hojaEvento) {
        HojaEvento(
            onCrear = { titulo, cuando, lugar, nota ->
                hojaEvento = false
                ambito.launch {
                    runCatching { app.repo.crearEvento(conversacionId, titulo, cuando, lugar, nota) }
                        .onFailure { aviso = it.message }
                }
            },
            onCerrar = { hojaEvento = false },
        )
    }

    // **Un solo panel para emoji, GIF y sticker.** Eran dos hojas distintas
    // abiertas desde dos botones, y los GIFs compartian la suya con los
    // stickers. Son tres cosas del mismo gesto -poner algo que no es texto- y
    // ahora viven en pestañas. Ver `PanelExpresion`.
    if (hojaEmoji || hojaSticker) {
        PanelExpresion(
            inicial = if (hojaSticker) PANEL_STICKERS else PANEL_EMOJIS,
            onEmoji = { emoji ->
                val k = texto.selection.start.coerceIn(0, texto.text.length)
                texto = TextFieldValue(
                    texto.text.substring(0, k) + emoji + texto.text.substring(k),
                    TextRange(k + emoji.length),
                )
            },
            onGif = { gifId ->
                val pie = texto.text.trim()
                texto = TextFieldValue("")
                ambito.launch {
                    runCatching { app.repo.enviarGif(conversacionId, gifId, pie) }
                        .onFailure { aviso = it.message }
                }
            },
            onSticker = { st ->
                // Se manda como adjunto de clase `sticker`, que es la que ya
                // dibuja la burbuja sin fondo ni marco desde el modulo D. El
                // pie NO se usa: un sticker con texto debajo deja de ser un
                // sticker y pasa a ser una imagen con pie.
                ambito.launch {
                    runCatching {
                        app.repo.enviarAdjunto(
                            conversacionId,
                            android.net.Uri.fromFile(java.io.File(st.archivo)),
                            ClaseAdjunto.STICKER,
                        )
                    }.onFailure { aviso = it.message }
                }
            },
            onCerrar = { hojaEmoji = false; hojaSticker = false },
        )
    }

    accionesDe?.let { m ->
        HojaAccionesMensaje(
            mensaje = m,
            // La UI solo decide que mostrar; el servidor vuelve a comprobarlo.
            puedeFijar = (chat?.miJerarquia ?: 10) >= 50,
            puedeBorrarAjeno = (chat?.miJerarquia ?: 10) >= 50,
            onCerrar = { accionesDe = null },
            miReaccion = leerReacciones(m.reaccionesJson)
                .firstOrNull { it.second.second }?.first,
            onReaccionar = { emoji, poner ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.reaccionar(m.id, emoji, poner) }
                        .onFailure { aviso = it.message }
                }
            },
            onResponder = { accionesDe = null; respondiendoA = m },
            onEditar = {
                accionesDe = null
                editando = m
                // El cursor al FINAL: quien edita casi siempre quiere corregir
                // o anadir al final, y arrancar en la posicion 0 obliga a
                // recorrer el mensaje entero antes de escribir una letra.
                texto = TextFieldValue(m.texto, TextRange(m.texto.length))
            },
            onCopiar = {
                accionesDe = null
                portapapeles.setText(AnnotatedString(m.texto))
            },
            onReenviar = {
                accionesDe = null
                ambito.launch {
                    runCatching {
                        app.repo.enviarTexto(conversacionId, m.texto, reenviadoDe = m.autor)
                    }.onFailure { aviso = it.message }
                }
            },
            onGuardarSticker = {
                accionesDe = null
                ambito.launch {
                    val f = m.rutaLocal?.let { java.io.File(it) }
                    val ok = f != null && f.exists() && app.repo.guardarStickerRecibido(f)
                    // El exito NO va por `aviso`: ese dialogo se titula "No se
                    // pudo completar", asi que anunciar algo que si salio bien
                    // debajo de ese titulo dice lo contrario de lo que paso.
                    // Lo vi al probarlo: "Guardado en tus stickers" bajo un
                    // cartel de error.
                    if (ok) confirmacion = "Guardado en tus stickers"
                    else aviso = "No se pudo guardar el sticker."
                }
            },
            onFijar = {
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.fijarMensaje(m.id, !m.fijado) }
                        .onFailure { aviso = it.message }
                }
            },
            onBorrarParaMi = {
                accionesDe = null
                ambito.launch { app.repo.borrarSoloParaMi(m.id) }
            },
            onReintentar = {
                accionesDe = null
                ambito.launch { app.repo.reintentar(m.id) }
            },
            onRetirar = {
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.retirarMensaje(m.id) }
                        .onFailure { aviso = it.message }
                }
            },
            onDenunciar = { accionesDe = null; denunciando = m },
        )
    }

    denunciando?.let { m ->
        DenunciarHoja(
            queSeDenuncia = "este mensaje",
            // Aqui SI se entrega texto, y por eso la hoja lo dice en grande.
            // Es la unica forma de que un moderador tenga algo que revisar: el
            // servidor no puede leer el mensaje, este telefono si.
            entregaTexto = true,
            enviando = enviandoDenuncia,
            onCerrar = { denunciando = null },
            onEnviar = { motivo, detalle, conContexto ->
                enviandoDenuncia = true
                ambito.launch {
                    val r = app.repo.denunciarMensaje(
                        conversacionId, m.id, motivo, detalle, conContexto,
                    )
                    enviandoDenuncia = false
                    denunciando = null
                    r.onSuccess { denunciaHecha = true }
                        .onFailure { aviso = it.message }
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
                    "Un moderador la va a revisar. El texto que entregaste se borra al " +
                        "cerrarse el caso.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = { denunciaHecha = false }) { Text("Entendido", color = Cian) }
            },
        )
    }

    // Una confirmacion se va sola: no interrumpe, no pide que la cierren, y
    // si nadie la ve tampoco se perdio nada -lo que confirma ya paso-.
    confirmacion?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2200)
            confirmacion = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BgElev)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = msg
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = Cian, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(msg, color = TextoPrimario, fontSize = 13.sp)
            }
        }
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("No se pudo completar", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }

    if (mostrarInfo && chat != null) {
        val miembros = chat.participantes.split(",").filter { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { mostrarInfo = false },
            containerColor = BgElev,
            icon = {
                Avatar(
                    nombre = chat.titulo,
                    url = ApiCliente.urlImagen(chat.avatarUsername, "avatar", chat.avatarVersion),
                    tamano = 72.dp,
                    esGrupo = chat.tipo == "grupo",
                )
            },
            title = {
                Text(
                    chat.titulo,
                    color = TextoPrimario,
                )
            },
            text = {
                // Con scroll: la descripcion de una ficha admite 600
                // caracteres y en un telefono corto el dialogo se pasaba de
                // alto, dejando el boton de cerrar fuera de la pantalla.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        // El username va AQUI, y es obligatorio ahora que el
                        // titulo puede ser un alias que escribi yo. Sin el, un
                        // chat que dice "Tatiana" no permite comprobar CON QUE
                        // CUENTA se esta hablando, y el nombre lo puse yo: si
                        // lo puse en la cuenta equivocada, nada me lo diria.
                        if (chat.tipo == "grupo") "${miembros.size + 1} miembros"
                        else "@${chat.nombre}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                    // La lista de miembros solo en un grupo. En una directa
                    // el unico miembro es la persona cuyo nombre ya esta de
                    // titulo dos lineas mas arriba, asi que se leia
                    // "@fulano / Conversacion directa / @fulano".
                    if (chat.tipo == "grupo") {
                        Spacer(Modifier.height(12.dp))
                        miembros.forEach { u ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(nombre = u, url = null, tamano = 30.dp)
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "@$u",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextoPrimario,
                                )
                            }
                        }
                    }
                    // La ficha de empresa de la otra persona. Va aqui y no en
                    // una pantalla aparte porque "ver contacto" es donde se va
                    // a buscar quien es alguien.
                    //
                    // `margenLateral = 0.dp`: el dialogo ya trae el suyo.
                    fichaDelOtro?.let { TarjetaEmpresa(it, margenLateral = 0.dp) }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = Cian, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Historial cifrado en este dispositivo",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextoTerciario,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarInfo = false }) { Text("Cerrar", color = Cian) }
            },
        )
    }

    if (confirmarSalir) {
        AlertDialog(
            onDismissRequest = { confirmarSalir = false },
            containerColor = BgElev,
            title = { Text("Salir del grupo", color = TextoPrimario) },
            text = {
                Text(
                    "Dejaras de recibir mensajes de este grupo. Tu historial local se conserva.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmarSalir = false
                    ambito.launch {
                        runCatching { app.repo.salirDe(conversacionId) }
                        onAtras()
                    }
                }) { Text("Salir", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalir = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }
    }
}

/**
 * Aviso del sistema dentro del chat: centrado, sin burbuja ni checks, porque no
 * lo dijo nadie. Es el rastro permanente de lo que la notificacion avisó una vez.
 */
@Composable
private fun LineaSistema(texto: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            texto,
            color = TextoSecundario,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(BgElev)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Burbuja(
    m: MensajeEnt,
    esGrupo: Boolean,
    /** En minusculas, para saber cual mencion es a mi. Vacio si aun no se sabe. */
    miUsuario: String,
    /** Como llamo yo a un username. Devuelve el username si no lo tengo agendado. */
    nombreDe: (String) -> String,
    /** El resultado de busqueda en el que estoy parado ahora. */
    resaltado: Boolean = false,
    onReintentar: () -> Unit,
    onDescargar: () -> Unit,
    onAbrir: (File) -> Unit,
    onMantener: () -> Unit,
    onReaccion: (String, Boolean) -> Unit,
    onAbrirContacto: (String) -> Unit,
    onResponder: () -> Unit,
) {
    val estado = runCatching { EstadoEnvio.valueOf(m.estado) }.getOrDefault(EstadoEnvio.PENDIENTE)
    val fallido = estado == EstadoEnvio.FALLIDO
    val pendiente = estado == EstadoEnvio.PENDIENTE

    // La semantica de color del sistema de diseno, aplicada:
    //   cian  = va bien    ambar = esperando    coral = se rompio
    val esSticker = m.adjuntoClase == ClaseAdjunto.STICKER && !m.retirado

    /**
     * Un contenido con estructura NO se pinta sobre el cian de la burbuja
     * propia, y es una decision, no un descuido.
     *
     * Una encuesta tiene dentro sus propias superficies, sus barras y su texto
     * secundario, toda una escala pensada contra un fondo oscuro. Sobre cian
     * pleno esa escala se cae: las barras desaparecen y el texto terciario
     * queda ilegible. Asi que estas burbujas usan la superficie neutra de las
     * dos lados, y quien envio se reconoce por el lado y por el check, que es
     * como se reconoce en cualquier mensajero.
     */
    val esEspecial = m.especial.isNotBlank() && !m.retirado

    /**
     * Si dentro de la burbuja hay algo que tocar.
     *
     * Solo las consultas: una ubicacion y un contacto tienen un boton, pero
     * uno solo y al final, asi que fusionarlas sigue leyendose bien. Una
     * encuesta tiene entre dos y doce controles y fusionarla los borra.
     */
    val seOpera = m.especial == ClaseContenido.ENCUESTA ||
        m.especial == ClaseContenido.EVENTO

    val fondo: Color = when {
        // Un sticker va suelto, sin burbuja: encerrarlo en un rectangulo de
        // color lo convierte en una calcomania pegada sobre otra.
        esSticker -> Color.Transparent
        m.retirado -> BgElev
        esEspecial -> BgElev
        !m.esMio -> BgElev
        fallido -> Coral.copy(alpha = 0.16f)
        pendiente -> Cian.copy(alpha = 0.35f)
        else -> Cian
    }
    val borde: Color? = when {
        esSticker -> null
        m.retirado -> Slate.copy(alpha = 0.5f)
        esEspecial && fallido -> Coral
        esEspecial && pendiente -> Ambar
        esEspecial -> Slate
        !m.esMio -> Slate
        fallido -> Coral
        pendiente -> Ambar
        else -> null
    }
    val sobreAcento =
        m.esMio && !pendiente && !fallido && !m.retirado && !esSticker && !esEspecial
    val colorTexto = if (sobreAcento) TextoSobreAcento else TextoPrimario
    // Sobre el acento, el cian de siempre no contrasta: la burbuja propia ya
    // es cian. Ahi la mencion se marca con el mismo color del texto pero en
    // negrita, que es lo que hace `textoConMenciones` con el peso.
    val colorMencion = if (sobreAcento) TextoSobreAcento else Cian

    val forma = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (m.esMio) 16.dp else 4.dp,
        bottomEnd = if (m.esMio) 4.dp else 16.dp,
    )

    val reacciones = remember(m.reaccionesJson) { leerReacciones(m.reaccionesJson) }

    // Deslizar para responder. Un mensaje retirado no se responde: no queda
    // nada a que responder, y ofrecerlo seria citar un hueco.
    ParaResponder(habilitado = !m.retirado, onResponder = onResponder) {
    Column(
        Modifier
            .fillMaxWidth()
            // El resaltado va en el CONTENEDOR y no en la burbuja: pintarlo
            // dentro obligaria a cambiarle el fondo, que es justamente lo que
            // dice de quien es el mensaje.
            .background(if (resaltado) Ambar.copy(alpha = 0.16f) else Color.Transparent)
            .padding(vertical = 3.dp),
        horizontalAlignment = if (m.esMio) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(forma)
                .background(fondo)
                .then(if (borde != null) Modifier.border(1.dp, borde, forma) else Modifier)
                .combinedClickable(
                    onClick = { if (fallido) onReintentar() },
                    onLongClick = onMantener,
                )
                // §15 · La burbuja se lee entera... salvo cuando hay algo
                // que TOCAR adentro.
                //
                // Fusionar esta bien para un bloque que solo se LEE y mal para
                // uno que se OPERA: al fusionar, las opciones de una encuesta
                // dejaban de ser nodos propios y perdian su rol y su accion.
                // Se vio volcando el arbol de accesibilidad del emulador —el
                // nodo de "Sala 3" salia con `clickable=false`—, que es
                // exactamente lo que ninguna prueba de texto puede ver.
                //
                // Asi que una encuesta o un evento NO se fusionan: sus
                // controles se recorren de a uno, que es lo que hace falta
                // para poder votar.
                //
                // El check de estado tenia `contentDescription = null`, que es
                // correcto mientras el color y la forma lo expliquen a la
                // vista e inservible cuando no hay vista: `DoneAll` es
                // "entregado" Y "leido", y lo unico que los separa es el
                // tinte. Va en `stateDescription` y no pegado al texto para
                // que TalkBack lo vuelva a anunciar cuando CAMBIE, sin tener
                // que salir y volver a enfocar. Ver `Accesibilidad`.
                .semantics(mergeDescendants = !seOpera) {
                    // Con controles dentro, la descripcion del contenedor
                    // sobra: la pondria delante de cada opcion al recorrerlas.
                    if (!seOpera) contentDescription = descripcionDeBurbuja(m, esGrupo, nombreDe)
                    estadoDeBurbuja(m)
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!m.esMio && esGrupo && !m.retirado) {
                Text(
                    // Sin "@" y con mi nombre para esa persona, igual que
                    // en la lista. El COLOR se sigue calculando con el
                    // username, que es lo estable: dos contactos pueden
                    // compartir alias y tendrian el mismo color.
                    nombreDe(m.autor),
                    color = colorDeNombre(m.autor),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(2.dp))
            }

            if (m.reenviadoDe != null && !m.retirado) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, null,
                        tint = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else TextoTerciario,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Reenviado de @" + m.reenviadoDe,
                        fontSize = 11.sp,
                        color = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else TextoTerciario,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (m.respondeA != null && m.respondeTexto != null && !m.retirado) {
                CitaMensaje(m.respondeAutor.orEmpty(), m.respondeTexto, sobreAcento)
            }

            // Una respuesta a una historia llega al chat como un mensaje mas.
            // Sin la cita seria un mensaje suelto sin contexto —y a las 24
            // horas, uno que ya no se puede reconstruir—.
            if (m.citaHistoriaId.isNotBlank() && !m.retirado) {
                CitaHistoriaEnBurbuja(m, sobreAcento)
            }

            if (m.retirado) {
                // Queda el hueco a proposito: que un mensaje desaparezca sin
                // rastro hace dudar de si alguna vez existio.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Block, null, tint = TextoTerciario, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mensaje eliminado", color = TextoTerciario, fontSize = 15.sp)
                }
            } else if (esEspecial) {
                ContenidoEspecialBurbuja(m, onAbrirContacto)
            } else {
                if (m.adjuntoClase.isNotBlank()) {
                    ContenidoAdjunto(m, sobreAcento, onDescargar, onAbrir, onReintentar)
                    // El pie solo si existe: un espacio vacio debajo de la foto
                    // se ve como un error de maquetado.
                    if (m.texto.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (m.texto.isNotBlank()) {
                    // Las menciones, marcadas. La mia en negrita y con fondo;
                    // las de otros solo en color. Ver `textoConMenciones`.
                    Text(
                        textoConMenciones(m.texto, miUsuario, colorTexto, colorMencion),
                        fontSize = 16.sp,
                    )
                }
            }

            Spacer(Modifier.height(3.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (m.editado && !m.retirado) {
                    Text(
                        "editado",
                        fontSize = 11.sp,
                        color = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.6f) else TextoTerciario,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                if (m.expiraEn > 0 && !m.retirado) {
                    Icon(
                        Icons.Filled.Timer, "Temporal",
                        tint = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.6f) else TextoTerciario,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when {
                        // El motivo del servidor es mas util que un "fallo"
                        // genérico: "ya no formas parte" no se arregla
                        // reintentando, y quien lo lee necesita saberlo.
                        fallido -> m.motivoFallo ?: "no se envio - toca para reintentar"
                        pendiente -> "en cola"
                        else -> hora(m.creadoEn)
                    },
                    fontSize = 11.sp,
                    color = when {
                        fallido -> Coral
                        pendiente -> Ambar
                        sobreAcento -> TextoSobreAcento.copy(alpha = 0.65f)
                        else -> TextoTerciario
                    },
                )
                if (m.esMio && !m.retirado) {
                    Spacer(Modifier.width(4.dp))
                    val (icono, tinteBase) = iconoEstado(estado)
                    // Sobre la burbuja propia -que es CIAN- el estado no
                    // puede pintarse en cian.
                    //
                    // El "leido" lo hacia, y hasta L.1 nadie lo noto porque
                    // LEIDO no ocurria nunca: las confirmaciones de lectura no
                    // existian, asi que el caso invisible no se daba. En
                    // cuanto empezaron a llegar, el resultado fue una burbuja
                    // sin ningun check: el icono estaba ahi, del mismo color
                    // que el fondo.
                    //
                    // Sobre cian, lo que distingue es el PESO: entregado en
                    // tinta a medias, leido en tinta plena.
                    val tinte = when (estado) {
                        EstadoEnvio.ENVIADO -> TextoSobreAcento.copy(alpha = 0.55f)
                        EstadoEnvio.ENTREGADO -> TextoSobreAcento.copy(alpha = 0.55f)
                        EstadoEnvio.LEIDO -> TextoSobreAcento
                        else -> tinteBase
                    }
                    Icon(
                        icono, null,
                        tint = if (sobreAcento) tinte else tinteBase,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        FilaReacciones(reacciones, onReaccion)
    }
    }
}

/**
 * Lee las reacciones que el servidor ya agrupo y conto.
 *
 * Si el JSON viene raro no se cae la burbuja: se dibuja sin reacciones. Un
 * formato inesperado no debe romper el chat.
 */
internal fun leerReacciones(json: String): List<Pair<String, Pair<Int, Boolean>>> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        jsonApp.decodeFromString<List<ReaccionAgrupada>>(json)
            .map { it.emoji to (it.total to it.mia) }
    }.getOrDefault(emptyList())
}
