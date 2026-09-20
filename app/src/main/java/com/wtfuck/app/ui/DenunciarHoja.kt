package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.MotivoDenuncia

/**
 * La hoja de denunciar.
 *
 * Aqui esta la decision mas incomoda del modulo G, y la razon por la que esta
 * pantalla tiene un aviso grande en vez de un boton y nada mas.
 *
 * El servidor **no puede leer los mensajes**. Con cifrado de punta a punta, una
 * denuncia sobre un mensaje le llega al moderador como un identificador y unos
 * bytes opacos: no hay nada que revisar. La unica forma de que haya algo que
 * revisar es que **el texto lo entregue quien denuncia**, porque su telefono ya
 * lo descifro y es el unico que puede.
 *
 * Que el usuario lo sepa ANTES de confirmar no es un detalle de cortesia: es lo
 * que separa una excepcion declarada de una puerta trasera. Denunciar sin saber
 * que se entrega el texto seria un engano, aunque el texto haga falta.
 *
 * Por eso tambien el interruptor arranca APAGADO. Entregar mas contexto casi
 * siempre ayuda al moderador, pero esa es una decision del denunciante, no una
 * casilla premarcada.
 */
@Composable
fun DenunciarHoja(
    /** Que se denuncia, en palabras: "@rosa", "este mensaje", "el grupo Turno B". */
    queSeDenuncia: String,
    /**
     * Si al denunciar se entrega texto en claro.
     *
     * Solo es cierto para mensajes. Denunciar a una persona o a un grupo no
     * entrega nada, y mostrar el aviso igual seria alarmar de gratis.
     */
    entregaTexto: Boolean,
    onCerrar: () -> Unit,
    onEnviar: (motivo: String, detalle: String, conContexto: Boolean) -> Unit,
    enviando: Boolean = false,
) {
    var motivo by remember { mutableStateOf(MotivoDenuncia.SPAM) }
    var detalle by remember { mutableStateOf("") }
    var conContexto by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!enviando) onCerrar() },
        containerColor = BgElev,
        title = { Text("Denunciar $queSeDenuncia", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                Text(
                    "Motivo",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(4.dp))

                // Lista cerrada de motivos, no un campo libre: un texto libre
                // no se puede ni ordenar ni medir, y la cola de revision se
                // prioriza por motivo.
                MotivoDenuncia.TODOS.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = motivo == m, onClick = { motivo = m })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = motivo == m,
                            onClick = { motivo = m },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Cian,
                                unselectedColor = Slate,
                            ),
                        )
                        Text(
                            MotivoDenuncia.etiqueta(m),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoPrimario,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = detalle,
                    onValueChange = { if (it.length <= 500) detalle = it },
                    label = { Text("Que paso (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (entregaTexto) {
                    Spacer(Modifier.height(14.dp))
                    AvisoEntregaTexto(
                        conContexto = conContexto,
                        onContexto = { conContexto = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !enviando,
                onClick = { onEnviar(motivo, detalle.trim(), conContexto) },
            ) {
                Text(if (enviando) "Enviando..." else "Denunciar", color = Coral)
            }
        },
        dismissButton = {
            TextButton(enabled = !enviando, onClick = onCerrar) {
                Text("Cancelar", color = TextoSecundario)
            }
        },
    )
}

/**
 * El aviso de que se entrega texto en claro.
 *
 * En ambar y no en rojo: no es un error ni un peligro, es un intercambio que
 * hay que entender. El rojo se reserva para lo que sale mal.
 */
@Composable
private fun AvisoEntregaTexto(conContexto: Boolean, onContexto: (Boolean) -> Unit) {
    Surface(
        color = Ambar.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LockOpen, null, tint = Ambar, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Se entrega el texto del mensaje",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ambar,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Tus mensajes van cifrados y el servidor no puede leerlos. Para que un " +
                    "moderador pueda revisar esto, tu telefono le envia el texto de lo que " +
                    "denuncias. Solo eso, y se borra cuando se cierra el caso.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(10.dp))
            // Conmuta la FILA entera, no solo el interruptor.
            //
            // Se detecto probando en el emulador: el volcado de accesibilidad
            // marcaba el Switch como NAF -sin etiqueta para un lector de
            // pantalla- y su area de toque era de 40dp perdida al costado de un
            // texto de dos lineas. Poniendo `toggleable` en la fila, la
            // semantica queda en el contenedor con su etiqueta, y el blanco es
            // todo el renglon.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = conContexto,
                        onValueChange = onContexto,
                        role = Role.Switch,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = conContexto,
                    // Nulo a proposito: quien maneja el gesto es la fila. Si el
                    // Switch tambien lo hiciera, habria dos objetivos de toque
                    // anidados y el lector de pantalla anunciaria dos controles.
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextoSobreAcento,
                        checkedTrackColor = Ambar,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Incluir los mensajes anteriores",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoPrimario,
                    )
                    Text(
                        "Ayuda a entender el contexto. Van los ultimos, no la conversacion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
            }
        }
    }
}
