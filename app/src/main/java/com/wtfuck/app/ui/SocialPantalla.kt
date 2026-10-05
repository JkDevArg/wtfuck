package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.HistoriaEnt
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.UsuarioPublico
import com.wtfuck.protocol.VistasDeHistoria
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La pestaña Social: los estados de la gente y el directorio de Usuarios.
 *
 * ## Por que existe
 *
 * Los estados vivian en una franja arriba de la lista de chats, y el pedido
 * fue sacarlos de ahi: la lista de chats es para hablar, y una franja que
 * cambia cada pocas horas empujaba las conversaciones hacia abajo. Aqui
 * tienen pantalla propia, junto con el otro lugar "para descubrir gente": el
 * directorio de quien quiso aparecer.
 *
 * Reemplaza a la pestaña Contactos, que paso al menu "Nuevo": la libreta es
 * a quien ya conoces, y se usa para empezar algo, no para pasear.
 */
private enum class SeccionSocial(val etiqueta: String) {
    ESTADOS("Estados"),
    USUARIOS("Usuarios"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialPantalla(
    onVerPersona: (String) -> Unit,
    onPrivacidad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var seccion by rememberSaveable { mutableStateOf(SeccionSocial.ESTADOS) }

    Scaffold(
        modifier = modifier,
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                title = { Text("Social", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(
                selectedTabIndex = seccion.ordinal,
                containerColor = BgSurface,
                contentColor = Cian,
            ) {
                SeccionSocial.entries.forEach { s ->
                    Tab(
                        selected = seccion == s,
                        onClick = { seccion = s },
                        text = {
                            Text(
                                s.etiqueta,
                                color = if (seccion == s) Cian else TextoTerciario,
                                fontWeight = if (seccion == s) FontWeight.Medium else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
            when (seccion) {
                SeccionSocial.ESTADOS -> SeccionEstados()
                SeccionSocial.USUARIOS -> SeccionUsuarios(onVerPersona, onPrivacidad)
            }
        }
    }
}

// ============================================================
//  Estados
// ============================================================

/**
 * Los estados, en una lista vertical: el mio, los que tienen algo sin ver y
 * los ya vistos. Es la forma de WhatsApp y no por copiarla: con la franja
 * horizontal, quien tenia veinte contactos con estado solo veia cinco y el
 * resto quedaba fuera de pantalla.
 *
 * El visor, el compositor y "quien lo vio" son los mismos de antes; esto solo
 * cambia DONDE viven.
 */
@Composable
private fun SeccionEstados() {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val yo = app.sesion.username.orEmpty()

    val historias by app.repo.historias().collectAsState(initial = emptyList())
    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val perfil by app.repo.miPerfil.collectAsStateWithLifecycle()

    var abiertoDe by remember { mutableStateOf<String?>(null) }
    var componiendo by remember { mutableStateOf(false) }
    var publicando by remember { mutableStateOf(false) }
    var errorPublicar by remember { mutableStateOf<String?>(null) }
    var vistasDe by remember { mutableStateOf<VistasDeHistoria?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    // Se sincroniza al entrar y cada vez que llega un aviso: el metadato vive
    // en el servidor y el contenido en el buzon, y la lista necesita los dos.
    LaunchedEffect(Unit) { app.repo.sincronizarHistorias() }
    LaunchedEffect(Unit) { app.repo.avisos.collect { app.repo.sincronizarHistorias() } }

    // La foto de cada autor sale de los chats directos, que ya la conocen con
    // su version. La franja anterior dibujaba siempre iniciales.
    val fotos = remember(chats, perfil) {
        buildMap {
            chats.filter { it.tipo == "directa" && it.avatarVersion > 0 }
                .forEach { put(it.nombre, ApiCliente.urlImagen(it.avatarUsername.ifBlank { it.nombre }, "avatar", it.avatarVersion)) }
            perfil?.let { p -> put(yo, ApiCliente.urlImagen(yo, "avatar", p.avatarVersion)) }
        }
    }
    val porAutor = remember(historias, yo) { autoresConHistorias(historias, yo) }
    val mias = porAutor.firstOrNull { it.first == yo }?.second.orEmpty()
    val otros = porAutor.filter { it.first != yo }
    val recientes = otros.filter { (_, h) -> h.any { !it.vista } }
    val vistos = otros.filter { (_, h) -> h.none { !it.vista } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                FilaEstado(
                    nombre = "Mi estado",
                    detalle = if (mias.isEmpty()) "Toca para publicar uno · dura 24 h"
                    else "${mias.size} " + (if (mias.size == 1) "publicado" else "publicados") +
                        " · " + haceDesde(mias.maxOf { it.creadaEn }),
                    url = fotos[yo],
                    anillo = if (mias.isEmpty()) Anillo.AGREGAR else Anillo.VISTO,
                    onClick = { if (mias.isEmpty()) componiendo = true else abiertoDe = yo },
                )
            }
            if (recientes.isNotEmpty()) {
                item { EncabezadoSocial("Recientes") }
                items(recientes, key = { "r-" + it.first }) { (autor, suyas) ->
                    FilaEstado(
                        nombre = autor,
                        detalle = haceDesde(suyas.maxOf { it.creadaEn }),
                        url = fotos[autor],
                        anillo = Anillo.NUEVO,
                        onClick = { abiertoDe = autor },
                    )
                }
            }
            if (vistos.isNotEmpty()) {
                item { EncabezadoSocial("Vistos") }
                items(vistos, key = { "v-" + it.first }) { (autor, suyas) ->
                    FilaEstado(
                        nombre = autor,
                        detalle = haceDesde(suyas.maxOf { it.creadaEn }),
                        url = fotos[autor],
                        anillo = Anillo.VISTO,
                        onClick = { abiertoDe = autor },
                    )
                }
            }
            if (otros.isEmpty()) {
                item {
                    Text(
                        "Cuando tus contactos publiquen un estado, aparece aquí. " +
                            "Quién ve los tuyos se decide en Privacidad.",
                        color = TextoTerciario,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { componiendo = true },
            containerColor = Cian,
            contentColor = TextoSobreAcento,
            icon = { Icon(Icons.Filled.PhotoCamera, null) },
            text = { Text("Nuevo estado") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    abiertoDe?.let { autor ->
        val suyas = historias.filter { it.autor == autor && it.conContenido }
        VisorHistorias(
            delAutor = autor,
            historias = suyas,
            miUsuario = yo,
            onVista = { id -> ambito.launch { app.repo.verHistoria(id) } },
            onRetirar = { id ->
                ambito.launch {
                    app.repo.retirarHistoria(id)
                    if (suyas.size <= 1) abiertoDe = null
                }
            },
            onVerQuienes = { id -> ambito.launch { vistasDe = app.repo.vistasDeHistoria(id) } },
            onDescargar = { id -> ambito.launch { app.repo.descargarArchivoDeHistoria(id) } },
            onResponder = { id, texto ->
                abiertoDe = null
                ambito.launch {
                    app.repo.responderHistoria(id, texto)
                        .onFailure { aviso = it.message ?: "No se pudo responder." }
                }
            },
            onCerrar = { abiertoDe = null },
        )
    }

    if (componiendo) {
        EditorDeEstado(
            publicando = publicando,
            error = errorPublicar,
            onPublicar = { nuevo ->
                // El editor se cierra solo si salio bien: un fallo de red no
                // puede costar la foto elegida ni lo que se escribio.
                publicando = true
                errorPublicar = null
                ambito.launch {
                    app.repo.publicarHistoria(nuevo.texto, nuevo.fondo, nuevo.medio, nuevo.renderizado)
                        .onSuccess { componiendo = false }
                        .onFailure { errorPublicar = it.message ?: "No se pudo publicar." }
                    publicando = false
                }
            },
            onCerrar = { componiendo = false; errorPublicar = null },
        )
    }

    vistasDe?.let { v -> HojaVistasHistoria(vistas = v, onCerrar = { vistasDe = null }) }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("No se pudo", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

private enum class Anillo { NUEVO, VISTO, AGREGAR }

/**
 * "hace 3 h" desde una hora del servidor. `haceCuanto` recibe lo TRANSCURRIDO,
 * no la marca: pasarle la marca decia "hace 497548 h". Con el reloj corregido
 * (`Reloj`), porque la hora de publicacion la puso el servidor.
 */
private fun haceDesde(marca: Long): String =
    haceCuanto((com.wtfuck.app.datos.Reloj.ahora() - marca).coerceAtLeast(0))

@Composable
private fun FilaEstado(
    nombre: String,
    detalle: String,
    url: String?,
    anillo: Anillo,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$nombre, $detalle" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (anillo == Anillo.NUEVO) 2.5.dp else 1.dp,
                        color = when (anillo) {
                            Anillo.NUEVO -> Cian
                            Anillo.VISTO -> Slate.copy(alpha = 0.6f)
                            Anillo.AGREGAR -> Slate.copy(alpha = 0.35f)
                        },
                        shape = CircleShape,
                    )
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(nombre = nombre, url = url, tamano = 50.dp)
            }
            if (anillo == Anillo.AGREGAR) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Cian)
                        .border(2.dp, BgBase, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, null, tint = TextoSobreAcento, modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, color = TextoPrimario, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detalle, color = TextoTerciario, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun EncabezadoSocial(texto: String) {
    Text(
        texto,
        color = Cian,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

// ============================================================
//  Usuarios
// ============================================================

/**
 * El directorio: la gente que marco "aparecer en Usuarios" en Privacidad.
 *
 * Quien aparece y que se ve de cada uno lo decide el servidor
 * (`Repo.directorio`): aqui solo se pide, se pagina y se dibuja. Tocar a
 * alguien abre su perfil, desde donde se le escribe o se le agrega; entrar a
 * mirar quien es alguien no es empezar a hablarle.
 */
@Composable
private fun SeccionUsuarios(onVerPersona: (String) -> Unit, onPrivacidad: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val privacidad by app.repo.privacidad.collectAsStateWithLifecycle()

    var consulta by rememberSaveable { mutableStateOf("") }
    var usuarios by remember { mutableStateOf<List<UsuarioPublico>>(emptyList()) }
    var siguiente by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lista = rememberLazyListState()

    LaunchedEffect(Unit) { app.repo.cargarPrivacidad() }

    // Con un retardo corto: escribir un nombre son varias consultas si se
    // dispara en cada letra, y solo la ultima importa.
    LaunchedEffect(consulta) {
        delay(if (consulta.isBlank()) 0 else 300)
        cargando = true
        app.repo.directorio(consulta)
            .onSuccess { usuarios = it.usuarios; siguiente = it.siguiente; error = null }
            .onFailure { error = it.message ?: "No se pudo cargar la lista." }
        cargando = false
    }

    // La siguiente pagina se pide al acercarse al final, no con un boton.
    val cercaDelFinal by remember {
        derivedStateOf {
            val ultimo = lista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            ultimo >= lista.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(cercaDelFinal, siguiente) {
        val desde = siguiente
        if (!cercaDelFinal || desde == null || cargando) return@LaunchedEffect
        cargando = true
        app.repo.directorio(consulta, desde)
            .onSuccess { r -> usuarios = usuarios + r.usuarios; siguiente = r.siguiente }
        cargando = false
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = consulta,
            onValueChange = { consulta = it.take(32) },
            placeholder = { Text("Buscar por nombre o @usuario", color = TextoTerciario) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoTerciario) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian,
                unfocusedBorderColor = Slate.copy(alpha = 0.4f),
                focusedTextColor = TextoPrimario,
                unfocusedTextColor = TextoPrimario,
                cursorColor = Cian,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )

        // Si yo no aparezco, se dice ARRIBA y con el atajo: es lo primero que
        // alguien se pregunta al ver la lista, y la respuesta esta en otra
        // pantalla.
        if (privacidad?.directorio == false) {
            Surface(
                color = BgElev,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VisibilityOff, null, tint = TextoTerciario, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tú no apareces aquí. Es opcional y se activa en Privacidad.",
                        color = TextoSecundario,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPrivacidad) { Text("Activar", color = Cian) }
                }
            }
        }

        when {
            error != null && usuarios.isEmpty() -> Text(
                error!!,
                color = Ambar,
                fontSize = 13.sp,
                modifier = Modifier.padding(20.dp),
            )
            !cargando && usuarios.isEmpty() -> Text(
                if (consulta.isBlank()) "Todavía nadie eligió aparecer en Usuarios."
                else "Nadie de la lista coincide con \"$consulta\".",
                color = TextoTerciario,
                fontSize = 13.sp,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(state = lista, modifier = Modifier.fillMaxSize()) {
                items(usuarios, key = { it.usuarioId }) { u ->
                    FilaUsuario(u) { onVerPersona(u.username) }
                }
                if (cargando) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Cian, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilaUsuario(u: UsuarioPublico, onClick: () -> Unit) {
    val nombre = u.nombreMostrado.ifBlank { u.username }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$nombre, @${u.username}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(nombre = nombre, url = ApiCliente.urlImagen(u.username, "avatar", u.avatarVersion), tamano = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, color = TextoPrimario, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${u.username}", color = TextoTerciario, fontSize = 12.sp, maxLines = 1)
            val linea = u.biografia.ifBlank { u.estadoTexto }
            if (linea.isNotBlank()) {
                Text(linea, color = TextoSecundario, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
