package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.Contacto
import com.wtfuck.protocol.UsuarioPublico
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La libreta.
 *
 * ## Por que existe, si ya hay una lista de chats
 *
 * Porque son dos cosas distintas. La lista de chats es *con quien hablaste*; la
 * libreta es *a quien conoces*. Sin ella, la unica forma de guardar a alguien es
 * abrirle una conversacion, y eso obliga a escribirle para poder recordarlo.
 *
 * Tiene ademas una consecuencia que no es de interfaz: desde el modulo I,
 * guardar a alguien lo convierte en "conocido" para los ajustes de privacidad,
 * asi que esta pantalla es donde se decide quien puede escribirte cuando tienes
 * el filtro puesto.
 *
 * ## El alias es mio
 *
 * Como YO llamo a esa persona, no como se llama. Es lo que separa una libreta
 * de una lista de usuarios, y no viaja a ningun lado: el otro nunca se entera.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactosPantalla(
    onAbrirChat: (String) -> Unit,
    /** Nulo cuando es una pestaña: no hay atras que ofrecer. */
    onAtras: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var contactos by remember { mutableStateOf<List<Contacto>>(emptyList()) }
    var busqueda by rememberSaveable { mutableStateOf("") }
    var buscandoUsuario by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<Contacto?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() { contactos = app.repo.contactos() }
    LaunchedEffect(Unit) { recargar() }

    val visibles = remember(contactos, busqueda) {
        if (busqueda.isBlank()) contactos
        else contactos.filter {
            it.username.contains(busqueda, true) ||
                it.nombreMostrado.contains(busqueda, true) ||
                it.alias.orEmpty().contains(busqueda, true)
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
                        onAtras?.let {
                            IconButton(onClick = it) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                            }
                        }
                    },
                    title = { Text("Contactos", color = TextoPrimario) },
                    actions = {
                        IconButton(onClick = { buscandoUsuario = true }) {
                            Icon(Icons.Filled.PersonSearch, "Buscar por usuario", tint = Cian)
                        }
                    },
                )
                if (contactos.isNotEmpty()) {
                    Surface(color = BgSurface, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = busqueda,
                            onValueChange = { busqueda = it },
                            placeholder = { Text("Buscar en mis contactos", color = TextoTerciario) },
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
            }
        },
    ) { pad ->
        when {
            contactos.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Icono, como en los otros vacios (canales, comunidades,
                // stickers). Sin el, este quedaba como el unico vacio de la
                // app que era solo texto flotando.
                Icon(
                    Icons.Filled.PersonSearch, null,
                    tint = Slate, modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Todavía no tienes contactos",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Busca a alguien por su @usuario y agrégalo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoTerciario,
                    // El bloque estaba centrado y el texto NO: con dos lineas,
                    // la segunda quedaba alineada a la izquierda dentro de un
                    // bloque centrado y el conjunto se veia torcido.
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { buscandoUsuario = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                ) { Text("Buscar por usuario") }
            }

            visibles.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                Text("Nada coincide con \"$busqueda\".", color = TextoTerciario)
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(visibles, key = { it.username }) { k ->
                    FilaContacto(
                        k,
                        onAbrir = {
                            ambito.launch {
                                runCatching { app.repo.nuevaDirecta(k.username) }
                                    .onSuccess { onAbrirChat(it) }
                                    .onFailure { aviso = it.message }
                            }
                        },
                        onMantener = { editando = k },
                    )
                    HorizontalDivider(
                        color = Slate.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 78.dp),
                    )
                }
            }
        }
    }

    if (buscandoUsuario) {
        HojaBuscarUsuario(
            yaGuardados = contactos.map { it.username }.toSet(),
            onCerrar = { buscandoUsuario = false },
            onAgregar = { usuario ->
                ambito.launch {
                    app.repo.guardarContacto(usuario, null)
                        .onSuccess { contactos = it }
                        .onFailure { aviso = it.message }
                }
            },
            onEscribir = { usuario ->
                buscandoUsuario = false
                ambito.launch {
                    runCatching { app.repo.nuevaDirecta(usuario) }
                        .onSuccess { onAbrirChat(it) }
                        .onFailure { aviso = it.message }
                }
            },
        )
    }

    editando?.let { k ->
        HojaContacto(
            contacto = k,
            onCerrar = { editando = null },
            onGuardar = { alias, favorito ->
                editando = null
                ambito.launch {
                    app.repo.guardarContacto(k.username, alias, favorito)
                        .onSuccess { contactos = it }
                        .onFailure { aviso = it.message }
                }
            },
            onBorrar = {
                editando = null
                ambito.launch {
                    app.repo.borrarContacto(k.username)
                        .onSuccess { contactos = it }
                        .onFailure { aviso = it.message }
                }
            },
        )
    }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaContacto(k: Contacto, onAbrir: () -> Unit, onMantener: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onAbrir, onLongClick = onMantener)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            nombre = k.alias?.ifBlank { null } ?: k.nombreMostrado.ifBlank { k.username },
            url = ApiCliente.urlImagen(k.username, "avatar", k.avatarVersion),
            tamano = 50.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // El alias manda sobre el nombre real: es como YO lo llamo.
                    k.alias?.ifBlank { null } ?: k.nombreMostrado.ifBlank { "@${k.username}" },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                    maxLines = 1,
                )
                if (k.favorito) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Star, "Favorito", tint = Ambar, modifier = Modifier.size(15.dp))
                }
            }
            Text(
                "@${k.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextoSecundario,
                maxLines = 1,
            )
        }
    }
}

