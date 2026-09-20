package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.CodigoVinculacion
import com.wtfuck.protocol.DispositivoInfo
import kotlinx.coroutines.launch

/**
 * Mis dispositivos.
 *
 * ## Lo que esta pantalla tiene que dejar claro
 *
 * Que cada dispositivo es **una copia mas de tus mensajes**. No es una lista de
 * aparatos por gusto administrativo: es la lista de sitios donde tus
 * conversaciones estan descifradas. Un aparato que no reconoces ahí es el
 * indicio mas directo de que alguien tiene tu cuenta.
 *
 * ## Por que solo el principal autoriza
 *
 * Si cualquiera pudiera, robar un secundario alcanzaria para vincular mas, y la
 * cuenta no se podria recuperar nunca: cada revocacion se contestaria con una
 * vinculacion nueva. Con un solo autorizador, para meter un aparato hay que
 * tener en la mano el que ya esta dentro.
 *
 * ## Y por que el codigo se genera aqui y no en el aparato nuevo
 *
 * Al reves -el nuevo genera y pide aprobacion- es el patron exacto de la
 * estafa de WhatsApp Web: el atacante manda su codigo y convence a la victima
 * de aprobarlo. Aqui no hay nada que aprobar a distancia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispositivosPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var lista by remember { mutableStateOf<List<DispositivoInfo>>(emptyList()) }
    var cargando by remember { mutableStateOf(true) }
    var codigo by remember { mutableStateOf<CodigoVinculacion?>(null) }
    var pidiendoClave by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        lista = app.repo.dispositivos()
        cargando = false
    }
    LaunchedEffect(Unit) { recargar() }

    val esteEsPrincipal = lista.any { it.esEste && it.principal }

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
                title = { Text("Mis dispositivos", color = TextoPrimario) },
            )
        },
    ) { pad ->
        if (cargando) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "Cada dispositivo es una copia mas de tus mensajes. Si ves uno que no " +
                    "reconoces, revocalo y cambia tu contrasena.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(16.dp))

            lista.forEach { d ->
                FilaDispositivo(
                    d = d,
                    puedeGestionar = esteEsPrincipal,
                    onRevocar = {
                        ambito.launch {
                            app.repo.revocarDispositivo(d.id)
                                .onSuccess { aviso = "\"${d.etiqueta}\" ya no tiene acceso."; recargar() }
                                .onFailure { aviso = it.message }
                        }
                    },
                    onPromover = { pidiendoClave = d.id },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Slate.copy(alpha = 0.25f))
            Spacer(Modifier.height(20.dp))

            // Pedir historial otra vez.
            //
            // Hace falta porque la sincronizacion al vincular es un tiro unico
            // y depende de que OTRO dispositivo este encendido en ese momento.
            // Si no lo estaba, sin este boton no hay forma de reintentar y el
            // aparato se queda vacio para siempre sin que nada lo explique.
            if (!esteEsPrincipal) {
                Text(
                    "Historial",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "El servidor no guarda tus mensajes, asi que el historial solo puede " +
                        "llegar desde otro de tus dispositivos, y solo si esta encendido.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        ambito.launch {
                            val h = app.repo.pedirHistorial()
                            aviso = when {
                                h == null -> "No se pudo pedir el historial."
                                !h.hayQuienResponda ->
                                    "Ninguno de tus otros dispositivos esta conectado. " +
                                        "Abre la app en el otro y vuelve a intentar."
                                else ->
                                    "Pedido enviado. Los mensajes van llegando en unos " +
                                        "segundos."
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cian),
                ) { Text("Pedir historial a mis otros dispositivos") }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Slate.copy(alpha = 0.25f))
                Spacer(Modifier.height(20.dp))
            }

            if (esteEsPrincipal) {
                Text(
                    "Agregar otro dispositivo",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Se genera un codigo aqui y lo escribes en el aparato nuevo. Vive cinco " +
                        "minutos y sirve una sola vez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { pidiendoClave = "codigo" },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                ) {
                    Icon(Icons.Filled.AddLink, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generar codigo")
                }
            } else {
                Surface(
                    color = Ambar.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Ambar, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // Se dice por que, no solo que no se puede.
                            "Solo el dispositivo principal puede agregar o revocar otros. Es lo " +
                                "que impide que robar este aparato alcance para meter mas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoSecundario,
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    codigo?.let { c ->
        DialogoCodigo(c) { codigo = null; ambito.launch { recargar() } }
    }

    pidiendoClave?.let { que ->
        DialogoClaveDispositivo(
            titulo = if (que == "codigo") "Generar codigo" else "Hacer principal",
            explicacion = if (que == "codigo") {
                "Pedimos la contrasena porque vincular un dispositivo da acceso a todo lo " +
                    "que llegue desde ahora. Una sesion robada no deberia poder hacerlo."
            } else {
                "El principal es el unico que autoriza dispositivos nuevos. Cambiarlo es una " +
                    "decision de la cuenta, no de este aparato."
            },
            onCerrar = { pidiendoClave = null },
            onConfirmar = { clave ->
                ambito.launch {
                    if (que == "codigo") {
                        app.repo.emitirCodigoVinculacion(clave)
                            .onSuccess { pidiendoClave = null; codigo = it }
                            .onFailure { pidiendoClave = null; aviso = it.message }
                    } else {
                        app.repo.promoverDispositivo(que, clave)
                            .onSuccess { pidiendoClave = null; aviso = "Listo. Ese es el principal."; recargar() }
                            .onFailure { pidiendoClave = null; aviso = it.message }
                    }
                }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("Dispositivos", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Cerrar", color = Cian) } },
        )
    }
}

@Composable
private fun FilaDispositivo(
    d: DispositivoInfo,
    puedeGestionar: Boolean,
    onRevocar: () -> Unit,
    onPromover: () -> Unit,
) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, null, tint = if (d.esEste) Cian else Slate, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    d.etiqueta,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextoPrimario,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                if (d.principal) {
                    Icon(Icons.Filled.Star, "Principal", tint = Ambar, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.weight(1f))
                if (d.esEste) {
                    Text("este", style = MaterialTheme.typography.labelSmall, color = Cian)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(if (d.principal) "Principal" else "Secundario")
                    append("  ·  desde ${fechaLarga(d.registradoEn)}")
                    d.ultimoVistoEn?.let { append("  ·  visto ${fechaLarga(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )
            d.vinculadoPor?.let {
                Text(
                    "Autorizado desde \"$it\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
            Text(
                if (d.nivelHardware == "SOFTWARE_DEV") "Sin enclave seguro (desarrollo)"
                else "Hardware verificado (${d.nivelHardware})",
                style = MaterialTheme.typography.labelSmall,
                color = if (d.nivelHardware == "SOFTWARE_DEV") Ambar else TextoTerciario,
            )

            if (!d.tieneClaves) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, tint = Ambar, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        // Explica un estado que si no se ve parece un fallo:
                        // un aparato vinculado que no recibe mensajes.
                        "Todavia no publico sus claves: no puede recibir mensajes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ambar,
                    )
                }
            }

            if (puedeGestionar && !d.esEste) {
                Spacer(Modifier.height(6.dp))
                Row {
                    if (!d.principal) {
                        TextButton(onClick = onPromover, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("Hacer principal", color = Cian, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onRevocar, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text("Revocar", color = Coral, fontSize = 13.sp)
                    }
                }
            } else if (puedeGestionar && d.esEste && d.principal) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // El motivo, no solo la ausencia del boton.
                    "No se puede revocar a si mismo: la cuenta quedaria sin quien autorice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        }
    }
}

/**
 * El codigo, una sola vez.
 *
 * Se muestra grande y con espacio en el medio porque se va a copiar leyendolo
 * de esta pantalla a otra. En la base solo esta su hash: si se cierra el
 * dialogo, se pide otro.
 */
