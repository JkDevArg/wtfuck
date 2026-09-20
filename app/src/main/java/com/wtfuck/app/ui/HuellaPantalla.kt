package com.wtfuck.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.CifradoDeConversacion
import com.wtfuck.app.datos.InfoCifrado
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Verificación de identidad (módulos E.5, E.6 y **E.7**).
 *
 * Esta pantalla existe por una razón concreta y no por completitud: el servidor
 * decide QUÉ clave entrega cuando alguien abre una conversación. Un servidor
 * malicioso podría entregar la suya y leer todo sin que nadie lo note. Contra
 * eso no hay criptografía que alcance —el cifrado funcionaría perfectamente,
 * solo que con la persona equivocada—. La única defensa es comparar la huella
 * por un canal distinto: una llamada, en persona, un papel.
 *
 * Por eso los 60 dígitos están grandes y agrupados de a cinco: están pensados
 * para leerse en voz alta por teléfono sin perder el hilo.
 *
 * ## E.7 · Una huella POR APARATO, y la pantalla no lo esconde
 *
 * La huella de Signal se calcula entre dos **claves de identidad**, y cada
 * dispositivo tiene la suya. Quien tiene teléfono y tablet tiene dos huellas
 * distintas, las dos legítimas. Hasta el módulo J daba lo mismo porque había
 * un aparato por cuenta; después, no: esta pantalla mostraba la huella del
 * primer dispositivo de la lista y, al verificarla, declaraba la conversación
 * "verificada" mientras el otro aparato seguía sin comprobar. Era una
 * respuesta tranquilizadora y falsa, que es lo peor que puede dar una pantalla
 * de seguridad.
 *
 * Ahora hay una tarjeta por aparato y el resumen dice **cuántos de cuántos**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuellaPantalla(
    conversacionId: String,
    onAtras: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var info by remember { mutableStateOf<CifradoDeConversacion?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }

    suspend fun recargar() {
        // Todo en una sola llamada suspendida: la huella se calcula contra cada
        // DISPOSITIVO y el almacen de Signal se consulta fuera del hilo
        // principal.
        info = runCatching { app.repo.infoCifrado(conversacionId) }.getOrNull()
        cargando = false
    }

    LaunchedEffect(conversacionId) { recargar() }

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
                title = { Text("Verificar cifrado", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val c = info
            when {
                cargando -> {
                    Spacer(Modifier.height(80.dp))
                    CircularProgressIndicator(color = Cian, strokeWidth = 2.5.dp)
                }

                c == null || c.aparatos.all { it.digitos.isBlank() } -> {
                    Spacer(Modifier.height(60.dp))
                    Icon(Icons.Filled.LockOpen, null, tint = Ambar, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Todavia no hay cifrado con esta conversacion.",
                        color = TextoPrimario,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "La sesion cifrada se establece al enviar el primer mensaje: " +
                            "hasta entonces no hay ninguna clave del otro lado que comparar.",
                        color = TextoSecundario,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    Spacer(Modifier.height(16.dp))
                    Resumen(c)

                    // Con varios aparatos hay que decir por que hay varios, o
                    // la pantalla parece repetida y se verifica solo el
                    // primero.
                    if (c.aparatos.size > 1 && !c.esGrupo) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(BgSurface)
                                .padding(14.dp),
                        ) {
                            Icon(
                                Icons.Filled.Devices,
                                null,
                                tint = Cian,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(11.dp))
                            Text(
                                "@${c.username} usa ${c.aparatos.size} aparatos, y cada uno " +
                                    "tiene su propio codigo. Hay que comparar los " +
                                    "${c.aparatos.size}: verificar uno solo deja al otro sin " +
                                    "comprobar.",
                                color = TextoSecundario,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    // En un grupo se agrupa por PERSONA: verificar es
                    // comparar numeros con alguien, y una lista plana de ocho
                    // tarjetas no dice con quien se esta comparando cada una.
                    if (c.esGrupo) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(BgSurface)
                                .padding(14.dp),
                        ) {
                            Icon(
                                Icons.Filled.Groups,
                                null,
                                tint = Cian,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(11.dp))
                            Text(
                                "En un grupo cada persona tiene su propio codigo -y cada " +
                                    "aparato suyo, el suyo-. Se verifica de a uno; no hay " +
                                    "un numero del grupo entero, porque no existe.",
                                color = TextoSecundario,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    val enOrden = if (c.esGrupo) {
                        c.porPersona.entries.sortedBy { it.key }.flatMap { (quien, suyos) ->
                            suyos.map { quien to it }
                        }
                    } else {
                        c.aparatos.map { c.username to it }
                    }

                    var ultimaPersona: String? = null
                    enOrden.forEach { (quien, h) ->
                        if (c.esGrupo && quien != ultimaPersona) {
                            ultimaPersona = quien
                            Spacer(Modifier.height(22.dp))
                            val suyos = c.porPersona[quien].orEmpty()
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "@$quien",
                                    color = Cian,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${suyos.count { it.verificada }}/${suyos.size}",
                                    color = if (suyos.all { it.verificada }) Cian else Ambar,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(if (c.esGrupo) 10.dp else 20.dp))
                        TarjetaAparato(
                            h = h,
                            varios = c.aparatos.size > 1,
                            onVerificar = { nueva ->
                                ambito.launch {
                                    app.repo.marcarVerificado(h.dispositivoId, nueva)
                                    recargar()
                                    aviso = if (nueva) {
                                        "\"${h.etiqueta}\" queda como verificado."
                                    } else {
                                        "Se quito la verificacion de \"${h.etiqueta}\"."
                                    }
                                }
                            },
                            onCopiado = { aviso = "Codigo copiado." },
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgSurface)
                            .padding(14.dp),
                    ) {
                        Icon(Icons.Filled.Info, null, tint = TextoTerciario, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(11.dp))
                        Text(
                            "Marcar como verificado no cambia el cifrado: los mensajes ya " +
                                "van cifrados de todas formas. Lo que cambia es que si esa " +
                                "clave cambia mas adelante, la app te va a avisar.",
                            color = TextoTerciario,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(Modifier.height(30.dp))
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

/**
 * El estado de la conversacion entera.
 *
 * Dice "1 de 2" y no "verificado" cuando falta un aparato. La tentacion es
 * redondear hacia el verde; es justo lo que esta pantalla no puede hacer.
 */
@Composable
private fun Resumen(c: CifradoDeConversacion) {
    val (icono, color, texto) = when {
        c.hayCambio -> Triple(Icons.Filled.Warning, Coral, "Una clave cambio")
        c.esGrupo && c.todoVerificado -> Triple(
            Icons.Filled.VerifiedUser, Cian,
            "Verificado: ${c.porPersona.size} personas",
        )
        c.esGrupo -> Triple(
            Icons.Filled.Lock, Ambar,
            "Verificado ${c.verificados} de ${c.aparatos.size} aparatos " +
                "(${c.porPersona.size} personas)",
        )
        c.todoVerificado && c.aparatos.size == 1 -> Triple(Icons.Filled.VerifiedUser, Cian, "Verificado")
        c.todoVerificado -> Triple(
            Icons.Filled.VerifiedUser, Cian,
            "Verificado: los ${c.aparatos.size} aparatos",
        )
        c.verificados > 0 -> Triple(
            Icons.Filled.Lock, Ambar,
            "Verificado ${c.verificados} de ${c.aparatos.size} aparatos",
        )
        else -> Triple(Icons.Filled.Lock, Ambar, "Cifrado, sin verificar")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(texto, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TarjetaAparato(
    h: InfoCifrado,
    varios: Boolean,
    onVerificar: (Boolean) -> Unit,
    onCopiado: () -> Unit,
) {
    val portapapeles = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Con un solo aparato el nombre sobra: es "el telefono de la otra
        // persona" y ya. Con varios es el unico dato que permite saber cual se
        // esta comparando.
        if (varios) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (h.verificada) Icons.Filled.VerifiedUser else Icons.Filled.Smartphone,
                    null,
                    tint = if (h.verificada) Cian else TextoTerciario,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    h.etiqueta,
                    color = TextoPrimario,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (h.verificada) "verificado" else "sin verificar",
                    color = if (h.verificada) Cian else Ambar,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // --- E.6: el aviso de cambio va ARRIBA de la huella ---
        if (h.cambio) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Coral.copy(alpha = 0.16f))
                    .padding(14.dp),
            ) {
                Icon(Icons.Filled.Warning, null, tint = Coral, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "La clave de seguridad cambio",
                        color = Coral,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Suele pasar porque @${h.username} reinstalo la app o cambio de " +
                            "telefono. Pero tambien es lo que se veria si alguien se " +
                            "estuviera poniendo en medio. Si te importa esta conversacion, " +
                            "verificala.",
                        color = TextoSecundario,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Un aparato sin huella calculable no se puede verificar, y ofrecer
        // el boton igual seria dejar marcar como "verificado" algo que nadie
        // comparo. Pasa cuando todavia no hay sesion con ese aparato -no se
        // le mando nada- o cuando su clave publicada no se puede leer.
        if (h.digitos.isBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LockOpen, null, tint = Ambar, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Todavia no hay sesion cifrada con este aparato, asi que no hay " +
                        "codigo que comparar. Aparece en cuanto se le entregue un mensaje.",
                    color = TextoSecundario,
                    fontSize = 13.sp,
                )
            }
            return@Column
        }

        QrDeHuella(h.escaneable)

        Spacer(Modifier.height(18.dp))
        Text(
            "Codigo de seguridad",
            color = TextoTerciario,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        // Los 60 digitos, en bloques de cinco y en tres filas. Asi se leen en
        // voz alta sin perder el lugar.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgElev)
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            h.digitos.chunked(5).chunked(4).forEach { fila ->
                Text(
                    fila.joinToString("  "),
                    color = TextoPrimario,
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(7.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Compara estos numeros con @${h.username} por otro medio: una llamada, en " +
                "persona. Si coinciden, nadie esta en medio. Si no coinciden, alguien lo esta.",
            color = TextoSecundario,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                portapapeles.setText(AnnotatedString(h.digitos.chunked(5).joinToString(" ")))
                onCopiado()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.ContentCopy, null, tint = Cian, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
            Text("Copiar codigo", color = Cian)
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onVerificar(!h.verificada) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (h.verificada) Slate.copy(alpha = 0.5f) else Cian,
                contentColor = if (h.verificada) TextoPrimario else TextoSobreAcento,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (h.verificada) Icons.Filled.Close else Icons.Filled.Check,
                null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(if (h.verificada) "Quitar la verificacion" else "Los numeros coinciden")
        }
    }
}

/**
 * El QR de la huella escaneable.
 *
 * Se genera en el momento y no se guarda: es un dato derivado de dos claves
 * publicas y cachearlo solo crearia la posibilidad de mostrar uno viejo.
 */
@Composable
private fun QrDeHuella(escaneable: String) {
    if (escaneable.isBlank()) return
    val bitmap = remember(escaneable) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matriz = QRCodeWriter().encode(escaneable, BarcodeFormat.QR_CODE, 420, 420, hints)
            Bitmap.createBitmap(matriz.width, matriz.height, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until matriz.width) {
                    for (y in 0 until matriz.height) {
                        setPixel(
                            x, y,
                            if (matriz.get(x, y)) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE,
                        )
                    }
                }
            }
        }.getOrNull()
    } ?: return

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Codigo QR de la huella",
        modifier = Modifier
            .size(190.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
    )
}
