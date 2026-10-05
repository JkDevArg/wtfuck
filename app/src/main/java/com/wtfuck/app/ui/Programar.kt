package com.wtfuck.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.datos.MomentoProgramado
import com.wtfuck.app.ui.theme.*
import java.time.LocalDate
import java.time.LocalTime

/**
 * A que hora sale lo escrito: tres atajos y "otra fecha y hora".
 *
 * La fecha y la hora a mano van con los selectores del sistema y no con unos
 * propios: son los que la persona ya conoce, y respetan su formato de 12 o 24
 * horas sin que haya que preguntarlo.
 */
@Composable
fun ElegirMomento(onElegir: (Long) -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val ahora = remember { MomentoProgramado.ahora() }
    val atajos = remember(ahora) { MomentoProgramado.atajos(ahora) }

    fun aMano() {
        DatePickerDialog(ctx, { _, a, m, d ->
            TimePickerDialog(ctx, { _, h, min ->
                val cuando = LocalDate.of(a, m + 1, d).atTime(LocalTime.of(h, min))
                    .atZone(ahora.zone).toInstant().toEpochMilli()
                if (MomentoProgramado.valido(cuando, System.currentTimeMillis())) onElegir(cuando)
                else Toast.makeText(ctx, "Elige una hora futura, dentro del próximo año.", Toast.LENGTH_LONG).show()
            }, ahora.hour, ahora.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
        }, ahora.year, ahora.monthValue - 1, ahora.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Programar envío", color = TextoPrimario) },
        text = {
            Column {
                atajos.forEach { o ->
                    FilaMomento(o.etiqueta, MomentoProgramado.etiqueta(o.cuando, ahora)) { onElegir(o.cuando) }
                }
                FilaMomento("Otra fecha y hora", "elige en el calendario", Icons.Filled.CalendarMonth) { aMano() }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sale desde este teléfono: si a esa hora está apagado o sin red, sale cuando vuelva. " +
                        "El servidor no se entera de que hay algo programado.",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = Cian) } },
    )
}

@Composable
private fun FilaMomento(
    titulo: String,
    detalle: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Schedule,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Cian, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(titulo, color = TextoPrimario)
            Text(detalle, color = TextoTerciario, fontSize = 12.sp)
        }
    }
}

/** Los programados de este chat, con "Enviar ahora" y "Cancelar". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaProgramados(
    programados: List<MensajeEnt>,
    onEnviarYa: (String) -> Unit,
    onCancelar: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    // Si ya no queda ninguno -se mandaron o se cancelaron todos-, la hoja vacia
    // no tiene nada que decir.
    LaunchedEffect(programados.isEmpty()) { if (programados.isEmpty()) onCerrar() }
    val ahora = MomentoProgramado.ahora()
    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                "Programados",
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Salen desde este teléfono, a su hora.",
                color = TextoTerciario,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn {
                items(programados, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(
                            MomentoProgramado.etiqueta(m.programadoPara, ahora),
                            color = Cian,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(m.texto, color = TextoPrimario, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = { onEnviarYa(m.id) }) { Text("Enviar ahora", color = Cian) }
                            TextButton(onClick = { onCancelar(m.id) }) { Text("Cancelar", color = Coral) }
                        }
                    }
                    HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                }
            }
        }
    }
}
