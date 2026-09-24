package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.Comentario
import com.wtfuck.protocol.Publicacion
import kotlinx.coroutines.launch

/**
 * Módulo AA · Los comentarios de una publicación de canal.
 *
 * ## Por qué esta pantalla no existía, y por qué eso no era el problema
 *
 * La tarjeta del muro decía "1 comentario" y no había forma de abrirlo. Parecía
 * una pantalla que faltaba. **No lo era**: el texto de los comentarios no
 * estaba en ninguna parte.
 *
 * Un canal público no reparte sobres —es lo que le permite escalar a diez mil
 * suscriptores— y el comentario se mandaba como mensaje cifrado, así que no
 * tenía a quién entregarse. Quedaba la fila de metadatos en el servidor, el
 * contador subía, y el cuerpo se perdía. Medido en la base: cero sobres y cero
 * cuerpo guardado, en todos.
 *
 * O sea que el contador era honesto —cuenta metadatos— y **no había nada que
 * esta pantalla pudiera haber mostrado**. Primero hubo que darle un sitio al
 * texto (`V34`), y esto es lo que lo lee.
 *
 * ## Las dos clases de canal se leen de sitios distintos
 *
 * | Canal | De dónde salen | Qué se ve |
 * |---|---|---|
 * | público | del servidor, en claro | todos, incluso los de antes de suscribirse |
 * | privado | de este teléfono, descifrados | **solo los que llegaron aquí** |
 *
 * Y el segundo caso **lo dice en pantalla**, porque si no se lee como una
 * lista completa que está incompleta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaComentarios(
    conversacionId: String,
    publicacion: Publicacion,
    /** Un canal público guarda en el servidor; uno privado, solo aquí. */
    publico: Boolean,
    /** Si el canal los tiene encendidos. Apagados se leen pero no se escriben. */
    puedeComentar: Boolean,
    onCerrar: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    /** `null` = todavía no se pudo leer. Ver el módulo Z.5. */
    var remotos by remember { mutableStateOf<List<Comentario>?>(null) }
    var cargando by remember { mutableStateOf(publico) }
    var texto by remember { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val locales by app.repo
        .comentariosLocales(conversacionId, publicacion.mensajeId)
        .collectAsStateWithLifecycle(emptyList())

    suspend fun recargar() {
        if (!publico) return
        remotos = app.repo.comentarios(conversacionId, publicacion.mensajeId)
        cargando = false
    }
    LaunchedEffect(publicacion.mensajeId) { recargar() }

    ModalBottomSheet(
        // Entero: la hoja lleva una lista y un campo de texto, y a media altura
        // el campo queda tapado por el teclado. Ver el módulo W.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Comentarios",
                color = TextoPrimario,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "en la publicación de @${publicacion.autor}",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f, fill = false).heightIn(min = 120.dp, max = 400.dp)) {
                when {
                    cargando -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
                    }

                    // Un canal privado no consulta al servidor: sus comentarios
                    // son mensajes de este teléfono.
                    !publico -> if (locales.isEmpty()) {
                        AvisoVacio(
                            "Todavía nadie comentó esta publicación.",
                            "Es un canal privado: aquí solo aparecen los comentarios " +
                                "que llegaron a este teléfono cifrados. Puede haber más " +
                                "que este aparato nunca recibió.",
                        )
                    } else {
                        LazyColumn {
                            items(locales, key = { it.id }) { m ->
                                FilaComentario(
                                    autor = m.autor,
                                    cuerpo = m.texto,
                                    creadoEn = m.creadoEn,
                                    editado = false,
                                )
                            }
                            item {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Canal privado: solo se ven los comentarios que " +
                                        "llegaron a este teléfono.",
                                    color = TextoTerciario,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    remotos == null -> EstadoDeError(
                        titulo = "No se pudieron cargar",
                        detalle = "Esto NO quiere decir que la publicación no tenga " +
                            "comentarios.",
                        onReintentar = { cargando = true; ambito.launch { recargar() } },
                    )

                    remotos!!.isEmpty() -> AvisoVacio(
                        "Todavía nadie comentó esta publicación.",
                        if (puedeComentar) "Podés ser el primero." else null,
                    )

                    else -> LazyColumn {
                        items(remotos!!, key = { it.mensajeId }) { k ->
                            FilaComentario(
                                autor = k.autor,
                                cuerpo = k.cuerpo,
                                creadoEn = k.creadoEn,
                                editado = k.editado,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.25f))

            if (!puedeComentar) {
                // Los comentarios apagados **se leen y no se escriben**: quien
                // llegó por una notificación vieja tiene que poder leer lo que
                // ya se dijo, y el campo de texto no puede estar ahí
                // prometiendo algo que va a dar 403.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Lock, null,
                        tint = TextoTerciario, modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Este canal tiene los comentarios desactivados.",
                        color = TextoTerciario,
                        fontSize = 12.5.sp,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        placeholder = { Text("Escribí un comentario", color = TextoTerciario) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                            focusedTextColor = TextoPrimario,
                            unfocusedTextColor = TextoPrimario,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val t = texto.trim()
                            if (t.isEmpty() || enviando) return@FilledIconButton
                            enviando = true
                            ambito.launch {
                                app.repo.comentar(
                                    conversacionId, publicacion.mensajeId, t,
                                    canalPublico = publico,
                                )
                                    // Se limpia SOLO si salió bien. Borrarlo
                                    // antes de saberlo deja a la persona sin lo
                                    // que escribió y sin el comentario.
                                    .onSuccess { texto = ""; recargar() }
                                    .onFailure { aviso = it.message }
                                enviando = false
                            }
                        },
                        enabled = texto.isNotBlank() && !enviando,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Cian,
                            contentColor = TextoSobreAcento,
                        ),
                        modifier = Modifier.size(46.dp),
                    ) {
                        if (enviando) {
                            CircularProgressIndicator(
                                color = TextoSobreAcento,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "Comentar")
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("No se pudo comentar", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) }
            },
        )
    }
}

/** Un comentario. Una fila y no una burbuja: aquí no hay dos lados. */
@Composable
private fun FilaComentario(
    autor: String,
    cuerpo: String,
    creadoEn: Long,
    editado: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@$autor",
                color = colorDeNombre(autor),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(horaCorta(creadoEn), color = TextoTerciario, fontSize = 10.5.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(cuerpo, color = TextoPrimario, fontSize = 14.sp)
        if (editado) {
            Text("editado", color = TextoTerciario, fontSize = 10.sp)
        }
    }
}

/**
 * Un vacío que dice algo.
 *
 * El detalle es opcional porque no siempre hay algo honesto que agregar, y
 * rellenarlo con una frase de aliento sería ruido.
 */
@Composable
private fun AvisoVacio(titulo: String, detalle: String?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(titulo, color = TextoSecundario, fontSize = 13.5.sp)
        if (detalle != null) {
            Spacer(Modifier.height(6.dp))
            Text(detalle, color = TextoTerciario, fontSize = 11.5.sp)
        }
    }
}

