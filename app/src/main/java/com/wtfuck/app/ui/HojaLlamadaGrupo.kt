package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.MAX_EN_LLAMADA
import com.wtfuck.protocol.MiembroDetalle

/**
 * Módulo AF · A quién llamar en un grupo.
 *
 * ## Por qué esta pantalla tiene que existir
 *
 * El botón de llamar **no aparecía en los grupos**, y el comentario que lo
 * ocultaba decía la verdad: la llamada en malla admite cuatro y no había forma
 * de elegir a quién. Pero el servidor era peor de lo que decía ese comentario
 * —llamaba a *todos* y rechazaba la llamada entera si eran más de cuatro—, así
 * que **un grupo de ocho no podía tener una llamada nunca**, ni entre tres de
 * sus miembros.
 *
 * El tope se estaba aplicando al grupo en vez de a la llamada. Esta hoja es lo
 * que faltaba para separar las dos cosas.
 *
 * ## El tope se dice, no se descubre
 *
 * El límite se explica arriba y las filas de más se **deshabilitan** en vez de
 * desaparecer: una lista que deja de reaccionar sin decir por qué se lee como
 * una pantalla rota. Y se cuenta `1 + elegidos`, porque quien llama también
 * ocupa un sitio —olvidarlo es cómo se llega a una llamada de cinco que el
 * servidor rechaza después de que la persona ya eligió.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaLlamarAlGrupo(
    conversacionId: String,
    onCerrar: () -> Unit,
    onLlamar: (invitados: List<String>, conVideo: Boolean) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var miembros by remember { mutableStateOf<List<MiembroDetalle>?>(null) }
    var elegidos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conversacionId) {
        runCatching { app.repo.miembros(conversacionId) }
            .onSuccess { lista ->
                // Yo no me invito a mí mismo: ya estoy en la llamada. Dejarme
                // en la lista gastaría uno de los cuatro sitios.
                val yo = app.sesion.username.orEmpty()
                miembros = lista.filter { it.usuario.username != yo }
            }
            .onFailure { error = it.message ?: "No se pudo leer quiénes están." }
    }

    val cupo = MAX_EN_LLAMADA - 1 // el sitio que ocupo yo
    val lleno = elegidos.size >= cupo

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Llamar al grupo",
                color = TextoPrimario, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Elige a quién hacer sonar. Entran hasta $MAX_EN_LLAMADA personas " +
                    "contándote.",
                color = TextoTerciario, fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            val lista = miembros
            when {
                error != null -> Text(
                    error!!, color = Coral, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 14.dp),
                )

                lista == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = Cian, strokeWidth = 2.dp) }

                lista.isEmpty() -> Text(
                    "No hay nadie más en este grupo.",
                    color = TextoTerciario, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 14.dp),
                )

                else -> lista.forEach { m ->
                    val id = m.usuario.usuarioId
                    val marcado = id in elegidos
                    // Se apaga solo lo que YA no se puede marcar. Lo marcado
                    // sigue vivo: si no, con el cupo lleno no se podría
                    // cambiar de opinión sin cerrar la hoja.
                    val apagado = lleno && !marcado
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !apagado) {
                                elegidos = if (marcado) elegidos - id else elegidos + id
                            }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = marcado,
                            onCheckedChange = null,
                            enabled = !apagado,
                            colors = CheckboxDefaults.colors(checkedColor = Cian),
                        )
                        Spacer(Modifier.width(8.dp))
                        Avatar(nombre = m.usuario.username, url = null, tamano = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.usuario.username,
                                color = if (apagado) TextoTerciario else TextoPrimario,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (m.rolClave != "miembro") {
                                Text(m.rolNombre, color = TextoTerciario, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (elegidos.isEmpty()) "Nadie elegido"
                else "${elegidos.size + 1} de $MAX_EN_LLAMADA" +
                    if (lleno) " · no entra nadie más" else "",
                color = if (lleno) Ambar else TextoTerciario,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgElev)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(14.dp))
            // Las dos clases de llamada, aquí y no en la barra del chat: la
            // elección de gente y la de audio o vídeo son la misma decisión y
            // se toman de una vez.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonLlamada(
                    icono = Icons.Filled.Phone,
                    texto = "Llamar",
                    habilitado = elegidos.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { onLlamar(elegidos.toList(), false) }
                BotonLlamada(
                    icono = Icons.Filled.Videocam,
                    texto = "Vídeo",
                    habilitado = elegidos.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { onLlamar(elegidos.toList(), true) }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun BotonLlamada(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    texto: String,
    habilitado: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Cian, contentColor = TextoSobreAcento,
            disabledContainerColor = Slate.copy(alpha = 0.4f),
            disabledContentColor = TextoTerciario,
        ),
    ) {
        Icon(icono, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(texto, fontSize = 14.sp)
    }
}
