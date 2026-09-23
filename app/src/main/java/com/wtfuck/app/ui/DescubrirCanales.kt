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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.CanalEnBusqueda
import com.wtfuck.protocol.CrearCanalReq
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wtfuck.app.datos.ChatFila
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Descubrir canales (F.5).
 *
 * Es la única pantalla del sistema donde se busca gente y contenido que uno no
 * conoce. En el resto de la app no hay directorio a propósito: sin teléfonos no
 * hay agenda que cruzar, y un buscador de usuarios sería un directorio de todo
 * el personal de la institución. Un canal público **eligió** ser encontrable, y
 * eso es lo que lo hace distinto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescubrirCanales(
    onAbrirCanal: (String) -> Unit,
    /** Nulo cuando esta pantalla es una pestaña: no hay atras que ofrecer. */
    onAtras: (() -> Unit)?,
    /**
     * Abrir el dialogo de crear nada mas entrar.
     *
     * Lo pide el boton "Nuevo · Canal" de la lista de chats: crear un canal
     * vive aqui, y sin esto ese boton solo podria dejar a la persona en esta
     * pantalla esperando que encuentre el icono de arriba. Llevar a alguien a
     * un sitio no es lo mismo que hacer lo que pidio.
     */
    abrirCreacion: Boolean = false,
    /** Se avisa al abrirlo para que la peticion no se repita al volver. */
    onCreacionAbierta: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var consulta by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<CanalEnBusqueda>>(emptyList()) }
    // F.7: la lista curada de canales aprobados. Es lo que se ve al entrar,
    // sin escribir nada. Null mientras carga: "todavia no se" y "no hay
    // ninguno" son cosas distintas y la pantalla las dice distinto.
    var directorio by remember { mutableStateOf<List<CanalEnBusqueda>?>(null) }
    var buscando by remember { mutableStateOf(false) }
    var creando by remember { mutableStateOf(false) }

    // Se consume la peticion al atenderla: si no, volver a esta pestaña mas
    // tarde reabriria el dialogo sin que nadie lo pidiera.
    LaunchedEffect(abrirCreacion) {
        if (abrirCreacion) { creando = true; onCreacionAbierta() }
    }
    var aviso by remember { mutableStateOf<String?>(null) }

    /**
     * La carga del directorio fallo. Es un tercer estado, ademas de
     * "cargando" y "no hay ninguno".
     *
     * Hace falta porque `directorio == null` ya significaba "todavia
     * cargando", asi que un fallo que devuelva null seria indistinguible de
     * una carga eterna. Tres estados, tres cosas distintas en pantalla.
     */
    var falloDirectorio by remember { mutableStateOf(false) }

    suspend fun cargarDirectorio() {
        val d = app.repo.directorioCanales()
        falloDirectorio = d == null
        directorio = d ?: emptyList()
    }

    LaunchedEffect(Unit) { cargarDirectorio() }

    // L.4 · Mis canales.
    //
    // Salen de la base LOCAL y no del servidor: son conversaciones que ya
    // estan sincronizadas, asi que pedirlas otra vez seria pagar una consulta
    // por algo que ya esta en el telefono -y que ademas se veria vacio los
    // primeros milisegundos-.
    //
    // Hasta aqui un canal suscrito solo aparecia en la lista de Chats, donde
    // convive con las conversaciones: la pestaña que se llama "Canales" no
    // mostraba los canales de uno. Eso es lo que cerraba L.4.
    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val mios = remember(chats) {
        chats.filter { it.tipo == "canal" && it.soyMiembro }
            .sortedByDescending { it.ultimaFecha ?: 0L }
    }

    // Espera antes de buscar: sin esto escribir "auditoria" son nueve consultas
    // para ver una.
    LaunchedEffect(consulta) {
        if (consulta.trim().length < 2) {
            resultados = emptyList()
            return@LaunchedEffect
        }
        buscando = true
        delay(350)
        resultados = app.repo.buscarCanales(consulta)
        buscando = false
    }

    Scaffold(
        modifier = modifier,
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    onAtras?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                        }
                    }
                },
                title = { Text("Canales", color = TextoPrimario) },
                actions = {
                    IconButton(onClick = { creando = true }) {
                        Icon(Icons.Filled.Add, "Crear canal", tint = Cian)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = consulta,
                onValueChange = { consulta = it },
                placeholder = { Text("Filtrar por nombre o @alias", color = TextoTerciario) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoSecundario) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian,
                    unfocusedBorderColor = Slate,
                    focusedContainerColor = BgSurface,
                    unfocusedContainerColor = BgSurface,
                ),
            )

            val sinConsulta = consulta.trim().length < 2

            when {
                // Sin consulta manda el directorio. Antes aqui habia un cartel
                // explicando como buscar, que es lo que se pone cuando no hay
                // nada que mostrar; con canales aprobados si hay algo que
                // mostrar, y una lista dice mas que cualquier instruccion.
                sinConsulta && directorio == null -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp) }

                // El fallo va antes del vacio: una carga fallida tambien
                // deja la lista vacia, y anunciar "no hay canales publicos"
                // sin haber podido preguntar es afirmar algo sobre la
                // plataforma entera basandose en un timeout.
                sinConsulta && falloDirectorio && mios.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) {
                    EstadoDeError(
                        titulo = "No se pudo cargar el directorio",
                        detalle = "Revisa tu conexión y vuelve a intentarlo.",
                        onReintentar = {
                            ambito.launch { directorio = null; cargarDirectorio() }
                        },
                    )
                }

                sinConsulta && directorio!!.isEmpty() && mios.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Campaign, null, tint = Slate, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Todavia no hay canales publicos.",
                            color = TextoSecundario,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Los canales los aprueba el dueno de la plataforma: el que " +
                                "crees aparece en esta lista cuando lo revise. A un canal " +
                                "privado se entra por invitacion y no se lista aqui.",
                            color = TextoTerciario,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                sinConsulta -> LazyColumn(Modifier.fillMaxSize()) {
                    if (mios.isNotEmpty()) {
                        item {
                            Text(
                                "Mis canales",
                                color = Cian,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(
                                    start = 18.dp, top = 2.dp, bottom = 6.dp,
                                ),
                            )
                        }
                        items(mios, key = { "mio-" + it.id }) { c ->
                            FilaMiCanal(c) { onAbrirCanal(c.id) }
                        }
                        item { Spacer(Modifier.height(14.dp)) }
                    }

                    item {
                        Text(
                            // El titulo cambia cuando hay canales propios
                            // arriba: "Canales de la plataforma" a secas, con
                            // una lista propia encima, se lee como si fueran
                            // dos veces lo mismo.
                            if (mios.isEmpty()) "Canales de la plataforma"
                            else "Descubrir mas canales",
                            color = TextoTerciario,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 6.dp),
                        )
                    }
                    items(directorio!!, key = { it.conversacionId }) { k ->
                        FilaCanal(k) { onAbrirCanal(k.conversacionId) }
                    }
                }

                buscando && resultados.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp) }

                resultados.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Ningun canal aprobado coincide con \"${consulta.trim()}\".",
                        color = TextoSecundario,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(34.dp),
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(resultados, key = { it.conversacionId }) { k ->
                        FilaCanal(k) { onAbrirCanal(k.conversacionId) }
                    }
                }
            }
        }
    }

    if (creando) {
        DialogoCrearCanal(
            onCerrar = { creando = false },
            onCrear = { req ->
                ambito.launch {
                    runCatching { app.repo.crearCanal(req) }
                        .onSuccess { creando = false; onAbrirCanal(it.conversacionId) }
                        .onFailure { aviso = it.message }
                }
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

@Composable
private fun FilaCanal(k: CanalEnBusqueda, onAbrir: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAbrir)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Cian.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Campaign, null, tint = Cian, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                k.nombre.ifBlank { "@${k.alias}" },
                color = TextoPrimario,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${k.alias} · ${k.suscriptores} " +
                    if (k.suscriptores == 1) "suscriptor" else "suscriptores",
                color = TextoSecundario,
                fontSize = 13.sp,
            )
            if (k.descripcion.isNotBlank()) {
                Text(
                    k.descripcion,
                    color = TextoTerciario,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (k.suscrito) {
            Icon(Icons.Filled.Check, "Ya lo sigues", tint = Cian, modifier = Modifier.size(19.dp))
        }
    }
    HorizontalDivider(color = Slate.copy(alpha = 0.18f), modifier = Modifier.padding(start = 76.dp))
}

/**
 * Crear un canal.
 *
 * El interruptor de público/privado cambia la promesa de seguridad, así que el
 * diálogo lo dice en el momento de elegir y no después. Un alias obligatorio
 * para los públicos también sale de ahí: un canal público sin alias no se
 * puede encontrar, y entonces no es público.
 */
@Composable
private fun DialogoCrearCanal(onCerrar: () -> Unit, onCrear: (CrearCanalReq) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var publico by remember { mutableStateOf(true) }
    var comentarios by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Nuevo canal", color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre", color = TextoTerciario) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' } },
                    label = { Text(if (publico) "Alias (obligatorio)" else "Alias (opcional)", color = TextoTerciario) },
                    prefix = { Text("@", color = TextoSecundario) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = descripcion,
                    onValueChange = { descripcion = it },
                    label = { Text("De que se trata", color = TextoTerciario) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    ),
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = publico,
                        onCheckedChange = { publico = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextoSobreAcento, checkedTrackColor = Cian,
                            uncheckedThumbColor = TextoSecundario, uncheckedTrackColor = BgBase,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Canal publico", color = TextoPrimario, fontSize = 15.sp)
                }
                Text(
                    if (publico) {
                        "Cualquiera lo encuentra y se suscribe. El historial se guarda en el " +
                            "servidor, asi que el contenido NO va cifrado de extremo a extremo."
                    } else {
                        "Solo por invitacion. El contenido va cifrado de extremo a extremo, y " +
                            "por eso quien entre despues no vera lo publicado antes."
                    },
                    color = if (publico) Ambar else Cian,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = comentarios,
                        onCheckedChange = { comentarios = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextoSobreAcento, checkedTrackColor = Cian,
                            uncheckedThumbColor = TextoSecundario, uncheckedTrackColor = BgBase,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Permitir comentarios", color = TextoPrimario, fontSize = 15.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCrear(
                        CrearCanalReq(
                            nombre = nombre.trim(),
                            alias = alias.trim().ifBlank { null },
                            publico = publico,
                            descripcion = descripcion.trim(),
                            comentarios = comentarios,
                        )
                    )
                },
                enabled = nombre.isNotBlank() && (!publico || alias.length >= 4),
            ) { Text("Crear", color = Cian) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}

/**
 * Un canal al que ya estoy suscrito.
 *
 * Es otra fila y no la misma que el directorio a proposito: aqui lo que
 * importa es si hay algo nuevo -el globo de no leidos- y no cuantos
 * suscriptores tiene, que es el dato con el que se decide si seguir uno
 * desconocido.
 */
@Composable
private fun FilaMiCanal(c: ChatFila, onAbrir: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAbrir)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(nombre = c.nombre, url = null, tamano = 44.dp, esGrupo = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.nombre,
                style = MaterialTheme.typography.titleSmall,
                color = TextoPrimario,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                c.ultimoTexto?.takeIf { it.isNotBlank() } ?: "Sin publicaciones todavia",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.noLeidos > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Cian, RoundedCornerShape(11.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    c.noLeidos.toString(),
                    color = TextoSobreAcento,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