/**
 * La fila de reacciones de una publicación.
 *
 * ## Lo que estaba roto, que no era la interfaz
 *
 * `Publicacion.reacciones` existía en el contrato desde el módulo F y **la
 * consulta del muro nunca las leía**. El campo tenía `= emptyList()` por
 * defecto, así que viajaba siempre vacío: no fallaba nada, simplemente no
 * había nunca ninguna reacción que dibujar, y el valor por defecto lo hacía
 * indistinguible de "esta publicación no tiene reacciones".
 *
 * Es el mismo error del módulo X con otra cara, esta vez en el servidor.
 *
 * ## Por qué los emojis rápidos están siempre a la vista
 *
 * En el chat, reaccionar es mantener pulsada la burbuja. En un canal la
 * reacción es **la única cosa que un suscriptor puede hacer** con una
 * publicación cuando los comentarios están apagados; esconderla detrás de un
 * gesto que hay que descubrir la deja sin usar.
 */
@Composable
fun FilaReacciones(
    p: Publicacion,
    habilitadas: Boolean,
    onReaccionar: (String, Boolean) -> Unit,
) {
    if (!habilitadas) return
    val mia = p.reacciones.firstOrNull { it.mia }?.emoji

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        REACCIONES_RAPIDAS.forEach { emoji ->
            val esta = p.reacciones.firstOrNull { it.emoji == emoji }
            val esMia = mia == emoji
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (esMia) Cian.copy(alpha = 0.22f) else BgElev)
                    // Tocar la mía la quita. Una reacción por persona: poner
                    // otra REEMPLAZA, y lo resuelve el servidor.
                    .clickable { onReaccionar(emoji, !esMia) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 13.sp)
                // El número solo aparece si hay alguno. Un "0" al lado de cada
                // emoji llena la fila de ceros.
                if (esta != null && esta.total > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${esta.total}",
                        color = if (esMia) Cian else TextoSecundario,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}
