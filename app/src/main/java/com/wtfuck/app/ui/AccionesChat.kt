package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wtfuck.app.datos.ChatFila
import com.wtfuck.app.ui.theme.*

/**
 * Menu que sale al mantener pulsado un chat.
 *
 * Silenciar, archivar y fijar son decisiones personales: no afectan a los demas
 * participantes. Bloquear y eliminar si tienen consecuencias, y por eso piden
 * confirmacion aparte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaAccionesChat(
    chat: ChatFila,
    onCerrar: () -> Unit,
    onSilenciar: (Int) -> Unit,
    onNoLeida: () -> Unit,
    onArchivar: (Boolean) -> Unit,
    onFijar: (Boolean) -> Unit,
    onBloquear: () -> Unit,
    onEliminar: () -> Unit,
    onDenunciar: () -> Unit,
) {
    var pidiendoSilencio by remember { mutableStateOf(false) }
    var confirmando by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    nombre = chat.titulo,
                    url = null,
                    tamano = 40.dp,
                    esGrupo = chat.tipo == "grupo",
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (chat.tipo == "grupo") chat.titulo else "@${chat.titulo}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextoPrimario,
                    )
                    Text(
                        if (chat.tipo == "grupo") "Grupo" else "Conversacion directa",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

            if (chat.silenciado) {
                Accion("Quitar silencio", Icons.Filled.NotificationsActive) { onSilenciar(0) }
            } else {
                Accion("Silenciar", Icons.Filled.NotificationsOff) { pidiendoSilencio = true }
            }

            // M.3 · Solo si NO hay nada sin leer. Con el globo ya puesto,
            // "marcar como no leida" no cambia nada de lo que se ve, y ofrecer
            // una accion que no hace nada hace dudar de si funciono.
            if (!chat.sinLeer) {
                Accion("Marcar como no leida", Icons.Filled.MarkChatUnread) { onNoLeida() }
            }

            Accion(
                if (chat.fijado) "Dejar de fijar" else "Fijar arriba",
                Icons.Filled.PushPin,
            ) { onFijar(!chat.fijado) }

            Accion(
                if (chat.archivado) "Desarchivar" else "Archivar",
                if (chat.archivado) Icons.Filled.Unarchive else Icons.Filled.Archive,
            ) { onArchivar(!chat.archivado) }

            if (chat.tipo == "directa") {
                HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))
                Accion("Bloquear a @${chat.nombre}", Icons.Filled.Block, Coral) { confirmando = "bloquear" }
            }

            // Denunciar va ANTES de eliminar, y separado de bloquear: bloquear
            // resuelve tu problema, denunciar avisa de uno que puede ser de
            // mas gente. Son dos cosas y conviene no obligar a elegir una.
            Accion(
                if (chat.tipo == "grupo") "Denunciar este grupo" else "Denunciar a @${chat.nombre}",
                Icons.Filled.Flag,
                Coral,
                onDenunciar,
            )

            Accion("Eliminar chat", Icons.Filled.DeleteOutline, Coral) { confirmando = "eliminar" }
        }
    }

    if (pidiendoSilencio) {
        AlertDialog(
            onDismissRequest = { pidiendoSilencio = false },
            containerColor = BgElev,
            title = { Text("Silenciar por", color = TextoPrimario) },
            text = {
                Column {
                    listOf(
                        "8 horas" to 8 * 60,
                        "1 semana" to 7 * 24 * 60,
                        "Siempre" to -1,
                    ).forEach { (etiqueta, minutos) ->
                        Text(
                            etiqueta,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextoPrimario,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pidiendoSilencio = false; onSilenciar(minutos) }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pidiendoSilencio = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    confirmando?.let { que ->
        val esBloqueo = que == "bloquear"
        AlertDialog(
            onDismissRequest = { confirmando = null },
            containerColor = BgElev,
            title = {
                Text(if (esBloqueo) "Bloquear a @${chat.nombre}" else "Eliminar chat", color = TextoPrimario)
            },
            text = {
                Text(
                    if (esBloqueo)
                        "No podran escribirte ni llamarte, y no veran tu foto ni tu estado. " +
                            "Puedes revertirlo cuando quieras."
                    else
                        "Se borra el historial de este dispositivo y sales de la conversacion. " +
                            "Esto NO borra el chat en el telefono de la otra persona.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmando = null
                    if (esBloqueo) onBloquear() else onEliminar()
                }) {
                    Text(if (esBloqueo) "Bloquear" else "Eliminar", color = Coral, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmando = null }) { Text("Cancelar", color = TextoSecundario) }
            },
        )
    }
}

@Composable
private fun Accion(
    texto: String,
    icono: ImageVector,
    color: Color = TextoPrimario,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(texto, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
