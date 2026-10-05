package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiError
import com.wtfuck.app.datos.MomentoProgramado
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.EstadoEnMensaje
import com.wtfuck.protocol.InfoMensaje

/**
 * "Info del mensaje" en un grupo: quien lo leyo, a quien le llego y a quien
 * todavia no. Ver `Mensajes.info` en el servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaInfoMensaje(mensajeId: String, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var info by remember { mutableStateOf<InfoMensaje?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mensajeId) {
        runCatching { app.repo.infoMensaje(mensajeId) }
            .onSuccess { info = it }
            .onFailure { error = (it as? ApiError)?.message ?: "No se pudo cargar. Revisa la conexión." }
    }
    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                "Info del mensaje",
                color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            val i = info
            when {
                error != null -> Text(error!!, color = Coral, modifier = Modifier.padding(20.dp))
                i == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Cian)
                }
                else -> {
                    val ahora = MomentoProgramado.ahora()
                    val leyeron = i.miembros.filter { it.leidoEn != null }
                    val llego = i.miembros.filter { it.leidoEn == null && it.entregadoEn != null }
                    val faltan = i.miembros.filter { it.entregadoEn == null }
                    LazyColumn {
                        if (!i.lecturasVisibles) {
                            item {
                                Text(
                                    "Apagaste las confirmaciones de lectura: tampoco ves las de los demás.",
                                    color = TextoTerciario, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                        seccion("Leído", Icons.Filled.DoneAll, Cian, leyeron) { it.leidoEn }
                        seccion("Entregado", Icons.Filled.DoneAll, TextoSecundario, llego) { it.entregadoEn }
                        seccion("Todavía no le llegó", Icons.Filled.Schedule, TextoTerciario, faltan) { null }
                        item {
                            Text(
                                "Quien no comparte sus confirmaciones de lectura aparece como entregado.",
                                color = TextoTerciario, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.seccion(
    titulo: String,
    icono: ImageVector,
    color: Color,
    gente: List<EstadoEnMensaje>,
    cuando: (EstadoEnMensaje) -> Long?,
) {
    if (gente.isEmpty()) return
    item {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icono, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("$titulo · ${gente.size}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    items(gente, key = { titulo + it.username }) { p ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(p.username, null, 34.dp)
            Spacer(Modifier.width(12.dp))
            Text("@${p.username}", color = TextoPrimario, modifier = Modifier.weight(1f))
            cuando(p)?.let {
                Text(MomentoProgramado.etiqueta(it, MomentoProgramado.ahora()), color = TextoTerciario, fontSize = 12.sp)
            }
        }
    }
}
