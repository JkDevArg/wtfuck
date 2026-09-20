package com.wtfuck.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ExcepcionesPrivacidad
import com.wtfuck.protocol.Privacidad
import kotlinx.coroutines.launch

/**
 * L.1 · Las listas del nivel `personalizado`.
 *
 * ## Por qué "personalizado" no es un cuarto nivel cualquiera
 *
 * `todos`, `conocidos` y `nadie` son **valores**: caben en una columna. "Todos
 * menos Fulano" y "sólo Mengano" no son valores, son **listas**, y una lista
 * por persona y por ajuste no cabe en un `text`. Por eso este nivel apunta a
 * una tabla, y por eso existe esta pantalla.
 *
 * ## Los dos modos, y por qué hacen falta los dos
 *
 * Son las dos maneras opuestas en que la gente piensa la privacidad, y las dos
 * son legítimas:
 *
 * - **Todos menos…** (lista negra): "que lo vea cualquiera, salvo estas
 *   personas".
 * - **Sólo…** (lista blanca): "que lo vean únicamente estas personas".
 *
 * Con un solo modo el otro caso se vuelve absurdo: una lista negra no puede
 * expresar "sólo mi familia" sin enumerar la plataforma entera.
 *
 * ## El defecto seguro
 *
 * Una lista blanca **vacía** no muestra el dato a nadie, y eso es a propósito:
 * si algo fallara al leer las excepciones, el resultado es ocultar el dato,
 * nunca mostrárselo a todos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcepcionesPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var ajustes by remember { mutableStateOf<List<ExcepcionesPrivacidad>?>(null) }
    var agregando by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() { ajustes = app.repo.excepcionesPrivacidad() }
    LaunchedEffect(Unit) { recargar() }

    fun guardar(e: ExcepcionesPrivacidad) {
        ambito.launch {
            app.repo.guardarExcepciones(e)
                .onSuccess { ajustes = it }
                .onFailure { aviso = it.message }
        }
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
                title = { Text("Listas personalizadas", color = TextoPrimario) },
            )
        },
    ) { pad ->
        val lista = ajustes
        if (lista == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Text(
                "Estas listas solo se usan cuando el ajuste esta en \"Personalizado\". " +
                    "Cada uno tiene la suya.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(16.dp))

            lista.forEach { e ->
                Surface(
                    color = BgSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            nombreDeAjuste(e.ajuste),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextoPrimario,
                        )

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Privacidad.MODO_SALVO to "Todos menos...",
                                Privacidad.MODO_SOLO to "Solo...",
                            ).forEach { (modo, etiqueta) ->
                                FilterChip(
                                    selected = e.modo == modo,
                                    onClick = { guardar(e.copy(modo = modo)) },
                                    label = { Text(etiqueta, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cian.copy(alpha = 0.18f),
                                        selectedLabelColor = Cian,
                                        labelColor = TextoTerciario,
                                    ),
                                )
                            }
                        }

                        if (e.usernames.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                // El caso vacio no es igual en los dos modos, y
                                // decirlo evita la sorpresa de "lo puse en
                                // personalizado y dejo de verlo todo el mundo".
                                if (e.modo == Privacidad.MODO_SOLO) {
                                    "Lista vacia: con \"Solo...\" esto no lo ve nadie."
                                } else {
                                    "Lista vacia: con \"Todos menos...\" esto lo ven todos."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Ambar,
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                            e.usernames.forEach { u ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "@$u",
                                        color = TextoSecundario,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            guardar(e.copy(usernames = e.usernames - u))
                                        },
                                        // 44 dp y no 32: el minimo de area
                                        // tactil es 48 y esta fila mide 44 de
                                        // alto. 32 es fallar el toque con el
                                        // pulgar, y lo que hay detras es
                                        // quitarle a alguien una excepcion de
                                        // privacidad sin querer.
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            "Quitar a @$u",
                                            tint = TextoTerciario,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { agregando = e.ajuste },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                null,
                                tint = Cian,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Agregar a alguien", color = Cian, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    agregando?.let { ajuste ->
        val actual = ajustes?.firstOrNull { it.ajuste == ajuste }
        var quien by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { agregando = null },
            containerColor = BgElev,
            title = { Text(nombreDeAjuste(ajuste), color = TextoPrimario) },
            text = {
                Column {
                    Text(
                        if (actual?.modo == Privacidad.MODO_SOLO) {
                            "Quien agregues SI va a ver esto."
                        } else {
                            "Quien agregues NO va a ver esto."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = quien,
                        onValueChange = { quien = it.filter { c -> !c.isWhitespace() }.lowercase() },
                        label = { Text("Usuario", color = TextoTerciario) },
                        prefix = { Text("@", color = TextoTerciario) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = quien.length >= 3 && actual != null,
                    onClick = {
                        actual?.let {
                            guardar(it.copy(usernames = (it.usernames + quien).distinct()))
                        }
                        agregando = null
                    },
                ) { Text("Agregar", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { agregando = null }) {
                    Text("Cancelar", color = TextoSecundario)
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

/** Los nombres internos no se muestran: `ultima_vez` no le dice nada a nadie. */
private fun nombreDeAjuste(ajuste: String): String = when (ajuste) {
    "foto" -> "Mi foto"
    "estado" -> "Mi estado"
    "nombre" -> "Mi nombre"
    "grupos" -> "Quien me agrega a grupos"
    "llamadas" -> "Quien me puede llamar"
    "busqueda" -> "Quien me encuentra por mi usuario"
    "ultima_vez" -> "Mi ultima conexion"
    else -> ajuste
}
