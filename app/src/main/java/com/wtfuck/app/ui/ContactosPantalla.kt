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
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.Contacto
import com.wtfuck.protocol.Descubierto
import com.wtfuck.protocol.Telefonos
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
    var descubriendo by remember { mutableStateOf(false) }
    var agregando by remember { mutableStateOf(false) }
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
                        IconButton(onClick = { descubriendo = true }) {
                            Icon(Icons.Filled.PersonSearch, "Buscar por telefono", tint = Cian)
                        }
                        IconButton(onClick = { agregando = true }) {
                            Icon(Icons.Filled.PersonAdd, "Agregar por usuario", tint = Cian)
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
                Text(
                    "Todavia no tienes contactos",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Busca por numero de telefono, o agrega a alguien por su usuario.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { descubriendo = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                ) { Text("Buscar por telefono") }
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

    if (descubriendo) {
        HojaDescubrir(
            onCerrar = { descubriendo = false },
            onAgregado = { ambito.launch { recargar() } },
        )
    }

    if (agregando) {
        DialogoAgregarPorUsuario(
            onCerrar = { agregando = false },
            onAgregar = { usuario, alias ->
                ambito.launch {
                    app.repo.guardarContacto(usuario, alias)
                        .onSuccess { agregando = false; contactos = it }
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
 * Buscar por numero de telefono, como la agenda de WhatsApp.
 *
 * La explicacion de arriba no es relleno: la gente espera buscar por nombre, y
 * aqui hace falta el numero **exacto**. Sin decirlo, parece que la busqueda
 * esta rota.
 *
 * Se normaliza mientras escribe y se muestra el resultado, porque un numero sin
 * prefijo es ambiguo y "no encontrado" por un prefijo mal supuesto es un fallo
 * imposible de diagnosticar desde aqui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaDescubrir(onCerrar: () -> Unit, onAgregado: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var texto by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<Descubierto>>(emptyList()) }
    var buscando by remember { mutableStateOf(false) }
    var buscado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Se normaliza aqui y tambien en el servidor. No es duplicar por gusto: el
    // cliente necesita el numero canonico para MOSTRARLO antes de buscar, y el
    // servidor no puede confiar en que el cliente lo haya hecho bien.
    val numeros = remember(texto) {
        texto.split(',', ';', '\n')
            .mapNotNull { Telefonos.normalizar(it) }
            .distinct()
    }

    LaunchedEffect(numeros) {
        if (numeros.isEmpty()) {
            resultados = emptyList()
            buscado = false
            return@LaunchedEffect
        }
        buscando = true
        delay(400)
        app.repo.descubrir(numeros)
            .onSuccess { resultados = it; error = null }
            .onFailure { error = it.message }
        buscando = false
        buscado = true
    }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Buscar por telefono", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(6.dp))
            Text(
                "Hace falta el numero exacto: no se puede buscar por nombre. " +
                    "Es lo que evita que alguien liste a todo el mundo probando.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                placeholder = { Text("+51 987 654 321", color = TextoTerciario) },
                minLines = 2,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                ),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian,
                    unfocusedBorderColor = Slate,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    texto.isBlank() ->
                        "Puedes pegar varios, separados por coma. Sin prefijo se asume " +
                            "+${Telefonos.PAIS_POR_DEFECTO}."
                    numeros.isEmpty() -> "Todavia no hay un numero completo."
                    numeros.size == 1 -> "Se buscara ${numeros.first()}"
                    else -> "Se buscaran ${numeros.size} numeros"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (texto.isNotBlank() && numeros.isEmpty()) Ambar else TextoTerciario,
            )

            Spacer(Modifier.height(14.dp))

            when {
                buscando -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Cian, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Buscando...", color = TextoSecundario)
                }

                error != null -> Text(error!!, color = Coral, style = MaterialTheme.typography.bodySmall)

                buscado && resultados.isEmpty() -> Text(
                    // Los tres motivos, porque los tres son posibles y el
                    // usuario no puede distinguirlos desde aqui.
                    "Nadie con ese numero. Puede que no tenga cuenta, que no haya " +
                        "verificado su telefono, o que no quiera que lo encuentren asi.",
                    color = TextoTerciario,
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> resultados.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            nombre = d.nombreMostrado.ifBlank { d.username },
                            url = ApiCliente.urlImagen(d.username, "avatar", d.avatarVersion),
                            tamano = 42.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.nombreMostrado.ifBlank { "@${d.username}" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextoPrimario,
                            )
                            Text(
                                "@${d.username}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextoTerciario,
                            )
                        }
                        if (d.yaEsContacto) {
                            Text("ya lo tienes", style = MaterialTheme.typography.labelSmall, color = Cian)
                        } else {
                            TextButton(onClick = {
                                ambito.launch {
                                    app.repo.guardarContacto(d.username)
                                        .onSuccess {
                                            resultados = resultados.map {
                                                if (it.username == d.username) it.copy(yaEsContacto = true) else it
                                            }
                                            onAgregado()
                                        }
                                        .onFailure { error = it.message }
                                }
                            }) { Text("Agregar", color = Cian) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogoAgregarPorUsuario(onCerrar: () -> Unit, onAgregar: (String, String?) -> Unit) {
    var usuario by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Agregar contacto", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it },
                    label = { Text("Usuario") },
                    prefix = { Text("@", color = TextoTerciario) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Como lo llamas (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "El alias es solo para ti. La otra persona no lo ve.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = usuario.isNotBlank(),
                onClick = {
                    onAgregar(
                        usuario.trim().removePrefix("@").lowercase(),
                        alias.trim().ifBlank { null },
                    )
                },
            ) { Text("Agregar", color = Cian) }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
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
