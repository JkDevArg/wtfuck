package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.ConsolaAbierta
import com.wtfuck.protocol.TokenConsola
import kotlinx.coroutines.launch

/**
 * L.8 · Abrir la consola web desde el teléfono.
 *
 * ## Por qué el teléfono emite el acceso y el navegador no tiene ingreso propio
 *
 * Ingresar con usuario y contraseña desde el navegador crearía un
 * **dispositivo**, y ahí se rompen dos reglas a la vez: una cuenta por
 * hardware —un navegador no tiene hardware que atestiguar— y la promesa de que
 * cada dispositivo es una copia más de tus mensajes. Un navegador no puede ser
 * eso.
 *
 * Así que la raíz de confianza sigue siendo este aparato: quien ya está dentro,
 * con su contraseña y su segundo factor, emite un token y lo pega en el
 * navegador. Dura una jornada, se ve aquí y se cierra desde aquí.
 *
 * ## Lo que la consola puede y no puede
 *
 * Puede: denuncias, cuentas, canales por aprobar, grupos, límites, bitácora.
 * No puede: **leer mensajes**. No es una decisión de la interfaz web; el
 * servidor no los tiene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolaWebPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current

    var abiertas by remember { mutableStateOf<List<ConsolaAbierta>?>(null) }
    var pidiendoClave by remember { mutableStateOf(false) }
    var recien by remember { mutableStateOf<TokenConsola?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() { abiertas = app.repo.consolasAbiertas() }
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
                title = { Text("Consola web", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(
                "Moderar desde una pantalla grande",
                style = MaterialTheme.typography.titleMedium,
                color = TextoPrimario,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Abre ${direccionConsola()} en el navegador y pega el token que se genera " +
                    "aqui. Dura 8 horas.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )

            Spacer(Modifier.height(14.dp))
            Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        // Se dice ANTES de abrirla, no en una nota al pie: quien
                        // espere leer conversaciones desde la web tiene que
                        // enterarse aqui y no despues de configurar todo.
                        "La consola NO muestra mensajes",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ambar,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No es una limitacion de la web: el servidor no tiene los mensajes, " +
                            "solo bytes cifrados que no puede abrir. La consola ve denuncias, " +
                            "cuentas, canales, limites y la bitacora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { pidiendoClave = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cian, contentColor = TextoSobreAcento,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Computer, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Abrir una consola")
            }

            val lista = abiertas
            if (lista != null && lista.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    "Consolas abiertas",
                    style = MaterialTheme.typography.labelLarge,
                    color = Cian,
                )
                lista.forEach { c ->
                    Spacer(Modifier.height(10.dp))
                    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.etiqueta.ifBlank { "Sin nombre" },
                                    color = TextoPrimario,
                                )
                                Text(
                                    buildString {
                                        if (c.emitidaDesde.isNotBlank()) {
                                            append("desde ${c.emitidaDesde} · ")
                                        }
                                        append("vence ${fechaLarga(c.expiraEn)}")
                                        if (c.ultimoUso > 0) {
                                            append(" · uso ${fechaLarga(c.ultimoUso)}")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextoTerciario,
                                )
                            }
                            TextButton(onClick = {
                                ambito.launch {
                                    app.repo.cerrarConsola(c.id)
                                        .onSuccess { aviso = "Consola cerrada."; recargar() }
                                        .onFailure { aviso = it.message }
                                }
                            }) { Text("Cerrar", color = Coral) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pidiendoClave) {
        var clave by remember { mutableStateOf("") }
        var etiqueta by remember { mutableStateOf("") }
        var trabajando by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!trabajando) pidiendoClave = false },
            containerColor = BgElev,
            title = { Text("Abrir una consola", color = TextoPrimario) },
            text = {
                Column {
                    Text(
                        // El mismo motivo que al vincular un dispositivo, y se
                        // dice igual: una sesion robada no deberia poder
                        // abrirse acceso administrativo.
                        "Pedimos la contrasena porque una consola puede suspender cuentas. " +
                            "Una sesion robada no deberia poder abrirla.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = etiqueta,
                        onValueChange = { etiqueta = it.take(60) },
                        label = { Text("Nombre (opcional)", color = TextoTerciario) },
                        placeholder = { Text("Laptop de la oficina", color = TextoTerciario) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = clave,
                        onValueChange = { clave = it },
                        label = { Text("Tu contrasena", color = TextoTerciario) },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !trabajando && clave.length >= 8,
                    onClick = {
                        trabajando = true
                        ambito.launch {
                            app.repo.abrirConsola(clave, etiqueta.ifBlank { null })
                                .onSuccess {
                                    trabajando = false
                                    pidiendoClave = false
                                    recien = it
                                    recargar()
                                }
                                .onFailure { trabajando = false; aviso = it.message }
                        }
                    },
                ) { Text(if (trabajando) "Abriendo..." else "Abrir", color = Cian) }
            },
            dismissButton = {
                TextButton(enabled = !trabajando, onClick = { pidiendoClave = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    recien?.let { t ->
        AlertDialog(
            onDismissRequest = { recien = null },
            containerColor = BgElev,
            title = { Text("Token de consola", color = TextoPrimario) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Pegalo en ${direccionConsola()}. No se puede volver a mostrar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(12.dp))

                    // El QR lleva el token entero. En una laptop se escanea con
                    // el gestor de contrasenas o se copia a mano; el texto
                    // queda igual porque un token de 43 caracteres tecleado es
                    // una fuente de errores garantizada.
                    CodigoQr(t.token, tamano = 190.dp)

                    Spacer(Modifier.height(12.dp))
                    Surface(color = BgSurface, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            t.token,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Cian,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        portapapeles.setText(AnnotatedString(t.token))
                        aviso = "Token copiado."
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy, null,
                            tint = Cian, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Copiar", color = Cian)
                    }
                    Text(
                        "Vive ${t.expiraEnSegundos / 3600} horas. Se puede cerrar desde aqui " +
                            "en cualquier momento.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { recien = null }) { Text("Listo", color = Cian) } },
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

/**
 * La direccion de la consola, derivada de la del servidor.
 *
 * Se calcula y no se escribe a mano porque en desarrollo el servidor vive en
 * `10.0.2.2:8088` y en produccion en otro sitio: un texto fijo mandaria a la
 * gente a una URL que no existe en la mitad de los entornos.
 */
private fun direccionConsola(): String =
    BuildConfig.SERVIDOR.trimEnd('/') + "/consola"
