package com.wtfuck.app.ui

import com.wtfuck.protocol.CodigoRecuperacion
import com.wtfuck.app.datos.CopiaAutomatica
import androidx.compose.material.icons.filled.Autorenew
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.CopiaSeguridad
import com.wtfuck.app.datos.Repositorio
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BE · Copia de seguridad cifrada de los chats.
 *
 * ## Por que esta pantalla existe
 *
 * El historial vive solo en el telefono. Sin esto, perderlo es perderlo todo.
 * Aqui se exporta un archivo cifrado con una frase que la persona elige y
 * guarda donde quiera, y se restaura en un telefono nuevo.
 *
 * ## La advertencia que no se puede omitir
 *
 * La frase NO se guarda en ningun lado -ese es el punto: ni el servidor ni
 * nosotros podemos abrir la copia-. Si se olvida, el archivo es inservible. Por
 * eso se pide dos veces y se avisa con todas las letras antes de exportar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopiaSeguridadPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var pidiendoFraseExport by remember { mutableStateOf(false) }
    var pidiendoFraseImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var ultimaCopia by remember { mutableStateOf(app.ajustes.ultimaCopia) }

    // La frase se guarda entre "pedir frase" y "elegir donde guardar": el
    // selector de archivo devuelve una URI, y recien entonces se escribe la
    // copia -en streaming, para que los adjuntos no pasen por memoria-.
    var fraseExport by remember { mutableStateOf<CharArray?>(null) }
    var codigoExport by remember { mutableStateOf<String?>(null) }

    val guardarArchivo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val frase = fraseExport
        val conIdentidad = codigoExport != null
        fraseExport = null
        if (uri == null || frase == null) { trabajando = false; return@rememberLauncherForActivityResult }
        ambito.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        app.repo.exportarCopiaA(it, frase, codigoExport)
                    } ?: error("sin flujo")
                }.isSuccess
            }
            trabajando = false
            codigoExport = null
            if (ok) app.ajustes.ultimaCopia = System.currentTimeMillis()
            ultimaCopia = app.ajustes.ultimaCopia
            // Se dice si lleva la identidad o no, porque cambia para que sirve
            // la copia: sin ella se recuperan los mensajes, pero al restaurar
            // en otro telefono a los contactos les saltara el aviso de que tu
            // clave cambio. Es mejor saberlo ahora que descubrirlo entonces.
            aviso = when {
                !ok -> "No se pudo escribir el archivo."
                conIdentidad ->
                    "Copia guardada, con tu identidad dentro. Al restaurarla en " +
                        "otro teléfono conservarás tu número de seguridad."
                else ->
                    "Copia guardada. No lleva tu identidad: si la restauras en " +
                        "otro teléfono, a tus contactos les avisará de que tu " +
                        "clave cambió."
            }
        }
    }

    val elegirArchivo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pidiendoFraseImport = uri }

    // --- copia automatica ---------------------------------------------
    val cfg = remember { CopiaAutomatica.Config(ctx) }
    var activa by remember { mutableStateOf(cfg.activa) }
    var carpeta by remember { mutableStateOf<String?>(null) }
    var frecuencia by remember { mutableStateOf(cfg.frecuencia) }
    var conAdjuntos by remember { mutableStateOf(cfg.conAdjuntos) }
    var ultimaAuto by remember { mutableStateOf(cfg.ultimaHecha) }
    var errorAuto by remember { mutableStateOf(cfg.ultimoError) }
    var conIdentidadAuto by remember { mutableStateOf(cfg.llevaIdentidad) }
    val enCurso by remember { CopiaAutomatica.enCurso(ctx) }.collectAsState(initial = false)
    var activando by remember { mutableStateOf(false) }
    var cambiandoFrase by remember { mutableStateOf(false) }
    var desactivando by remember { mutableStateOf(false) }
    // Entre "pedir frase" y "elegir carpeta", igual que en la manual.
    var fraseAuto by remember { mutableStateOf<CharArray?>(null) }
    var codigoAuto by remember { mutableStateOf<String?>(null) }

    fun refrescar() {
        activa = cfg.activa
        carpeta = cfg.carpeta?.let { CopiaAutomatica.nombreCarpeta(ctx.contentResolver, android.net.Uri.parse(it)) }
        frecuencia = cfg.frecuencia
        conAdjuntos = cfg.conAdjuntos
        ultimaAuto = cfg.ultimaHecha
        errorAuto = cfg.ultimoError
        conIdentidadAuto = cfg.llevaIdentidad
        ultimaCopia = app.ajustes.ultimaCopia
    }
    LaunchedEffect(enCurso) { if (!enCurso) refrescar() }

    val elegirCarpeta = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val frase = fraseAuto
        fraseAuto = null
        if (uri == null) { codigoAuto = null; return@rememberLauncherForActivityResult }
        // Persistente: sin esto el permiso muere con la pantalla, y la copia
        // de manana no podria escribir.
        val banderas = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, banderas) }
        // La carpeta anterior, si se esta cambiando, ya no se necesita.
        cfg.carpeta?.takeIf { it != uri.toString() }?.let { vieja ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(vieja), banderas) }
        }
        cfg.carpeta = uri.toString()
        cfg.ultimoError = null
        if (frase != null) {
            cfg.guardarFrase(frase)
            frase.fill('\u0000')
            cfg.guardarClaveIdentidad(
                codigoAuto?.let { CodigoRecuperacion.normalizar(it) }
                    ?.let { CodigoRecuperacion.claveDeIdentidad(it) }
            )
            codigoAuto = null
        }
        CopiaAutomatica.programar(ctx, cfg.frecuencia)
        CopiaAutomatica.ahora(ctx)
        refrescar()
    }

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
                title = { Text("Copia de seguridad", color = TextoPrimario, maxLines = 1) },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                "Tus chats viven solo en este teléfono. Si lo pierdes, se pierden. " +
                    "Una copia cifrada te deja recuperarlos en otro teléfono.",
                color = TextoSecundario, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            // Cuándo fue la última copia. Ámbar cuando nunca o hace mucho: la
            // copia es manual, y sin este recordatorio se olvida hasta que ya es
            // tarde. No molesta con un banner global; vive donde se actúa.
            val dias = if (ultimaCopia == 0L) -1
                       else ((System.currentTimeMillis() - ultimaCopia) / 86_400_000L).toInt()
            val vieja = dias < 0 || dias >= 30
            Surface(
                color = (if (vieja) Ambar else Cian).copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        dias < 0 -> "Nunca has hecho una copia. Es buen momento para la primera."
                        dias == 0 -> "Última copia: hoy."
                        dias == 1 -> "Última copia: ayer."
                        dias < 30 -> "Última copia: hace $dias días."
                        else -> "Última copia: hace $dias días. Conviene hacer una nueva."
                    },
                    color = if (vieja) Ambar else TextoSecundario,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))

            Tarjeta(
                icono = Icons.Filled.Download,
                titulo = "Crear una copia",
                detalle = "Guarda tus chats en un archivo cifrado con una frase.",
                onClick = { if (!trabajando) pidiendoFraseExport = true },
                habilitado = !trabajando,
            )
            Spacer(Modifier.height(12.dp))
            Tarjeta(
                icono = Icons.Filled.Upload,
                titulo = "Restaurar una copia",
                detalle = "Recupera tus chats desde un archivo que hayas guardado.",
                onClick = { if (!trabajando) elegirArchivo.launch(arrayOf("application/octet-stream", "*/*")) },
                habilitado = !trabajando,
            )

            Spacer(Modifier.height(24.dp))
            SeccionCopiaAutomatica(
                activa = activa,
                carpeta = carpeta,
                frecuencia = frecuencia,
                conAdjuntos = conAdjuntos,
                ultima = ultimaAuto,
                error = errorAuto,
                conIdentidad = conIdentidadAuto,
                enCurso = enCurso,
                onActivar = { activando = true },
                onFrecuencia = { f ->
                    cfg.frecuencia = f
                    CopiaAutomatica.programar(ctx, f)
                    frecuencia = f
                },
                onAdjuntos = { v -> cfg.conAdjuntos = v; conAdjuntos = v },
                onAhora = { CopiaAutomatica.ahora(ctx) },
                onCambiarCarpeta = { elegirCarpeta.launch(null) },
                onCambiarFrase = { cambiandoFrase = true },
                onDesactivar = { desactivando = true },
            )

            if (trabajando) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cian)
                    Spacer(Modifier.width(10.dp))
                    Text("Trabajando…", color = TextoTerciario)
                }
            }

            Spacer(Modifier.height(24.dp))
            Surface(color = Ambar.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Lee esto", color = Ambar, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // La última línea decía "para restaurar en un teléfono
                        // nuevo, primero entra a tu cuenta" — y en un teléfono
                        // nuevo NO se podía entrar: la cuenta está atada al
                        // hardware. La pantalla mandaba a hacer algo imposible,
                        // y quien la creyera descubría el problema el día que
                        // ya no tenía arreglo. Ahora dice qué hace falta de
                        // verdad, y dónde conseguirlo.
                        "• La frase no se guarda en ningún lado. Si la olvidas, la copia " +
                            "no sirve — ni el servidor ni nadie puede abrirla.\n" +
                            "• La copia incluye el texto de tus chats y sus fotos y archivos.\n" +
                            "• Si añades tu código de recuperación, también llevará tu " +
                            "identidad cifrada, y al restaurarla conservarás tu número de " +
                            "seguridad.\n" +
                            "• Para entrar en un teléfono nuevo hace falta el código de " +
                            "recuperación. Créalo en Cuenta si aún no lo tienes: sin él, " +
                            "esta copia no se puede restaurar en otro teléfono.",
                        color = TextoSecundario, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // --- exportar: pedir frase (dos veces) ---------------------------
    if (pidiendoFraseExport) {
        DialogoFrase(
            titulo = "Frase para la copia",
            confirmar = "Crear",
            pedirDosVeces = true,
            ofrecerCodigo = true,
            explicacionCodigo =
                "Si lo añades, la copia llevará también tu identidad cifrada, y al " +
                    "restaurarla en otro teléfono conservarás tu número de seguridad. " +
                    "Sin él se guardan los mensajes igual.",
            onCerrar = { pidiendoFraseExport = false },
            onFrase = { frase, codigo ->
                pidiendoFraseExport = false
                trabajando = true
                // La frase se lleva al selector de archivo; la copia se escribe
                // en streaming cuando la persona elige donde guardar.
                fraseExport = frase
                codigoExport = codigo
                guardarArchivo.launch("wtfuck-copia-${System.currentTimeMillis() / 1000}.wtfbackup")
            },
        )
    }

    // --- copia automatica: la frase, al activar o al cambiarla -----------
    if (activando || cambiandoFrase) {
        DialogoFrase(
            titulo = "Frase de las copias automáticas",
            confirmar = if (activando) "Elegir carpeta" else "Guardar",
            pedirDosVeces = true,
            ofrecerCodigo = true,
            explicacionCodigo =
                "Si lo añades, las copias llevarán tu identidad cifrada. No se guarda el " +
                    "código: solo la llave que sella la identidad, que no sirve para " +
                    "entrar a tu cuenta.",
            onCerrar = { activando = false; cambiandoFrase = false },
            onFrase = { frase, codigo ->
                if (activando) {
                    activando = false
                    fraseAuto = frase
                    codigoAuto = codigo
                    elegirCarpeta.launch(null)
                } else {
                    cambiandoFrase = false
                    cfg.guardarFrase(frase)
                    frase.fill('\u0000')
                    cfg.guardarClaveIdentidad(
                        codigo?.let { CodigoRecuperacion.normalizar(it) }
                            ?.let { CodigoRecuperacion.claveDeIdentidad(it) }
                    )
                    refrescar()
                    aviso = "Frase cambiada. Las copias que ya estaban siguen abriéndose con la anterior."
                }
            },
        )
    }

    if (desactivando) {
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { desactivando = false },
            title = { Text("Desactivar la copia automática", color = TextoPrimario) },
            text = {
                Text(
                    "Se olvidan la frase y la carpeta. Las copias que ya están en la carpeta se quedan allí.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    desactivando = false
                    CopiaAutomatica.cancelar(ctx)
                    cfg.carpeta?.let { vieja ->
                        runCatching {
                            ctx.contentResolver.releasePersistableUriPermission(
                                android.net.Uri.parse(vieja),
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                    }
                    cfg.borrarTodo()
                    refrescar()
                }) { Text("Desactivar", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { desactivando = false }) { Text("Cancelar", color = TextoSecundario) }
            },
        )
    }

    // --- restaurar: pedir frase (una vez) ----------------------------
    pidiendoFraseImport?.let { uri ->
        DialogoFrase(
            titulo = "Frase de la copia",
            confirmar = "Restaurar",
            pedirDosVeces = false,
            ofrecerCodigo = true,
            explicacionCodigo =
                "Si la copia lleva tu identidad, hace falta el código con el que " +
                    "se guardó para recuperarla. Sin él se restauran los mensajes igual.",
            onCerrar = { pidiendoFraseImport = null },
            onFrase = { frase, codigo ->
                pidiendoFraseImport = null
                trabajando = true
                ambito.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use {
                                app.repo.restaurarCopiaDe(it, frase, codigo)
                            }
                                ?: Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO))
                        }.getOrElse { Result.failure(CopiaSeguridad.ErrorCopia(CopiaSeguridad.Fallo.FORMATO)) }
                    }
                    trabajando = false
                    r.onSuccess {
                        val fotos = if (it.adjuntos > 0) " y ${it.adjuntos} archivos" else ""
                        // Lo que paso con la identidad se dice SIEMPRE. El caso
                        // CODIGO_NO_ABRE es el que hay que decir a tiempo:
                        // todavia se puede reintentar con el codigo correcto, y
                        // si no se avisa, la persona se entera cuando sus
                        // contactos le pregunten por que les salto una alarma.
                        val id = when (it.identidad) {
                            Repositorio.ResumenRestauracion.Identidad.RESTAURADA ->
                                " Tu identidad volvio: tus contactos no veran ningun aviso."
                            Repositorio.ResumenRestauracion.Identidad.SIN_CODIGO ->
                                " La copia trae tu identidad, pero no diste el codigo. " +
                                    "Vuelve a restaurar con el si quieres conservar tu " +
                                    "numero de seguridad."
                            Repositorio.ResumenRestauracion.Identidad.CODIGO_NO_ABRE ->
                                " Ese codigo no abre la identidad de esta copia. Los " +
                                    "mensajes si se restauraron."
                            Repositorio.ResumenRestauracion.Identidad.YA_HABIA_OTRA ->
                                " Este telefono ya tenia otra identidad en uso y se " +
                                    "respeto: cambiarla habria roto tus conversaciones."
                            Repositorio.ResumenRestauracion.Identidad.NO_VENIA -> ""
                        }
                        aviso = "Restaurados ${it.mensajes} mensajes$fotos de ${it.conversaciones} chats.$id"
                    }.onFailure { e ->
                        aviso = when ((e as? CopiaSeguridad.ErrorCopia)?.fallo) {
                            CopiaSeguridad.Fallo.FORMATO -> "Ese archivo no es una copia de wtfuck."
                            CopiaSeguridad.Fallo.FRASE_O_DANADO -> "Frase incorrecta, o el archivo está dañado."
                            null -> "No se pudo restaurar."
                        }
                    }
                }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            containerColor = BgElev,
            onDismissRequest = { aviso = null },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
            text = { Text(msg, color = TextoPrimario) },
        )
    }
}