@Composable
private fun DialogoCodigo(c: CodigoVinculacion, onCerrar: () -> Unit) {
    val portapapeles = LocalClipboardManager.current
    var copiado by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text("Codigo de vinculacion", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Escanealo desde el aparato nuevo, en \"Vincular a una cuenta\". " +
                        "Tambien se puede escribir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(14.dp))

                // J.6. El QR lleva EXACTAMENTE el mismo codigo: misma vida de
                // cinco minutos, mismos cinco intentos. No cambia la
                // seguridad, cambia la tasa de error al teclear ocho
                // caracteres mirando otra pantalla.
                CodigoQr(c.codigo, tamano = 170.dp)

                Spacer(Modifier.height(14.dp))
                Surface(color = BgSurface, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        c.codigo,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Cian,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    portapapeles.setText(AnnotatedString(c.codigo))
                    copiado = true
                }) { Text(if (copiado) "Copiado" else "Copiar", color = Cian) }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Vive ${c.expiraEnSegundos / 60} minutos y sirve una sola vez. " +
                        "No se puede volver a mostrar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Listo", color = Cian) } },
    )
}

@Composable
private fun DialogoClaveDispositivo(
    titulo: String,
    explicacion: String,
    onCerrar: () -> Unit,
    onConfirmar: (String) -> Unit,
) {
    var clave by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(titulo, color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column {
                Text(explicacion, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    label = { Text("Tu contrasena") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = clave.isNotBlank(), onClick = { onConfirmar(clave) }) {
                Text("Continuar", color = Cian)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
}
