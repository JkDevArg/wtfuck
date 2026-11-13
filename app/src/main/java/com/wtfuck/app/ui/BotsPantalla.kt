package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.BotCreado
import com.wtfuck.protocol.BotResumen
import com.wtfuck.protocol.DelegacionActiva
import com.wtfuck.protocol.DelegacionCreada
import com.wtfuck.protocol.MAX_BOTS_POR_DUENO
import kotlinx.coroutines.launch

/**
 * Mis bots (estilo BotFather). Cada bot es una cuenta de cifrado headless que
 * corre donde el dueno quiera. Aqui se crean, se ve su codigo de alta y se
 * borran. Ver docs/14-BOTS.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var bots by remember { mutableStateOf<List<BotResumen>>(emptyList()) }
    var creando by remember { mutableStateOf(false) }
    var creado by remember { mutableStateOf<BotCreado?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var delegaciones by remember { mutableStateOf<List<DelegacionActiva>>(emptyList()) }
    var tokenNuevo by remember { mutableStateOf<DelegacionCreada?>(null) }

    suspend fun recargar() {
        bots = app.repo.misBots()
        delegaciones = app.repo.delegacionesBot()
        cargando = false
    }
    LaunchedEffect(Unit) { recargar() }

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
                title = { Text("Mis bots", color = TextoPrimario) },
            )
        },
        floatingActionButton = {
            if (bots.size < MAX_BOTS_POR_DUENO) {
                ExtendedFloatingActionButton(
                    onClick = { creando = true },
                    containerColor = Cian, contentColor = TextoSobreAcento,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Crear bot") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Un bot es una cuenta aparte que vos controlás con un programa, en tu propio " +
                    "servidor (Lambda, EC2, una VM…). Lo que hablás con un bot va cifrado igual " +
                    "que todo lo demás. Podés tener hasta $MAX_BOTS_POR_DUENO.",
                style = MaterialTheme.typography.bodyMedium, color = TextoSecundario,
            )
            Spacer(Modifier.height(16.dp))

            if (cargando) {
                CircularProgressIndicator(color = Cian)
            } else if (bots.isEmpty()) {
                Text("Todavía no creaste ningún bot.", color = TextoTerciario)
            } else {
                bots.forEach { b ->
                    FilaBot(b, onBorrar = {
                        ambito.launch {
                            app.repo.borrarBot(b.username)
                                .onSuccess { recargar() }
                                .onFailure { aviso = it.message }
                        }
                    })
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (!cargando) {
                Spacer(Modifier.height(24.dp))
                SeccionDelegacion(
                    delegaciones = delegaciones,
                    onAutorizar = {
                        ambito.launch {
                            app.repo.crearDelegacionBot()
                                .onSuccess { tokenNuevo = it; recargar() }
                                .onFailure { aviso = it.message ?: "No se pudo autorizar." }
                        }
                    },
                    onRevocar = { id ->
                        ambito.launch {
                            app.repo.revocarDelegacionBot(id)
                                .onSuccess { recargar() }
                                .onFailure { aviso = it.message }
                        }
                    },
                )
            }
        }
    }

    tokenNuevo?.let { t -> DialogoTokenDelegacion(t, onCerrar = { tokenNuevo = null }) }

    if (creando) {
        DialogoCrearBot(
            onCerrar = { creando = false },
            onCrear = { nombre, publico, desc ->
                ambito.launch {
                    app.repo.crearBot(nombre, publico, desc)
                        .onSuccess { creando = false; creado = it; recargar() }
                        .onFailure { aviso = it.message ?: "No se pudo crear." }
                }
            },
        )
    }

    creado?.let { c -> DialogoCodigoBot(c, onCerrar = { creado = null }) }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("Atención", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Cerrar", color = Cian) } },
        )
    }
}

@Composable
private fun FilaBot(b: BotResumen, onBorrar: () -> Unit) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SmartToy, null, tint = Cian, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${b.username}", color = TextoPrimario, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (b.registrado) "Activo" else "Pendiente de conectar") +
                        (if (b.publico) " · público" else " · privado"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (b.registrado) Cian else Ambar,
                )
                if (b.descripcion.isNotBlank()) {
                    Text(b.descripcion, style = MaterialTheme.typography.bodySmall, color = TextoSecundario, maxLines = 2)
                }
            }
            IconButton(onClick = onBorrar) {
                Icon(Icons.Filled.Delete, "Borrar", tint = Coral)
            }
        }
    }
}

@Composable
private fun DialogoCrearBot(onCerrar: () -> Unit, onCrear: (String, Boolean, String) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var publico by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf("") }
    val valido = Regex("^[a-z0-9_]{3,20}$").matches(nombre.trim().lowercase().removePrefix("@"))

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Crear un bot", color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre (@usuario del bot)") },
                    singleLine = true,
                    isError = nombre.isNotBlank() && !valido,
                    supportingText = { Text("3-20: letras, números y guion bajo.", style = MaterialTheme.typography.labelSmall, color = TextoTerciario) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cian, unfocusedBorderColor = Slate),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { if (it.length <= 200) desc = it },
                    label = { Text("Descripción (opcional)") },
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cian, unfocusedBorderColor = Slate),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = publico, onCheckedChange = { publico = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Público (otros pueden encontrarlo y escribirle)", color = TextoSecundario, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido,
                onClick = { onCrear(nombre.trim().lowercase().removePrefix("@"), publico, desc.trim()) },
            ) { Text("Crear", color = Cian) }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
}

@Composable
private fun SeccionDelegacion(
    delegaciones: List<DelegacionActiva>,
    onAutorizar: () -> Unit,
    onRevocar: (String) -> Unit,
) {
    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Que un bot te ayude a crear bots", color = TextoPrimario, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Generá un código y pegáselo al bot de recepción en el chat con /autorizar. " +
                    "Así puede crear y listar tus bots por vos (nunca borrarlos). Vale 24 horas y " +
                    "lo podés revocar acá cuando quieras.",
                style = MaterialTheme.typography.bodySmall, color = TextoSecundario,
            )
            Spacer(Modifier.height(12.dp))
            delegaciones.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Autorización activa", color = TextoPrimario, style = MaterialTheme.typography.bodySmall)
                        Text("Vence ${fmt.format(java.util.Date(d.venceEn))}", color = TextoTerciario, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onRevocar(d.id) }) { Text("Revocar", color = Coral) }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = onAutorizar,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = BgElev, contentColor = Cian),
            ) { Text("Generar código de autorización") }
        }
    }
}

@Composable
private fun DialogoTokenDelegacion(t: DelegacionCreada, onCerrar: () -> Unit) {
    val portapapeles = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Código de autorización", color = TextoPrimario) },
        text = {
            Column {
                Text(
                    "Pegáselo al bot de recepción en el chat con:  /autorizar <código>. " +
                        "Se muestra UNA sola vez. Vale 24 horas; podés revocarlo desde Mis bots.",
                    style = MaterialTheme.typography.bodySmall, color = TextoSecundario,
                )
                Spacer(Modifier.height(12.dp))
                Surface(color = BgSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        t.token,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Cian,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { portapapeles.setText(AnnotatedString("/autorizar ${t.token}")) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = Cian)
                    Spacer(Modifier.width(6.dp))
                    Text("Copiar  /autorizar <código>", color = Cian)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Listo", color = Cian) } },
    )
}

@Composable
private fun DialogoCodigoBot(c: BotCreado, onCerrar: () -> Unit) {
    val portapapeles = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("@${c.username} creado", color = TextoPrimario) },
        text = {
            Column {
                Text(
                    "Este es el código de alta del bot. Se muestra UNA sola vez: con él " +
                        "conectás y corrés tu bot desde tu servidor. Guardalo donde guardarías una clave.",
                    style = MaterialTheme.typography.bodySmall, color = TextoSecundario,
                )
                Spacer(Modifier.height(12.dp))
                Surface(color = BgSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        c.codigo,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Cian,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { portapapeles.setText(AnnotatedString(c.codigo)) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = Cian)
                    Spacer(Modifier.width(6.dp))
                    Text("Copiar el código", color = Cian)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Listo, lo guardé", color = Cian) } },
    )
}