/**
 * La copia automatica: apagada, un boton; encendida, todo lo que se puede
 * cambiar y como le fue a la ultima.
 */
@Composable
private fun SeccionCopiaAutomatica(
    activa: Boolean,
    carpeta: String?,
    frecuencia: CopiaAutomatica.Frecuencia,
    conAdjuntos: Boolean,
    ultima: Long,
    error: String?,
    conIdentidad: Boolean,
    enCurso: Boolean,
    onActivar: () -> Unit,
    onFrecuencia: (CopiaAutomatica.Frecuencia) -> Unit,
    onAdjuntos: (Boolean) -> Unit,
    onAhora: () -> Unit,
    onCambiarCarpeta: () -> Unit,
    onCambiarFrase: () -> Unit,
    onDesactivar: () -> Unit,
) {
    if (!activa) {
        Tarjeta(
            icono = Icons.Filled.Autorenew,
            titulo = "Copia automática",
            detalle = "Desactivada. Actívala y no dependerás de acordarte.",
            onClick = onActivar,
            habilitado = true,
        )
        return
    }
    Surface(color = BgSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Autorenew, null, tint = Cian, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Copia automática · activada", color = TextoPrimario, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(10.dp))
            val estado = when {
                enCurso -> "Haciendo una copia…"
                error != null -> error
                ultima == 0L -> "Todavía no se ha hecho ninguna."
                else -> "Última: " + cuandoFueCopia(ultima) + "."
            }
            Text(
                estado,
                color = if (error != null && !enCurso) Coral else TextoSecundario,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Carpeta: " + (carpeta ?: "no disponible"),
                color = if (carpeta == null) Coral else TextoTerciario,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (conIdentidad) "Lleva tu identidad cifrada." else "No lleva tu identidad.",
                color = TextoTerciario, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                CopiaAutomatica.Frecuencia.entries.forEach { f ->
                    FilterChip(
                        selected = f == frecuencia,
                        onClick = { onFrecuencia(f) },
                        label = { Text(f.etiqueta) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Incluir fotos y archivos", color = TextoPrimario, modifier = Modifier.weight(1f))
                Switch(checked = conAdjuntos, onCheckedChange = onAdjuntos)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = onAhora, enabled = !enCurso) { Text("Hacer una ahora", color = Cian) }
                TextButton(onClick = onCambiarCarpeta) { Text("Cambiar carpeta", color = Cian) }
            }
            Row {
                TextButton(onClick = onCambiarFrase) { Text("Cambiar frase", color = Cian) }
                TextButton(onClick = onDesactivar) { Text("Desactivar", color = Coral) }
            }
            Spacer(Modifier.height(4.dp))
            // Lo que no resuelve, donde se decide. Ver `CopiaAutomatica`.
            Text(
                "Se guardan las dos últimas. Si la carpeta está en este teléfono y lo pierdes, " +
                    "se pierden con él: para eso, elige una tarjeta SD o una carpeta que otra " +
                    "app sincronice con la nube.",
                color = TextoTerciario, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** "hoy, 01:30", "ayer, 22:10" o "hace 5 días". */
private fun cuandoFueCopia(ms: Long): String {
    val zona = java.time.ZoneId.systemDefault()
    val dia = java.time.Instant.ofEpochMilli(ms).atZone(zona)
    val hoy = java.time.LocalDate.now(zona)
    val hora = dia.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val dias = java.time.temporal.ChronoUnit.DAYS.between(dia.toLocalDate(), hoy)
    return when {
        dias <= 0L -> "hoy, $hora"
        dias == 1L -> "ayer, $hora"
        else -> "hace $dias días"
    }
}

@Composable
private fun Tarjeta(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    titulo: String, detalle: String, onClick: () -> Unit, habilitado: Boolean,
) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = habilitado,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Cian.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icono, null, tint = Cian, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(titulo, color = TextoPrimario, style = MaterialTheme.typography.bodyLarge)
                Text(detalle, color = TextoTerciario, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DialogoFrase(
    titulo: String,
    confirmar: String,
    pedirDosVeces: Boolean,
    /**
     * Si tambien se ofrece el codigo de recuperacion.
     *
     * Es OPCIONAL en los dos sentidos, y a proposito. Al exportar: quien no lo
     * tenga a mano debe poder guardar sus mensajes igual — perder el historial
     * por no encontrar un papel seria el peor cambio posible. Al restaurar: la
     * copia puede no traerlo, y los mensajes se restauran lo mismo.
     *
     * Lo que se pierde sin el esta escrito en la propia pantalla, no enterrado
     * en la documentacion.
     */
    ofrecerCodigo: Boolean = false,
    explicacionCodigo: String = "",
    onCerrar: () -> Unit,
    onFrase: (CharArray, String?) -> Unit,
) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    // Un codigo a medio escribir bloquea el boton: es mejor que dejar seguir y
    // que el sellado falle -o peor, que se guarde una copia SIN identidad
    // creyendo que la lleva-.
    val codigoMalo = ofrecerCodigo && codigo.isNotBlank() &&
        !CodigoRecuperacion.valido(codigo)
    // Minimo 8: una copia protegida por "1234" no esta protegida. Al exportar se
    // exige confirmar la frase, porque un error de tipeo en algo que no se ve
    // dejaria una copia que no abre y no habria como saberlo hasta necesitarla.
    val corta = a.length < 8
    val noCoincide = pedirDosVeces && a != b
    val malo = corta || noCoincide

    AlertDialog(
        containerColor = BgElev,
        onDismissRequest = onCerrar,
        title = { Text(titulo, color = TextoPrimario) },
        text = {
            Column {
                OutlinedTextField(
                    value = a, onValueChange = { a = it },
                    label = { Text("Frase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { if (corta) Text("Al menos 8 caracteres.", color = TextoTerciario) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pedirDosVeces) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = b, onValueChange = { b = it },
                        label = { Text("Repite la frase") },
                        singleLine = true,
                        isError = b.isNotEmpty() && noCoincide,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            if (b.isNotEmpty() && noCoincide) Text("No coincide.", color = Coral)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ofrecerCodigo) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Slate.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        explicacionCodigo,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(8.dp))
                    CampoCodigoRecuperacion(
                        valor = codigo,
                        onCambio = { codigo = it },
                        etiqueta = "Código de recuperación (opcional)",
                        ayuda = "Puedes dejarlo en blanco.",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !malo && !codigoMalo,
                onClick = { onFrase(a.toCharArray(), codigo.ifBlank { null }) },
            ) {
                Text(confirmar, color = if (malo || codigoMalo) TextoTerciario else Cian)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) } },
    )
}
