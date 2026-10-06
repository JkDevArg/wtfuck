package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.datos.MomentoProgramado
import com.wtfuck.app.ui.theme.*

/**
 * Los mensajes destacados: de un chat, o de todos si [conversacionId] es
 * vacio. Los de chats protegidos no salen en la lista de todos: se ve sin la
 * huella. Tocar uno lleva al mensaje.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaDestacados(
    conversacionId: String = "",
    onIr: (conversacionId: String, MensajeEnt) -> Unit,
    onCerrar: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val lista by app.repo.destacados(conversacionId).collectAsStateWithLifecycle(emptyList())
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())
    val ahora = MomentoProgramado.ahora()
    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Ambar, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Destacados", color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            }
            if (lista.isEmpty()) {
                Text(
                    "Mantén pulsado un mensaje y elige \"Destacar\" para encontrarlo aquí.",
                    color = TextoTerciario, fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn {
                items(lista, key = { it.id }) { m ->
                    val chat = chats.firstOrNull { it.id == m.conversacionId }
                    Column(
                        Modifier.fillMaxWidth().clickable { onIr(m.conversacionId, m) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Row {
                            Text(
                                buildString {
                                    if (conversacionId.isBlank()) append(chat?.titulo ?: "Chat").append(" · ")
                                    append(if (m.esMio) "Tú" else "@${m.autor}")
                                },
                                color = Cian, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(MomentoProgramado.etiqueta(m.creadoEn, ahora), color = TextoTerciario, fontSize = 12.sp)
                        }
                        Text(
                            if (m.adjuntoClase.isNotBlank()) Media.resumen(m.adjuntoClase, m.texto, m.adjuntoNombre)
                            else m.texto,
                            color = TextoPrimario, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                }
            }
        }
    }
}
