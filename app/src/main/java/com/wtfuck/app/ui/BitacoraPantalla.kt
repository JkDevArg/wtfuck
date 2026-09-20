package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.LineaBitacora
import kotlinx.coroutines.delay

/**
 * H.6 · La bitácora: quién hizo qué.
 *
 * ## Por qué hacía falta la pantalla y no solo la tabla
 *
 * `auditoria` existe desde el módulo A y se escribe en cada acción con
 * consecuencias —suspender, cambiar un rol, aprobar un canal, relajar un
 * límite—. Lo que no existía era forma de **leerla** sin entrar a la base. Una
 * bitácora que nadie puede leer no disuade a nadie ni resuelve ninguna
 * discusión, que son sus dos únicas funciones.
 *
 * ## Lo que NO se puede buscar aquí
 *
 * Mensajes. La bitácora registra acciones de administración, no contenido: el
 * servidor no tiene el contenido y esta pantalla no es una puerta trasera para
 * conseguirlo. Es la misma línea que el panel respeta en todo lo demás.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitacoraPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp

    var lineas by remember { mutableStateOf<List<LineaBitacora>?>(null) }
    var filtro by remember { mutableStateOf("") }

    // Espera antes de consultar: escribir un nombre son ocho consultas para
    // ver una. El mismo patron que la busqueda de personas y de canales.
    LaunchedEffect(filtro) {
        if (lineas != null) delay(350)
        lineas = app.repo.bitacora(filtro.trim().ifBlank { null })
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
                title = { Text("Bitacora", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = filtro,
                onValueChange = { filtro = it },
                placeholder = { Text("Filtrar por accion o persona", color = TextoTerciario) },
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

            val lista = lineas
            when {
                lista == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Cian)
                }

                lista.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.History, null, tint = Slate, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (filtro.isBlank()) "Todavia no hay nada anotado"
                        else "Nada coincide con \"${filtro.trim()}\"",
                        color = TextoSecundario,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    items(lista, key = { it.id }) { l ->
                        FilaBitacora(l)
                        HorizontalDivider(color = Slate.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilaBitacora(l: LineaBitacora) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                // La accion tal cual se guardo -"canal.aprobar",
                // "limite.ajustado"-. Traducirla a lenguaje natural obligaria a
                // mantener un diccionario que se desincroniza en silencio, y
                // una bitacora con nombres inventados es peor que una con
                // nombres tecnicos.
                l.accion,
                color = TextoPrimario,
                fontSize = 14.sp,
                style = estiloHuella,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(if (l.actor.isBlank()) "sistema" else "@${l.actor}")
                    if (!l.objetivo.isNullOrBlank()) append(" → @${l.objetivo}")
                    if (l.recursoTipo.isNotBlank()) append(" · ${l.recursoTipo}")
                },
                color = TextoSecundario,
                fontSize = 13.sp,
            )
            l.detalle?.takeIf { it.isNotBlank() && it != "null" }?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it.take(160),
                    color = TextoTerciario,
                    fontSize = 11.sp,
                    style = estiloHuella,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            fechaLarga(l.creadoEn),
            color = TextoTerciario,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
