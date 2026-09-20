package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.*
import kotlinx.coroutines.launch

/**
 * Panel de moderacion.
 *
 * ## Lo que este panel no puede hacer, y por que importa
 *
 * No muestra mensajes. No los busca, no los lee, no los exporta. En una app
 * normal un panel administrativo se llena de eso; aqui es **imposible por
 * construccion**, porque el servidor solo guarda bytes opacos. Lo unico legible
 * que existe es lo que un denunciante entrego voluntariamente, vive dentro de
 * su denuncia, y se borra al cerrarla.
 *
 * Que la limitacion sea del diseno y no de esta pantalla es el punto: no hay
 * una version del panel con mas permisos que si pueda leer.
 *
 * ## Por que la cola muestra los contadores del denunciado
 *
 * Porque es lo que separa un caso de un patron. Un moderador que solo ve la
 * denuncia de adelante trata las diez denuncias de la misma persona como diez
 * incidentes sueltos, y decide diez veces lo mismo mal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelPantalla(
    onAtras: () -> Unit,
    onLimites: () -> Unit,
    onBitacora: () -> Unit,
    onConversaciones: () -> Unit,
    onConsolaWeb: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var resumen by remember { mutableStateOf<ResumenPanel?>(null) }
    var cola by remember { mutableStateOf<List<DenunciaEnCola>>(emptyList()) }
    var abierta by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }

    var pestana by rememberSaveable { mutableIntStateOf(0) }
    var consulta by rememberSaveable { mutableStateOf("") }
    var personas by remember { mutableStateOf<List<UsuarioPanel>>(emptyList()) }
    var suspendiendo by remember { mutableStateOf<UsuarioPanel?>(null) }

    // F.7: la cola de canales. Solo la ve el propietario, asi que la pestana
    // tambien: ofrecer una pestana que devuelve 404 es peor que no ofrecerla.
    var canales by remember { mutableStateOf<List<CanalPendiente>>(emptyList()) }
    var aprobados by remember { mutableStateOf<List<CanalPendiente>>(emptyList()) }
    var rechazando by remember { mutableStateOf<CanalPendiente?>(null) }
    // H.3: dentro de la pestana de canales, que se esta mirando. Aprobar no
    // puede ser una puerta de un solo sentido: lo ya publicado tambien hay que
    // poder verlo para poder retirarlo.
    var verAprobados by rememberSaveable { mutableStateOf(false) }

    suspend fun recargar() {
        resumen = app.repo.resumenPanel()
        cola = app.repo.colaModeracion()
        // La cola de canales solo existe para el propietario; para el resto
        // vuelve vacia y la pestana ni aparece.
        canales = if ((resumen?.miNivel ?: 0) >= 100) app.repo.colaCanales() else emptyList()
        aprobados = if ((resumen?.miNivel ?: 0) >= 100) {
            app.repo.colaCanales(EstadoCanal.APROBADO)
        } else emptyList()
        cargando = false
    }

    LaunchedEffect(Unit) { recargar() }

    // Espera antes de buscar, igual que en los canales: sin esto, escribir un
    // nombre son ocho consultas para ver una.
    LaunchedEffect(consulta) {
        if (consulta.trim().length < 2) {
            personas = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        personas = app.repo.buscarUsuariosPanel(consulta)
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
                title = { Text("Moderacion", color = TextoPrimario) },
                actions = {
                    // H.6. Solo para administrador (80) o mas: el servidor
                    // responde 404 a un moderador, asi que ofrecer el boton
                    // seria ofrecer una puerta cerrada.
                    if ((resumen?.miNivel ?: 0) >= 80) {
                        IconButton(onClick = onConsolaWeb) {
                            Icon(Icons.Filled.Computer, "Consola web", tint = TextoSecundario)
                        }
                        IconButton(onClick = onConversaciones) {
                            Icon(Icons.Filled.Groups, "Grupos y canales", tint = TextoSecundario)
                        }
                        IconButton(onClick = onLimites) {
                            Icon(Icons.Filled.Speed, "Limites", tint = TextoSecundario)
                        }
                        IconButton(onClick = onBitacora) {
                            Icon(Icons.Filled.History, "Bitacora", tint = TextoSecundario)
                        }
                    }
                    IconButton(onClick = { ambito.launch { recargar() } }) {
                        Icon(Icons.Filled.Refresh, "Actualizar", tint = TextoSecundario)
                    }
                },
            )
        },
    ) { pad ->
        if (cargando) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(14.dp)) {
            // El tablero solo va en la cola.
            //
            // Al principio estaba arriba de las dos pestañas, y probando en el
            // emulador se vio el problema: buscar una persona dejaba el
            // resultado a 1800px, detras del teclado. Ademas el tablero mide la
            // carga de trabajo de la COLA, asi que en "Personas" no solo
            // estorbaba: no venia al caso.
            if (pestana == 0) {
                resumen?.let { rs ->
                    item {
                        Tablero(rs)
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }

            item {
                // Dos pestanas y no dos pantallas: son las dos formas de llegar
                // al mismo sitio -una persona-, una desde lo que denunciaron y
                // otra desde su nombre. Separarlas en dos destinos obligaria a
                // volver atras cada vez que se cambia de camino.
                TabRow(
                    selectedTabIndex = pestana,
                    containerColor = BgBase,
                    contentColor = Cian,
                    divider = { HorizontalDivider(color = Slate.copy(alpha = 0.25f)) },
                ) {
                    val esPropietario = (resumen?.miNivel ?: 0) >= 100
                    val etiquetas = buildList {
                        add("Cola de revision")
                        add("Personas")
                        if (esPropietario) {
                            add(if (canales.isEmpty()) "Canales" else "Canales (${canales.size})")
                        }
                    }
                    etiquetas.forEachIndexed { i, texto ->
                        Tab(
                            selected = pestana == i,
                            onClick = { pestana = i },
                            selectedContentColor = Cian,
                            unselectedContentColor = TextoTerciario,
                            text = { Text(texto, fontSize = 13.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (pestana == 1) {
                item {
                    OutlinedTextField(
                        value = consulta,
                        onValueChange = { consulta = it },
                        placeholder = { Text("Buscar por usuario", color = TextoTerciario) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoSecundario) },
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // El limite es del servidor, y el motivo conviene que
                        // se lea: con una letra esto seria el listado completo
                        // de la plataforma disfrazado de busqueda.
                        "Hacen falta al menos dos letras.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                items(personas, key = { it.username }) { u ->
                    FilaPersona(u) { suspendiendo = u }
                    Spacer(Modifier.height(8.dp))
                }

                if (personas.isEmpty() && consulta.trim().length >= 2) {
                    item {
                        Text(
                            "Nadie con ese nombre.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoTerciario,
                        )
                    }
                }
                return@LazyColumn
            }

            if (pestana == 2) {
                item {
                    Text(
                        "Un canal no aparece en el directorio ni admite suscriptores hasta " +
                            "que lo apruebes. Es la superficie de mas alcance de la " +
                            "plataforma, y la unica cuyo contenido el servidor guarda en claro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(12.dp))

                    // Dos vistas y no dos pestanas: es el mismo objeto en dos
                    // momentos, y la accion que importa -retirar- solo se
                    // encuentra si lo publicado esta a un toque de la cola.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !verAprobados,
                            onClick = { verAprobados = false },
                            label = { Text("Por aprobar (${canales.size})", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cian.copy(alpha = 0.18f),
                                selectedLabelColor = Cian,
                                labelColor = TextoTerciario,
                            ),
                        )
                        FilterChip(
                            selected = verAprobados,
                            onClick = { verAprobados = true },
                            label = { Text("En el directorio (${aprobados.size})", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cian.copy(alpha = 0.18f),
                                selectedLabelColor = Cian,
                                labelColor = TextoTerciario,
                            ),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                val lista = if (verAprobados) aprobados else canales
                if (lista.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Inbox, null, tint = Slate, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (verAprobados) "Ningun canal publicado" else "Ningun canal esperando",
                                color = TextoSecundario,
                            )
                        }
                    }
                } else {
                    items(lista, key = { it.conversacionId }) { k ->
                        FilaCanalPendiente(
                            k,
                            onAprobar = {
                                ambito.launch {
                                    app.repo.revisarCanal(k.conversacionId, true)
                                        .onSuccess { aviso = "Canal aprobado: ya aparece en el directorio."; recargar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                            onRechazar = { rechazando = k },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                return@LazyColumn
            }

            if (cola.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Inbox, null, tint = Slate, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No hay nada pendiente", color = TextoSecundario)
                    }
                }
            } else {
                items(cola, key = { it.id }) { d ->
                    FilaDenuncia(d) { abierta = d.id }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    abierta?.let { id ->
        HojaDenuncia(
            denunciaId = id,
            onCerrar = { abierta = null },
            onResuelta = { msg ->
                abierta = null
                aviso = msg
                ambito.launch { recargar() }
            },
        )
    }

    rechazando?.let { k ->
        DialogoRechazarCanal(
            canal = k,
            onCerrar = { rechazando = null },
            onRechazar = { motivo ->
                ambito.launch {
                    app.repo.revisarCanal(k.conversacionId, false, motivo)
                        .onSuccess { rechazando = null; aviso = "Canal rechazado."; recargar() }
                        .onFailure { aviso = it.message }
                }
            },
        )
    }

    suspendiendo?.let { u ->
        HojaPersona(
            persona = u,
            nivelPropio = resumen?.miNivel ?: 0,
            onCerrar = { suspendiendo = null },
            onHecho = { msg, nueva ->
                suspendiendo = null
                aviso = msg
                // Se reemplaza la fila en la lista en vez de rebuscar: la
                // respuesta del servidor YA trae el estado nuevo.
                personas = personas.map { if (it.username == nueva.username) nueva else it }
                ambito.launch { recargar() }
            },
            onError = { suspendiendo = null; aviso = it },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("Listo", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Cerrar", color = Cian) } },
        )
    }
}

@Composable
private fun Tablero(rs: ResumenPanel) {
    Column {
        Row {
            Metrica("Pendientes", rs.denunciasPendientes, Ambar, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Metrica("En revision", rs.denunciasEnRevision, Cian, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Metrica("Cerradas hoy", rs.denunciasResueltasHoy, Slate, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Metrica("Suspendidos", rs.usuariosSuspendidos, Coral, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Metrica("Advertencias", rs.advertenciasVigentes, Ambar, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            // Los limites excedidos son la senal mas temprana de un abuso: un
            // script se estrella contra ellos antes de que alguien lo denuncie.
            Metrica("Limites hoy", rs.limitesExcedidosHoy, Taupe, Modifier.weight(1f))
        }

        // F.7. Solo si hay alguno: una metrica en cero todos los dias es ruido
        // que entrena a no mirar el tablero.
        if (rs.canalesPendientes > 0) {
            Spacer(Modifier.height(10.dp))
            Row {
                Metrica("Canales por aprobar", rs.canalesPendientes, Cian, Modifier.weight(1f))
            }
        }

        if (rs.porMotivo.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Lo abierto, por motivo", style = MaterialTheme.typography.labelLarge, color = TextoSecundario)
            Spacer(Modifier.height(6.dp))
            rs.porMotivo.forEach { (motivo, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        MotivoDenuncia.etiqueta(motivo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoPrimario,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$n", style = MaterialTheme.typography.bodyMedium, color = Cian)
                }
            }
        }
    }
}

@Composable
private fun Metrica(titulo: String, valor: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text("$valor", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
            Text(titulo, style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
        }
    }
}

@Composable
private fun FilaDenuncia(d: DenunciaEnCola, onAbrir: () -> Unit) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAbrir),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etiqueta(d.tipo, Slate)
                Spacer(Modifier.width(6.dp))
                Etiqueta(MotivoDenuncia.etiqueta(d.motivo), Ambar)
                Spacer(Modifier.weight(1f))
                if (d.estado == "en_revision") Etiqueta("en revision", Cian)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("@${d.denunciante} denuncio ")
                    append(
                        when (d.tipo) {
                            TipoDenuncia.USUARIO -> "a @${d.objetivoUsuario}"
                            TipoDenuncia.MENSAJE -> "un mensaje de @${d.objetivoUsuario}"
                            else -> "\"${d.objetivoTitulo ?: "una conversacion"}\""
                        }
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextoPrimario,
            )
            if (d.detalle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(d.detalle, style = MaterialTheme.typography.bodySmall, color = TextoSecundario, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fechaLarga(d.creadaEn), style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
                Spacer(Modifier.weight(1f))
                // Los dos numeros que convierten un caso en un patron.
                if (d.denunciasDelObjetivo > 1) {
                    Text(
                        "${d.denunciasDelObjetivo} denuncias abiertas",
                        style = MaterialTheme.typography.labelSmall,
                        color = Coral,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (d.advertenciasDelObjetivo > 0) {
                    Text(
                        "${d.advertenciasDelObjetivo} advertencias",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ambar,
                    )
                }
            }
        }
    }
}

@Composable
private fun Etiqueta(texto: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * El detalle de una denuncia, con la evidencia y las acciones.
 *
 * Tomarla antes de resolverla no es burocracia: es lo que impide que dos
 * moderadores trabajen la misma denuncia y lleguen a decisiones distintas. El
 * servidor rechaza al segundo que la toma.
 */