/**
 * Buscar a alguien por su @usuario. Reemplaza a la busqueda por telefono.
 *
 * ## Que se encuentra, y que no
 *
 * Dos fuentes, y las dos respetan lo que cada persona eligio:
 *
 *  - el usuario EXACTO, con cualquiera que se deje encontrar (`priv_busqueda`):
 *    es lo que ya hacia "nueva conversacion" escribiendo el @;
 *  - sugerencias por PREFIJO, pero solo de quien eligio aparecer en Usuarios.
 *
 * No hay un buscador por prefijo abierto a todos a proposito: con eso,
 * escribir "a", "b", "c"... listaria a cada persona del sistema, que es justo
 * lo que el directorio voluntario existe para no hacer.
 *
 * Por eso la linea de abajo lo explica: sin decirlo, "no aparece nadie" al
 * escribir medio nombre parece una busqueda rota.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaBuscarUsuario(
    yaGuardados: Set<String>,
    onCerrar: () -> Unit,
    onAgregar: (String) -> Unit,
    onEscribir: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var texto by remember { mutableStateOf("") }
    var buscando by remember { mutableStateOf(false) }
    var resultados by remember { mutableStateOf<List<UsuarioPublico>>(emptyList()) }
    val consulta = texto.trim().removePrefix("@").lowercase()

    LaunchedEffect(consulta) {
        if (consulta.length < 2) { resultados = emptyList(); return@LaunchedEffect }
        delay(350)
        buscando = true
        val exacto = app.repo.perfilDe(consulta)
        val sugeridos = app.repo.directorio(consulta).getOrNull()?.usuarios.orEmpty()
        resultados = listOfNotNull(exacto) + sugeridos.filter { it.username != exacto?.username }
        buscando = false
    }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgSurface,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp).imePadding()) {
            Text("Buscar por usuario", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it.take(33) },
                prefix = { Text("@", color = TextoTerciario) },
                placeholder = { Text("usuario", color = TextoTerciario) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian,
                    unfocusedBorderColor = Slate.copy(alpha = 0.6f),
                    focusedTextColor = TextoPrimario,
                    unfocusedTextColor = TextoPrimario,
                    cursorColor = Cian,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Aparece quien escribas exacto, y por parte del nombre solo quien eligió " +
                    "aparecer en Usuarios.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
            )
            Spacer(Modifier.height(10.dp))

            when {
                consulta.length < 2 -> Unit
                buscando && resultados.isEmpty() -> Text("Buscando...", color = TextoSecundario)
                resultados.isEmpty() -> Text(
                    "No hay nadie con ese usuario, o no quiere que lo encuentren así.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(resultados, key = { it.usuarioId }) { u ->
                        val nombre = u.nombreMostrado.ifBlank { u.username }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onEscribir(u.username) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                nombre = nombre,
                                url = ApiCliente.urlImagen(u.username, "avatar", u.avatarVersion),
                                tamano = 42.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nombre, color = TextoPrimario, maxLines = 1)
                                Text("@${u.username}", style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
                            }
                            if (u.username in yaGuardados) {
                                Text("ya lo tienes", style = MaterialTheme.typography.labelSmall, color = Cian)
                            } else {
                                TextButton(onClick = { onAgregar(u.username) }) { Text("Agregar", color = Cian) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaContacto(
    contacto: Contacto,
    onCerrar: () -> Unit,
    onGuardar: (String?, Boolean) -> Unit,
    onBorrar: () -> Unit,
) {
    var alias by remember { mutableStateOf(contacto.alias.orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("@${contacto.username}", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Como lo llamas") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian,
                    unfocusedBorderColor = Slate,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onGuardar(alias.trim().ifBlank { null }, !contacto.favorito) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Star,
                    null,
                    tint = if (contacto.favorito) Ambar else Slate,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    if (contacto.favorito) "Quitar de favoritos" else "Marcar como favorito",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextoPrimario,
                )
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))

            Row {
                TextButton(onClick = { onGuardar(alias.trim().ifBlank { null }, contacto.favorito) }) {
                    Text("Guardar", color = Cian, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBorrar) { Text("Quitar de contactos", color = Coral) }
            }
        }
    }
}
