package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.Bloqueado
import kotlinx.coroutines.launch

/**
 * A quiénes bloqueé, y desbloquearlos. Desde Privacidad.
 *
 * ## Por qué hacía falta
 *
 * Se podía bloquear desde el chat y desde la ficha de una persona, pero no
 * había forma de deshacerlo: el bloqueo vivía en el servidor y ninguna pantalla
 * lo mostraba. Un bloqueo por error era para siempre.
 *
 * ## Qué muestra
 *
 * Solo el @usuario y desde cuándo. Ni foto ni nombre: los rige la privacidad
 * del otro, y esta lista existe para desbloquear, no para seguir mirando.
 *
 * Al abrirla, de paso, la lista del modo cerca se pone al día con la del
 * servidor. Ver `Repositorio.bloqueados`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloqueadosPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    // null = cargando. Un fallo se guarda aparte para poder reintentar.
    var lista by remember { mutableStateOf<List<Bloqueado>?>(null) }
    var fallo by remember { mutableStateOf(false) }
    var desbloqueando by remember { mutableStateOf<Bloqueado?>(null) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        fallo = false
        app.repo.bloqueados()
            .onSuccess { lista = it }
            .onFailure { if (lista == null) fallo = true else aviso = "No se pudo actualizar la lista." }
    }
    LaunchedEffect(Unit) { recargar() }

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
                title = { Text("Bloqueados", color = TextoPrimario) },
            )
        },
    ) { pad ->
        val actual = lista
        when {
            fallo -> EstadoDeError(
                titulo = "No se pudo cargar la lista",
                detalle = "Los bloqueos viven en el servidor: hace falta conexión para verlos.",
                onReintentar = { ambito.launch { recargar() } },
                modifier = Modifier.padding(pad),
            )

            actual == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Text(
                        "No pueden escribirte ni llamarte, y no ven tu foto ni tu estado. " +
                            "No se les avisa cuando los bloqueas ni cuando los desbloqueas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (actual.isEmpty()) {
                    item { SinBloqueados() }
                }

                items(actual, key = { it.usuarioId }) { b ->
                    FilaBloqueado(
                        b = b,
                        ocupado = ocupado == b.usuarioId,
                        onDesbloquear = { desbloqueando = b },
                    )
                }

                if (actual.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Modo cerca: al bloquear cambia tu clave de cercanía. Quien " +
                                "desbloquees te vuelve a reconocer por Bluetooth cuando le " +
                                "llegue tu próximo mensaje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoTerciario,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }

    desbloqueando?.let { b ->
        AlertDialog(
            onDismissRequest = { desbloqueando = null },
            containerColor = BgSurface,
            title = { Text("¿Desbloquear a @${b.username}?", color = TextoPrimario) },
            text = {
                Text(
                    "Podrá volver a escribirte y llamarte, según lo que permita tu privacidad. " +
                        "No se le avisa.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    desbloqueando = null
                    ocupado = b.usuarioId
                    ambito.launch {
                        runCatching { app.repo.desbloquear(b.username) }
                            .onSuccess { lista = lista?.filterNot { it.usuarioId == b.usuarioId } }
                            .onFailure { aviso = "No se pudo desbloquear. Revisa la conexión e inténtalo de nuevo." }
                        ocupado = null
                    }
                }) { Text("Desbloquear", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { desbloqueando = null }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    aviso?.let {
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgSurface,
            text = { Text(it, color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) }
            },
        )
    }
}

@Composable
private fun FilaBloqueado(b: Bloqueado, ocupado: Boolean, onDesbloquear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(nombre = b.username, url = null, tamano = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("@${b.username}", color = TextoPrimario, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (b.desde > 0) {
                Text(
                    "Desde ${fechaLarga(b.desde)}",
                    color = TextoTerciario,
                    fontSize = 12.5.sp,
                )
            }
        }
        if (ocupado) {
            CircularProgressIndicator(color = Cian, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            OutlinedButton(
                onClick = onDesbloquear,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cian),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Desbloquear", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SinBloqueados() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Block, null, tint = Slate, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            "No has bloqueado a nadie",
            color = TextoPrimario,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Para bloquear a alguien, abre su chat o su perfil.",
            color = TextoSecundario,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}
