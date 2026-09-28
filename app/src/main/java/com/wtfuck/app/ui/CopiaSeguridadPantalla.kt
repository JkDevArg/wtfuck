package com.wtfuck.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.CopiaSeguridad
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BE · Copia de seguridad cifrada de los chats.
 *
 * ## Por que esta pantalla existe
 *
 * El historial vive solo en el telefono. Sin esto, perderlo es perderlo todo.
 * Aqui se exporta un archivo cifrado con una frase que la persona elige y
 * guarda donde quiera, y se restaura en un telefono nuevo.
 *
 * ## La advertencia que no se puede omitir
 *
 * La frase NO se guarda en ningun lado -ese es el punto: ni el servidor ni
 * nosotros podemos abrir la copia-. Si se olvida, el archivo es inservible. Por
 * eso se pide dos veces y se avisa con todas las letras antes de exportar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopiaSeguridadPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var pidiendoFraseExport by remember { mutableStateOf(false) }
    var pidiendoFraseImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    // La frase se guarda entre "pedir frase" y "elegir donde guardar": el
    // selector de archivo devuelve una URI, y recien entonces se escribe la
    // copia -en streaming, para que los adjuntos no pasen por memoria-.
    var fraseExport by remember { mutableStateOf<CharArray?>(null) }

    val guardarArchivo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val frase = fraseExport
        fraseExport = null
        if (uri == null || frase == null) { trabajando = false; return@rememberLauncherForActivityResult }
        ambito.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { app.repo.exportarCopiaA(it, frase) }
                        ?: error("sin flujo")
                }.isSuccess
            }
            trabajando = false
            aviso = if (ok) "Copia guardada. Guárdala bien y no olvides la frase."
                    else "No se pudo escribir el archivo."
        }
    }

    val elegirArchivo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pidiendoFraseImport = uri }

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
                title = { Text("Copia de seguridad", color = TextoPrimario, maxLines = 1) },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                "Tus chats viven solo en este teléfono. Si lo pierdes, se pierden. " +
                    "Una copia cifrada te deja recuperarlos en otro teléfono.",
                color = TextoSecundario, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))

            Tarjeta(
                icono = Icons.Filled.Download,
                titulo = "Crear una copia",
                detalle = "Guarda tus chats en un archivo cifrado con una frase.",
                onClick = { if (!trabajando) pidiendoFraseExport = true },
                habilitado = !trabajando,
            )
            Spacer(Modifier.height(12.dp))
            Tarjeta(
                icono = Icons.Filled.Upload,
                titulo = "Restaurar una copia",
                detalle = "Recupera tus chats desde un archivo que hayas guardado.",
                onClick = { if (!trabajando) elegirArchivo.launch(arrayOf("application/octet-stream", "*/*")) },
                habilitado = !trabajando,
            )

            if (trabajando) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cian)
                    Spacer(Modifier.width(10.dp))
                    Text("Trabajando…", color = TextoTerciario)
                }
            }

            Spacer(Modifier.height(24.dp))
            Surface(color = Ambar.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Lee esto", color = Ambar, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• La frase no se guarda en ningún lado. Si la olvidas, la copia " +
                            "no sirve — ni el servidor ni nadie puede abrirla.\n" +
                            "• La copia incluye el texto de tus chats y sus fotos y archivos.\n" +
                            "• Para restaurar en un teléfono nuevo, primero entra a tu cuenta.",
                        color = TextoSecundario, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // --- exportar: pedir frase (dos veces) ---------------------------
    if (pidiendoFraseExport) {
        DialogoFrase(
            titulo = "Frase para la copia",
            confirmar = "Crear",
            pedirDosVeces = true,
            onCerrar = { pidiendoFraseExport = false },
            onFrase = { frase ->
                pidiendoFraseExport = false
                trabajando = true
                // La frase se lleva al selector de archivo; la copia se escribe
                // en streaming cuando la persona elige donde guardar.
                fraseExport = frase
                guardarArchivo.launch("wtfuck-copia-${System.currentTimeMillis() / 1000}.wtfbackup")
            },
        )
    }

    // --- restaurar: pedir frase (una vez) ----------------------------
    pidiendoFraseImport?.let { uri ->
        DialogoFrase(
            titulo = "Frase de la copia",
            confirmar = "Restaurar",
            pedirDosVeces = false,
            onCerrar = { pidiendoFraseImport = null },
            onFrase = { frase ->
                pidiendoFraseImport = null
                trabajando = true
                ambito.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use { app.repo.restaurarCopiaDe(it, frase) }
                                ?: Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO))
                        }.getOrElse { Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO)) }
                    }
                    trabajando = false
                    r.onSuccess {
                        val fotos = if (it.adjuntos > 0) " y ${it.adjuntos} archivos" else ""
                        aviso = "Restaurados ${it.mensajes} mensajes$fotos de ${it.conversaciones} chats."
                    }.onFailure { e ->
                        aviso = when ((e as? CopiaSeguridad.ErrorCopia)?.fallo) {
                            CopiaSeguridad.Fallo.FORMATO -> "Ese archivo no es una copia de wtfuck."
                            CopiaSeguridad.Fallo.FRASE_O_DANADO -> "Frase incorrecta, o el archivo está dañado."
                            null -> "No se pudo restaurar."
                        }
                    }
                }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { aviso = null },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
            text = { Text(msg, color = TextoPrimario) },
        )
    }
}

@Composable
private fun Tarjeta(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    titulo: String, detalle: String, onClick: () -> Unit, habilitado: Boolean,
) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = habilitado,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Cian.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icono, null, tint = Cian, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(titulo, color = TextoPrimario, style = MaterialTheme.typography.bodyLarge)
                Text(detalle, color = TextoTerciario, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DialogoFrase(
    titulo: String,
    confirmar: String,
    pedirDosVeces: Boolean,
    onCerrar: () -> Unit,
    onFrase: (CharArray) -> Unit,
) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    // Minimo 8: una copia protegida por "1234" no esta protegida. Al exportar se
    // exige confirmar la frase, porque un error de tipeo en algo que no se ve
    // dejaria una copia que no abre y no habria como saberlo hasta necesitarla.
    val corta = a.length < 8
    val noCoincide = pedirDosVeces && a != b
    val malo = corta || noCoincide

    AlertDialog(
        containerColor = BgElev,
        onDismissRequest = onCerrar,
        title = { Text(titulo, color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = a, onValueChange = { a = it },
                    label = { Text("Frase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { if (corta) Text("Al menos 8 caracteres.", color = TextoTerciario) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pedirDosVeces) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = b, onValueChange = { b = it },
                        label = { Text("Repite la frase") },
                        singleLine = true,
                        isError = b.isNotEmpty() && noCoincide,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            if (b.isNotEmpty() && noCoincide) Text("No coincide.", color = Coral)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !malo, onClick = { onFrase(a.toCharArray()) }) {
                Text(confirmar, color = if (malo) TextoTerciario else Cian)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
}
