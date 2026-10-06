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
 * "Reenviar a...": elegir a donde va un mensaje. Ver [ElegirChats].
 */
@Composable
fun HojaReenviar(mensajes: List<MensajeEnt>, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    // En el orden en que se dijeron: reenviar una conversacion desordenada
    // la vuelve otra conversacion.
    val enOrden = remember(mensajes) { mensajes.sortedBy { it.creadoEn } }
    ElegirChats(
        titulo = if (enOrden.size == 1) "Reenviar a" else "Reenviar ${enOrden.size} mensajes a",
        verbo = "Reenviar",
        hecho = "Reenviado",
        onCerrar = onCerrar,
        accion = { destino -> enOrden.forEach { app.repo.reenviar(it, destino) } },
    )
}

/**
 * "Enviar a...": lo que llego desde otra app con "Compartir". Ver [ElegirChats]
 * y `Compartido`. Si se eligio un solo chat, se abre: es lo que espera quien
 * acaba de mandar algo.
 */
@Composable
fun HojaCompartir(
    compartido: com.wtfuck.app.Compartido,
    /** El chat que se eligio en la fila de compartir del sistema: ya viene marcado. */
    preseleccion: String? = null,
    onCerrar: () -> Unit,
    onAbrirChat: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    ElegirChats(
        titulo = "Enviar a",
        verbo = "Enviar",
        hecho = "Enviado",
        onCerrar = onCerrar,
        accion = { destino -> app.repo.enviarCompartido(destino, compartido.texto, compartido.archivos) },
        onListo = { ids -> if (ids.size == 1) onAbrirChat(ids[0]) },
        preseleccion = preseleccion,
        resumen = buildList {
            if (!compartido.texto.isNullOrBlank()) add("texto")
            if (compartido.archivos.isNotEmpty()) {
                add(if (compartido.archivos.size == 1) "1 archivo" else "${compartido.archivos.size} archivos")
            }
        }.joinToString(" y "),
    )
}

/**
 * Elegir uno o varios chats y hacer algo con cada uno.
 *
 * La "Nota para mi" va primero y siempre, aunque todavia no exista: reenviarse
 * o mandarse algo es la forma mas comun de guardarlo, y es para lo que sirve
 * la nota. Los canales aparecen solo si puedo publicar en ellos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElegirChats(
    titulo: String,
    verbo: String,
    hecho: String,
    onCerrar: () -> Unit,
    accion: suspend (String) -> Unit,
    onListo: (List<String>) -> Unit = {},
    /** Un chat ya marcado al abrir. */
    preseleccion: String? = null,
    /** Que se va a mandar, en pocas palabras ("texto y 2 archivos"). */
    resumen: String = "",
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val chats by app.repo.todasLasConversaciones.collectAsStateWithLifecycle(emptyList())
    val ambito = rememberCoroutineScope()
    var filtro by remember { mutableStateOf("") }
    var elegidos by remember { mutableStateOf(listOfNotNull(preseleccion)) }
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

    // Abierta del todo: a media altura el boton quedaba debajo del borde, y no
    // habia forma de saber que habia que arrastrar.
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!enviando) onCerrar() },
        sheetState = hoja,
        containerColor = BgSurface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                titulo,
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (resumen.isNotBlank()) {
                Text(
                    resumen.replaceFirstChar { it.uppercase() },
                    color = TextoTerciario,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
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
                        val ids = mutableListOf<String>()
                        val r = runCatching {
                            for (d in elegidos) {
                                val id = if (d == CLAVE_NOTA) nota?.id ?: app.repo.abrirNotaParaMi() else d
                                accion(id)
                                ids += id
                            }
                        }
                        enviando = false
                        r.onSuccess {
                            val a = if (elegidos.size == 1) {
                                if (elegidos[0] == CLAVE_NOTA) "tu nota"
                                else chats.firstOrNull { it.id == elegidos[0] }?.titulo ?: "1 chat"
                            } else "${elegidos.size} chats"
                            Toast.makeText(app, "$hecho a $a", Toast.LENGTH_SHORT).show()
                            onCerrar()
                            onListo(ids)
                        }.onFailure {
                            Toast.makeText(app, it.message ?: "No se pudo.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    when {
                        enviando -> "$verbo..."
                        elegidos.isEmpty() -> "Elige a dónde"
                        else -> "$verbo (${elegidos.size})"
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
