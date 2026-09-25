package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.LlamadaEnHistorial

/**
 * K.7 · El historial de llamadas.
 *
 * ## Por que esto SI lo sabe el servidor
 *
 * Es la excepcion aparente al buzon tonto, y no lo es: el servidor necesita
 * saber quien llama a quien para autorizar la llamada y para frenar el abuso,
 * asi que ya lo sabe. Guardarlo para mostrarlo no le da ningun dato nuevo. Lo
 * que NO guarda es el SDP -ni una columna existe para eso-, porque ahi estan
 * las huellas DTLS de las que depende el cifrado del medio.
 *
 * O sea: el historial dice *que hubo una llamada*, nunca *que se dijo*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialLlamadasPantalla(onAtras: () -> Unit, onAbrirChat: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var llamadas by remember { mutableStateOf<List<LlamadaEnHistorial>?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val llamar = recordarInicioLlamada { aviso = it }
    /**
     * A que grupo se le va a devolver la llamada.
     *
     * Devolver una llamada de grupo no es redialar: el tope de la malla son
     * cuatro y hay que elegir a quien, igual que al llamar desde el chat. Con
     * el boton llamando al grupo entero, en un grupo de ocho el servidor
     * responde 409 y el boton no servia para nada.
     */
    var grupoADevolver by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { llamadas = app.repo.llamadas.historial() }

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
                title = { Text("Llamadas", color = TextoPrimario) },
            )
        },
    ) { pad ->
        val lista = llamadas
        when {
            lista == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }

            lista.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Todavía no hay llamadas",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Llama desde un chat con el icono del teléfono.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoTerciario,
                )
            }

            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                aviso?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Coral,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(lista, key = { it.id }) { l ->
                        FilaLlamada(
                            l,
                            onAbrir = { onAbrirChat(l.conversacionId) },
                            onDevolver = {
                                if (l.esGrupo) {
                                    grupoADevolver = l.conversacionId
                                } else {
                                    llamar(l.conversacionId, l.titulo, l.conVideo)
                                }
                            },
                        )
                        HorizontalDivider(
                            color = Slate.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 64.dp),
                        )
                    }
                }
            }
        }
    }

    // Devolver una llamada de grupo abre la MISMA hoja que el chat, en vez de
    // redialar: el tope de la malla son cuatro y hay que elegir a quien. Con
    // el boton llamando al grupo entero, en un grupo de ocho el servidor
    // respondia 409 y el boton no servia para nada.
    grupoADevolver?.let { conv ->
        HojaLlamarAlGrupo(
            conversacionId = conv,
            onCerrar = { grupoADevolver = null },
            onLlamar = { invitados, conVideo ->
                val titulo = llamadas.orEmpty()
                    .firstOrNull { it.conversacionId == conv }?.titulo.orEmpty()
                grupoADevolver = null
                llamar(conv, titulo.ifBlank { "Grupo" }, conVideo, invitados)
            },
        )
    }
}

@Composable
private fun FilaLlamada(l: LlamadaEnHistorial, onAbrir: () -> Unit, onDevolver: () -> Unit) {
    val esGrupo = l.esGrupo
    // Perdida en coral, el resto neutro. Es el unico caso que reclama una
    // accion, y pintar los tres estados de colores distintos convertiria la
    // lista en un semaforo sin significado.
    val color = if (l.perdida) Coral else TextoTerciario
    val icono = when {
        l.perdida -> Icons.AutoMirrored.Filled.CallMissed
        l.fueMia -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAbrir)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `esGrupo` y no adivinarlo: un grupo dibujado con el icono de una
        // persona es el mismo defecto que el modulo AE encontro en los
        // canales, y aqui estaba esperando un archivo mas alla.
        Avatar(nombre = l.titulo, url = null, tamano = 40.dp, esGrupo = esGrupo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                l.titulo.ifBlank { "Llamada" },
                style = MaterialTheme.typography.titleSmall,
                color = if (l.perdida) Coral else TextoPrimario,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icono, null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    textoDeLlamada(l),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextoTerciario,
                )
            }
            conQuienes(l)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextoTerciario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDevolver) {
            Icon(
                if (l.conVideo) Icons.Filled.Videocam else Icons.Filled.Phone,
                if (l.conVideo) "Devolver videollamada" else "Devolver llamada",
                tint = Cian,
            )
        }
    }
}

/** Fecha y, si se hablo, cuanto. Una llamada de 0 segundos no dice "0:00". */
private fun textoDeLlamada(l: LlamadaEnHistorial): String {
    val cuando = fechaLarga(l.iniciadaEn)
    return if (l.duracion > 0) "$cuando · ${duracionHabla(l.duracion)}" else cuando
}

/**
 * Con quien fue una llamada de grupo.
 *
 * Solo en los grupos: en una directa el titulo YA es la persona y repetirlo
 * debajo no dice nada. En un grupo es lo unico que cambia entre dos llamadas
 * al mismo sitio, y era justo lo que no se veia: dos filas identicas de
 * "Equipo seguridad" sin forma de saber si fueron las mismas personas.
 */
internal fun conQuienes(l: LlamadaEnHistorial): String? {
    if (!l.esGrupo || l.participantes.isEmpty()) return null
    // Dos nombres y el resto contado. Cuatro nombres no entran en una linea de
    // lista y cortarlos con puntos suspensivos no dice cuantos faltan.
    val nombres = l.participantes.sorted()
    return when {
        nombres.size <= 2 -> "con " + nombres.joinToString(", ")
        else -> "con ${nombres[0]}, ${nombres[1]} y ${nombres.size - 2} más"
    }
}
