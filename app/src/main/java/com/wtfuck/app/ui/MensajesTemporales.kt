package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.protocol.DuracionMensaje
import com.wtfuck.app.ui.theme.*

/**
 * El selector del temporizador de mensajes.
 *
 * ## Por que existe como pieza aparte
 *
 * Porque lo usan DOS sitios —la info del grupo y el menu de un chat directo— y
 * antes solo existia en el primero, escrito a mano, con tres botones sueltos.
 * Eso traia dos defectos que no se ven hasta que alguien lo usa de verdad:
 *
 *  1. **No decia como estaba.** Tres botones iguales, ninguno marcado. No
 *     habia forma de saber si el temporizador estaba encendido ni en cuanto, y
 *     un control de privacidad que no dice su estado es peor que no tenerlo:
 *     invita a creer que esta puesto.
 *  2. **No estaba en los chats de dos**, que es justo donde mas se quiere.
 *
 * Ahora es una lista con el valor actual marcado, y sale del mismo sitio en
 * los dos casos.
 */
@Composable
fun DialogoTemporales(
    /** Lo que hay puesto ahora, en segundos. `0` = permanentes. */
    actual: Int,
    /** `null` = apagarlo. */
    onElegir: (Int?) -> Unit,
    onCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Mensajes temporales", color = TextoPrimario) },
        text = {
            Column {
                Text(
                    "Los mensajes nuevos de este chat se borran solos, en todos " +
                        "los teléfonos, pasado el tiempo que elijas. Lo que ya " +
                        "está escrito no se toca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(14.dp))

                // "Desactivado" primero: es el estado por defecto y la salida.
                // Quien entra aqui a apagarlo no deberia tener que recorrer
                // siete plazos para encontrar el "no".
                OpcionTemporal("Desactivado", actual <= 0) { onElegir(null) }
                DuracionMensaje.OPCIONES.forEach { segundos ->
                    OpcionTemporal(
                        DuracionMensaje.texto(segundos).replaceFirstChar { it.uppercase() },
                        actual == segundos,
                    ) { onElegir(segundos) }
                }

                // El limite honesto de la funcion. Se dice aqui y no en la
                // documentacion porque es aqui donde alguien decide confiar en
                // ella: borrar en el otro telefono depende de que el otro
                // telefono lo haga, y eso lo garantiza su app, no este chat.
                Spacer(Modifier.height(10.dp))
                Text(
                    "Lo cumple la app de cada persona. Alguien con la pantalla " +
                        "capturada o con otra copia puede conservar lo que leyó.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCerrar) { Text("Cerrar", color = TextoSecundario) }
        },
    )
}

@Composable
private fun OpcionTemporal(etiqueta: String, elegida: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = elegida,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Cian, unselectedColor = Slate),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            etiqueta,
            style = MaterialTheme.typography.bodyLarge,
            color = if (elegida) Cian else TextoPrimario,
            fontWeight = if (elegida) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * El reloj en la cabecera del chat, cuando el temporizador esta encendido.
 *
 * Es la mitad importante de la funcion. Un temporizador que solo se ve entrando
 * al menu no sirve para decidir que escribir: la decision se toma mirando el
 * chat, y es entonces cuando hay que saber si lo que se escriba va a quedar.
 * Por lo mismo el aviso de que alguien lo cambio queda escrito como linea de
 * sistema en la conversacion, y no como una notificacion que se va.
 */
@Composable
fun IndicadorTemporales(segundos: Int, modifier: Modifier = Modifier) {
    if (segundos <= 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Timer, "Mensajes temporales", Modifier.size(13.dp), tint = Cian)
        Spacer(Modifier.width(3.dp))
        Text(DuracionMensaje.texto(segundos), color = Cian, fontSize = 11.sp)
    }
}
