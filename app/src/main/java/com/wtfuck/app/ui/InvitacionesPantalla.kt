package com.wtfuck.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.InvitacionResp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BC · Los códigos de invitación, desde la app.
 *
 * ## Por qué hace falta una pantalla y no bastaba la API
 *
 * El módulo de invitaciones vivía entero en el servidor: se podían crear
 * códigos con `curl` y nada más. Eso sirve para probarlo, no para usarlo.
 * Quien administra un despliegue cerrado reparte códigos a diario y desde el
 * teléfono, normalmente mientras habla con la persona a la que va a invitar;
 * mandarlo a una terminal es garantizar que el servidor se quede abierto.
 *
 * ## Por qué el código se copia y se comparte desde aquí
 *
 * Porque el paso siguiente a crearlo es SIEMPRE mandárselo a alguien. Un
 * código de doce caracteres sin `0`, `O`, `1`, `I` ni `l` está pensado para
 * poder dictarse, pero dictarlo es el peor caso, no el normal.
 *
 * ## Lo que esta pantalla no hace
 *
 * No borra. Revocar marca la fila y la deja: borrarla se llevaría en cascada
 * el rastro de quién entró con ese código, que es justo lo que hace falta
 * conservar cuando se revoca algo. Ver `V41__invitaciones_de_registro.sql`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitacionesPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var lista by remember { mutableStateOf<List<InvitacionResp>>(emptyList()) }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var creando by remember { mutableStateOf(false) }
    // El último código creado se destaca arriba.
    //
    // En la lista, ordenada por fecha, el recién creado es la primera fila y se
    // confunde con las demás: se crea uno, se mira la pantalla y no está claro
    // cuál de los doce que hay es el de hace dos segundos. Aquí no hay duda.
    var recien by remember { mutableStateOf<String?>(null) }
    var confirmar by remember { mutableStateOf<InvitacionResp?>(null) }

    suspend fun recargar() {
        lista = runCatching { app.repo.listarInvitaciones() }.getOrElse {
            aviso = "No se pudo cargar la lista."
            emptyList()
        }
        cargando = false
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
                title = { Text("Invitaciones", color = TextoPrimario, maxLines = 1) },
                actions = {
                    IconButton(onClick = { ambito.launch { recargar() } }) {
                        Icon(Icons.Filled.Refresh, "Actualizar", tint = TextoSecundario)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creando = true },
                containerColor = Cian,
                contentColor = TextoSobreAcento,
            ) { Text("Nuevo código") }
        },
    ) { pad ->
        if (cargando) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 88.dp),
        ) {
            item { Encabezado(); Spacer(Modifier.height(14.dp)) }

            recien?.let { codigo ->
                item {
                    RecienCreado(codigo, ctx)
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (lista.isEmpty()) {
                item {
                    Text(
                        "Todavía no hay ninguno.",
                        color = TextoTerciario,
                        modifier = Modifier.padding(vertical = 30.dp),
                    )
                }
            }

            items(lista, key = { it.codigo }) { inv ->
                Fila(
                    inv = inv,
                    onCopiar = { copiar(ctx, inv.codigo); aviso = "Copiado." },
                    onRevocar = { confirmar = inv },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (creando) {
        DialogoNuevo(
            onCerrar = { creando = false },
            onCrear = { usos, dias, nota ->
                creando = false
                ambito.launch {
                    runCatching { app.repo.crearInvitacion(usos, dias, nota) }
                        .onSuccess { recien = it.codigo; recargar() }
                        // Un administrador recibe 404 si el servidor ya no lo
                        // tiene por tal. Decir "no se pudo" a secas mandaría a
                        // buscar un fallo de red que no existe.
                        .onFailure { aviso = "No se pudo crear. ¿Sigues siendo administrador?" }
                }
            },
        )
    }

    confirmar?.let { inv ->
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { confirmar = null },
            title = { Text("¿Revocar este código?", color = TextoPrimario) },
            text = {
                Text(
                    if (inv.usos > 0) {
                        "Dejará de servir para cuentas nuevas. Las ${inv.usos} que ya " +
                            "entraron con él se quedan como están: revocar no expulsa a nadie."
                    } else {
                        "Dejará de servir, aunque le queden usos. No se borra: queda " +
                            "marcado para poder ver después que existió."
                    },
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = inv.codigo
                    confirmar = null
                    ambito.launch {
                        runCatching { app.repo.revocarInvitacion(c) }
                            .onSuccess {
                                if (recien == c) recien = null
                                recargar()
                            }
                            .onFailure { aviso = "No se pudo revocar." }
                    }
                }) { Text("Revocar", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = null }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    aviso?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2600)
            aviso = null
        }
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp), Alignment.BottomCenter) {
            Surface(color = BgElev, shape = RoundedCornerShape(10.dp), shadowElevation = 6.dp) {
                Text(msg, color = TextoPrimario, modifier = Modifier.padding(14.dp, 10.dp))
            }
        }
    }
}

@Composable
private fun Encabezado() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    var cerrado by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { cerrado = app.repo.registroPideInvitacion() }

    Surface(
        color = if (cerrado == false) Ambar.copy(alpha = 0.12f) else BgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (cerrado) {
                // El caso que importa, y el que no se ve venir: se pueden crear
                // códigos con el registro ABIERTO. Funcionan, pero no hacen
                // falta —cualquiera entra sin ninguno— y quien los reparte se
                // queda creyendo que el servidor está cerrado. Se dice aquí
                // porque es el único sitio donde alguien lo va a leer.
                false -> {
                    Text("Este servidor está ABIERTO", color = Ambar, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cualquiera puede registrarse sin código. Los que crees aquí " +
                            "van a funcionar, pero no hacen falta.\n\n" +
                            "Para cerrarlo: WTFUCK_REGISTRO=invitacion en el servidor, y reiniciarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                }
                true -> {
                    Text("Este servidor es CERRADO", color = Cian, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sólo se entra con un código de estos. Reparte los de un uso " +
                            "para invitar a alguien concreto; los de varios, para un grupo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                }
                null -> Text("Comprobando…", color = TextoTerciario)
            }
        }
    }
}

@Composable
private fun RecienCreado(codigo: String, ctx: Context) {
    Surface(
        color = Cian.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("RECIÉN CREADO", color = Cian, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                codigo,
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextoPrimario,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = { compartir(ctx, codigo) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cian,
                        contentColor = TextoSobreAcento,
                    ),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enviar")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { copiar(ctx, codigo) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = TextoSecundario)
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar", color = TextoSecundario)
                }
            }
        }
    }
}

@Composable
private fun Fila(inv: InvitacionResp, onCopiar: () -> Unit, onRevocar: () -> Unit) {
    val agotado = inv.usos >= inv.usosMax
    val caducado = inv.expiraEn != 0L && inv.expiraEn < System.currentTimeMillis()
    val muerto = inv.revocada || agotado || caducado

    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !muerto, onClick = onCopiar),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    inv.codigo,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    // Un código muerto en el mismo color que uno vivo obliga a
                    // leer la línea de abajo para saber cuál es cuál.
                    color = if (muerto) TextoTerciario else TextoPrimario,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    estado(inv, agotado, caducado),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        inv.revocada -> Coral
                        agotado || caducado -> TextoTerciario
                        else -> TextoSecundario
                    },
                )
                if (inv.nota.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(inv.nota, style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
                }
            }
            // Revocar algo que ya no sirve no cambia nada y el servidor
            // contesta 404. Ofrecerlo sería ofrecer un botón que falla.
            if (!muerto) {
                TextButton(onClick = onRevocar) { Text("Revocar", color = Coral) }
            }
        }
    }
}

