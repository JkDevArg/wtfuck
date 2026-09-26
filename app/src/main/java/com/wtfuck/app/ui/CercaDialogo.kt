package com.wtfuck.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.TransporteCerca
import com.wtfuck.app.ui.theme.*

/**
 * Los permisos que hacen falta, segun la version de Android.
 *
 * Antes de Android 12 el Bluetooth iba con `BLUETOOTH`/`BLUETOOTH_ADMIN`, que
 * se conceden al instalar y no se piden. Desde Android 12 hay que pedirlos, y
 * pedir los viejos en un aparato nuevo devuelve un rechazo permanente — que se
 * ve como "el modo cerca no anda y no dice por que".
 */
private val PERMISOS_CERCA: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        emptyArray()
    }

private fun tienePermisosCerca(ctx: android.content.Context): Boolean =
    PERMISOS_CERCA.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Encender o apagar la mensajería sin internet.
 *
 * ## Por qué es un diálogo y no un interruptor en Ajustes
 *
 * Esto no es una preferencia que se deja puesta: es algo que se enciende **en
 * un momento y en un sitio** —sin señal, con la otra persona enfrente— y se
 * apaga al salir. Un interruptor en Ajustes se queda encendido para siempre,
 * y una radio escuchando conexiones de cualquiera que pase no puede ser el
 * estado por defecto de una app de mensajería.
 *
 * El diálogo también es el único sitio donde cabe decir lo que hace falta
 * saber: que sólo funciona con gente con la que ya hablaste antes. Un
 * interruptor sin esa frase se enciende, no pasa nada, y no hay forma de
 * saber si está roto o si es así.
 */
@Composable
fun DialogoCerca(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val cerca = app.repo.cerca

    val estado by cerca.estado.collectAsState()
    val conQuien by cerca.conQuien.collectAsState()

    var permisos by remember { mutableStateOf(tienePermisosCerca(ctx)) }
    // Un contador y no un booleano: hay que poder reintentar, y un booleano
    // que ya está en `true` no vuelve a disparar nada.
    var intento by remember { mutableIntStateOf(0) }
    var aviso by remember { mutableStateOf<String?>(null) }

    fun arrancar() {
        when {
            !cerca.hayRadio() -> aviso = "Este aparato no tiene Bluetooth."
            !cerca.radioEncendida() -> {
                aviso = "El Bluetooth está apagado. Enciéndelo y vuelve a intentar."
                // Se ABRE los ajustes, no se enciende la radio por su cuenta.
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            !cerca.encender() -> aviso = "No se pudo encender el modo cerca."
            else -> aviso = null
        }
    }

    val pedir = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { dados ->
        permisos = dados.values.all { it }
        if (permisos) {
            arrancar()
        } else {
            aviso = "Sin permiso de Bluetooth no se puede buscar a nadie cerca."
        }
        intento++
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        icon = {
            Icon(
                Icons.Filled.Bluetooth, null,
                tint = if (estado == TransporteCerca.Estado.APAGADO) TextoSecundario else Cian,
                modifier = Modifier.size(28.dp),
            )
        },
        title = { Text("Modo cerca", color = TextoPrimario) },
        text = {
            Column {
                Text(
                    when (estado) {
                        TransporteCerca.Estado.APAGADO ->
                            "Manda y recibe mensajes por Bluetooth, sin internet, con alguien " +
                                "que esté a unos metros."
                        TransporteCerca.Estado.ESCUCHANDO ->
                            "Buscando… deja esta pantalla abierta en los dos teléfonos."
                        TransporteCerca.Estado.ENLAZADO ->
                            "Conectado con ${conQuien ?: "alguien"}. Lo que escribas sale por aquí."
                    },
                    color = TextoSecundario,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                // La frase que evita el rato de pensar que está roto.
                Text(
                    "Sólo funciona con gente con la que ya hablaste antes: las claves para " +
                        "empezar una conversación nueva viven en el servidor, y aquí no hay " +
                        "servidor.",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                )
                aviso?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = Coral, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (estado != TransporteCerca.Estado.APAGADO) {
                    cerca.apagar()
                    aviso = null
                } else if (permisos) {
                    arrancar()
                } else {
                    pedir.launch(PERMISOS_CERCA)
                }
            }) {
                Text(
                    if (estado == TransporteCerca.Estado.APAGADO) "Encender" else "Apagar",
                    color = if (estado == TransporteCerca.Estado.APAGADO) Cian else Coral,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cerrar", color = TextoSecundario) }
            Spacer(Modifier.width(0.dp))
        },
    )
}
