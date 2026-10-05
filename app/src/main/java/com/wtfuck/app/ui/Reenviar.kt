package com.wtfuck.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/** La "Nota para mi" en la lista, exista o no todavia. */
private const val CLAVE_NOTA = "\u0000nota"

/**
 * Hasta cuantos chats a la vez. El mismo tope de WhatsApp, y por lo mismo: un
 * reenvio masivo es como corre una cadena falsa, y cinco alcanzan para todo
 * lo demas.
 */
private const val MAX_DESTINOS = 5

/**
 * "Reenviar a...": elegir a donde va un mensaje.
 *
 * La "Nota para mi" va primero y siempre, aunque todavia no exista: reenviarse
 * algo es la forma mas comun de guardarlo, y es para lo que sirve la nota.
 * Los canales aparecen solo si puedo publicar en ellos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaReenviar(mensaje: MensajeEnt, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())
    val ambito = rememberCoroutineScope()
    var filtro by remember { mutableStateOf("") }
    var elegidos by remember { mutableStateOf(listOf<String>()) }
    var enviando by remember { mutableStateOf(false) }

    val nota = chats.firstOrNull { it.tipo == "notas" }
    val candidatos = chats
        .filter { it.soyMiembro && it.tipo != "notas" && (it.tipo != "canal" || it.miJerarquia >= 50) }
        .filter { filtro.isBlank() || it.titulo.contains(filtro, true) || it.nombre.contains(filtro, true) }
    val muestraNota = filtro.isBlank() || "nota para mí".contains(filtro.trim(), true)

    fun alternar(id: String) {
        elegidos = when {
            id in elegidos -> elegidos - id
            elegidos.size >= MAX_DESTINOS -> {
                Toast.makeText(app, "Hasta $MAX_DESTINOS chats a la vez", Toast.LENGTH_SHORT).show()
                elegidos
            }
            else -> elegidos + id
        }
    }

    // Abierta del todo: a media altura el boton de reenviar quedaba debajo
    // del borde, y no habia forma de saber que habia que arrastrar.
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!enviando) onCerrar() },
        sheetState = hoja,
        containerColor = BgSurface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                "Reenviar a",
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                value = filtro,
                onValueChange = { filtro = it },
                placeholder = { Text("Buscar chat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                if (muestraNota) {
                    item(key = CLAVE_NOTA) {
                        FilaDestino(
                            titulo = "Nota para mí",
                            detalle = "solo tú",
                            url = null,
                            clase = ClaseDeChat.NOTAS,
                            marcado = CLAVE_NOTA in elegidos,
                        ) { alternar(CLAVE_NOTA) }
                    }
                }
                items(candidatos, key = { it.id }) { c ->
                    FilaDestino(
                        titulo = c.titulo,
                        detalle = when (c.tipo) {
                            "grupo" -> "grupo"
                            "canal" -> "canal"
                            else -> "@${c.nombre}"
                        },
                        url = ApiCliente.urlImagen(c.avatarUsername, "avatar", c.avatarVersion),
                        clase = claseDeTipo(c.tipo),
                        marcado = c.id in elegidos,
                    ) { alternar(c.id) }
                }
            }
            Button(
                enabled = elegidos.isNotEmpty() && !enviando,
                onClick = {
                    enviando = true
                    ambito.launch {
                        val r = runCatching {
                            for (d in elegidos) {
                                val id = if (d == CLAVE_NOTA) nota?.id ?: app.repo.abrirNotaParaMi() else d
                                app.repo.reenviar(mensaje, id)
                            }
                        }
                        enviando = false
                        r.onSuccess {
                            val a = if (elegidos.size == 1) {
                                if (elegidos[0] == CLAVE_NOTA) "tu nota"
                                else chats.firstOrNull { it.id == elegidos[0] }?.titulo ?: "1 chat"
                            } else "${elegidos.size} chats"
                            Toast.makeText(app, "Reenviado a $a", Toast.LENGTH_SHORT).show()
                            onCerrar()
                        }.onFailure {
                            Toast.makeText(app, it.message ?: "No se pudo reenviar.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    when {
                        enviando -> "Reenviando..."
                        elegidos.isEmpty() -> "Elige a dónde"
                        else -> "Reenviar (${elegidos.size})"
                    },
                )
            }
        }
    }
}

@Composable
private fun FilaDestino(
    titulo: String,
    detalle: String,
    url: String?,
    clase: ClaseDeChat,
    marcado: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = titulo
                stateDescription = if (marcado) "elegido" else "sin elegir"
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarDeChat(nombre = titulo, url = url, clase = clase, tamano = 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = TextoPrimario, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detalle, color = TextoTerciario, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (marcado) Cian else BgElev),
            contentAlignment = Alignment.Center,
        ) {
            if (marcado) Icon(Icons.Filled.Check, null, tint = TextoSobreAcento, modifier = Modifier.size(16.dp))
        }
    }
}