@Composable
private fun HojaDenuncia(
    denunciaId: String,
    onCerrar: () -> Unit,
    onResuelta: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var detalle by remember { mutableStateOf<DenunciaDetalle?>(null) }
    var nota by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(denunciaId) { detalle = app.repo.denunciaDetalle(denunciaId) }

    fun resolver(accion: String, horas: Int?) {
        trabajando = true
        ambito.launch {
            // Se toma primero: si otra persona la tiene, el 409 aparece ANTES
            // de haber sancionado a nadie.
            if (detalle?.cabecera?.estado == "pendiente") {
                app.repo.tomarDenuncia(denunciaId)
            }
            app.repo.resolverDenuncia(denunciaId, ResolverReq(accion, nota.trim(), horas))
                .onSuccess { hecho ->
                    trabajando = false
                    onResuelta(
                        buildString {
                            append("Denuncia cerrada como \"$accion\".")
                            hecho.escaladaAutomatica?.let {
                                append(" Se acumularon ${hecho.advertenciasVigentes} advertencias: ")
                                append("suspension automatica aplicada.")
                            }
                        }
                    )
                }
                .onFailure { trabajando = false; error = it.message }
        }
    }

    val d = detalle
    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = { Text("Denuncia", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Cian)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        MotivoDenuncia.etiqueta(d.cabecera.motivo),
                        style = MaterialTheme.typography.titleSmall,
                        color = Ambar,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@${d.cabecera.denunciante} → " +
                            (d.cabecera.objetivoUsuario?.let { "@$it" }
                                ?: d.cabecera.objetivoTitulo ?: "una conversacion"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoPrimario,
                    )
                    if (d.cabecera.detalle.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(d.cabecera.detalle, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
                    }

                    if (d.evidencia.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LockOpen, null, tint = Ambar, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Texto entregado por quien denuncia",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ambar,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // Que el moderador sepa de donde viene el texto es
                            // parte de poder valorarlo: no es una captura del
                            // servidor, es la version de una de las partes.
                            "El servidor no puede leer los mensajes. Esto lo aporto el denunciante, " +
                                "y se borra al cerrar el caso.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextoTerciario,
                        )
                        Spacer(Modifier.height(8.dp))
                        d.evidencia.forEach { e ->
                            Surface(
                                color = BgSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        "@${e.autor}  ·  ${fechaLarga(e.enviadoEn)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorDeNombre(e.autor),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        e.contenido,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextoPrimario,
                                    )
                                }
                            }
                        }
                    } else if (d.cabecera.tipo == TipoDenuncia.MENSAJE) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Sin texto adjunto: quien denuncio no lo entrego, o el caso ya se cerro. " +
                                "El servidor no lo tiene y no puede recuperarlo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoTerciario,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = nota,
                        onValueChange = { if (it.length <= 400) nota = it },
                        label = { Text("Nota de la resolucion") },
                        minLines = 2,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Que hacer", style = MaterialTheme.typography.labelLarge, color = TextoSecundario)
                    Spacer(Modifier.height(6.dp))

                    Accion("Descartar: no tenia fundamento", Slate, trabajando) {
                        resolver(AccionModeracion.DESCARTAR, null)
                    }
                    Accion("Cerrar sin accion", Slate, trabajando) {
                        resolver(AccionModeracion.SIN_ACCION, null)
                    }
                    if (d.cabecera.objetivoUsuario != null) {
                        Accion("Advertir", Ambar, trabajando) {
                            resolver(AccionModeracion.ADVERTIR, null)
                        }
                        if (d.cabecera.objetivoConversacion != null) {
                            Accion("Silenciar 24 h en esa conversacion", Ambar, trabajando) {
                                resolver(AccionModeracion.SILENCIAR, 24)
                            }
                            Accion("Expulsar de esa conversacion", Coral, trabajando) {
                                resolver(AccionModeracion.EXPULSAR, null)
                            }
                        }
                        Accion("Suspender la cuenta 72 h", Coral, trabajando) {
                            resolver(AccionModeracion.SUSPENDER, 72)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !trabajando, onClick = onCerrar) {
                Text("Cerrar", color = TextoSecundario)
            }
        },
    )

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            containerColor = BgElev,
            title = { Text("No se pudo", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

@Composable
private fun Accion(
    texto: String,
    color: androidx.compose.ui.graphics.Color,
    ocupado: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !ocupado,
        border = ButtonDefaults.outlinedButtonBorder(enabled = !ocupado),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(texto, fontSize = 13.sp)
    }
}

/**
 * Una persona en el panel.
 *
 * Los tres numeros que se muestran -advertencias vivas, denuncias recibidas,
 * denuncias hechas- estan elegidos: el ultimo existe porque **denunciar es un
 * arma**. Alguien con cuarenta denuncias hechas y ninguna recibida no es una
 * victima frecuente, y sin ese dato el patron es invisible.
 */
@Composable
private fun FilaPersona(u: UsuarioPanel, onAbrir: () -> Unit) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAbrir),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "@${u.username}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextoPrimario,
                )
                Spacer(Modifier.width(8.dp))
                if (u.staffNivel > 0) {
                    Etiqueta(
                        when (u.staffNivel) {
                            100 -> "propietario"
                            80 -> "administrador"
                            else -> "moderador"
                        },
                        Cian,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (u.suspendido) Etiqueta("suspendido", Coral)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "en wtfuck desde ${fechaLarga(u.creadoEn)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Dato("advertencias", u.advertenciasVigentes, if (u.advertenciasVigentes > 0) Ambar else Slate)
                Spacer(Modifier.width(18.dp))
                Dato("denuncias recibidas", u.denunciasRecibidas, if (u.denunciasRecibidas > 0) Coral else Slate)
                Spacer(Modifier.width(18.dp))
                Dato("denuncias hechas", u.denunciasHechas, Slate)
            }
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text("$valor", style = MaterialTheme.typography.titleSmall, color = color)
        Text(etiqueta, style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
    }
}

