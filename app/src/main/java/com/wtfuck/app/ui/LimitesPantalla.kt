package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.LimiteAjustable
import kotlinx.coroutines.launch

/**
 * H.6 · Los límites de abuso, ajustables.
 *
 * ## Por qué esta pantalla existe
 *
 * Los límites son la única defensa contra el abuso automatizado, y el número
 * correcto no se sabe de antemano: se descubre mirando la plataforma real. Con
 * los números compilados, ajustar uno es un despliegue —y un despliegue en
 * medio de un ataque es lo último que uno quiere estar haciendo—.
 *
 * ## Lo que la pantalla no deja hacer
 *
 * No hay "sin límite", ni tope cero, ni ventanas de días: el servidor y la
 * base los rechazan. Un límite configurable que admite apagarse no es un
 * límite, es un interruptor para la defensa, y tarde o temprano alguien lo usa
 * "un momento" y se olvida.
 *
 * Y cada valor se muestra junto al **de fábrica**. Sin eso, quien mira no
 * puede saber si el número que ve es el probado o el que alguien cambió un
 * martes, y "restaurar" sería un botón a ciegas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitesPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var limites by remember { mutableStateOf<List<LimiteAjustable>?>(null) }
    var editando by remember { mutableStateOf<LimiteAjustable?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() { limites = app.repo.limitesPanel() }
    LaunchedEffect(Unit) { recargar() }

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
                title = { Text("Limites", color = TextoPrimario) },
                actions = {
                    IconButton(onClick = { ambito.launch { recargar() } }) {
                        Icon(Icons.Filled.Refresh, "Actualizar", tint = TextoSecundario)
                    }
                },
            )
        },
    ) { pad ->
        val lista = limites
        if (lista == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(14.dp)) {
            item {
                Text(
                    "Cada limite cuenta cuantas veces se puede hacer algo dentro de una " +
                        "ventana de tiempo. Tocalo para cambiarlo; el valor de fabrica queda " +
                        "a la vista para poder volver.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(14.dp))
            }

            items(lista, key = { it.clave }) { l ->
                FilaLimite(l) { editando = l }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    editando?.let { l ->
        DialogoLimite(
            l = l,
            onCerrar = { editando = null },
            onGuardar = { tope, ventana ->
                ambito.launch {
                    app.repo.ajustarLimite(l.clave, tope, ventana)
                        .onSuccess { editando = null; aviso = "Limite actualizado."; recargar() }
                        .onFailure { aviso = it.message }
                }
            },
            onRestaurar = {
                ambito.launch {
                    app.repo.restaurarLimite(l.clave)
                        .onSuccess { editando = null; aviso = "Volvio al valor de fabrica."; recargar() }
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
private fun FilaLimite(l: LimiteAjustable, onClick: () -> Unit) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Speed,
                null,
                // Cian = de fabrica; ambar = alguien lo cambio. No es decoracion:
                // un limite tocado es la primera cosa que hay que mirar cuando
                // algo raro pasa en la plataforma.
                tint = if (l.esDefecto) Cian else Ambar,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.etiqueta, color = TextoPrimario, fontSize = 15.sp)
                if (l.detalle.isNotBlank()) {
                    Text(
                        l.detalle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
                if (!l.esDefecto) {
                    Text(
                        "Cambiado" + (l.actualizadoPor?.let { " por @$it" } ?: "") +
                            " · de fabrica: ${l.topeDefecto} / ${ventanaCorta(l.ventanaDefectoSegundos)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ambar,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${l.tope}",
                    color = if (l.esDefecto) TextoPrimario else Ambar,
                    fontSize = 17.sp,
                )
                Text(
                    "por ${ventanaCorta(l.ventanaSegundos)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        }
    }
}

@Composable
private fun DialogoLimite(
    l: LimiteAjustable,
    onCerrar: () -> Unit,
    onGuardar: (Int, Int) -> Unit,
    onRestaurar: () -> Unit,
) {
    var tope by remember { mutableStateOf(l.tope.toString()) }
    var ventana by remember { mutableStateOf(l.ventanaSegundos.toString()) }

    val topeN = tope.toIntOrNull()
    val ventanaN = ventana.toIntOrNull()
    // Los mismos rangos que valida el servidor. Validar aqui tambien no es
    // duplicar por gusto: es la diferencia entre un campo que se pone rojo y
    // un error que llega despues de guardar.
    val valido = topeN != null && topeN in 1..1_000_000 &&
        ventanaN != null && ventanaN in 1..86_400

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(l.etiqueta, color = TextoPrimario) },
        text = {
            Column {
                Text(
                    "Cuantas veces se puede, y en cuanto tiempo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tope,
                    onValueChange = { tope = it.filter { c -> c.isDigit() }.take(7) },
                    label = { Text("Veces", color = TextoTerciario) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ventana,
                    onValueChange = { ventana = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Cada cuantos segundos", color = TextoTerciario) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Maximo 24 horas de ventana. De fabrica: ${l.topeDefecto} cada " +
                        "${ventanaCorta(l.ventanaDefectoSegundos)}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
                if (!l.esDefecto) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onRestaurar) {
                        Text("Volver al valor de fabrica", color = Ambar)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGuardar(topeN ?: 0, ventanaN ?: 0) },
                enabled = valido,
            ) { Text("Guardar", color = if (valido) Cian else Slate) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}

/** "90 s" no se lee; "1.5 min" tampoco. Se redondea a la unidad natural. */
fun ventanaCorta(segundos: Int): String = when {
    segundos % 3600 == 0 && segundos >= 3600 -> {
        val h = segundos / 3600
        if (h == 1) "hora" else "$h horas"
    }
    segundos % 60 == 0 && segundos >= 60 -> {
        val m = segundos / 60
        if (m == 1) "minuto" else "$m min"
    }
    else -> "$segundos s"
}
