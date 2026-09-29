package com.wtfuck.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "La app se cerro sola la ultima vez": ofrece mandar el informe.
 *
 * ## Por que se pregunta en vez de mandarlo solo
 *
 * Porque mandar trazas sin permiso es telemetria, y esta es una app cuya
 * premisa entera es que nada sale del telefono sin querer. Instalar
 * Crashlytics aqui seria contradecir el producto para ahorrarse un dialogo.
 *
 * Asi que el informe se queda en el aparato y **la persona decide**: puede
 * verlo entero antes, y compartirlo por donde quiera.
 *
 * ## Por que se puede leer antes de mandarlo
 *
 * No es transparencia decorativa: es la unica forma de que la promesa de "no
 * lleva tus mensajes" se pueda comprobar en vez de creer. El informe esta
 * saneado -ver `Fallos.sanear`- y aun asi se muestra, porque un filtro que
 * nadie puede auditar es un filtro en el que hay que confiar a ciegas.
 *
 * ## Por que sale UNA vez
 *
 * Al descartarlo se borran los informes. Un aviso que vuelve en cada arranque
 * se aprende a cerrar sin leer, y entonces el siguiente -el que si importa-
 * tampoco se lee.
 */
@Composable
fun AvisoDeFallo() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var informe by remember { mutableStateOf<String?>(null) }
    var viendo by remember { mutableStateOf(false) }
    var descartado by remember { mutableStateOf(false) }

    // Se lee en IO: es disco, y en el arranque el hilo principal ya tiene
    // bastante con abrir la base cifrada.
    LaunchedEffect(Unit) {
        informe = withContext(Dispatchers.IO) { app.fallos.ultimo() }
    }

    val texto = informe
    if (texto == null || descartado) return

    fun descartar() {
        descartado = true
        ambito.launch { withContext(Dispatchers.IO) { app.fallos.borrarTodos() } }
    }

    if (viendo) {
        AlertDialog(
            onDismissRequest = { viendo = false },
            containerColor = BgElev,
            title = { Text("El informe", color = TextoPrimario, fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Esto es todo lo que se mandaría. No lleva tus mensajes ni " +
                            "tu usuario.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(color = BgSurface, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            texto,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                            color = TextoSecundario,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viendo = false; compartir(ctx, texto); descartar() }) {
                    Text("Compartir", color = Cian)
                }
            },
            dismissButton = {
                TextButton(onClick = { viendo = false }) { Text("Atrás", color = TextoSecundario) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { descartar() },
        containerColor = BgElev,
        title = { Text("La app se cerró sola", color = TextoPrimario, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    "Pasó la última vez que la usaste. Hay un informe guardado en " +
                        "este teléfono que ayudaría a arreglarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "No se ha enviado nada. No lleva el texto de tus chats ni tu " +
                        "usuario: puedes leerlo entero antes de decidir.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTerciario,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viendo = true }) { Text("Ver y enviar", color = Cian) }
        },
        dismissButton = {
            TextButton(onClick = { descartar() }) { Text("No, gracias", color = TextoSecundario) }
        },
    )
}

/**
 * Lo pasa al selector del sistema.
 *
 * Como texto y no como archivo: un `.txt` adjunto obliga a abrirlo para
 * mirarlo, y lo normal es que acabe pegado en un chat de todas formas.
 */
private fun compartir(ctx: android.content.Context, texto: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "wtfuck · informe de cierre")
        putExtra(Intent.EXTRA_TEXT, texto)
    }
    runCatching { ctx.startActivity(Intent.createChooser(i, "Enviar el informe")) }
}
