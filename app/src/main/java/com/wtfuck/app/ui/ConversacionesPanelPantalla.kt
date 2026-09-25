package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ConversacionPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * H.3 · Grupos y canales de la plataforma.
 *
 * ## Qué significa "cerrar", y qué no
 *
 * Cerrar = **nadie escribe más**. No borra, no oculta y no expulsa a nadie:
 * los miembros siguen siendo miembros y el historial que ya tienen en sus
 * teléfonos sigue siendo suyo. Lo único que cambia es que la conversación deja
 * de producir contenido nuevo.
 *
 * Esa distinción se dice en la pantalla en lugar de esconderse. El servidor
 * **no puede** borrar lo ya entregado —está cifrado en aparatos ajenos— y un
 * botón de "eliminar el grupo" que en realidad solo esconde una fila sería
 * mentir en la pantalla de moderación, que es el peor sitio para mentir.
 *
 * ## Por qué hay que buscar
 *
 * Sin filtro esto sería el listado completo de la plataforma, que no cabe en
 * una pantalla y no ayuda a decidir nada. Es la misma regla que en la búsqueda
 * de personas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversacionesPanelPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var filtro by remember { mutableStateOf("") }
    var soloCerradas by rememberSaveable { mutableStateOf(false) }
    var lista by remember { mutableStateOf<List<ConversacionPanel>?>(null) }
    var cerrando by remember { mutableStateOf<ConversacionPanel?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        lista = app.repo.conversacionesPanel(filtro.trim().ifBlank { null }, soloCerradas)
    }

    LaunchedEffect(filtro, soloCerradas) {
        if (lista != null) delay(350)
        recargar()
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
                title = { Text("Grupos y canales", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = filtro,
                onValueChange = { filtro = it },
                placeholder = { Text("Buscar por nombre", color = TextoTerciario) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoSecundario) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian,
                    unfocusedBorderColor = Slate,
                    focusedContainerColor = BgSurface,
                    unfocusedContainerColor = BgSurface,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            )

            Row(
                Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !soloCerradas,
                    onClick = { soloCerradas = false },
                    label = { Text("Todas", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cian.copy(alpha = 0.18f),
                        selectedLabelColor = Cian,
                        labelColor = TextoTerciario,
                    ),
                )
                FilterChip(
                    selected = soloCerradas,
                    onClick = { soloCerradas = true },
                    label = { Text("Cerradas", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Coral.copy(alpha = 0.18f),
                        selectedLabelColor = Coral,
                        labelColor = TextoTerciario,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            val l = lista
            when {
                l == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Cian)
                }

                l.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (filtro.isBlank() && !soloCerradas) {
                            "Escribe para buscar un grupo o un canal."
                        } else if (soloCerradas) {
                            "Ninguna conversación cerrada."
                        } else {
                            "Nada coincide con \"${filtro.trim()}\"."
                        },
                        color = TextoSecundario,
                        modifier = Modifier.padding(32.dp),
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    items(l, key = { it.id }) { c ->
                        FilaConversacion(
                            c,
                            onCerrar = { cerrando = c },
                            onReabrir = {
                                ambito.launch {
                                    app.repo.reabrirConversacion(c.id)
                                        .onSuccess { aviso = "Reabierta."; recargar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    cerrando?.let { c ->
        DialogoCerrar(
            c = c,
            onCerrar = { cerrando = null },
            onConfirmar = { motivo ->
                ambito.launch {
                    app.repo.cerrarConversacion(c.id, motivo)
                        .onSuccess { cerrando = null; aviso = "Cerrada."; recargar() }
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
private fun FilaConversacion(
    c: ConversacionPanel,
    onCerrar: () -> Unit,
    onReabrir: () -> Unit,
) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (c.tipo == "canal") Icons.Filled.Campaign else Icons.Filled.Group,
                    null,
                    tint = if (c.cerrada) Coral else Cian,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    c.nombre,
                    color = if (c.cerrada) Coral else TextoPrimario,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Las denuncias son el dato que decide: un grupo grande y
                // tranquilo no es un problema, uno chico con ocho denuncias si.
                if (c.denuncias > 0) {
                    Text(
                        "${c.denuncias} denuncias",
                        color = Ambar,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(if (c.tipo == "canal") "canal" else "grupo")
                    if (c.creador.isNotBlank()) append(" · de @${c.creador}")
                    append(" · ${c.miembros} miembros")
                    append(" · ${c.mensajes} mensajes")
                },
                color = TextoTerciario,
                style = MaterialTheme.typography.labelSmall,
            )

            if (c.cerrada) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cerrada" + (c.cerradaPor?.let { " por @$it" } ?: "") +
                        (c.cierreMotivo?.let { ": $it" } ?: ""),
                    color = Coral,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            if (c.cerrada) {
                OutlinedButton(
                    onClick = onReabrir,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cian),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reabrir") }
            } else {
                OutlinedButton(
                    onClick = onCerrar,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cerrar") }
            }
        }
    }
}

@Composable
private fun DialogoCerrar(
    c: ConversacionPanel,
    onCerrar: () -> Unit,
    onConfirmar: (String) -> Unit,
) {
    var motivo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Cerrar ${c.nombre}", color = TextoPrimario) },
        text = {
            Column {
                Text(
                    // Las dos mitades de la verdad, juntas. Prometer que se
                    // borra lo que ya se entrego seria imposible de cumplir.
                    "Nadie va a poder escribir más, ni sus administradores. Sus " +
                        "${c.miembros} miembros van a poder seguir leyendo lo que ya esta " +
                        "en sus teléfonos: eso no se puede borrar desde aquí, porque el " +
                        "servidor no lo tiene.",
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
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sus miembros van a ver este motivo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmar(motivo.trim()) },
                enabled = motivo.trim().length >= 3,
            ) {
                Text("Cerrar", color = if (motivo.trim().length >= 3) Coral else Slate)
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}