/**
 * Suspender o restaurar una cuenta.
 *
 * Solo administrador (80). La pantalla no ofrece el boton a un moderador en vez
 * de dejarlo intentar y comerse un 404: ensenar una accion que el servidor va a
 * rechazar es una promesa falsa. Quien autoriza sigue siendo el servidor.
 */
@Composable
private fun HojaPersona(
    persona: UsuarioPanel,
    nivelPropio: Int,
    onCerrar: () -> Unit,
    onHecho: (String, UsuarioPanel) -> Unit,
    onError: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var motivo by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }

    val puedeSancionar = nivelPropio >= 80 && persona.staffNivel < nivelPropio

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = { Text("@${persona.username}", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (persona.suspendido) {
                    Text(
                        "Suspendido" + (persona.suspendidoHasta
                            ?.let { " hasta el ${fechaLarga(it)}" } ?: " sin fecha de fin"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Coral,
                    )
                    persona.suspensionMotivo?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                    }
                } else {
                    Text(
                        "Cuenta activa.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                }

                Spacer(Modifier.height(14.dp))
                if (!puedeSancionar) {
                    Text(
                        if (nivelPropio < 80) {
                            "Suspender una cuenta necesita nivel de administrador."
                        } else {
                            "No puedes sancionar a alguien de tu mismo nivel o superior."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                } else if (persona.suspendido) {
                    Accion("Restaurar la cuenta", Cian, trabajando) {
                        trabajando = true
                        ambito.launch {
                            app.repo.restaurarUsuario(persona.username)
                                .onSuccess { onHecho("@${persona.username} vuelve a poder escribir.", it) }
                                .onFailure { onError(it.message ?: "No se pudo.") }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = motivo,
                        onValueChange = { if (it.length <= 300) motivo = it },
                        label = { Text("Motivo (obligatorio)") },
                        minLines = 2,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // El servidor exige motivo. No es tramite: sin motivo no
                        // hay nada que explicarle al suspendido, y una sancion
                        // que no se explica no corrige nada.
                        "El suspendido ve este texto en su cuenta.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(10.dp))

                    listOf("24 horas" to 24, "7 dias" to 168, "Sin fecha de fin" to null)
                        .forEach { (etiqueta, horas) ->
                            Accion("Suspender: $etiqueta", Coral, trabajando || motivo.isBlank()) {
                                trabajando = true
                                ambito.launch {
                                    app.repo.suspenderUsuario(persona.username, motivo.trim(), horas)
                                        .onSuccess { onHecho("@${persona.username} queda suspendido.", it) }
                                        .onFailure { onError(it.message ?: "No se pudo.") }
                                }
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !trabajando, onClick = onCerrar) {
                Text("Cerrar", color = TextoSecundario)
            }
        },
    )
}


// ============================================================
//  F.7 · Canales esperando decision
// ============================================================

@Composable
private fun FilaCanalPendiente(
    k: CanalPendiente,
    onAprobar: () -> Unit,
    onRechazar: () -> Unit,
) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Campaign, null, tint = Cian, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    k.nombre,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextoPrimario,
                    modifier = Modifier.weight(1f),
                )
                // Publico o privado cambia por completo lo que se esta
                // aprobando: uno es una tribuna abierta y el otro no se lista
                // en ningun lado. Va a la vista y no en un detalle.
                Text(
                    if (k.publico) "publico" else "privado",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (k.publico) Ambar else Slate,
                )
            }

            k.alias?.let {
                Spacer(Modifier.height(2.dp))
                Text("@$it", style = MaterialTheme.typography.labelMedium, color = Cian)
            }

            if (k.descripcion.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    k.descripcion,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                    maxLines = 4,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                // Un canal puede haber quedado sin creador -la cuenta se
                // elimino- y "@ ·" a secas se lee como un error de la app.
                (if (k.creador.isNotBlank()) "@${k.creador} · " else "") +
                    fechaLarga(k.creadoEn) +
                    (if (k.suscriptores > 0) {
                        " · ${k.suscriptores} " +
                            if (k.suscriptores == 1) "suscriptor" else "suscriptores"
                    } else "") +
                    (if (k.publicaciones > 0) {
                        " · ${k.publicaciones} " +
                            if (k.publicaciones == 1) "publicacion" else "publicaciones"
                    } else ""),
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )

            Spacer(Modifier.height(12.dp))
            val yaAprobado = k.estado == EstadoCanal.APROBADO
            if (yaAprobado) {
                // Un canal publicado no se "rechaza": se RETIRA. Y es lo unico
                // que se ofrece, porque aprobar lo aprobado no hace nada.
                OutlinedButton(
                    onClick = onRechazar,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retirar del directorio") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onAprobar,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cian, contentColor = TextoSobreAcento,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Aprobar") }
                    OutlinedButton(
                        onClick = onRechazar,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
                        modifier = Modifier.weight(1f),
                    ) { Text("Rechazar") }
                }
            }
        }
    }
}

