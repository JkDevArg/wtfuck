package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.AlcanceResp
import kotlinx.coroutines.launch

/**
 * H.7 · Pedidos de alcance de los bots de pentesting.
 *
 * ## Qué se aprueba acá, y por qué importa
 *
 * Un operador le pide a un bot (por chat) auditar un objetivo; el bot lo registra
 * como PENDIENTE y recién cuando un admin lo aprueba desde esta pantalla el bot
 * puede escanearlo. Es un control de dos personas: el operador pide, otro aprueba.
 * Sin esto, un bot con herramientas de pentest sería una botonera de escaneo
 * contra cualquiera. Ver docs/14-BOTS.md.
 *
 * Aprobar un objetivo es afirmar que ESE operador está autorizado a auditar ESE
 * objetivo. Queda en la auditoría con quién aprobó y cuándo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlcancePantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var lista by remember { mutableStateOf<List<AlcanceResp>?>(null) } // null = cargando
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        lista = app.repo.pedidosAlcance()
    }

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
                title = { Text("Alcances pendientes", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                "Un operador pidió auditar estos objetivos con un bot. Aprobar es " +
                    "afirmar que está autorizado a hacerlo; queda registrado.",
                color = TextoTerciario,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            val l = lista
            when {
                l == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Cian)
                }

                l.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "No hay pedidos pendientes.",
                        color = TextoSecundario,
                        modifier = Modifier.padding(32.dp),
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    items(l, key = { it.id }) { p ->
                        FilaAlcance(
                            p,
                            onAprobar = {
                                ambito.launch {
                                    app.repo.aprobarAlcance(p.id)
                                        .onSuccess { aviso = "Aprobado: @${p.operador} ya puede auditar ${p.objetivo}."; recargar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                            onRechazar = {
                                ambito.launch {
                                    app.repo.rechazarAlcance(p.id)
                                        .onSuccess { aviso = "Rechazado."; recargar() }
                                        .onFailure { aviso = it.message }
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
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
private fun FilaAlcance(
    p: AlcanceResp,
    onAprobar: () -> Unit,
    onRechazar: () -> Unit,
) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GpsFixed, null, tint = Cian, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    p.objetivo,
                    color = TextoPrimario,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "pedido por @${p.operador} · ${fecha(p.creadoEn)}",
                color = TextoTerciario,
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onAprobar,
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                    modifier = Modifier.weight(1f),
                ) { Text("Aprobar") }
                OutlinedButton(
                    onClick = onRechazar,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
                    modifier = Modifier.weight(1f),
                ) { Text("Rechazar") }
            }
        }
    }
}

private fun fecha(ms: Long): String =
    java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
