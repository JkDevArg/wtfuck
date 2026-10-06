package com.wtfuck.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.EnlaceDeContacto
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.UsuarioPublico
import kotlinx.coroutines.launch

/**
 * Mi enlace de contacto y su QR: "agregame" sin dictar el usuario.
 *
 * ## Lo que se dice en la pantalla, y por que
 *
 * Que el enlace salta "quien me encuentra" y NO salta "quien me escribe". Es la
 * diferencia entre un enlace y un usuario, y quien lo reparte tiene que saber
 * que lo que reparte es una llave: quien la tenga te encuentra aunque te hayas
 * ocultado, y si no te conoce, te llega como solicitud. Ver `EnlaceContacto`
 * en el servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiEnlacePantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var cargando by remember { mutableStateOf(true) }
    var codigo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmar by remember { mutableStateOf<String?>(null) }   // "cambiar" | "apagar"

    fun hacer(bloque: suspend () -> String?) {
        cargando = true
        ambito.launch {
            runCatching { bloque() }
                .onSuccess { codigo = it; error = null }
                .onFailure { error = it.message ?: "No se pudo." }
            cargando = false
        }
    }
    LaunchedEffect(Unit) { hacer { app.repo.miEnlace() } }

    val url = codigo?.let { EnlaceDeContacto.url(BuildConfig.SERVIDOR, it) }

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
                title = { Text("Mi enlace y QR", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                cargando && codigo == null -> CircularProgressIndicator(color = Cian, modifier = Modifier.padding(40.dp))
                url == null -> {
                    Text(
                        "Un enlace para que te escriban sin tener que buscarte. Lo compartes, o " +
                            "te escanean el código, y te escriben.",
                        color = TextoSecundario, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { hacer { app.repo.crearEnlace() } },
                        enabled = !cargando,
                        colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                    ) { Text("Crear mi enlace") }
                }
                else -> {
                    // Fondo blanco siempre: un QR claro sobre oscuro lo leen
                    // mal muchas camaras.
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),
                    ) { CodigoQr(url, tamano = 240.dp) }
                    Spacer(Modifier.height(10.dp))
                    Text("@" + app.sesion.username.orEmpty(), color = TextoPrimario, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        url, color = TextoTerciario, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "Escríbeme en wtfuck: $url"),
                                    "Compartir mi enlace",
                                )
                            )
                        }) {
                            Icon(Icons.Filled.Share, null, tint = Cian, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Compartir", color = Cian)
                        }
                        TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("enlace", url))
                            Toast.makeText(ctx, "Enlace copiado", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.ContentCopy, null, tint = Cian, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copiar", color = Cian)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            "• Quien tenga este enlace te encuentra, aunque en Privacidad nadie pueda " +
                                "encontrarte por tu usuario.\n" +
                                "• Quién te puede escribir no cambia: si solo dejas a conocidos, quien " +
                                "no lo sea te llega como solicitud y tú decides.\n" +
                                "• Si circula por donde no querías, cámbialo: el anterior deja de " +
                                "funcionar en el acto.",
                            color = TextoSecundario, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = { confirmar = "cambiar" }, enabled = !cargando) {
                            Text("Cambiar enlace", color = Cian)
                        }
                        TextButton(onClick = { confirmar = "apagar" }, enabled = !cargando) {
                            Text("Apagar", color = Coral)
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Coral, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirmar?.let { que ->
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { confirmar = null },
            title = { Text(if (que == "cambiar") "Cambiar el enlace" else "Apagar el enlace", color = TextoPrimario) },
            text = {
                Text(
                    if (que == "cambiar") "El enlace y el QR de ahora dejan de funcionar, también los que ya " +
                        "repartiste. Tendrás uno nuevo."
                    else "El enlace y el QR dejan de funcionar. Puedes crear otro cuando quieras.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmar = null
                    if (que == "cambiar") hacer { app.repo.crearEnlace() }
                    else hacer { app.repo.apagarEnlace(); null }
                }) { Text(if (que == "cambiar") "Cambiar" else "Apagar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Cancelar", color = TextoSecundario) } },
        )
    }
}

/**
 * Lo que aparece al abrir el enlace de otra persona: quien es -lo que esa
 * persona deja ver- y "Escribir".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaContactoPorEnlace(codigo: String, onCerrar: () -> Unit, onAbrirChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var usuario by remember(codigo) { mutableStateOf<UsuarioPublico?>(null) }
    var error by remember(codigo) { mutableStateOf<String?>(null) }
    var abriendo by remember { mutableStateOf(false) }

    LaunchedEffect(codigo) {
        runCatching { app.repo.resolverEnlace(codigo) }
            .onSuccess { usuario = it }
            .onFailure { error = it.message ?: "Ese enlace no existe o ya no funciona." }
    }

    ModalBottomSheet(onDismissRequest = onCerrar, containerColor = BgSurface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val u = usuario
            when {
                error != null -> {
                    Text("No se pudo abrir el enlace", color = TextoPrimario, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(error.orEmpty(), color = TextoSecundario, textAlign = TextAlign.Center)
                }
                u == null -> CircularProgressIndicator(color = Cian, modifier = Modifier.padding(24.dp))
                u.username == app.sesion.username -> {
                    Text("Es tu propio enlace", color = TextoPrimario, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("Compártelo para que otras personas te escriban.", color = TextoSecundario)
                }
                else -> {
                    val nombre = u.nombreMostrado.ifBlank { u.username }
                    Avatar(nombre, ApiCliente.urlImagen(u.username, "avatar", u.avatarVersion), tamano = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(nombre, color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                    Text("@" + u.username, color = TextoTerciario)
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            abriendo = true
                            ambito.launch {
                                runCatching { app.repo.abrirPorEnlace(u.username, codigo) }
                                    .onSuccess { onCerrar(); onAbrirChat(it) }
                                    .onFailure { error = it.message ?: "No se pudo abrir el chat." }
                                abriendo = false
                            }
                        },
                        enabled = !abriendo,
                        colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Escribir") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Según su privacidad, puede llegarle como solicitud.",
                        color = TextoTerciario, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