private val FECHA = SimpleDateFormat("d MMM", Locale("es"))

private fun estado(inv: InvitacionResp, agotado: Boolean, caducado: Boolean): String {
    val uso = "${inv.usos}/${inv.usosMax}"
    return when {
        inv.revocada -> "Revocado · usado $uso"
        caducado -> "Caducado · usado $uso"
        agotado -> "Agotado · usado $uso"
        inv.expiraEn == 0L -> "$uso · sin caducidad"
        else -> "$uso · hasta el ${FECHA.format(Date(inv.expiraEn))}"
    }
}

@Composable
private fun DialogoNuevo(onCerrar: () -> Unit, onCrear: (Int, Int, String) -> Unit) {
    // Los valores por defecto son los del caso normal: invitar a UNA persona,
    // con una semana para que lo use. Quien quiera otra cosa los cambia; quien
    // no, pulsa y ya.
    var usos by rememberSaveable { mutableStateOf("1") }
    var dias by rememberSaveable { mutableStateOf("7") }
    var nota by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        containerColor = BgElev,
        onDismissRequest = onCerrar,
        title = { Text("Nuevo código", color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = usos,
                    onValueChange = { usos = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Cuántas cuentas puede crear") },
                    supportingText = {
                        Text(
                            "1 para invitar a una persona.",
                            color = TextoTerciario,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = dias,
                    onValueChange = { dias = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Días hasta que caduque") },
                    supportingText = {
                        Text(
                            "0 = no caduca nunca.",
                            color = TextoTerciario,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it.take(120) },
                    label = { Text("Para quién es") },
                    supportingText = {
                        // Se dice que sólo lo ve el staff porque si no, nadie
                        // escribe nada aquí, y una lista de doce códigos sin
                        // nota es doce códigos indistinguibles.
                        Text(
                            "Sólo lo ve el staff. Sirve para saber después cuál era cuál.",
                            color = TextoTerciario,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCrear(usos.toIntOrNull() ?: 1, dias.toIntOrNull() ?: 0, nota.trim())
            }) { Text("Crear", color = Cian) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}

private fun copiar(ctx: Context, codigo: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("codigo", codigo))
}

/**
 * El código sale con instrucciones, no solo.
 *
 * Quien recibe doce letras sueltas por un chat no sabe qué son ni dónde se
 * ponen. El texto acompaña siempre; el trabajo de explicarlo no puede recaer
 * en quien invita, porque entonces cada uno lo explica distinto —y algunos no
 * lo explican—.
 */
private fun compartir(ctx: Context, codigo: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "Te invito a wtfuck. Tu código para crear la cuenta:\n\n$codigo\n\n" +
                "Se pone al registrarte, en el campo \"Código de invitación\".",
        )
    }
    ctx.startActivity(Intent.createChooser(i, "Enviar código"))
}
