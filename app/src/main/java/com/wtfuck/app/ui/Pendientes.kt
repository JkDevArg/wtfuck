package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.Media
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Lo que espera salir: que mensajes son, en que chat, por que no salen, y la
 * opcion de descartarlos.
 *
 * Antes la barra decia "Enviando 2 pendientes..." para siempre -dos mensajes
 * a una cuenta que nunca publico sus claves- y no habia forma de saber cuales
 * eran ni de sacarlos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaPendientes(onCerrar: () -> Unit, onAbrirChat: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    // Null hasta que la base contesta: con una lista vacia de entrada, el
    // "cerrar si no queda nada" de abajo cerraba la hoja en el mismo instante
    // en que se abria.
    val cargados by app.repo.pendientes.collectAsStateWithLifecycle<List<com.wtfuck.app.datos.MensajeEnt>?>(null)
    val pendientes = cargados.orEmpty()
    val atascos by app.repo.atascos.collectAsStateWithLifecycle()
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(cargados?.isEmpty()) { if (cargados?.isEmpty() == true) onCerrar() }

    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    "Por enviar · ${pendientes.size}",
                    color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ambito.launch { app.repo.despachar() } }) { Text("Reintentar ahora", color = Cian) }
            }
            LazyColumn {
                items(pendientes, key = { it.id }) { m ->
                    val chat = chats.firstOrNull { it.id == m.conversacionId }
                    val motivo = atascos[m.conversacionId]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(
                            chat?.titulo ?: "Chat",
                            color = Cian, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onAbrirChat(m.conversacionId) },
                        )
                        Text(
                            when {
                                m.oculto -> "Voto en una encuesta"
                                m.adjuntoClase.isNotBlank() -> Media.resumen(m.adjuntoClase, m.texto, m.adjuntoNombre)
                                else -> m.texto
                            },
                            color = TextoPrimario, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            motivo ?: "Esperando su turno en la cola.",
                            color = if (motivo != null) Ambar else TextoTerciario,
                            fontSize = 12.sp,
                        )
                        Row {
                            TextButton(onClick = { onAbrirChat(m.conversacionId) }) { Text("Ir al chat", color = Cian) }
                            TextButton(onClick = { ambito.launch { app.repo.descartarPendiente(m.id) } }) {
                                Text("Descartar", color = Coral)
                            }
                        }
                    }
                    HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                }
            }
        }
    }
}
