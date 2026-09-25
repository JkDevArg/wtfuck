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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacidadPantalla(onAtras: () -> Unit, onExcepciones: () -> Unit) {
    // El de la app: `mapaDeTerceros` es estado de Compose y dos instancias
    // leen el mismo disco con estados distintos.
    val ajustes = (androidx.compose.ui.platform.LocalContext.current
        .applicationContext as com.wtfuck.app.WtfuckApp).ajustes
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    // `null` = todavia no se pudieron leer. Ver la nota de `Repositorio`.
    val priv by app.repo.privacidad.collectAsStateWithLifecycle()

    var abierto by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        app.repo.cargarMiPerfil()
        app.repo.cargarPrivacidad()
        cargando = false
    }

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
        // **Sin datos no se dibuja ningun ajuste.**
        //
        // Antes se dibujaban los valores por DEFECTO, que son los mas
        // permisivos, y quedaban en pantalla como si fueran la configuracion
        // de la persona: con `nadie` guardado en cinco ajustes, la pantalla
        // decia "Todos" en los cinco. En la unica pantalla cuyo trabajo es
        // decir quien te ve, mostrar lo contrario de la verdad es peor que no
        // mostrar nada.
        //
        // Y ademas cierra la otra mitad: guardar manda los quince campos, asi
        // que tocar un ajuste partiendo de los defectos escribiria los otros
        // catorce. Sin controles no hay nada que tocar.
        val actual = priv
        if (actual == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                if (cargando) {
                    CircularProgressIndicator(color = Cian)
                } else {
                    EstadoDeError(
                        titulo = "No se pudieron cargar tus ajustes",
                        detalle = "Revisa tu conexión y vuelve a intentarlo. Tus ajustes " +
                            "no cambiaron: siguen guardados en el servidor.",
                        onReintentar = {
                            ambito.launch {
                                cargando = true
                                app.repo.cargarPrivacidad()
                                cargando = false
                            }
                        },
                    )
                }
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            Ajuste(
                icono = Icons.Filled.Photo,
                titulo = "Quien ve mi foto",
                valor = actual.foto,
            ) { abierto = "foto" }

            Ajuste(
                icono = Icons.Filled.Info,
                titulo = "Quien ve mi estado",
                valor = actual.estado,
            ) { abierto = "estado" }

            // Va pegada al estado y no al final: son los dos textos del
            // perfil y se deciden mirandolos juntos.
            Ajuste(
                icono = Icons.AutoMirrored.Filled.Notes,
                titulo = "Quien ve mi biografia",
                valor = actual.biografia,
            ) { abierto = "biografia" }

            Ajuste(
                icono = Icons.AutoMirrored.Filled.Chat,
                titulo = "Quien me puede escribir",
                valor = actual.escribe,
            ) { abierto = "escribe" }

            Ajuste(
                icono = Icons.Filled.Group,
                titulo = "Quien me puede agregar a grupos",
                valor = actual.grupos,
            ) { abierto = "grupos" }

            // Justo debajo de la de grupos porque es su continuacion: el
            // ajuste de grupos cubre que me agreguen a un grupo; este cubre lo
            // que ese no puede, que es que agreguen a una comunidad el grupo
            // en el que YA estaba. Separarlos en la pantalla haria pensar que
            // uno implica al otro.
            Ajuste(
                icono = Icons.Filled.Campaign,
                titulo = "Quien me puede sumar a una comunidad",
                valor = actual.comunidades,
            ) { abierto = "comunidades" }

            Ajuste(
                icono = Icons.Filled.Phone,
                titulo = "Quien me puede llamar",
                valor = actual.llamadas,
            ) { abierto = "llamadas" }

            // Debajo de las llamadas, porque se aplica ADEMAS de ese ajuste:
            // si el audio esta cerrado, el video tambien. Ponerla lejos haria
            // creer que son dos puertas independientes.
            Ajuste(
                icono = Icons.Filled.Videocam,
                titulo = "Quien me puede hacer videollamadas",
                valor = actual.videollamadas,
            ) { abierto = "videollamadas" }

            Ajuste(
                icono = Icons.Filled.Badge,
                titulo = "Quien ve mi nombre",
                valor = actual.nombre,
            ) { abierto = "nombre" }

            Ajuste(
                icono = Icons.Filled.Schedule,
                titulo = "Quien ve mi última conexión",
                valor = actual.ultimaVez,
            ) { abierto = "ultimaVez" }

            Ajuste(
                icono = Icons.Filled.Search,
                titulo = "Quien me encuentra por mi usuario",
                valor = actual.busqueda,
            ) { abierto = "busqueda" }

            // Este es booleano y no de tres niveles, asi que va con
            // interruptor: meterlo en el mismo dialogo de "todos / conocidos /
            // nadie" obligaria a inventar niveles que no significan nada.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = actual.lectura,
                        role = Role.Switch,
                        onValueChange = { guardar(actual.copy(lectura = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DoneAll,
                    null,
                    tint = if (actual.lectura) Cian else Slate,
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
                    checked = actual.lectura,
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
                        value = actual.escribiendo,
                        role = Role.Switch,
                        onValueChange = { guardar(actual.copy(escribiendo = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Edit,
                    null,
                    tint = if (actual.escribiendo) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Avisar cuando escribo", color = TextoPrimario)
                    Text(
                        // La senal no se guarda EN NINGUN SITIO, y decirlo aqui
                        // importa: es la diferencia entre un ajuste de cortesia
                        // y un dato que alguien podria pedir despues.
                        "También reciproco. Esta senal no se guarda en ningún sitio: vale " +
                            "tres segundos y se olvida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = actual.escribiendo,
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
            // Aparte del de escribir, y no dentro: son dos avisos distintos y
            // hay gente a la que no le importa uno y si el otro.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = actual.grabando,
                        role = Role.Switch,
                        onValueChange = { guardar(actual.copy(grabando = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    null,
                    tint = if (actual.grabando) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Avisar cuando grabo un audio", color = TextoPrimario)
                    Text(
                        "Va aparte de \"cuando escribo\": teclear dice que hay algo en camino, grabar dice además que el microfono esta abierto ahora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = actual.grabando,
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

            // Solo hace algo con «quien me escribe» en «conocidos». Se deja
            // visible igual: esconderlo obligaria a descubrir que existe
            // justo cuando ya cerraste la puerta.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = actual.solicitudes,
                        role = Role.Switch,
                        onValueChange = { guardar(actual.copy(solicitudes = it)) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.MarkEmailUnread,
                    null,
                    tint = if (actual.solicitudes) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aceptar solicitudes de mensaje", color = TextoPrimario)
                    Text(
                        "Quien no puede escribirte directamente puede mandar una solicitud. Vive aparte y la aceptas o la rechazas vos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = actual.solicitudes,
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
                    Icons.AutoMirrored.Filled.PlaylistAddCheck,
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

            Spacer(Modifier.height(18.dp))

            // ---------------------------------------------------------------
            // AN - lo unico de esta pantalla que NO lo aplica el servidor
            // ---------------------------------------------------------------
            //
            // Va al final y con su propio titulo porque todo lo de arriba es
            // de la CUENTA —viaja, lo hace cumplir el servidor, vale en todos
            // los aparatos— y esto es de ESTE telefono. Mezclarlo arriba haria
            // creer que tambien se sincroniza.
            Text(
                "Este aparato",
                style = MaterialTheme.typography.labelLarge,
                color = TextoSecundario,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = ajustes.mapaDeTerceros,
                        onValueChange = { ajustes.fijarMapaDeTerceros(it) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Map,
                    null,
                    tint = if (ajustes.mapaDeTerceros) Cian else Slate,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Cargar mapas de OpenStreetMap", color = TextoPrimario)
                    Text(
                        "Para dibujar un mapa hay que pedirle las imagenes de esa zona " +
                            "a OpenStreetMap, y eso le muestra tu direccion IP y que " +
                            "lugar estas mirando. Apagado se sigue viendo el rastro: " +
                            "por donde va y cuanto, sin mapa y sin pedirle nada a nadie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                Switch(
                    checked = ajustes.mapaDeTerceros,
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
                        "wtfuck no lee tu agenda del teléfono. Cuenta como conocida " +
                            "cualquier persona con la que ya tengas una conversación directa abierta.",
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

    // El dialogo tambien depende de que haya datos: sin ellos no se abre.
    // Es inalcanzable —no hay filas que tocar— pero el compilador lo exige y
    // esta bien que lo exija: es el mismo agujero por otra puerta.
    val cargados = priv
    if (abierto != null && cargados != null) {
        val campo = abierto!!
        val opciones = if (campo == "escribe") Privacidad.NIVELES_ESCRIBE else Privacidad.NIVELES
        val elegido = when (campo) {
            "foto" -> cargados.foto
            "estado" -> cargados.estado
            "escribe" -> cargados.escribe
            "llamadas" -> cargados.llamadas
            "biografia" -> cargados.biografia
            "videollamadas" -> cargados.videollamadas
            "nombre" -> cargados.nombre
            "ultimaVez" -> cargados.ultimaVez
            "busqueda" -> cargados.busqueda
            "comunidades" -> cargados.comunidades
            else -> cargados.grupos
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
                        "biografia" -> "Quien ve mi biografia"
                        "videollamadas" -> "Quien me puede hacer videollamadas"
                        "nombre" -> "Quien ve mi nombre"
                        "ultimaVez" -> "Quien ve mi última conexión"
                        "busqueda" -> "Quien me encuentra por mi usuario"
                        "comunidades" -> "Quien me puede sumar a una comunidad"
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
                                            "comunidades" -> cargados.copy(comunidades = nivel)
                                            "foto" -> cargados.copy(foto = nivel)
                                            "estado" -> cargados.copy(estado = nivel)
                                            "escribe" -> cargados.copy(escribe = nivel)
                                            "llamadas" -> cargados.copy(llamadas = nivel)
                                            "biografia" -> cargados.copy(biografia = nivel)
                                            "videollamadas" -> cargados.copy(videollamadas = nivel)
                                            "nombre" -> cargados.copy(nombre = nivel)
                                            "ultimaVez" -> cargados.copy(ultimaVez = nivel)
                                            "busqueda" -> cargados.copy(busqueda = nivel)
                                            else -> cargados.copy(grupos = nivel)
                                        }
                                    )
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Privacidad.etiqueta(nivel),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (nivel == elegido) Cian else TextoPrimario,
                                modifier = Modifier.weight(1f),
                            )
                            if (nivel == elegido) {
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
                            "Es reciproco: con \"nadie\" tampoco ves la última conexión de " +
                                "los demas."
                        "nombre" ->
                            "Tu usuario NO se puede ocultar: es la direccion con la que " +
                                "existis aquí. Quien no vea tu nombre verá @tu_usuario."
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
