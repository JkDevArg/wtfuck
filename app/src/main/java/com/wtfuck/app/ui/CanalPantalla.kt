package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ConfigCanal
import com.wtfuck.protocol.EstadisticasCanal
import com.wtfuck.protocol.EstadoCanal
import com.wtfuck.protocol.Publicacion
import kotlinx.coroutines.launch

/**
 * Un canal (módulo F).
 *
 * Se parece a un chat pero **no lo es**, y la pantalla lo refleja: publicar y
 * leer son papeles distintos, así que el campo de texto solo aparece para
 * quien puede publicar. A un suscriptor no se le muestra un campo que el
 * servidor va a rechazar.
 *
 * El contenido se lee del servidor, no de la base local. En un canal público
 * eso es lo correcto: el historial vive allá y tiene que estar disponible para
 * quien se suscriba mañana. Es la misma razón por la que un canal público no
 * va cifrado de extremo a extremo, y la pantalla lo dice en vez de esconderlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanalPantalla(
    conversacionId: String,
    onAtras: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<ConfigCanal?>(null) }
    /**
     * Modulo Z.5: `null` = no se pudo leer el muro. Vacia = no hay nada.
     *
     * Antes era una lista a secas: `publicaciones` ya devolvia `null` al
     * fallar -eso se arreglo en el modulo X- y **esta pantalla lo tiraba** con
     * un `?: emptyList()`. El comentario de `refrescar` decia que estaba
     * resuelto y el codigo de al lado lo deshacia.
     *
     * El caso que quedaba vivo: `canal` responde y `publicaciones` no. Son
     * dos peticiones distintas, asi que pasa, y la pantalla decia "Este canal
     * todavia no tiene publicaciones".
     */
    var feed by remember { mutableStateOf<List<Publicacion>?>(null) }
    var texto by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<EstadisticasCanal?>(null) }
    var menuAbierto by remember { mutableStateOf(false) }
    var mostrarInfo by remember { mutableStateOf(false) }
    /** La ultima carga no llego. Distinto de "no hay nada". */
    var fallo by remember { mutableStateOf(false) }
    /** La publicacion cuyos comentarios se estan viendo. */
    var comentando by remember { mutableStateOf<Publicacion?>(null) }

    /**
     * Modulo AC: la imagen elegida para la proxima publicacion.
     *
     * Un canal solo podia publicar texto, y para una pagina de anuncios ese es
     * el hueco grande: un aviso con imagen es lo normal, no la excepcion.
     */
    var imagen by remember { mutableStateOf<android.net.Uri?>(null) }
    /** URLs firmadas ya pedidas, por id de adjunto. */
    var urlesImagen by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** La imagen abierta a pantalla completa, si hay alguna. */
    var viendoImagen by remember { mutableStateOf<String?>(null) }
    var publicando by remember { mutableStateOf(false) }
    val elegirImagen = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) imagen = uri }
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())

    /**
     * El nombre que ya sabemos de este canal, sin preguntarle a nadie.
     *
     * Es la copia local de la conversacion, que esta ahi desde que alguien se
     * suscribio. Sirve para que la barra de arriba **nunca quede vacia**: sin
     * esto, un servidor que no contesta dejaba una cabecera con una flecha de
     * volver, tres puntos y nada en el medio, que se ve como una pantalla rota.
     */
    // La clave del `remember` lleva `chats` ademas del id, y hace falta: la
    // lista llega por un Flow, asi que en la PRIMERA composicion esta vacia.
    // Recordando solo por el id, el nombre se calculaba una vez contra la
    // lista vacia y se quedaba vacio para siempre -el sintoma fue una
    // cabecera que decia "Canal" teniendo el nombre a mano en la base-.
    val nombreLocal = remember(conversacionId, chats) {
        chats.firstOrNull { it.id == conversacionId }?.titulo.orEmpty()
    }

    suspend fun refrescar() {
        cfg = app.repo.canal(conversacionId)
        // `null` y no una lista vacia cuando falla. Antes, `publicaciones`
        // devolvia `emptyList()` en el error, asi que la pantalla decia
        // "este canal todavia no tiene publicaciones": **afirmaba algo falso
        // sobre el canal cuando lo unico que pasaba era que no habia red**.
        feed = app.repo.publicaciones(conversacionId)
        fallo = cfg == null
        cargando = false
    }

    LaunchedEffect(conversacionId) { refrescar() }

    // Una publicación nueva llega como aviso del servidor, no como mensaje:
    // se recarga el feed en vez de insertar nada a mano.
    LaunchedEffect(conversacionId) {
        app.repo.avisos.collect { e ->
            // La decision del dueno tambien recarga: quien creo el canal
            // suele estar mirando ESTA pantalla mientras espera, y dejarla
            // diciendo "pendiente" despues de que se aprobo obliga a salir y
            // volver a entrar para descubrir que ya estaba aprobado.
            val suyo = e.conversacionId == conversacionId
            if (suyo && e.tipo in setOf("canal_publicacion", "canal_aprobado", "canal_rechazado")) {
                refrescar()
            }
        }
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
                        Text(
                            // Del servidor si llego; si no, el que ya
                            // teniamos. Una cabecera vacia no es un estado
                            // valido de esta pantalla.
                            cfg?.nombre?.ifBlank { null } ?: nombreLocal.ifBlank { "Canal" },
                            color = TextoPrimario,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = cfg?.let { c ->
                            buildString {
                                append("${c.suscriptores} ")
                                append(if (c.suscriptores == 1) "suscriptor" else "suscriptores")
                                c.alias?.let { append(" · @$it") }
                            }
                        }.orEmpty()
                        if (sub.isNotBlank()) Text(sub, color = TextoSecundario, fontSize = 13.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { menuAbierto = true }) {
                        Icon(Icons.Filled.MoreVert, "Más opciones", tint = TextoPrimario)
                    }
                    DropdownMenu(
                        expanded = menuAbierto,
                        onDismissRequest = { menuAbierto = false },
                        containerColor = BgElev,
                    ) {
                        OpcionMenu("Info del canal", Icons.Filled.Info) {
                            menuAbierto = false; mostrarInfo = true
                        }
                        if (cfg?.puedoGestionar == true) {
                            OpcionMenu("Estadisticas", Icons.Filled.BarChart) {
                                menuAbierto = false
                                ambito.launch { stats = app.repo.estadisticasCanal(conversacionId) }
                            }
                        }
                        if (cfg?.suscrito == true && cfg?.puedoGestionar != true) {
                            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                            OpcionMenu("Dejar de seguir", Icons.Filled.NotificationsOff, Coral) {
                                menuAbierto = false
                                ambito.launch {
                                    runCatching { app.repo.desuscribirCanal(conversacionId) }
                                        .onSuccess { onAtras() }
                                        .onFailure { aviso = it.message }
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            val c = cfg ?: return@Scaffold
            Surface(color = BgSurface) {
                when {
                    // Quien puede publicar escribe; quien no, no ve un campo
                    // que el servidor le va a rechazar.
                    c.puedoPublicar -> Column(
                        Modifier.navigationBarsPadding().imePadding(),
                    ) {
                    // La imagen elegida, ANTES de publicar y con forma de
                    // quitarla. Sin vista previa, adjuntar es un acto de fe: no
                    // se sabe que se eligio hasta que ya se publico.
                    imagen?.let { u ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            coil3.compose.AsyncImage(
                                model = u,
                                contentDescription = "Imagen elegida",
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Se publica con esta imagen.",
                                color = TextoSecundario,
                                fontSize = 12.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { imagen = null }) {
                                Icon(Icons.Filled.Close, "Quitar la imagen", tint = TextoSecundario)
                            }
                        }
                        HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                    }
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(
                            onClick = {
                                elegirImagen.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = !publicando,
                        ) {
                            Icon(Icons.Filled.Image, "Adjuntar una imagen", tint = TextoSecundario)
                        }
                        OutlinedTextField(
                            value = texto,
                            onValueChange = { texto = it },
                            placeholder = { Text("Publicar en el canal", color = TextoTerciario) },
                            modifier = Modifier.weight(1f),
                            maxLines = 6,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cian,
                                unfocusedBorderColor = Slate,
                                focusedContainerColor = BgElev,
                                unfocusedContainerColor = BgElev,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                val t = texto
                                val img = imagen
                                texto = ""
                                imagen = null
                                publicando = true
                                ambito.launch {
                                    app.repo.publicar(conversacionId, t, img)
                                        .onSuccess { refrescar() }
                                        // Se devuelven las DOS cosas al
                                        // compositor: perder el texto y la foto
                                        // elegida por un fallo de red obliga a
                                        // rehacer el trabajo entero.
                                        .onFailure { aviso = it.message; texto = t; imagen = img }
                                    publicando = false
                                }
                            },
                            // Con imagen alcanza: una foto sola es una
                            // publicacion.
                            enabled = (texto.isNotBlank() || imagen != null) && !publicando,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Cian,
                                contentColor = TextoSobreAcento,
                                disabledContainerColor = Slate.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            if (publicando) {
                                CircularProgressIndicator(
                                    color = TextoSobreAcento,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, "Publicar")
                            }
                        }
                    }
                    }

                    !c.suscrito -> Box(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) {
                        Button(
                            onClick = {
                                ambito.launch {
                                    runCatching { app.repo.suscribirCanal(conversacionId) }
                                        .onSuccess { refrescar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Cian, contentColor = TextoSobreAcento,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Seguir el canal")
                        }
                    }

                    else -> Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Campaign, null, tint = TextoTerciario, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Solo los administradores publican aquí",
                            color = TextoTerciario,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // F.7: el estado de revision, arriba de todo.
            //
            // Quien crea un canal tiene que saber por que no aparece en
            // ninguna lista y por que nadie se suscribe. Sin este cartel, un
            // canal pendiente se ve exactamente igual que un canal aprobado
            // que no le interesa a nadie, y esa confusion dura dias.
            cfg?.let { c ->
                if (c.estado != EstadoCanal.APROBADO) {
                    val rechazado = c.estado == EstadoCanal.RECHAZADO
                    val tinte = if (rechazado) Coral else Ambar
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(tinte.copy(alpha = 0.14f))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (rechazado) Icons.Filled.Block else Icons.Filled.HourglassEmpty,
                            null, tint = tinte, modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (rechazado) {
                                "Canal rechazado" + (c.motivoRechazo?.let { ": $it" } ?: "")
                            } else {
                                "Pendiente de aprobacion: todavía no se lista ni se puede seguir"
                            },
                            color = tinte,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // El aviso de cifrado va ARRIBA y siempre visible, no en un menú.
            cfg?.let { c ->
                if (!c.cifrado) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Ambar.copy(alpha = 0.14f))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.LockOpen, null, tint = Ambar, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Canal publico: el contenido no va cifrado de extremo a extremo",
                            color = Ambar,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            when {
                cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
                }

                // El fallo va ANTES que el vacio: si no, no llegaria nunca,
                // porque una carga fallida deja el feed vacio tambien.
                fallo -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EstadoDeError(
                        titulo = "No se pudo cargar el canal",
                        detalle = "Revisa tu conexión y vuelve a intentarlo.",
                        onReintentar = { ambito.launch { cargando = true; refrescar() } },
                    )
                }

                // El muro no se pudo leer, aunque la configuracion del canal
                // si. Dos peticiones, dos resultados posibles.
                feed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EstadoDeError(
                        titulo = "No se pudieron cargar las publicaciones",
                        detalle = "El canal esta, pero no pudimos leer su muro. " +
                            "Esto NO quiere decir que no tenga publicaciones.",
                        onReintentar = { ambito.launch { cargando = true; refrescar() } },
                    )
                }

                feed!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Campaign, null, tint = Slate, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (cfg?.puedoPublicar == true) "Todavía no publicaste nada."
                            else "Este canal todavía no tiene publicaciones.",
                            color = TextoSecundario,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        if (cfg?.cifrado == true) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Es un canal privado: su historial no se guarda en el " +
                                    "servidor, así que solo se ve lo que llego a este teléfono.",
                                color = TextoTerciario,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    items(feed.orEmpty(), key = { it.mensajeId }) { p ->
                        // La URL se pide una vez por publicacion y se
                        // recuerda mientras la pantalla vive. Pedirla dentro de
                        // la tarjeta la volveria a pedir en cada recomposicion
                        // del scroll.
                        LaunchedEffect(p.adjuntoId) {
                            val id = p.adjuntoId ?: return@LaunchedEffect
                            if (urlesImagen[id] == null) {
                                app.repo.urlImagenPublicacion(id)?.let {
                                    urlesImagen = urlesImagen + (id to it)
                                }
                            }
                        }
                        TarjetaPublicacion(
                            p = p,
                            comentariosActivos = cfg?.comentarios == true,
                            reaccionesActivas = cfg?.reacciones == true,
                            urlImagen = p.adjuntoId?.let { urlesImagen[it] },
                            onComentarios = { comentando = p },
                            onAbrirImagen = {
                                p.adjuntoId?.let { urlesImagen[it] }?.let { viendoImagen = it }
                            },
                            onReaccionar = { emoji, poner ->
                                ambito.launch {
                                    runCatching { app.repo.reaccionar(p.mensajeId, emoji, poner) }
                                        // El motivo del servidor llega a la
                                        // pantalla: un canal puede tener las
                                        // reacciones apagadas y el 403 lo dice.
                                        .onSuccess { refrescar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    stats?.let { e ->
        AlertDialog(
            onDismissRequest = { stats = null },
            containerColor = BgElev,
            title = { Text("Estadisticas", color = TextoPrimario) },
            text = {
                Column {
                    FilaDatoCanal("Suscriptores", e.suscriptores.toString())
                    FilaDatoCanal("Altas esta semana", e.altasSemana.toString())
                    FilaDatoCanal("Publicaciones", e.publicaciones.toString())
                    FilaDatoCanal("Comentarios", e.comentarios.toString())
                    FilaDatoCanal("Reacciones", e.reacciones.toString())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No hay cuenta de lecturas: saber quien leyo cada publicación " +
                            "exigiria que cada suscriptor lo reporte, y eso es un problema " +
                            "de privacidad antes que de escala.",
                        color = TextoTerciario,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { stats = null }) { Text("Cerrar", color = Cian) } },
        )
    }

    if (mostrarInfo) {
        val c = cfg
        AlertDialog(
            onDismissRequest = { mostrarInfo = false },
            containerColor = BgElev,
            title = { Text(c?.nombre.orEmpty(), color = TextoPrimario) },
            text = {
                Column {
                    c?.alias?.let { FilaDatoCanal("Alias", "@$it") }
                    FilaDatoCanal("Tipo", if (c?.publico == true) "Publico" else "Privado")
                    FilaDatoCanal("Cifrado", if (c?.cifrado == true) "De extremo a extremo" else "No")
                    FilaDatoCanal("Comentarios", if (c?.comentarios == true) "Activados" else "Desactivados")
                    FilaDatoCanal("Reacciones", if (c?.reacciones == true) "Activadas" else "Desactivadas")
                    if (!c?.descripcion.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(c.descripcion, color = TextoSecundario, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mostrarInfo = false }) { Text("Cerrar", color = Cian) } },
        )
    }

    viendoImagen?.let { url ->
        Dialog(
            onDismissRequest = { viendoImagen = null },
            // Sin esto el dialogo se queda con el ancho de un cuadro normal y
            // un visor a medio abrir no es un visor. Ver `VisorImagen`.
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .clickable { viendoImagen = null },
                contentAlignment = Alignment.Center,
            ) {
                coil3.compose.AsyncImage(
                    model = url,
                    contentDescription = "Imagen de la publicación",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    comentando?.let { pub ->
        HojaComentarios(
            conversacionId = conversacionId,
            publicacion = pub,
            publico = cfg?.publico == true,
            puedeComentar = cfg?.comentarios == true,
            onCerrar = {
                comentando = null
                // Al cerrar se refresca: el contador de la tarjeta lo calcula
                // el servidor, asi que comentar no lo cambia solo.
                ambito.launch { refrescar() }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            text = { Text(msg, color = TextoPrimario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

/**
 * Una publicación.
 *
 * Es una tarjeta a lo ancho y no una burbuja: en un canal no hay dos lados de
 * la conversación, hay una voz y una audiencia. Alinearla a la derecha sería
 * mentir sobre la forma de la conversación.
 */
@Composable
private fun TarjetaPublicacion(
    p: Publicacion,
    comentariosActivos: Boolean,
    reaccionesActivas: Boolean,
    /** Firmada y caduca, asi que se pide por publicacion y no viene en el muro. */
    urlImagen: String?,
    onComentarios: () -> Unit,
    onAbrirImagen: () -> Unit,
    onReaccionar: (String, Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@${p.autor}",
                color = colorDeNombre(p.autor),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (p.fijado) {
                Spacer(Modifier.width(7.dp))
                Icon(Icons.Filled.PushPin, "Fijada", tint = Cian, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(hora(p.creadoEn), color = TextoTerciario, fontSize = 11.sp)
        }
        // Modulo AC: la imagen, si tiene, ANTES del texto.
        //
        // Arriba y no abajo porque en un anuncio la imagen es el titular: es lo
        // que hace parar el scroll, y el texto explica lo que ya se vio.
        if (p.adjuntoId != null) {
            Spacer(Modifier.height(10.dp))
            // La proporcion real, para que la tarjeta no salte de alto cuando
            // la imagen termina de cargar.
            // El suelo es 0.8 y no 0.6 como en el chat, y se decidio mirandolo:
            // la tarjeta del canal ocupa el ANCHO COMPLETO, asi que una foto
            // vertical a 0.6 mide 1.66 veces el ancho de la pantalla y se come
            // el texto, las reacciones y el boton de comentar. En el chat la
            // burbuja es mas angosta y el mismo numero da una altura razonable.
            //
            // Recorta una vertical, si. Pero una publicacion donde no se ve el
            // texto que la acompana no es una publicacion, es una foto.
            val prop = if (p.adjuntoAncho > 0 && p.adjuntoAlto > 0) {
                (p.adjuntoAncho.toFloat() / p.adjuntoAlto).coerceIn(0.8f, 1.9f)
            } else 1.4f
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(prop)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgBase)
                    .clickable(enabled = urlImagen != null) { onAbrirImagen() },
                contentAlignment = Alignment.Center,
            ) {
                if (urlImagen != null) {
                    coil3.compose.AsyncImage(
                        model = urlImagen,
                        contentDescription = "Imagen de la publicación",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // Sin URL todavia: se marca el hueco en vez de dejar la
                    // tarjeta con un rectangulo vacio sin explicacion.
                    CircularProgressIndicator(
                        color = Cian, strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (p.cuerpo.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(p.cuerpo, color = TextoPrimario, fontSize = 16.sp)
        }

        if (p.editado) {
            Spacer(Modifier.height(6.dp))
            Text("editada", color = TextoTerciario, fontSize = 11.sp)
        }

        // Modulo AA: las reacciones se pueden TOCAR, y los comentarios se
        // pueden ABRIR. Antes las dos cosas eran texto: el recuento de
        // reacciones -que ademas viajaba siempre vacio, ver `FilaReacciones`- y
        // "N comentarios" sin nada detras.
        if (reaccionesActivas) {
            Spacer(Modifier.height(10.dp))
            FilaReacciones(p, habilitadas = true, onReaccionar = onReaccionar)
        }

        if (comentariosActivos) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onComentarios)
                    .padding(vertical = 5.dp, horizontal = 2.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = when (p.comentarios) {
                            0 -> "Comentar esta publicación"
                            1 -> "Ver 1 comentario"
                            else -> "Ver ${p.comentarios} comentarios"
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Comment, null,
                    tint = Cian, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // Con cero tambien se ofrece, y por eso el texto cambia:
                    // "0 comentarios" no invita a nada y esconder la fila deja
                    // sin puerta a quien quiere ser el primero.
                    when (p.comentarios) {
                        0 -> "Comentar"
                        1 -> "1 comentario"
                        else -> "${p.comentarios} comentarios"
                    },
                    color = Cian,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun FilaDatoCanal(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(etiqueta, color = TextoTerciario, fontSize = 13.sp, modifier = Modifier.width(140.dp))
        Text(valor, color = TextoPrimario, fontSize = 13.sp)
    }
}
