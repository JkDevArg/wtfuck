package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.Privacidad
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Badge
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlaylistAddCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacidadPantalla(onAtras: () -> Unit, onExcepciones: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val priv by app.repo.privacidad.collectAsStateWithLifecycle()

    var abierto by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { app.repo.cargarMiPerfil() }

    fun guardar(nueva: Privacidad) {
        ambito.launch {
            runCatching { app.repo.guardarPrivacidad(nueva) }
                .onFailure { error = it.message }
            abierto = null
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
                title = { Text("Privacidad", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            Ajuste(
                icono = Icons.Filled.Photo,
                titulo = "Quien ve mi foto",
                valor = priv.foto,
            ) { abierto = "foto" }

            Ajuste(
                icono = Icons.Filled.Info,
                titulo = "Quien ve mi estado",
                valor = priv.estado,
            ) { abierto = "estado" }

            Ajuste(
                icono = Icons.Filled.Chat,
                titulo = "Quien me puede escribir",
                valor = priv.escribe,
            ) { abierto = "escribe" }

            Ajuste(
                icono = Icons.Filled.Group,
                titulo = "Quien me puede agregar a grupos",
                valor = priv.grupos,
            ) { abierto = "grupos" }

            Ajuste(
                icono = Icons.Filled.Phone,
                titulo = "Quien me puede llamar",
                valor = priv.llamadas,
            ) { abierto = "llamadas" }

            Ajuste(
                icono = Icons.Filled.Badge,
                titulo = "Quien ve mi nombre",
                valor = priv.nombre,
            ) { abierto = "nombre" }

            Ajuste(
                icono = Icons.Filled.Schedule,
                titulo = "Quien ve mi ultima conexion",
                valor = priv.ultimaVez,
            ) { abierto = "ultimaVez" }

            Ajuste(
                icono = Icons.Filled.Search,
                titulo = "Quien me encuentra por mi usuario",
                valor = priv.busqueda,
            ) { abierto = "busqueda" }

            // Este es booleano y no de tres niveles, asi que va con
            // interruptor: meterlo en el mismo dialogo de "todos / conocidos /
            // nadie" obligaria a inventar niveles que no significan nada.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = priv.lectura,
                        role = Role.Switch,
                        onValueChange = { guardar(priv.copy(lectura = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DoneAll,
                    null,
                    tint = if (priv.lectura) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Confirmaciones de lectura", color = TextoPrimario)
                    Text(
                        // La reciprocidad se dice ANTES de tocarlo. Si se
                        // descubre despues, la persona siente que la app le
                        // quito algo a cambio de nada.
                        "Si lo apagas, no las mandas y tampoco las ves. En los grupos " +
                            "siempre se muestran.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = priv.lectura,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextoSobreAcento,
                        checkedTrackColor = Cian,
                        uncheckedThumbColor = TextoTerciario,
                        uncheckedTrackColor = BgElev,
                        uncheckedBorderColor = Slate,
                    ),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = priv.escribiendo,
                        role = Role.Switch,
                        onValueChange = { guardar(priv.copy(escribiendo = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Edit,
                    null,
                    tint = if (priv.escribiendo) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Avisar cuando escribo", color = TextoPrimario)
                    Text(
                        // La senal no se guarda EN NINGUN SITIO, y decirlo aqui
                        // importa: es la diferencia entre un ajuste de cortesia
                        // y un dato que alguien podria pedir despues.
                        "Tambien reciproco. Esta senal no se guarda en ningun sitio: vale " +
                            "tres segundos y se olvida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = priv.escribiendo,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextoSobreAcento,
                        checkedTrackColor = Cian,
                        uncheckedThumbColor = TextoTerciario,
                        uncheckedTrackColor = BgElev,
                        uncheckedBorderColor = Slate,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            // La entrada a las listas va DESPUES de los ajustes: primero se
            // elige el nivel y solo entonces las listas significan algo.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExcepciones)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlaylistAddCheck,
                    null,
                    tint = Cian,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Listas personalizadas", color = TextoPrimario)
                    Text(
                        "\"Todos menos...\" y \"Solo...\", para los ajustes que pongas en " +
                            "Personalizado",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    null,
                    tint = TextoTerciario,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // Explicar el modelo evita que "conocidos" se interprete como la
            // agenda del telefono, que es lo que la gente espera de WhatsApp.
            Surface(
                color = BgSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Que significa \"solo con quien ya hablo\"",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextoPrimario,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "wtfuck no lee tu agenda del telefono. Cuenta como conocida " +
                            "cualquier persona con la que ya tengas una conversacion directa abierta.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Estos ajustes los aplica el servidor, no solo la app: si alguien " +
                            "no puede ver tu foto, tampoco la obtiene pidiendo la direccion directamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    abierto?.let { campo ->
        val opciones = if (campo == "escribe") Privacidad.NIVELES_ESCRIBE else Privacidad.NIVELES
        val actual = when (campo) {
            "foto" -> priv.foto
            "estado" -> priv.estado
            "escribe" -> priv.escribe
            "llamadas" -> priv.llamadas
            "nombre" -> priv.nombre
            "ultimaVez" -> priv.ultimaVez
            "busqueda" -> priv.busqueda
            else -> priv.grupos
        }
        AlertDialog(
            onDismissRequest = { abierto = null },
            containerColor = BgElev,
            title = {
                Text(
                    when (campo) {
                        "foto" -> "Quien ve mi foto"
                        "estado" -> "Quien ve mi estado"
                        "escribe" -> "Quien me puede escribir"
                        "llamadas" -> "Quien me puede llamar"
                        "nombre" -> "Quien ve mi nombre"
                        "ultimaVez" -> "Quien ve mi ultima conexion"
                        "busqueda" -> "Quien me encuentra por mi usuario"
                        else -> "Quien me puede agregar a grupos"
                    },
                    color = TextoPrimario,
                )
            },
            text = {
                Column {
                    opciones.forEach { nivel ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    guardar(
                                        when (campo) {
                                            "foto" -> priv.copy(foto = nivel)
                                            "estado" -> priv.copy(estado = nivel)
                                            "escribe" -> priv.copy(escribe = nivel)
                                            "llamadas" -> priv.copy(llamadas = nivel)
                                            "nombre" -> priv.copy(nombre = nivel)
                                            "ultimaVez" -> priv.copy(ultimaVez = nivel)
                                            "busqueda" -> priv.copy(busqueda = nivel)
                                            else -> priv.copy(grupos = nivel)
                                        }
                                    )
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Privacidad.etiqueta(nivel),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (nivel == actual) Cian else TextoPrimario,
                                modifier = Modifier.weight(1f),
                            )
                            if (nivel == actual) {
                                Icon(Icons.Filled.Check, null, tint = Cian, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    // Cada ajuste tiene una consecuencia que no es obvia, y
                    // se dice en el momento de elegir y no en un manual.
                    val nota = when (campo) {
                        "escribe" ->
                            "No existe \"nadie\": una cuenta a la que nadie puede escribir no " +
                                "es una cuenta de mensajeria."
                        "ultimaVez" ->
                            "Es reciproco: con \"nadie\" tampoco ves la ultima conexion de " +
                                "los demas."
                        "nombre" ->
                            "Tu usuario NO se puede ocultar: es la direccion con la que " +
                                "existis aqui. Quien no vea tu nombre vera @tu_usuario."
                        "busqueda" ->
                            "Con \"nadie\" no dejas de ser alcanzable: quien ya habla con vos " +
                                "sigue escribiendote y los enlaces de invitacion siguen " +
                                "funcionando. Solo deja de encontrarte quien teclea tu nombre."
                        "llamadas" ->
                            "Una llamada suena, interrumpe y despierta. Por eso este empieza " +
                                "en \"solo con quien ya hablo\" y no en \"todos\"."
                        else -> null
                    }
                    nota?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextoTerciario,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { abierto = null }) { Text("Cerrar", color = TextoSecundario) }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            containerColor = BgElev,
            title = { Text("No se pudo guardar", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

@Composable
private fun Ajuste(icono: ImageVector, titulo: String, valor: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Cian, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge, color = TextoPrimario)
            Spacer(Modifier.height(2.dp))
            Text(
                Privacidad.etiqueta(valor),
                style = MaterialTheme.typography.bodyMedium,
                color = TextoSecundario,
            )
        }
    }
}
