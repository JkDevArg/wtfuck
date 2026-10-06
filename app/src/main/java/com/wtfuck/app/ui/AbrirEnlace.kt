package com.wtfuck.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.datos.EnlaceDeContacto
import com.wtfuck.app.datos.EnlaceSeguro
import com.wtfuck.app.ui.theme.*

/**
 * Todo enlace que se toca dentro de la app pregunta antes de abrirse.
 *
 * ## Por que reemplazando `LocalUriHandler` y no en cada sitio
 *
 * Los enlaces del texto (`LinkAnnotation.Url`) y la tarjeta de vista previa
 * abren con el `UriHandler` de la composicion. Cambiarlo una vez, en la raiz,
 * hace que pregunten todos, incluidos los que se agreguen mañana sin que
 * nadie se acuerde de esto. Un `if` en cada sitio es un sitio donde olvidarlo.
 *
 * Ver [EnlaceSeguro] para lo que se mira de cada enlace.
 */
@Composable
fun ConfirmarEnlaces(contenido: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val sistema = LocalUriHandler.current
    var pendiente by remember { mutableStateOf<String?>(null) }
    val confirmador = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                // Un enlace de contacto de NUESTRO servidor no va al
                // navegador: se abre aqui mismo, como si se escaneara.
                val codigo = EnlaceDeContacto.codigoDe(uri)
                if (codigo != null && uri.startsWith(BuildConfig.SERVIDOR.trimEnd('/') + "/c/")) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("wtfuck://c/$codigo")).setPackage(ctx.packageName)
                        )
                    }
                    return
                }
                pendiente = uri
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides confirmador) { contenido() }

    pendiente?.let { url ->
        DialogoAbrirEnlace(
            url = url,
            onAbrir = {
                pendiente = null
                runCatching { sistema.openUri(url) }
                    .onFailure { Toast.makeText(ctx, "No hay una app para abrir este enlace.", Toast.LENGTH_SHORT).show() }
            },
            onCopiar = {
                pendiente = null
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("enlace", url))
                Toast.makeText(ctx, "Enlace copiado", Toast.LENGTH_SHORT).show()
            },
            onCerrar = { pendiente = null },
        )
    }
}

@Composable
private fun DialogoAbrirEnlace(url: String, onAbrir: () -> Unit, onCopiar: () -> Unit, onCerrar: () -> Unit) {
    val analisis = remember(url) { EnlaceSeguro.analizar(url) }
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        icon = { Icon(Icons.Filled.OpenInBrowser, null, tint = Cian) },
        title = { Text("¿Abrir este enlace?", color = TextoPrimario) },
        text = {
            Column {
                if (analisis == null) {
                    Text("Este enlace no se puede abrir desde un mensaje.", color = Coral)
                } else {
                    Text("Te lleva a", color = TextoTerciario, fontSize = 12.sp)
                    Text(
                        analisis.sitio, color = TextoPrimario, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    url, color = TextoSecundario, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                analisis?.avisos?.forEach { aviso ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.WarningAmber, null, tint = Ambar, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(aviso, color = Ambar, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Se abre fuera de wtfuck: el sitio verá tu conexión.",
                    color = TextoTerciario, style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            if (analisis != null) TextButton(onClick = onAbrir) { Text("Abrir", color = Cian) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopiar) { Text("Copiar", color = TextoSecundario) }
                TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
            }
        },
    )
}
