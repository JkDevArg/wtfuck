package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.ConversacionEnt
import com.wtfuck.app.ui.theme.BgBase
import com.wtfuck.app.ui.theme.BgSurface
import com.wtfuck.app.ui.theme.Cian
import com.wtfuck.app.ui.theme.Coral
import com.wtfuck.app.ui.theme.Slate
import com.wtfuck.app.ui.theme.TextoPrimario
import com.wtfuck.app.ui.theme.TextoSecundario
import com.wtfuck.app.ui.theme.TextoTerciario
import com.wtfuck.protocol.PreferenciasChat
import com.wtfuck.protocol.UsuarioPublico
import kotlinx.coroutines.launch

/**
 * El perfil de una persona, desde su conversación.
 *
 * ## Qué se muestra y qué no
 *
 * La referencia obvia es el panel de contacto de Telegram, y la mayor parte
 * traduce directo. Dos cosas no:
 *
 *  - **No hay número de teléfono.** Esta app registra por username y no pide
 *    teléfono ni correo, así que esa fila no existe. En su lugar va lo que
 *    sí hay y además importa más: la **huella de cifrado**, que es el único
 *    dato con el que se puede comprobar que se está hablando con quien uno
 *    cree. Poner un hueco donde otra app pone un teléfono habría sido copiar
 *    la forma sin la sustancia.
 *  - **No hay id numérico.** La identidad acá es el `@username`, y agregar un
 *    número interno sólo daría algo que copiar y pegar mal.
 *
 * ## Los recuentos salen de este teléfono
 *
 * "93 fotos" no se le pregunta a nadie: el servidor es un buzón tonto que no
 * guarda el historial, así que **no sabe** cuántas fotos se mandaron en un
 * chat. Se cuentan sobre la base local. Es la propiedad central del producto
 * vista desde el otro lado — la función no le pide nada al servidor porque el
 * servidor no tiene el dato.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPantalla(
    conversacionId: String,
    onAtras: () -> Unit,
    onMensaje: () -> Unit,
    onVerificarCifrado: () -> Unit,
    onCompartido: (String) -> Unit,
    onDenunciar: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current

    var chat by remember { mutableStateOf<ConversacionEnt?>(null) }
    var perfil by remember { mutableStateOf<UsuarioPublico?>(null) }
    var resumen by remember { mutableStateOf<Map<String, Int>?>(null) }
    var enLibreta by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf("") }
    var editandoAlias by remember { mutableStateOf(false) }
    var confirmando by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    // La llamada la arranca ESTA pantalla y no quien la abrio: el router no
    // tiene por que saber de llamadas, y este es el mismo ayudante que usa la
    // cabecera del chat, asi que los dos botones hacen exactamente lo mismo.
    val llamar = recordarInicioLlamada { aviso = it }

    // La conversación y el recuento salen de la base y están al instante. El
    // perfil viene de la red y puede tardar o no llegar: por eso son tres
    // estados y no uno. Esperar a los tres para pintar algo dejaría la
    // pantalla en blanco por culpa de lo único que no es imprescindible.
    LaunchedEffect(conversacionId) {
        chat = runCatching { app.repo.conversacion(conversacionId) }.getOrNull()
        resumen = runCatching { app.repo.compartidoResumen(conversacionId) }.getOrElse { emptyMap() }
        val user = chat?.nombre.orEmpty()
        if (user.isNotBlank()) {
            perfil = app.repo.perfilDe(user)
            val libreta = runCatching { app.repo.contactos() }.getOrElse { emptyList() }
            val c = libreta.firstOrNull { it.username.equals(user, ignoreCase = true) }
            enLibreta = c != null
            alias = c?.alias.orEmpty()
        }
    }

    val c = chat
    val usuario = c?.nombre.orEmpty()
    val silenciado = c != null &&
        (c.silenciadoHasta == -1L || c.silenciadoHasta > System.currentTimeMillis())

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
                title = { Text("Perfil", color = TextoPrimario) },
            )
        },
    ) { pad ->
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            // ---------------------------------------------------- cabecera
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarDeChat(
                    nombre = c.titulo(alias),
                    url = ApiCliente.urlImagen(c.avatarUsername, "avatar", c.avatarVersion),
                    clase = ClaseDeChat.DIRECTA,
                    tamano = 96.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    c.titulo(alias),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextoPrimario,
                )
                // La presencia sale del perfil y no se inventa: sin dato no se
                // escribe nada. Es la misma regla que en la cabecera del chat,
                // y viene del defecto que arregló L.1 — "conectado" por
                // defecto era mentira la mayor parte del tiempo.
                val p = perfil
                if (p != null) {
                    val sub = presencia(p.enLinea, p.ultimaVez)
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sub == "en linea") Cian else TextoTerciario,
                        )
                    }
                }
            }

            // ------------------------------------------------- acciones
            //
            // Cuatro, en una fila, con el icono arriba del texto. Son las que
            // se usan; todo lo demás está más abajo, donde no compite.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccionRapida("Mensaje", Icons.AutoMirrored.Filled.Chat, Modifier.weight(1f), onMensaje)
                AccionRapida("Llamar", Icons.Filled.Phone, Modifier.weight(1f)) {
                    llamar(conversacionId, c.titulo(alias), false)
                }
                AccionRapida("Video", Icons.Filled.Videocam, Modifier.weight(1f)) {
                    llamar(conversacionId, c.titulo(alias), true)
                }
                AccionRapida(
                    if (silenciado) "Activar" else "Silenciar",
                    if (silenciado) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                    Modifier.weight(1f),
                ) {
                    ambito.launch {
                        runCatching {
                            app.repo.preferencias(
                                conversacionId,
                                PreferenciasChat(silenciarMinutos = if (silenciado) 0 else -1),
                            )
                            chat = app.repo.conversacion(conversacionId)
                        }.onFailure { aviso = it.message }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Slate.copy(alpha = 0.25f))

            // ------------------------------------------------- los datos
            FilaDato(
                Icons.Filled.AlternateEmail,
                "@$usuario",
                "Nombre de usuario",
                accion = {
                    IconButton(onClick = {
                        portapapeles.setText(AnnotatedString("@$usuario"))
                        aviso = "Copiado"
                    }) {
                        Icon(Icons.Filled.ContentCopy, "Copiar", tint = TextoTerciario, modifier = Modifier.size(18.dp))
                    }
                },
            )

            // La ficha de empresa, si esa cuenta la tiene.
            //
            // Antes vivia en un dialogo del chat y se pedia con `chat.titulo`
            // —que puede ser el alias que YO le puse—, asi que no aparecia
            // nunca para un contacto renombrado. Aqui se pide con el username,
            // que es lo que el servidor conoce.
            perfil?.empresa?.let { TarjetaEmpresa(it) }

            val bio = perfil?.estadoTexto.orEmpty()
            if (bio.isNotBlank()) {
                FilaDato(Icons.Filled.Info, bio, "Biografía")
            }

            // Donde otra app pone el teléfono. Acá el dato de identidad es la
            // huella, y es el único con el que se puede comprobar de verdad
            // con quién se está hablando.
            FilaDato(
                Icons.Filled.Shield,
                "Verificar el cifrado",
                "Comparen la huella y sabrán que nadie está en el medio",
                onClick = onVerificarCifrado,
            )

            HorizontalDivider(color = Slate.copy(alpha = 0.25f))

            // --------------------------------------------- lo compartido
            val r = resumen
            if (r != null && r.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                // Orden FIJO y no por cantidad. Ordenar por cuantos hay
                // hace que la lista se reacomode sola a medida que se usa la
                // app: la fila de las fotos aparecería hoy tercera y mañana
                // primera, y nunca se aprendería dónde está nada.
                for ((clase, cuantos) in ordenCompartido(r)) {
                    FilaCompartido(clase, cuantos) { onCompartido(clase) }
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
            }

            // ------------------------------------------------- contacto
            FilaDato(Icons.Filled.Share, "Compartir este contacto", null, onClick = {
                confirmando = "compartir"
            })

            if (enLibreta) {
                FilaDato(
                    Icons.Filled.Edit,
                    if (alias.isBlank()) "Ponerle un nombre" else "Lo llamás \"$alias\"",
                    "Sólo lo ves vos",
                    onClick = { editandoAlias = true },
                )
                FilaDato(
                    Icons.Filled.PersonRemove,
                    "Quitar de contactos",
                    null,
                    color = Coral,
                    onClick = { confirmando = "quitar" },
                )
            } else {
                FilaDato(
                    Icons.Filled.PersonAddAlt,
                    "Agregar a contactos",
                    null,
                    onClick = {
                        ambito.launch {
                            app.repo.guardarContacto(usuario)
                                .onSuccess { enLibreta = true }
                                .onFailure { aviso = it.message }
                        }
                    },
                )
            }

            FilaDato(
                Icons.Filled.Flag,
                "Denunciar a @$usuario",
                null,
                color = Coral,
                onClick = onDenunciar,
            )
            FilaDato(
                Icons.Filled.Block,
                "Bloquear a @$usuario",
                null,
                color = Coral,
                onClick = { confirmando = "bloquear" },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    // ------------------------------------------------------- diálogos
    if (editandoAlias) {
        var borrador by remember { mutableStateOf(alias) }
        AlertDialog(
            onDismissRequest = { editandoAlias = false },
            containerColor = BgSurface,
            title = { Text("Cómo lo llamás", color = TextoPrimario) },
            text = {
                Column {
                    Text(
                        "Este nombre no le llega a nadie: sólo cambia cómo lo ves vos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = borrador,
                        onValueChange = { borrador = it.take(48) },
                        singleLine = true,
                        placeholder = { Text("@$usuario", color = TextoTerciario) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = borrador.trim()
                    editandoAlias = false
                    ambito.launch {
                        app.repo.guardarContacto(usuario, alias = v)
                            .onSuccess {
                                alias = v
                                chat = app.repo.conversacion(conversacionId)
                            }
                            .onFailure { aviso = it.message }
                    }
                }) { Text("Guardar", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { editandoAlias = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    confirmando?.let { que ->
        val (titulo, cuerpo, etiqueta) = when (que) {
            "bloquear" -> Triple(
                "¿Bloquear a @$usuario?",
                "No va a poder escribirte ni llamarte, y no se le avisa.",
                "Bloquear",
            )
            "quitar" -> Triple(
                "¿Quitarlo de contactos?",
                "El chat y los mensajes se quedan como están. Se pierde el nombre que le pusiste.",
                "Quitar",
            )
            else -> Triple(
                "¿Compartir este contacto?",
                "Se manda su @$usuario a otro chat, para que puedan escribirle.",
                "Elegir chat",
            )
        }
        AlertDialog(
            onDismissRequest = { confirmando = null },
            containerColor = BgSurface,
            title = { Text(titulo, color = TextoPrimario) },
            text = { Text(cuerpo, color = TextoSecundario) },
            confirmButton = {
                TextButton(onClick = {
                    val accion = que
                    confirmando = null
                    ambito.launch {
                        when (accion) {
                            "bloquear" -> runCatching { app.repo.bloquear(usuario) }
                                .onSuccess { onAtras() }
                                .onFailure { aviso = it.message }
                            "quitar" -> app.repo.borrarContacto(usuario)
                                .onSuccess { enLibreta = false; alias = "" }
                                .onFailure { aviso = it.message }
                            else -> aviso = "Elegí el chat desde el botón de adjuntar"
                        }
                    }
                }) { Text(etiqueta, color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmando = null }) {
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

/**
 * El nombre que corresponde: el que le puse, el que eligió, o su username.
 *
 * El alias va PRIMERO y por eso entra por parámetro: no vive en la
 * conversación sino en la libreta. Sin él, esta pantalla decía "tatiana"
 * mientras la cabecera del chat de la que se venía decía "Tati" — dos nombres
 * para la misma persona en dos pantallas seguidas, que es justo lo que hace
 * dudar de haber abierto lo que se quería abrir.
 */
private fun ConversacionEnt.titulo(alias: String = ""): String =
    alias.ifBlank { nombreMostrado }.ifBlank { nombre }

@Composable
private fun AccionRapida(
    etiqueta: String,
    icono: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icono, null, tint = Cian, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(etiqueta, style = MaterialTheme.typography.labelSmall, color = TextoSecundario)
    }
}

/**
 * Una fila de dato.
 *
 * `onClick` nulo la deja **sin** efecto de toque, y eso importa: una fila que
 * se ilumina al tocarla y no hace nada enseña a desconfiar de las que sí
 * hacen algo.
 */
@Composable
private fun FilaDato(
    icono: ImageVector,
    titulo: String,
    detalle: String?,
    color: androidx.compose.ui.graphics.Color = TextoPrimario,
    onClick: (() -> Unit)? = null,
    accion: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = if (color == TextoPrimario) Cian else color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = color)
            if (detalle != null) {
                Text(detalle, style = MaterialTheme.typography.bodySmall, color = TextoTerciario)
            }
        }
        accion?.invoke()
    }
}