/** Rechazar pide motivo: el servidor lo exige y la persona lo necesita. */
@Composable
private fun DialogoRechazarCanal(
    canal: CanalPendiente,
    onCerrar: () -> Unit,
    onRechazar: (String) -> Unit,
) {
    var motivo by remember { mutableStateOf("") }
    val retirar = canal.estado == EstadoCanal.APROBADO
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = {
            Text(
                (if (retirar) "Retirar " else "Rechazar ") + canal.nombre,
                color = TextoPrimario,
            )
        },
        text = {
            Column {
                Text(
                    if (retirar) {
                        // Hay que decirlo: retirar NO borra lo que la gente
                        // ya leyo. Prometer lo contrario seria mentir.
                        "Sale del directorio y nadie mas se puede suscribir. Sus " +
                            "${canal.suscriptores} suscriptores actuales conservan lo que ya " +
                            "recibieron: eso no se puede deshacer."
                    } else {
                        "Quien lo creo va a ver este motivo. Sin el, un rechazo es un " +
                            "muro sin salida."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = motivo,
                    onValueChange = { motivo = it },
                    placeholder = { Text("Motivo", color = TextoTerciario) },
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Coral,
                        unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRechazar(motivo.trim()) },
                enabled = motivo.trim().length >= 3,
            ) {
                Text(
                    if (retirar) "Retirar" else "Rechazar",
                    color = if (motivo.trim().length >= 3) Coral else Slate,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}
