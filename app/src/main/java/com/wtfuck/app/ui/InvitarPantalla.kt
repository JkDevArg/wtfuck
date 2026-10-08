package com.wtfuck.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.InvitacionResp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invitar a alguien que no tiene Android -sobre todo, un iPhone-: con la
 * invitación crea su cuenta desde la web (W5).
 *
 * ## Por qué hace falta una invitación
 *
 * El registro desde la web pide una invitación de alguien que ya usa wtfuck,
 * además de un correo verificado. Un correo se consigue gratis; una invitación
 * no: es lo que frena las cuentas automáticas. De un solo uso, vence en 7 días,
 * y queda registrado quién invitó a quién.
 *
 * El cupo (5 sin usar a la vez, 20 al mes) lo lleva el servidor; la pantalla
 * solo lo muestra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitarPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var lista by remember { mutableStateOf<List<InvitacionResp>?>(null) }
    var disponibles by remember { mutableStateOf(0) }
    var fallo by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        fallo = false
        app.repo.misInvitacionesWeb()
            .onSuccess { lista = it.invitaciones; disponibles = it.disponibles }
            .onFailure { if (lista == null) fallo = true else aviso = it.message }
    }
    LaunchedEffect(Unit) { recargar() }

    // La web vive en el mismo servidor que la API, en /web/, salvo que el
    // armado diga otra cosa (-Pweb=https://webfck.hackl4bs.com/web/).
    val base = BuildConfig.WEB.ifBlank { BuildConfig.SERVIDOR.trimEnd('/') + "/web/" }
    fun enlace(codigo: String) = "$base?invitacion=$codigo"

    fun compartir(codigo: String) {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                    Intent.EXTRA_TEXT,
                    "Te invito a wtfuck. Abre este enlace, y en iPhone toca Compartir → Agregar a pantalla de inicio " +
                        "antes de crear la cuenta: ${enlace(codigo)}",
                ),
                "Compartir invitación",
            ),
        )
    }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = TextoPrimario)
                    }
                },
                title = { Text("Invitar a alguien", color = TextoPrimario) },
            )
        },
        snackbarHost = {
            aviso?.let { a ->
                Snackbar(Modifier.padding(16.dp), action = { TextButton(onClick = { aviso = null }) { Text("OK", color = Cian) } }) { Text(a) }
            }
        },
    ) { pad ->
        val actual = lista
        when {
            fallo -> EstadoDeError(
                titulo = "No se pudieron cargar tus invitaciones",
                detalle = "Viven en el servidor: hace falta conexión para verlas.",
                onReintentar = { ambito.launch { recargar() } },
                modifier = Modifier.padding(pad),
            )

            actual == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Para quien no tiene Android, por ejemplo un iPhone: con tu invitación crea su cuenta " +
                            "desde la web y la instala en su pantalla de inicio. Cada invitación sirve una sola vez " +
                            "y vence en 7 días. Queda registrado que la invitaste tú.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                }
                item {
                    Button(
                        onClick = {
                            ocupado = true
                            ambito.launch {
                                app.repo.crearInvitacionWeb()
                                    .onSuccess { recargar(); compartir(it.codigo) }
                                    .onFailure { aviso = it.message ?: "No se pudo crear." }
                                ocupado = false
                            }
                        },
                        enabled = !ocupado && disponibles > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = BgBase),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (disponibles > 0) "Crear y compartir una invitación (te quedan $disponibles)"
                            else "No te quedan invitaciones por ahora",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (actual.isEmpty()) {
                    item { Text("Todavía no creaste ninguna.", color = TextoTerciario) }
                }
                items(actual, key = { it.codigo }) { inv ->
                    FilaInvitacion(
                        inv = inv,
                        onCompartir = { compartir(inv.codigo) },
                        onCopiar = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("invitacion", enlace(inv.codigo)))
                            aviso = "Enlace copiado."
                        },
                        onRevocar = {
                            ambito.launch {
                                app.repo.revocarInvitacionWeb(inv.codigo)
                                    .onSuccess { recargar() }
                                    .onFailure { aviso = it.message ?: "No se pudo revocar." }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaInvitacion(inv: InvitacionResp, onCompartir: () -> Unit, onCopiar: () -> Unit, onRevocar: () -> Unit) {
    val viva = !inv.revocada && inv.usos < inv.usosMax && (inv.expiraEn == 0L || inv.expiraEn > System.currentTimeMillis())
    val estado = when {
        inv.revocada -> "Revocada"
        inv.usos >= inv.usosMax -> "Usada"
        !viva -> "Vencida"
        else -> "Vence el " + SimpleDateFormat("dd/MM", Locale("es", "PE")).format(Date(inv.expiraEn))
    }
    Surface(color = BgSurface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inv.codigo, fontFamily = FontFamily.Monospace, color = TextoPrimario, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(estado, color = if (viva) Cian else TextoTerciario, fontSize = 13.sp)
            }
            if (viva) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCompartir) { Text("Compartir", color = Cian) }
                    TextButton(onClick = onCopiar) { Text("Copiar enlace", color = Cian) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRevocar) { Text("Revocar", color = TextoSecundario) }
                }
            }
        }
    }
}
