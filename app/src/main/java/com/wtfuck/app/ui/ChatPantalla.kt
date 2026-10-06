package com.wtfuck.app.ui

import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import com.wtfuck.app.datos.emojiSolo
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.EstadoConexion
import com.wtfuck.app.datos.jsonApp
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.datos.ServicioUbicacionViva
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.protocol.ClaseContenido
import com.wtfuck.protocol.DuracionMensaje
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
    /**
     * Un mensaje al que saltar al abrir, o vacio.
     *
     * Lo usa el aviso de "no se envio": llevar al chat no alcanzaba, porque el
     * mensaje fallido puede ser viejo y el chat abre por el final. Se llegaba
     * al sitio correcto y habia que buscar igual.
     */
    irAMensaje: String = "",
    onInfoGrupo: () -> Unit,
    /** La ficha de la otra persona, en una directa. */
    onInfoPersona: () -> Unit,
    onVerificarCifrado: () -> Unit,
    /** Abrir la conversacion con alguien: lo pide la tarjeta de contacto. */
    onAbrirChatCon: (String) -> Unit,
    onAtras: () -> Unit,
) {
    // La puerta de los chats protegidos. Va AQUI, delante de todo, y no en la
    // lista: a un chat se llega tambien desde una notificacion, el buscador,
    // un contacto o un enlace, y la puerta tiene que estar en todos.
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ctx = LocalContext.current
    val ambito = rememberCoroutineScope()
    // null = todavia no se sabe. Mientras tanto no se dibuja el chat: un
    // instante con los mensajes a la vista es justo lo que no tiene que pasar.
    var cerrado by remember(conversacionId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(conversacionId) {
        cerrado = app.repo.estaProtegido(conversacionId) && !app.repo.recienAbierto(conversacionId)
    }
    // Se vuelve a cerrar si pasa mas de un minuto fuera de la app. No al
    // instante: elegir una foto o abrir la camara tambien "sale" de la app, y
    // cerrar el chat ahi perderia lo que se estaba eligiendo.
    var salioEn by remember { mutableLongStateOf(0L) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        salioEn = System.currentTimeMillis()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        if (salioEn > 0 && System.currentTimeMillis() - salioEn > 60_000) {
            ambito.launch { if (app.repo.estaProtegido(conversacionId)) cerrado = true }
        }
    }
    fun abrir() {
        pedirAutenticacion(
            ctx,
            onOk = { app.repo.marcarAbierto(conversacionId); cerrado = false },
            onError = { if (it.isNotBlank()) android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() },
            titulo = "Abrir chat protegido",
        )
    }
    when (cerrado) {
        null -> Box(Modifier.fillMaxSize().background(BgBase))
        true -> {
            // La huella se pide sola al llegar; el boton queda por si se cancelo.
            LaunchedEffect(Unit) { abrir() }
            PuertaProtegida(onAbrir = { abrir() }, onAtras = onAtras)
        }
        false -> ChatAbierto(
            conversacionId, irAMensaje, onInfoGrupo, onInfoPersona,
            onVerificarCifrado, onAbrirChatCon, onAtras,
        )
    }
}

/** Lo que se ve de un chat protegido antes de verificarse: nada de el. */
@Composable
private fun PuertaProtegida(onAbrir: () -> Unit, onAtras: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BgBase).systemBarsPadding()) {
        IconButton(onClick = onAtras, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, tint = Cian, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text("Chat protegido", color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Usa tu huella, tu rostro o el PIN del teléfono para abrirlo.",
                color = TextoTerciario,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAbrir,
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
            ) { Text("Abrir") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatAbierto(
    conversacionId: String,
    irAMensaje: String,
    onInfoGrupo: () -> Unit,
    onInfoPersona: () -> Unit,
    onVerificarCifrado: () -> Unit,
    onAbrirChatCon: (String) -> Unit,
    onAtras: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    val mensajes by app.repo.mensajes(conversacionId).collectAsStateWithLifecycle(emptyList())

    // La lista se dibuja INVERTIDA (ver la LazyColumn): el indice 0 es el
    // mensaje mas nuevo, abajo. `mensajes` sigue en orden cronologico porque
    // asi lo piensa todo lo demas -rafagas, buscador, fijados-, y estas dos
    // piezas traducen entre un orden y el otro.
    val invertidos = remember(mensajes) { mensajes.asReversed() }
    fun enLista(cronologico: Int): Int = (mensajes.lastIndex - cronologico).coerceAtLeast(0)
    val fijados by app.repo.fijados(conversacionId).collectAsStateWithLifecycle(emptyList())
    // Con gracia, igual que la lista de chats: el reenganche al desbloquear el
    // telefono no debe pintar "sin conexion" en el subtitulo por un segundo.
    val conexion = estadoConGracia(app.repo.estadoConexion.collectAsStateWithLifecycle().value)
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())
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
        // `@todos` primero, y solo para quien puede fijar en un grupo: es la
        // misma regla que aplica el servidor, que sin permiso lo ignora. Ver
        // `MENCION_TODOS`.
        val todos = if (chat?.tipo == "grupo" && (chat?.miJerarquia ?: 10) >= 50) {
            listOf(CandidatoMencion(com.wtfuck.protocol.MENCION_TODOS, "Todo el grupo"))
        } else emptyList()
        candidatosMencion = todos + gente.map { CandidatoMencion(it, nombres[it] ?: it) }
    }
    var menuAbierto by remember { mutableStateOf(false) }
    var eligiendoTemporales by remember { mutableStateOf(false) }
    var confirmarExportar by remember { mutableStateOf(false) }
    var accionesDe by remember { mutableStateOf<MensajeEnt?>(null) }
    /** Traducciones hechas en esta pantalla. Solo en memoria: ver `Traductor`. */
    val traducciones = remember { mutableStateMapOf<String, String>() }
    /** El mensaje del que se mira "Info". */
    var infoDe by remember { mutableStateOf<String?>(null) }
    /** Lo que se esta por reenviar: abre "Reenviar a...". */
    var reenviando by remember { mutableStateOf<List<MensajeEnt>?>(null) }
    /** Modo seleccion: los ids marcados. Vacio = no se esta seleccionando. */
    var seleccion by remember(conversacionId) { mutableStateOf(setOf<String>()) }
    var confirmarBorrarSeleccion by remember { mutableStateOf(false) }
    var verDestacados by remember { mutableStateOf(false) }
    var eligiendoFondo by remember { mutableStateOf(false) }
    var eligiendoSonido by remember { mutableStateOf(false) }
    /** El mensaje para el que se elige la hora del recordatorio. */
    var recordarDe by remember { mutableStateOf<MensajeEnt?>(null) }
    val recordatorios by app.repo.recordatoriosDe(conversacionId).collectAsStateWithLifecycle(emptyList())
    val conRecordatorio = remember(recordatorios) { recordatorios.map { it.mensajeId }.toSet() }
    var grabandoVideonota by remember { mutableStateOf(false) }
    /** Varias fotos o videos elegidos a la vez, esperando confirmacion. */
    var variosAEnviar by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    /** Lo proximo que se elija en la galeria va como "ver una vez". */
    var proximoUnaVez by remember { mutableStateOf(false) }
    /** Con que valor arranca el interruptor del editor de fotos. */
    var fotoUnaVez by remember { mutableStateOf(false) }
    /** El "ver una vez" que se esta viendo, con su archivo. */
    var viendoUnaVez by remember { mutableStateOf<Pair<MensajeEnt, File>?>(null) }
    var denunciando by remember { mutableStateOf<MensajeEnt?>(null) }
    var enviandoDenuncia by remember { mutableStateOf(false) }
    var denunciaHecha by remember { mutableStateOf(false) }
    var respondiendoA by remember { mutableStateOf<MensajeEnt?>(null) }
    // "Responder en privado" desde un grupo deja la cita esperando aqui.
    LaunchedEffect(conversacionId) {
        app.repo.tomarRespuestaPendiente(conversacionId)?.let { respondiendoA = it }
    }
    var editando by remember { mutableStateOf<MensajeEnt?>(null) }

    // --- borrador -------------------------------------------------------
    //
    // Lo que se deja escrito sin enviar se guarda, por chat, en la base
    // cifrada. Antes salir del chat lo perdia, y la lista no daba ninguna
    // pista de que habia algo a medias.
    //
    // Se carga solo si el campo esta vacio: si ya hay texto -una rotacion,
    // un texto compartido desde otra app- ese manda.
    var borradorCargado by remember(conversacionId) { mutableStateOf(false) }
    LaunchedEffect(conversacionId) {
        val b = app.repo.borradorDe(conversacionId)
        if (texto.text.isEmpty() && b.isNotEmpty()) {
            texto = TextFieldValue(b, androidx.compose.ui.text.TextRange(b.length))
        }
        borradorCargado = true
    }
    // Con un respiro: guardar en cada tecla seria una escritura a la base por
    // letra. Mientras se EDITA un mensaje ya enviado no se guarda nada: ese
    // texto no es un borrador, es el mensaje viejo.
    LaunchedEffect(texto.text, borradorCargado, editando) {
        if (!borradorCargado || editando != null) return@LaunchedEffect
        delay(500)
        app.repo.guardarBorrador(conversacionId, texto.text)
    }
    // Y al salir, sin esperar el respiro: salir medio segundo despues de la
    // ultima letra no puede perderla. En el ambito de la app, que sobrevive
    // a esta pantalla.
    val textoActual by rememberUpdatedState(texto.text)
    val editandoActual by rememberUpdatedState(editando)
    DisposableEffect(conversacionId) {
        onDispose {
            if (borradorCargado && editandoActual == null) {
                val t = textoActual
                app.ambito.launch { app.repo.guardarBorrador(conversacionId, t) }
            }
        }
    }
    /** El menu de la pulsacion larga en el boton de enviar. */
    var menuEnviar by remember { mutableStateOf(false) }
    /** Eligiendo a que hora sale lo escrito. Ver `Programados`. */
    var eligiendoMomento by remember { mutableStateOf(false) }
    var hojaProgramados by remember { mutableStateOf(false) }
    val programados by app.repo.programadosDe(conversacionId).collectAsStateWithLifecycle(emptyList())

    // --- vista previa del enlace que se esta escribiendo ----------------
    //
    // La arma ESTE telefono antes de enviar y viaja en el sobre: ver
    // `VistaPreviaHtml`. Con un respiro, para no pedir una pagina por cada
    // letra de una URL a medio escribir. La X la quita para ese enlace.
    var previa by remember { mutableStateOf<com.wtfuck.protocol.VistaPreviaEnlace?>(null) }
    var previaQuitada by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(texto.text, app.ajustes.vistasPrevias) {
        val url = com.wtfuck.app.datos.VistaPreviaHtml.primerEnlace(texto.text)
        if (url == null || !app.ajustes.vistasPrevias || editando != null) {
            previa = null
            return@LaunchedEffect
        }
        if (url == previa?.url || url == previaQuitada) return@LaunchedEffect
        delay(700)
        previa = app.repo.vistaPreviaDe(url)
    }
    var aviso by remember { mutableStateOf<String?>(null) }
    val portapapeles = LocalClipboardManager.current
    /**
     * La ficha de empresa de la otra persona, si tiene.
     *
     * Se pide al abrir el contacto y no al abrir el chat: es un dato que casi
     * nadie va a mirar, y pedirlo siempre seria una peticion de red por cada
     * conversacion que se abre para dibujar algo que esta detras de un menu.
     */

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
    /** Modulo AF: elegir a quien llamar en un grupo. */
    var hojaLlamarGrupo by remember { mutableStateOf(false) }
    val lista = rememberLazyListState()
    val contexto = LocalContext.current
    var sonidoPropio by remember(conversacionId) {
        mutableStateOf(com.wtfuck.app.datos.Notificaciones.tieneSonidoPropio(contexto, conversacionId))
    }
    // Al volver de los ajustes del sistema puede haber cambiado.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        sonidoPropio = com.wtfuck.app.datos.Notificaciones.tieneSonidoPropio(contexto, conversacionId)
    }

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

    // Notas de voz encadenadas, como en WhatsApp: al terminar una, suena la
    // siguiente si es el mensaje que viene justo despues. Solo si ya esta en
    // el telefono: bajarla en ese momento seria un silencio sin explicacion.
    val mensajesAhora by rememberUpdatedState(mensajes)
    DisposableEffect(reproductor) {
        reproductor.alTerminar = { id ->
            val lista = mensajesAhora.filter { !it.esSistema }
            val i = lista.indexOfFirst { it.id == id }
            lista.getOrNull(i + 1)
                ?.takeIf { it.adjuntoClase == ClaseAdjunto.NOTA_VOZ && !it.unaVez && !it.retirado }
                ?.let { sig -> sig.rutaLocal?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { sig to it } }
                ?.let { (sig, archivo) -> reproductor.reproducir(sig.id, archivo) }
        }
        onDispose { reproductor.alTerminar = null }
    }
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

    /** La foto elegida que se esta editando antes de mandarla. Ver `EditorFotoChat`. */
    var fotoAEditar by remember { mutableStateOf<android.net.Uri?>(null) }

    fotoAEditar?.let { foto ->
        EditorFotoChat(
            foto = foto,
            pieInicial = texto.text.trim(),
            unaVezInicial = fotoUnaVez,
            onEnviar = { lista, pie, unaVez, spoiler ->
                fotoAEditar = null
                if (!unaVez) texto = TextFieldValue("")
                ambito.launch {
                    runCatching {
                        app.repo.enviarAdjunto(
                            conversacionId, lista, ClaseAdjunto.IMAGEN, pie, unaVez = unaVez, spoiler = spoiler,
                        )
                    }.onFailure { aviso = it.message }
                }
            },
            onCerrar = { fotoAEditar = null },
        )
    }

    /** Envia un archivo usando el texto escrito como pie de foto. */
    fun mandarArchivo(uri: android.net.Uri, clase: String, onda: String = "", unaVez: Boolean = false) {
        // Un "ver una vez" no lleva pie: lo escrito se queda en el campo.
        val pie = if (unaVez) "" else texto.text.trim()
        if (!unaVez) texto = TextFieldValue("")
        ambito.launch {
            runCatching { app.repo.enviarAdjunto(conversacionId, uri, clase, pie, onda = onda, unaVez = unaVez) }
                .onFailure { aviso = it.message }
        }
    }

    // El selector de fotos del sistema no necesita permiso de almacenamiento:
    // devuelve solo lo que la persona eligio. Pedir READ_MEDIA_IMAGES para esto
    // seria pedir acceso a la galeria entera sin motivo.
    // Exportar la conversacion en claro. El selector del sistema decide donde
    // se guarda: esta app no elige por la persona donde deja un archivo sin
    // cifrar con sus conversaciones dentro.
    val guardarExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ambito.launch {
            runCatching {
                contexto.contentResolver.openOutputStream(uri)?.use {
                    app.repo.exportarChatA(conversacionId, it)
                } ?: error("sin flujo")
            }.onSuccess { r ->
                val omitidos = if (r.temporalesOmitidos > 0) {
                    " Se dejaron fuera ${r.temporalesOmitidos} mensajes temporales."
                } else ""
                aviso = "Exportados ${r.incluidos} mensajes.$omitidos"
            }.onFailure { aviso = "No se pudo exportar: ${it.message}" }
        }
    }

    val elegirMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // Se consume aqui, se haya elegido algo o no: si no, cancelar el
        // selector dejaria el proximo envio marcado sin que nadie lo sepa.
        val unaVez = proximoUnaVez
        proximoUnaVez = false
        uri?.let {
            val mime = contexto.contentResolver.getType(it).orEmpty()
            // Una foto pasa por el editor, como en cualquier mensajero. Un GIF
            // no -el editor lo dejaria quieto- ni un video, que se manda tal cual.
            if (Media.claseDe(mime) == ClaseAdjunto.IMAGEN && mime != "image/gif") {
                fotoUnaVez = unaVez
                fotoAEditar = it
            } else mandarArchivo(it, Media.claseDe(mime), unaVez = unaVez)
        }
    }

    /**
     * La galeria con varios a la vez, hasta diez. Con uno se hace lo de
     * siempre -la foto pasa por el editor-; con mas, se muestran y salen todos.
     */
    val elegirVarios = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> {
                val it = uris[0]
                val mime = contexto.contentResolver.getType(it).orEmpty()
                if (Media.claseDe(mime) == ClaseAdjunto.IMAGEN && mime != "image/gif") {
                    fotoUnaVez = false
                    fotoAEditar = it
                } else mandarArchivo(it, Media.claseDe(mime))
            }
            else -> variosAEnviar = uris
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
                    mandarArchivo(android.net.Uri.fromFile(f), ClaseAdjunto.NOTA_VOZ, grabadora.onda())
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
            runCatching { lista.scrollToItem(enLista(app.repo.posicionDe(conversacionId, m))) }
        }
    }

    /**
     * Si ya se salto al mensaje pedido.
     *
     * Hace falta la marca porque el salto al final se dispara con cada mensaje
     * nuevo, y sin esto una respuesta que llegara justo despues arrastraria la
     * lista lejos del mensaje al que se vino a mirar.
     */
    var yaSalto by remember(irAMensaje) { mutableStateOf(irAMensaje.isBlank()) }

    /** Cuantos mensajes habia en la ultima pasada; 0 = todavia no cargo. */
    var cargados by remember(conversacionId) { mutableIntStateOf(0) }

    /**
     * A donde mirar cuando la lista cambia: UN solo efecto, no dos.
     *
     * Eran dos y se peleaban. El salto al mensaje ponia `yaSalto = true`, eso
     * era una clave del otro efecto, el otro efecto se relanzaba y mandaba la
     * lista al final: el salto ocurria y se deshacia en el mismo instante. La
     * pantalla quedaba exactamente igual que sin el arreglo.
     *
     * Con uno solo la prioridad se lee de arriba abajo, que es lo que era todo
     * el tiempo: primero el mensaje al que se vino, si hay; si no, el final.
     */
    // Tambien con el id del ULTIMO: un mensaje que cambia de sitio sin que
    // cambie el tamano -el mio, al recibir la hora del servidor- tiene que
    // llevarse la vista con el.
    LaunchedEffect(mensajes.size, mensajes.lastOrNull()?.id, buscando) {
        // Mientras se busca no se mueve nada: el buscador acaba de poner la
        // lista donde la persona esta leyendo, y un mensaje nuevo que llegue
        // en ese momento se la arrastraria lejos.
        if (mensajes.isEmpty() || buscando) return@LaunchedEffect

        if (!yaSalto && irAMensaje.isNotBlank()) {
            // El indice sale de la lista que YA esta cargada, no de otra
            // consulta: si el mensaje no esta en ella no hay a donde saltar, y
            // quedarse donde se estaba es mejor que saltar a un sitio
            // cualquiera.
            val i = mensajes.indexOfFirst { it.id == irAMensaje }
            if (i >= 0) runCatching { lista.scrollToItem(enLista(i)) }
            // Se da por hecho PASE LO QUE PASE: dejarlo en false cuando el
            // mensaje no aparece dejaria la pantalla intentandolo con cada
            // mensaje nuevo, y sin volver al final nunca.
            yaSalto = true
            return@LaunchedEffect
        }

        // El final es el indice 0, y una lista invertida ARRANCA ahi: abrir un
        // chat ya muestra lo ultimo, sin animar desde el primer mensaje.
        //
        // Antes la lista iba al derecho y se mandaba al final con
        // `animateScrollToItem(lastIndex)`. Quedaba anclada ARRIBA: al abrirse
        // el teclado, o al cargar una foto o un sticker por encima, lo de
        // abajo -lo nuevo- se salia de la pantalla. "Abro el chat y me lleva a
        // mensajes anteriores". Invertida, se ancla abajo y lo que cambia de
        // tamano empuja hacia arriba lo viejo, como en cualquier app de chat.
        //
        // Un mensaje nuevo baja la lista solo si se estaba cerca del final o
        // si es mio. Antes arrastraba siempre: quien subia a releer algo era
        // devuelto abajo con cada mensaje que llegaba.
        val primeraCarga = cargados == 0
        cargados = mensajes.size
        if (primeraCarga) {
            runCatching { lista.scrollToItem(0) }
        } else if (lista.firstVisibleItemIndex <= 2 || mensajes.lastOrNull()?.esMio == true) {
            runCatching { lista.animateScrollToItem(0) }
        }
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
            if (seleccion.isNotEmpty()) {
                val marcados = mensajes.filter { it.id in seleccion }
                BarraSeleccionMensajes(
                    cuantos = seleccion.size,
                    todosDestacados = marcados.isNotEmpty() && marcados.all { it.destacado },
                    puedeReenviar = marcados.any { !it.unaVez && it.especial.isBlank() && !it.retirado },
                    onSalir = { seleccion = emptySet() },
                    onCopiar = {
                        // Como WhatsApp: cada uno con su hora y su autor, para
                        // que pegado en otro lado se entienda quien dijo que.
                        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                        portapapeles.setText(AnnotatedString(
                            marcados.sortedBy { it.creadoEn }.filter { it.texto.isNotBlank() }.joinToString("\n") {
                                "[${fmt.format(java.util.Date(it.creadoEn))}] ${if (it.esMio) "Tú" else it.autor}: ${it.texto}"
                            },
                        ))
                        seleccion = emptySet()
                    },
                    onReenviar = {
                        reenviando = marcados.filter { !it.unaVez && it.especial.isBlank() && !it.retirado }
                        seleccion = emptySet()
                    },
                    onDestacar = {
                        val todos = marcados.all { it.destacado }
                        val ids = seleccion
                        seleccion = emptySet()
                        ambito.launch { app.repo.destacar(ids, !todos) }
                    },
                    onBorrar = { confirmarBorrarSeleccion = true },
                )
                return@Scaffold
            }
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
                                        enLista(app.repo.posicionDe(conversacionId, hallazgos[cualHallazgo]))
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
                    // Tocar la cabecera abre la ficha, como en cualquier app
                    // de mensajeria. Antes eso solo estaba en el menu de tres
                    // puntos: el sitio donde nadie lo buscaba, porque el sitio
                    // donde todo el mundo lo busca es el nombre.
                    Row(
                        Modifier.clickable {
                            // La nota no tiene ficha: no hay nadie del otro lado.
                            when (chat?.tipo) {
                                "grupo" -> onInfoGrupo()
                                "notas" -> Unit
                                else -> onInfoPersona()
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarDeChat(
                            nombre = chat?.titulo.orEmpty(),
                            url = ApiCliente.urlImagen(
                                chat?.avatarUsername.orEmpty(), "avatar", chat?.avatarVersion ?: 0L,
                            ),
                            clase = claseDeTipo(chat?.tipo),
                            tamano = 38.dp,
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
                                conexion != EstadoConexion.CONECTADO -> "sin conexión"
                                chat?.tipo == "notas" -> "solo tú · en todos tus aparatos"
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                // El reloj, si el temporizador esta puesto. Va
                                // aqui —pegado al nombre, siempre visible— y no
                                // solo dentro del menu: lo que decide si
                                // escribir algo es saber si va a quedar, y eso
                                // se decide mirando el chat.
                                if ((chat?.temporalesSegundos ?: 0) > 0) {
                                    if (sub.isNotEmpty()) Spacer(Modifier.width(6.dp))
                                    IndicadorTemporales(chat!!.temporalesSegundos)
                                }
                            }
                        }
                    }
                },
                actions = {
                    // Una directa llama directo: no hay a quien elegir.
                    if (chat?.tipo == "directa" && chat.soyMiembro) {
                        IconButton(onClick = { llamar(conversacionId, chat.titulo, false) }) {
                            Icon(Icons.Filled.Phone, "Llamar", tint = TextoSecundario)
                        }
                        IconButton(onClick = { llamar(conversacionId, chat.titulo, true) }) {
                            Icon(Icons.Filled.Videocam, "Videollamada", tint = TextoSecundario)
                        }
                    }
                    // Modulo AF. Un grupo abre la hoja para elegir a quien.
                    //
                    // Antes aqui no habia boton, y el comentario que lo
                    // ocultaba decia que la malla admite cuatro y no habia
                    // forma de elegir. Era cierto —y el servidor era peor: le
                    // llamaba a todo el grupo y rechazaba la llamada entera si
                    // pasaban de cuatro, asi que un grupo de ocho no podia
                    // tener una llamada NUNCA, ni entre tres—. Ahora se elige,
                    // y un solo boton porque audio o video se decide dentro,
                    // junto con la gente: es una misma decision.
                    if (chat?.tipo == "grupo" && chat.soyMiembro) {
                        IconButton(onClick = { hojaLlamarGrupo = true }) {
                            Icon(Icons.Filled.Phone, "Llamar al grupo", tint = TextoSecundario)
                        }
                    }
                    IconButton(onClick = { menuAbierto = true }) {
                        Icon(Icons.Filled.MoreVert, "Más opciones", tint = TextoSecundario)
                    }
                    DropdownMenu(
                        expanded = menuAbierto,
                        onDismissRequest = { menuAbierto = false },
                        containerColor = BgElev,
                    ) {
                        if (chat?.tipo != "notas") {
                            OpcionMenu(
                                if (chat?.tipo == "grupo") "Info del grupo" else "Ver contacto",
                                Icons.Filled.Info,
                            ) {
                                menuAbierto = false
                                // Un grupo tiene administracion; una directa solo
                                // una tarjeta con los datos del contacto.
                                if (chat?.tipo == "grupo") onInfoGrupo() else onInfoPersona()
                            }
                        }

                        OpcionMenu("Buscar en el chat", Icons.Filled.Search) {
                            menuAbierto = false; buscando = true
                        }

                        OpcionMenu("Destacados", Icons.Filled.Star) {
                            menuAbierto = false; verDestacados = true
                        }

                        OpcionMenu("Fondo de este chat", Icons.Filled.Wallpaper) {
                            menuAbierto = false; eligiendoFondo = true
                        }

                        OpcionMenu(
                            if (sonidoPropio) "Sonido de este chat · propio" else "Sonido de este chat",
                            Icons.Filled.MusicNote,
                        ) {
                            menuAbierto = false; eligiendoSonido = true
                        }

                        OpcionMenu("Vaciar chat", Icons.Filled.DeleteSweep) {
                            menuAbierto = false
                            ambito.launch { app.repo.vaciarChat(conversacionId) }
                        }

                        OpcionMenu("Exportar conversación", Icons.Filled.Description) {
                            menuAbierto = false; confirmarExportar = true
                        }

                        if (chat != null) {
                            OpcionMenu(
                                if (chat.protegido) "Quitar la protección con huella" else "Proteger con huella",
                                if (chat.protegido) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            ) {
                                menuAbierto = false
                                // Adentro ya se verifico: quitarla no vuelve a
                                // pedir la huella. Ponerla necesita que el
                                // telefono tenga con que pedirla.
                                if (!chat.protegido && !sePuedeBloquear(contexto)) {
                                    aviso = "Primero configura una huella o un PIN en el teléfono."
                                } else {
                                    if (!chat.protegido) app.repo.marcarAbierto(conversacionId)
                                    ambito.launch { app.repo.proteger(conversacionId, !chat.protegido) }
                                }
                            }
                        }

                        // En la nota no hay con quien comparar numeros: los
                        // aparatos propios se verifican al vincularlos.
                        if (chat?.tipo != "notas") {
                            OpcionMenu("Verificar cifrado", Icons.Filled.Lock, Cian) {
                                menuAbierto = false; onVerificarCifrado()
                            }
                        }

                        // El temporizador. En una directa lo puede poner
                        // cualquiera de los dos —no es de nadie, y quien no
                        // este de acuerdo lo apaga—; en un grupo es una regla
                        // del grupo y la pone quien lo administra.
                        //
                        // En un canal no se ofrece: lo que se publica ahi es
                        // para que la gente lo lea cuando entre, y un canal
                        // cuyas publicaciones se borran solas no es un canal.
                        if (chat != null && chat.soyMiembro && chat.tipo != "canal") {
                            val puedoPonerlo =
                                chat.tipo == "directa" || chat.tipo == "notas" ||
                                    chat.miRol == "admin" || chat.miRol == "dueno"
                            if (puedoPonerlo) {
                                OpcionMenu(
                                    if (chat.temporalesSegundos > 0) {
                                        "Temporales: ${DuracionMensaje.texto(chat.temporalesSegundos)}"
                                    } else {
                                        "Mensajes temporales"
                                    },
                                    Icons.Filled.Timer,
                                    if (chat.temporalesSegundos > 0) Cian else TextoPrimario,
                                ) { menuAbierto = false; eligiendoTemporales = true }
                            }
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

                if (programados.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { hojaProgramados = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Schedule, null, tint = Cian, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (programados.size == 1) "1 mensaje programado" else "${programados.size} mensajes programados") +
                                " · el próximo " + com.wtfuck.app.datos.MomentoProgramado.etiqueta(
                                    programados.first().programadoPara, com.wtfuck.app.datos.MomentoProgramado.ahora(),
                                ),
                            color = TextoSecundario,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ChevronRight, null, tint = TextoTerciario, modifier = Modifier.size(18.dp))
                    }
                }

                previa?.let { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TarjetaEnlace(p, Modifier.weight(1f))
                        IconButton(onClick = { previaQuitada = p.url; previa = null }) {
                            Icon(Icons.Filled.Close, "Quitar la vista previa", tint = TextoTerciario)
                        }
                    }
                }

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
                            else mandarArchivo(android.net.Uri.fromFile(f), ClaseAdjunto.NOTA_VOZ, grabadora.onda())
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
                // Modulo Z.4: escribir un emoji y nada mas ofrece los
                // stickers etiquetados con el. Se calcula en cada tecla y no
                // cuesta nada: `emojiSolo` devuelve "" en cuanto hay una letra,
                // que es el caso normal, y el filtro es sobre una lista que ya
                // esta en memoria.
                val emojiEscrito = emojiSolo(texto.text)
                val sugeridos by remember(emojiEscrito) {
                    app.repo.stickersConEmoji(emojiEscrito)
                }.collectAsStateWithLifecycle(emptyList())
                if (emojiEscrito.isNotBlank()) {
                    TiraDeStickersSugeridos(
                        stickers = sugeridos,
                        onElegir = { s ->
                            // Se limpia el emoji: la persona eligio el sticker
                            // EN VEZ del emoji, no ademas de el. Dejarlo
                            // escrito manda las dos cosas.
                            texto = TextFieldValue("")
                            ambito.launch {
                                app.repo.usarSticker(s.id)
                                runCatching {
                                    app.repo.enviarAdjunto(
                                        conversacionId,
                                        android.net.Uri.fromFile(java.io.File(s.archivo)),
                                        ClaseAdjunto.STICKER,
                                    )
                                }.onFailure { aviso = it.message }
                            }
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
                            // En la nota no: solo lo verian mis otros aparatos,
                            // diciendome que estoy escribiendo.
                            if (it.text.isNotEmpty() && chat?.tipo != "notas") app.repo.avisarQueEscribo(conversacionId)
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
                    fun enviar(silencioso: Boolean) {
                        val t = texto.text
                        val cita = respondiendoA
                        val edit = editando
                        // Solo si el enlace sigue en el texto: si se borro,
                        // la tarjeta hablaria de algo que ya no esta.
                        val conPrevia = previa?.takeIf { it.url in t }
                        texto = TextFieldValue("")
                        respondiendoA = null
                        editando = null
                        previa = null
                        previaQuitada = null
                        ambito.launch {
                            runCatching {
                                if (edit != null) app.repo.editarMensaje(edit.id, t)
                                else app.repo.enviarTexto(
                                    conversacionId, t, respondeA = cita, silencioso = silencioso, previa = conPrevia,
                                )
                            }.onFailure { aviso = it.message }
                        }
                    }
                    // Un boton propio y no `FilledIconButton`: ese no admite
                    // pulsacion larga, y la pulsacion larga es donde vive
                    // "enviar sin sonido", como en Telegram. Se ve igual.
                    Box {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Cian)
                                .combinedClickable(
                                    onClickLabel = if (hayTexto) "Enviar" else "Grabar nota de voz",
                                    onLongClickLabel = if (hayTexto && editando == null) "Más opciones de envío" else null,
                                    onLongClick = { if (hayTexto && editando == null) menuEnviar = true },
                                    onClick = { if (hayTexto) enviar(silencioso = false) else grabarNotaVoz() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hayTexto) Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = TextoSobreAcento)
                            else Icon(Icons.Filled.Mic, "Grabar nota de voz", tint = TextoSobreAcento)
                        }
                        DropdownMenu(
                            expanded = menuEnviar,
                            onDismissRequest = { menuEnviar = false },
                            containerColor = BgElev,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Enviar sin sonido", color = TextoPrimario)
                                        Text(
                                            "Le llega, pero su teléfono no suena",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextoTerciario,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.NotificationsOff, null, tint = Cian) },
                                onClick = { menuEnviar = false; enviar(silencioso = true) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Programar envío", color = TextoPrimario)
                                        Text(
                                            "Sale solo, a la hora que elijas",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextoTerciario,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.Schedule, null, tint = Cian) },
                                onClick = { menuEnviar = false; eligiendoMomento = true },
                            )
                        }
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
                        if (i >= 0) ambito.launch { lista.animateScrollToItem(enLista(i)) }
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

        // El fondo va en la LISTA y no en el Scaffold: asi la barra de arriba
        // y el compositor de abajo se quedan con el color liso de la app. Un
        // degradado que se cuela detras de la barra de herramientas hace que
        // los iconos pierdan el fondo constante contra el que se midieron.
        LazyColumn(
            state = lista,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                // El del chat si tiene uno propio; si no, el general.
                .fondoDeChat(
                    chat?.fondo?.takeIf { it.isNotBlank() }
                        ?.let { f -> runCatching { com.wtfuck.app.ui.theme.FondoChat.valueOf(f) }.getOrNull() }
                        ?: app.ajustes.fondoChat,
                    claroAhora(app.ajustes.tema), Cian,
                ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            // Anclada abajo, en lo mas nuevo. Ver el efecto de arriba.
            reverseLayout = true,
        ) {
            if (chat?.tipo == "notas" && mensajes.isEmpty()) {
                item(key = "nota-vacia") { NotaVacia() }
            }
            itemsIndexed(invertidos, key = { _, m -> m.id }) { j, m ->
                // `i` es la posicion CRONOLOGICA: los vecinos de abajo la usan
                // para saber que vino antes y que despues.
                val i = mensajes.lastIndex - j
                if (m.esSistema) {
                    LineaSistema(m.texto)
                } else {
                    // Los vecinos, para saber si este mensaje abre o cierra una
                    // rafaga. Se calcula aqui -donde se conoce la lista- y no
                    // dentro de la burbuja, que solo se ve a si misma.
                    val antes = mensajes.getOrNull(i - 1)
                    val despues = mensajes.getOrNull(i + 1)
                    val abre = antes == null || !mismaRafaga(
                        antes.autor, antes.creadoEn, antes.esSistema,
                        m.autor, m.creadoEn, m.esSistema,
                    ) || antes.esMio != m.esMio
                    val cierra = despues == null || !mismaRafaga(
                        m.autor, m.creadoEn, m.esSistema,
                        despues.autor, despues.creadoEn, despues.esSistema,
                    ) || despues.esMio != m.esMio

                    Burbuja(
                        m = m,
                        traduccion = traducciones[m.id],
                        enSeleccion = seleccion.isNotEmpty(),
                        marcado = m.id in seleccion,
                        conRecordatorio = m.id in conRecordatorio,
                        onAlternar = {
                            seleccion = if (m.id in seleccion) seleccion - m.id else seleccion + m.id
                        },
                        esGrupo = chat?.tipo == "grupo",
                        miUsuario = app.sesion.username.orEmpty(),
                        nombreDe = { u -> nombresDeGente[u] ?: u },
                        resaltado = buscando && hallazgos.getOrNull(cualHallazgo)?.id == m.id,
                        cierraRafaga = cierra,
                        abreRafaga = abre,
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
                        onVerUnaVez = {
                            ambito.launch {
                                val f = app.repo.abrirUnaVez(m.id)
                                if (f != null) viendoUnaVez = m to f
                                else aviso = "No se pudo abrir. Revisa la conexión e inténtalo otra vez."
                            }
                        },
                        onReaccion = { emoji, poner ->
                            ambito.launch {
                                runCatching { app.repo.reaccionar(m.id, emoji, poner) }
                                    .onFailure { aviso = it.message }
                            }
                        },
                        // Tocar el resumen de una llamada vuelve a llamar,
                        // con el mismo tipo que la de antes: quien toca una
                        // videollamada perdida quiere una videollamada.
                        onDevolverLlamada = { conVideo ->
                            chat?.let { llamar(conversacionId, it.titulo, conVideo) }
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
                elegirVarios.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            },
            onVideonota = { grabandoVideonota = true },
            onUnaVez = {
                proximoUnaVez = true
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
            onEnviar = { lat, lon, precision, etiqueta, modo ->
                // Elegir "con mapa" ES el permiso de este aparato, igual que
                // en el compartido en vivo: se acaba de leer el costo.
                if (modo.conMapa) app.ajustes.fijarMapaDeTerceros(true)
                hojaUbicacion = false
                ambito.launch {
                    runCatching {
                        app.repo.enviarUbicacion(
                            conversacionId, lat, lon, precision, etiqueta,
                            conMapa = modo.conMapa,
                        )
                    }.onFailure { aviso = it.message }
                }
            },
            onCompartirEnVivo = { lat, lon, precision, duracion, modo ->
                hojaUbicacion = false
                // Elegir "con mapa" ES el permiso de este aparato para
                // pedirle baldosas a un tercero: quien lo eligio acaba de
                // leer el costo en la hoja, y volver a preguntarselo en su
                // propia burbuja seria preguntar dos veces lo mismo.
                if (modo.conMapa) app.ajustes.fijarMapaDeTerceros(true)
                ambito.launch {
                    runCatching {
                        // La fecha se calcula UNA vez, aqui, y viaja dentro de
                        // la carga. Calcularla en el servicio dejaria el
                        // vencimiento a merced de cuanto tardara en arrancar.
                        val hasta = System.currentTimeMillis() + duracion
                        val id = app.repo.iniciarUbicacionEnVivo(
                            conversacionId, lat, lon, precision, hasta,
                            conMapa = modo.conMapa,
                        )
                        ServicioUbicacionViva.arrancar(contexto, conversacionId, id, hasta)
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

    if (hojaLlamarGrupo) {
        HojaLlamarAlGrupo(
            conversacionId = conversacionId,
            onCerrar = { hojaLlamarGrupo = false },
            onLlamar = { invitados, conVideo ->
                hojaLlamarGrupo = false
                llamar(conversacionId, chat?.titulo ?: "Grupo", conVideo, invitados)
            },
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

    reenviando?.let { ms -> HojaReenviar(ms, onCerrar = { reenviando = null }) }

    // Atras sale de la seleccion antes que del chat.
    androidx.activity.compose.BackHandler(enabled = seleccion.isNotEmpty()) { seleccion = emptySet() }

    if (confirmarBorrarSeleccion) {
        val n = seleccion.size
        AlertDialog(
            onDismissRequest = { confirmarBorrarSeleccion = false },
            containerColor = BgElev,
            title = { Text(if (n == 1) "¿Borrar el mensaje para ti?" else "¿Borrar $n mensajes para ti?", color = TextoPrimario) },
            text = { Text("Se borran de este teléfono. Los demás los siguen viendo.", color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = seleccion
                    confirmarBorrarSeleccion = false
                    seleccion = emptySet()
                    ambito.launch { ids.forEach { app.repo.borrarSoloParaMi(it) } }
                }) { Text("Borrar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrarSeleccion = false }) { Text("Cancelar", color = Cian) } },
        )
    }

    recordarDe?.let { m ->
        ElegirMomento(
            titulo = "Recordarme este mensaje",
            nota = "Te llega una notificación a esa hora que te trae aquí. Solo en este teléfono.",
            onElegir = { cuando ->
                recordarDe = null
                ambito.launch {
                    app.repo.recordar(m, cuando)
                    android.widget.Toast.makeText(
                        contexto,
                        "Te lo recuerdo " + com.wtfuck.app.datos.MomentoProgramado.etiqueta(
                            cuando, com.wtfuck.app.datos.MomentoProgramado.ahora(),
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onCerrar = { recordarDe = null },
        )
    }

    if (eligiendoFondo) {
        val actual = chat?.fondo.orEmpty()
        AlertDialog(
            onDismissRequest = { eligiendoFondo = false },
            containerColor = BgElev,
            title = { Text("Fondo de este chat", color = TextoPrimario) },
            text = {
                Column {
                    val opciones = listOf<Pair<String, String>>("" to "El de todos los chats") +
                        com.wtfuck.app.ui.theme.FondoChat.entries.map { it.name to it.etiqueta }
                    opciones.forEach { (clave, etiqueta) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                eligiendoFondo = false
                                ambito.launch { app.repo.fijarFondo(conversacionId, clave.ifBlank { null }) }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = actual == clave, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(etiqueta, color = TextoPrimario)
                        }
                    }
                    Text(
                        "Solo en este teléfono.",
                        color = TextoTerciario, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { eligiendoFondo = false }) { Text("Listo", color = Cian) } },
        )
    }

    if (eligiendoSonido) {
        AlertDialog(
            onDismissRequest = { eligiendoSonido = false },
            containerColor = BgElev,
            title = { Text("Sonido de este chat", color = TextoPrimario) },
            text = {
                Text(
                    if (sonidoPropio) "Este chat tiene su propio sonido. Puedes cambiarlo en los ajustes de Android o volver al de siempre."
                    else "Para que este chat suene distinto, se le crea un ajuste propio en Android, donde eliges el sonido.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    eligiendoSonido = false
                    val i = com.wtfuck.app.datos.Notificaciones.sonidoPropio(
                        contexto, conversacionId, chat?.titulo.orEmpty(), chat?.protegido == true,
                    )
                    sonidoPropio = true
                    runCatching { contexto.startActivity(i) }
                }) { Text(if (sonidoPropio) "Cambiar sonido" else "Elegir sonido", color = Cian) }
            },
            dismissButton = {
                if (sonidoPropio) {
                    TextButton(onClick = {
                        eligiendoSonido = false
                        com.wtfuck.app.datos.Notificaciones.quitarSonidoPropio(contexto, conversacionId)
                        sonidoPropio = false
                    }) { Text("Volver al de siempre", color = Coral) }
                }
            },
        )
    }

    if (verDestacados) {
        HojaDestacados(
            conversacionId = conversacionId,
            onIr = { _, m ->
                verDestacados = false
                ambito.launch { runCatching { lista.animateScrollToItem(enLista(app.repo.posicionDe(conversacionId, m))) } }
            },
            onCerrar = { verDestacados = false },
        )
    }

    infoDe?.let { id -> HojaInfoMensaje(id, onCerrar = { infoDe = null }) }

    variosAEnviar?.let { uris ->
        HojaVariosAdjuntos(
            uris = uris,
            pieInicial = texto.text.trim(),
            onEnviar = { pie, spoiler ->
                variosAEnviar = null
                texto = TextFieldValue("")
                app.repo.enviarVarios(conversacionId, uris, pie, spoiler)
            },
            onCerrar = { variosAEnviar = null },
        )
    }

    if (grabandoVideonota) {
        GrabadorVideonota(
            onListo = { archivo ->
                grabandoVideonota = false
                ambito.launch {
                    // Por el FileProvider y no `file://`: sin proveedor el
                    // sistema no sabe el tipo, y un video sin tipo no es video.
                    runCatching {
                        app.repo.enviarAdjunto(
                            conversacionId, app.archivos.uriCompartible(archivo), ClaseAdjunto.VIDEO,
                            forma = FORMA_CIRCULO,
                        )
                    }.onFailure { aviso = it.message }
                    archivo.delete()
                }
            },
            onCerrar = { grabandoVideonota = false },
        )
    }

    if (eligiendoMomento) {
        ElegirMomento(
            onElegir = { cuando ->
                eligiendoMomento = false
                val t = texto.text
                val cita = respondiendoA
                texto = TextFieldValue("")
                respondiendoA = null
                ambito.launch {
                    runCatching { app.repo.enviarTexto(conversacionId, t, respondeA = cita, programadoPara = cuando) }
                        .onSuccess {
                            // Un Toast y no `aviso`: `aviso` es el dialogo de
                            // "No se pudo completar", y esto salio bien.
                            android.widget.Toast.makeText(
                                contexto,
                                "Programado para " + com.wtfuck.app.datos.MomentoProgramado.etiqueta(
                                    cuando, com.wtfuck.app.datos.MomentoProgramado.ahora(),
                                ),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .onFailure { aviso = it.message }
                }
            },
            onCerrar = { eligiendoMomento = false },
        )
    }

    if (hojaProgramados) {
        HojaProgramados(
            programados = programados,
            onEnviarYa = { id -> ambito.launch { app.repo.enviarProgramadoYa(id) } },
            onCancelar = { id -> ambito.launch { app.repo.cancelarProgramado(id) } },
            onCerrar = { hojaProgramados = false },
        )
    }

    viendoUnaVez?.let { (m, archivo) ->
        VisorUnaVez(archivo, m.adjuntoClase) {
            viendoUnaVez = null
            ambito.launch { app.repo.cerrarUnaVez(m.id) }
        }
    }

    accionesDe?.let { m ->
        HojaAccionesMensaje(
            mensaje = m,
            onTranscribir = if (
                (m.adjuntoClase == ClaseAdjunto.NOTA_VOZ || m.adjuntoClase == ClaseAdjunto.AUDIO) &&
                !m.unaVez && m.transcripcion.isBlank()
            ) ({
                accionesDe = null
                android.widget.Toast.makeText(contexto, "Transcribiendo en el teléfono…", android.widget.Toast.LENGTH_SHORT).show()
                ambito.launch {
                    runCatching { app.repo.transcribir(m.id) }.onFailure { aviso = it.message }
                }
            }) else null,
            onTraducir = if (
                !m.esMio && m.texto.isNotBlank() && m.adjuntoClase.isBlank() && m.especial.isBlank() &&
                m.id !in traducciones
            ) ({
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.traducir(m.texto) }
                        .onSuccess { traducciones[m.id] = it }
                        .onFailure { aviso = it.message }
                }
            }) else null,
            // Solo lo mio, en un grupo y ya registrado en el servidor: en una
            // directa los checks ya lo dicen todo.
            onInfo = if (chat?.tipo == "grupo" && m.esMio && !m.retirado &&
                m.estado != EstadoEnvio.PENDIENTE.name && m.estado != EstadoEnvio.FALLIDO.name
            ) ({ accionesDe = null; infoDe = m.id }) else null,
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
                reenviando = listOf(m)
            },
            onSeleccionar = { accionesDe = null; seleccion = setOf(m.id) },
            conRecordatorio = m.id in conRecordatorio,
            onRecordar = if (m.retirado) null else ({
                accionesDe = null
                if (m.id in conRecordatorio) ambito.launch { app.repo.quitarRecordatorio(m.id) }
                else recordarDe = m
            }),
            onResponderEnPrivado = if (chat?.tipo == "grupo" && !m.esMio && !m.retirado) ({
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.responderEnPrivado(m) }
                        .onSuccess { onAbrirChatCon(it) }
                        .onFailure { aviso = it.message ?: "No se pudo abrir el chat con @${m.autor}." }
                }
            }) else null,
            onDestacar = {
                accionesDe = null
                ambito.launch { app.repo.destacar(listOf(m.id), !m.destacado) }
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

    if (confirmarExportar) {
        AlertDialog(
            onDismissRequest = { confirmarExportar = false },
            containerColor = BgElev,
            title = { Text("Exportar conversación", color = TextoPrimario) },
            text = {
                Column {
                    Text(
                        "Se guarda un archivo de texto con los mensajes de este chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Lo importante va en ámbar y ANTES del botón. Este archivo
                    // deshace a propósito lo que hace el resto de la app, y la
                    // persona tiene que saberlo mientras decide, no después.
                    Surface(
                        color = Ambar.copy(alpha = 0.10f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Una línea por aviso, cada una su Text. Se lee mejor
                        // que un párrafo con viñetas dentro, y de paso evita
                        // un literal con saltos escapados.
                        Column(Modifier.padding(12.dp)) {
                            listOf(
                                "• El archivo NO va cifrado: quien lo abra lo lee.",
                                "• Incluye lo que escribió la otra persona, y ella no se entera.",
                                "• Los mensajes temporales NO se exportan: quien los escribió " +
                                    "pidió que no quedaran.",
                                "• Las fotos y archivos no van, solo su nombre.",
                            ).forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ambar,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmarExportar = false
                    guardarExport.launch(
                        com.wtfuck.app.datos.ExportarChat.nombreSugerido(
                            chat?.titulo.orEmpty(), System.currentTimeMillis(),
                        )
                    )
                }) { Text("Elegir dónde guardar", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarExportar = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    if (eligiendoTemporales) {
        DialogoTemporales(
            actual = chat?.temporalesSegundos ?: 0,
            onElegir = { segundos ->
                eligiendoTemporales = false
                ambito.launch {
                    runCatching { app.repo.configurarTemporales(conversacionId, segundos) }
                        .onFailure { aviso = it.message }
                }
            },
            onCerrar = { eligiendoTemporales = false },
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
    /** La traduccion hecha en el telefono, si se pidio. Ver `Traductor`. */
    traduccion: String? = null,
    /** Tiene un recordatorio pendiente: se ve una campanita. */
    conRecordatorio: Boolean = false,
    /** Modo seleccion: un toque marca o desmarca, en vez de lo de siempre. */
    enSeleccion: Boolean = false,
    marcado: Boolean = false,
    onAlternar: () -> Unit = {},
    esGrupo: Boolean,
    /** En minusculas, para saber cual mencion es a mi. Vacio si aun no se sabe. */
    miUsuario: String,
    /** Como llamo yo a un username. Devuelve el username si no lo tengo agendado. */
    nombreDe: (String) -> String,
    /** El resultado de busqueda en el que estoy parado ahora. */
    resaltado: Boolean = false,
    /**
     * Si este mensaje CIERRA una rafaga del mismo autor.
     *
     * Decide dos cosas: si lleva pico -solo el ultimo lo lleva- y cuanto
     * espacio queda debajo. Ver `mismaRafaga`.
     */
    cierraRafaga: Boolean = true,
    /** Si ABRE la rafaga. Solo el primero repite el nombre del autor. */
    abreRafaga: Boolean = true,
    onReintentar: () -> Unit,
    onDescargar: () -> Unit,
    onAbrir: (File) -> Unit,
    onMantener: () -> Unit,
    onVerUnaVez: () -> Unit,
    onReaccion: (String, Boolean) -> Unit,
    onAbrirContacto: (String) -> Unit,
    /** Volver a llamar desde el resumen de una llamada. El booleano es el video. */
    onDevolverLlamada: (Boolean) -> Unit,
    onResponder: () -> Unit,
) {
    val estado = runCatching { EstadoEnvio.valueOf(m.estado) }.getOrDefault(EstadoEnvio.PENDIENTE)
    val fallido = estado == EstadoEnvio.FALLIDO
    // Llego por el modo cerca a quien estaba enfrente y espera la red para el
    // resto: no es "en cola" para quien lo mira, ya lo tiene la otra persona.
    val porCerca = estado == EstadoEnvio.PENDIENTE && m.cercaEntregado.isNotBlank()
    val pendiente = estado == EstadoEnvio.PENDIENTE && !porCerca

    // La semantica de color del sistema de diseno, aplicada:
    //   cian  = va bien    ambar = esperando    coral = se rompio
    // Sin burbuja: un sticker y una videonota van sueltos. Encerrarlos en un
    // rectangulo de color los convierte en una calcomania pegada sobre otra.
    val esSticker = !m.retirado && (
        m.adjuntoClase == ClaseAdjunto.STICKER ||
            (m.adjuntoClase == ClaseAdjunto.VIDEO && m.adjuntoForma == FORMA_CIRCULO)
        )

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

    // El pico solo en el ULTIMO de una rafaga: apunta a quien habla, y cinco
    // seguidos parecerian cinco intervenciones en vez de una persona hablando
    // seguido. Ver `BurbujaConPico`.
    val forma = remember(m.esMio, cierraRafaga) { BurbujaConPico(m.esMio, cierraRafaga) }

    val reacciones = remember(m.reaccionesJson) { leerReacciones(m.reaccionesJson) }

    // Para `sinAbrirConPulsacionLarga`: el modificador vive lo que vive la
    // burbuja, y asi llama siempre a la accion de ahora y no a la del primer
    // pintado.
    val mantener by rememberUpdatedState(onMantener)

    // Deslizar para responder. Un mensaje retirado no se responde: no queda
    // nada a que responder, y ofrecerlo seria citar un hueco.
    val alternar by rememberUpdatedState(onAlternar)
    ParaResponder(habilitado = !m.retirado && !enSeleccion, onResponder = onResponder) {
    Column(
        Modifier
            .fillMaxWidth()
            // En modo seleccion el toque es de la fila entera, y gana sobre
            // todo lo de adentro -enlaces, fotos, audios-: se mira en la
            // pasada `Initial` y se consume antes de que llegue a ellos.
            .then(
                if (enSeleccion) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        abajo.consume()
                        while (true) {
                            val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                .changes.firstOrNull { it.id == abajo.id } ?: break
                            c.consume()
                            if (!c.pressed) { alternar(); break }
                        }
                    }
                } else Modifier
            )
            .background(if (marcado) Cian.copy(alpha = 0.16f) else Color.Transparent)
            // El resaltado va en el CONTENEDOR y no en la burbuja: pintarlo
            // dentro obligaria a cambiarle el fondo, que es justamente lo que
            // dice de quien es el mensaje.
            .background(if (resaltado) Ambar.copy(alpha = 0.16f) else Color.Transparent)
            // Apretado dentro de una rafaga y con aire al cerrarla. Es lo
            // que agrupa visualmente sin dibujar ninguna caja: el ojo junta
            // lo que esta cerca y separa lo que no.
            .padding(top = if (abreRafaga) 5.dp else 1.dp, bottom = if (cierraRafaga) 5.dp else 1.dp),
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
            // El nombre solo en el PRIMERO de la rafaga. Repetirlo en cada
            // mensaje de una tanda es lo que hace que un grupo activo se lea
            // como una lista de fichas en vez de como gente hablando.
            if (!m.esMio && esGrupo && !m.retirado && abreRafaga) {
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

            // Por la copia y no por el id: una respuesta en privado a algo de un
            // grupo trae la cita sin id. Ver `Repositorio.enviarTexto`.
            if (m.respondeTexto != null && !m.retirado) {
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
                ContenidoEspecialBurbuja(m, onAbrirContacto, onDevolverLlamada)
            } else {
                if (m.adjuntoClase.isNotBlank()) {
                    // La foto, el audio y el documento se tocan para abrirse o
                    // sonar, y por eso se quedaban con la pulsacion larga: abrian
                    // el visor en vez del menu. Igual que con los enlaces.
                    Box(Modifier.sinAbrirConPulsacionLarga { mantener() }) {
                        ContenidoAdjunto(m, sobreAcento, onDescargar, onAbrir, onReintentar, onVerUnaVez)
                    }
                    if (m.transcripcion.isNotBlank()) {
                        Text(
                            m.transcripcion,
                            color = colorTexto.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // El pie solo si existe: un espacio vacio debajo de la foto
                    // se ve como un error de maquetado.
                    if (m.texto.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (m.texto.isNotBlank()) {
                    // Las menciones, marcadas. La mia en negrita y con fondo;
                    // las de otros solo en color. Ver `textoConMenciones`.
                    // `color` va AQUI y no solo dentro de los tramos.
                    //
                    // `textoConMenciones` tiene un atajo: sin menciones
                    // devuelve el texto sin ningun tramo de color, que es el
                    // caso comun y evita construir un AnnotatedString por cada
                    // mensaje de la lista. Pero entonces el color lo pone el
                    // `Text`, y no lo ponia: caia al color de contenido por
                    // defecto de Material.
                    //
                    // Solo se veia en la burbuja PROPIA. En las recibidas el
                    // color por defecto coincide con el correcto, asi que el
                    // defecto era invisible en la mitad de la pantalla y
                    // dejaba el texto claro sobre el cian en la otra mitad.
                    // El formato (*negrita*, ||spoiler||...) y las menciones,
                    // juntos. Ver `textoDeMensaje`. El spoiler se destapa por
                    // mensaje y no queda destapado al volver al chat.
                    var spoilerVisible by remember(m.id) { mutableStateOf(false) }
                    val previaDelMensaje = remember(m.previaJson) {
                        if (m.previaJson.isBlank()) null else runCatching {
                            com.wtfuck.app.datos.jsonApp.decodeFromString(
                                com.wtfuck.protocol.VistaPreviaEnlace.serializer(), m.previaJson,
                            )
                        }.getOrNull()
                    }
                    if (previaDelMensaje != null) {
                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                        TarjetaEnlace(
                            previaDelMensaje,
                            Modifier
                                .padding(bottom = 6.dp)
                                .sinAbrirConPulsacionLarga { mantener() }
                                .clickable { runCatching { uri.openUri(previaDelMensaje.url) } },
                            colorTexto = colorTexto,
                        )
                    }
                    Text(
                        textoDeMensaje(
                            m.texto, miUsuario, colorTexto, colorMencion,
                            spoilerVisible = spoilerVisible,
                            onVerSpoiler = { spoilerVisible = true },
                        ),
                        color = colorTexto,
                        fontSize = 16.sp,
                        modifier = Modifier.sinAbrirConPulsacionLarga { mantener() },
                    )
                    if (traduccion != null) {
                        HorizontalDivider(
                            color = colorTexto.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Text(
                            "Traducido en el teléfono",
                            color = colorTexto.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                        )
                        Text(traduccion, color = colorTexto, fontSize = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(3.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (conRecordatorio && !m.retirado) {
                    Icon(
                        Icons.Filled.NotificationsActive, "Con recordatorio",
                        tint = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else Cian,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (m.destacado && !m.retirado) {
                    Icon(
                        Icons.Filled.Star, "Destacado",
                        tint = if (sobreAcento) TextoSobreAcento.copy(alpha = 0.7f) else Ambar,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
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
                        porCerca -> "por Bluetooth"
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
                // Una llamada no lleva tildes de entrega.
                //
                // El resumen se escribe en ESTE telefono y no se manda a
                // ningun lado, asi que un doble tilde de "entregado" estaria
                // afirmando algo que no ocurrio. Y no es solo purismo: el
                // tilde es lo que la gente mira para saber si algo llego, y
                // que aparezca donde no significa nada le quita valor donde
                // si.
                if (m.esMio && !m.retirado && m.especial != ClaseContenido.LLAMADA) {
                    Spacer(Modifier.width(4.dp))
                    val (icono, tinteBase) =
                        if (porCerca) Icons.Filled.Bluetooth to TextoTerciario else iconoEstado(estado)
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


/**
 * La tarjeta de un enlace: miniatura, titulo, descripcion y el DOMINIO real.
 *
 * El dominio sale de la URL y no del `sitio` que mando quien escribio: es lo
 * que la persona tiene que mirar antes de tocar, y no puede venir de quien
 * quiere que lo toque.
 */
@Composable
fun TarjetaEnlace(
    p: com.wtfuck.protocol.VistaPreviaEnlace,
    modifier: Modifier = Modifier,
    /**
     * El color del texto. Sin decirlo, la tarjeta heredaba el del tema -claro,
     * para el fondo oscuro- y dentro de mi burbuja cian el titulo casi no se
     * leia. Dentro de una burbuja va el color de esa burbuja.
     */
    colorTexto: Color = Color.Unspecified,
) {
    val miniatura = remember(p.imagen) {
        if (p.imagen.isBlank()) null else runCatching {
            val b = android.util.Base64.decode(p.imagen, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(
        color = Color.Black.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (miniatura != null) {
                androidx.compose.foundation.Image(
                    miniatura, null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (p.titulo.isNotBlank()) {
                    Text(p.titulo, color = colorTexto, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (p.descripcion.isNotBlank()) {
                    Text(p.descripcion, color = colorTexto, fontSize = 12.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(0.8f))
                }
                Text(
                    com.wtfuck.app.datos.VistaPreviaHtml.dominio(p.url),
                    color = colorTexto, fontSize = 11.sp, modifier = Modifier.alpha(0.7f),
                )
            }
        }
    }
}


/**
 * Lo que se ve en una "Nota para mi" sin nada todavia: para que sirve, y que
 * pasa con lo que se escribe aqui.
 */
@Composable
private fun NotaVacia() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(BgElev),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Bookmark, null, tint = Cian, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Tu nota", color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Apunta, guarda enlaces y reenvíate mensajes, fotos y archivos. Aparece en " +
                "todos tus aparatos vinculados y va cifrada como cualquier chat: el " +
                "servidor solo pasa sobres que no puede abrir.",
            color = TextoTerciario,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}


/** La barra de arriba mientras se seleccionan mensajes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarraSeleccionMensajes(
    cuantos: Int,
    todosDestacados: Boolean,
    puedeReenviar: Boolean,
    onSalir: () -> Unit,
    onCopiar: () -> Unit,
    onReenviar: () -> Unit,
    onDestacar: () -> Unit,
    onBorrar: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgElev),
        navigationIcon = {
            IconButton(onClick = onSalir) { Icon(Icons.Filled.Close, "Salir de la selección", tint = TextoPrimario) }
        },
        title = { Text("$cuantos", color = TextoPrimario) },
        actions = {
            IconButton(onClick = onCopiar) { Icon(Icons.Filled.ContentCopy, "Copiar", tint = TextoSecundario) }
            if (puedeReenviar) {
                IconButton(onClick = onReenviar) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Reenviar", tint = TextoSecundario)
                }
            }
            IconButton(onClick = onDestacar) {
                Icon(
                    if (todosDestacados) Icons.Filled.StarBorder else Icons.Filled.Star,
                    if (todosDestacados) "Quitar destacado" else "Destacar",
                    tint = TextoSecundario,
                )
            }
            IconButton(onClick = onBorrar) { Icon(Icons.Filled.DeleteOutline, "Borrar para mí", tint = Coral) }
        },
    )
}
