package com.wtfuck.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wtfuck.app.datos.EstadoAvisos
import com.wtfuck.app.datos.Notificaciones
import com.wtfuck.app.datos.ProblemaAviso
import com.wtfuck.app.datos.pasosDelFabricante
import com.wtfuck.app.datos.problemasDeAvisos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import kotlinx.coroutines.launch
import com.wtfuck.app.ui.theme.*

/**
 * L.6 · Ajustes de notificaciones.
 *
 * ## Por qué esto NO viaja al servidor
 *
 * Una notificación la recibe un aparato, no una persona. Con multidispositivo,
 * querer que suene el teléfono y no la tablet es lo normal, y guardarlo en la
 * cuenta obligaría a que las dos se comporten igual. Además el servidor no
 * tiene por qué saber qué te molesta: es una preferencia de este teléfono y se
 * queda en este teléfono.
 *
 * ## Por qué hay interruptores si Android ya tiene canales de notificación
 *
 * Son dos cosas distintas y las dos hacen falta. El sistema manda sobre el
 * sonido, la vibración y la importancia —y para eso está el acceso directo a
 * sus ajustes—; lo de aquí es más fuerte: con una categoría apagada, la app
 * **no publica** la notificación. No es bajarle el volumen, es no decirlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificacionesPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val ajustes = remember { Notificaciones.Ajustes(ctx) }

    // Copia en estado para que la pantalla se redibuje: las preferencias son
    // un archivo, no un flujo, y leerlas de nuevo no recompone nada.
    var mensajes by remember { mutableStateOf(ajustes.mensajes) }
    var grupos by remember { mutableStateOf(ajustes.grupos) }
    var canales by remember { mutableStateOf(ajustes.canales) }
    var llamadas by remember { mutableStateOf(ajustes.llamadas) }
    var mostrarQuien by remember { mutableStateOf(ajustes.mostrarQuien) }

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
                title = { Text("Notificaciones", color = TextoPrimario) },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Encabezado("Que avisa")

            FilaAviso(
                icono = Icons.AutoMirrored.Filled.Chat,
                titulo = "Mensajes directos",
                detalle = "Cuando alguien te escribe",
                marcado = mensajes,
            ) { mensajes = it; ajustes.mensajes = it }

            FilaAviso(
                icono = Icons.Filled.Group,
                titulo = "Grupos",
                detalle = "Mensajes de grupo y cuando te agregan a uno",
                marcado = grupos,
            ) { grupos = it; ajustes.grupos = it }

            FilaAviso(
                icono = Icons.Filled.Campaign,
                titulo = "Canales",
                detalle = "Publicaciones de los canales que sigues",
                marcado = canales,
            ) { canales = it; ajustes.canales = it }

            FilaAviso(
                icono = Icons.Filled.Phone,
                titulo = "Llamadas",
                detalle = "Llamadas y videollamadas entrantes",
                marcado = llamadas,
            ) { llamadas = it; ajustes.llamadas = it }

            Spacer(Modifier.height(18.dp))
            Encabezado("Que se ve")

            FilaAviso(
                icono = Icons.Filled.Visibility,
                titulo = "Mostrar quien escribe",
                detalle = "Con esto apagado, la notificación solo dice que hay algo nuevo",
                marcado = mostrarQuien,
            ) { mostrarQuien = it; ajustes.mostrarQuien = it }

            Spacer(Modifier.height(6.dp))
            Text(
                // Esto no es un ajuste: es la promesa que la app cumple
                // siempre, y conviene que se lea aqui, donde alguien podria
                // esperar lo contrario.
                "El texto de los mensajes NUNCA aparece en una notificación. Va cifrado " +
                    "de extremo a extremo, y copiarlo a la pantalla de bloqueo lo dejaria " +
                    "legible justo donde cualquiera lo ve sin desbloquear nada.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(20.dp))
            Encabezado("Con la app cerrada")
            SeccionAppCerrada()

            Spacer(Modifier.height(20.dp))
            Encabezado("Lo que decide Android")

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Sonido, vibracion y "no molestar" los administra el
                        // sistema por canal. Duplicarlos aqui daria dos
                        // interruptores para lo mismo que se contradicen.
                        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        }
                        runCatching { ctx.startActivity(i) }
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    null,
                    tint = Cian,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sonido y vibracion", color = TextoPrimario)
                    Text(
                        "Se configuran por categoria en los ajustes del sistema",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Gavel,
                    null,
                    tint = Ambar,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    // No hay interruptor y se dice por que: una advertencia
                    // silenciable convierte la sancion posterior en una
                    // emboscada.
                    "Las advertencias y sanciones de moderacion no se pueden apagar: una " +
                        "advertencia que no llega no cumple su unica funcion, que es dar " +
                        "la oportunidad de corregir antes de la sanción.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Estos ajustes son de ESTE aparato. Si entras con la misma cuenta en otro, " +
                    "ahi se configuran aparte.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Si los avisos pueden despertar la app con el telefono suspendido, y que
 * hacer si no. La regla esta en `problemasDeAvisos`; aqui se junta el estado,
 * que solo Android conoce, y se ofrece el ajuste que corresponde.
 *
 * Se vuelve a medir al volver a la pantalla: la persona va a los ajustes del
 * sistema, cambia algo y vuelve, y lo que ve tiene que reflejarlo.
 */
@Composable
private fun SeccionAppCerrada() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as com.wtfuck.app.WtfuckApp
    val ambito = rememberCoroutineScope()
    var vuelta by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vuelta++ }

    val estado = remember(vuelta) {
        val energia = ctx.getSystemService(android.os.PowerManager::class.java)
        EstadoAvisos(
            notificacionesPermitidas =
                androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            servidorConPush = app.push.disponible,
            googlePlay = runCatching {
                com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
                    .isGooglePlayServicesAvailable(ctx) == com.google.android.gms.common.ConnectionResult.SUCCESS
            }.getOrDefault(false),
            tokenRegistrado = app.push.registrado,
            bateriaSinRestriccion = energia?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true,
            fabricante = Build.MANUFACTURER.orEmpty(),
        )
    }
    val problemas = problemasDeAvisos(estado)

    fun abrir(i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val ajustesApp = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.parse("package:${ctx.packageName}"))

    if (problemas.isEmpty()) {
        AvisoEstado(
            ok = true,
            titulo = "Todo listo",
            texto = "Los avisos pueden despertar la app aunque el teléfono esté suspendido.",
        )
        return
    }
    for (p in problemas) {
        when (p) {
            ProblemaAviso.SIN_PERMISO -> AvisoEstado(
                titulo = "Las notificaciones están apagadas",
                texto = "Android no deja que wtfuck muestre nada. Sin esto, lo demás no importa.",
                accion = "Activarlas",
            ) {
                abrir(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    } else ajustesApp
                )
            }
            ProblemaAviso.SERVIDOR_SIN_PUSH -> AvisoEstado(
                titulo = "El servidor no tiene push",
                texto = "Con la app cerrada nada puede despertarla: los mensajes llegan " +
                    "cuando la abres. Se arregla en el servidor, no en este teléfono.",
            )
            ProblemaAviso.SIN_GOOGLE -> AvisoEstado(
                titulo = "Este teléfono no tiene servicios de Google",
                texto = "El aviso con la app cerrada viaja por ahí. Mientras wtfuck siga " +
                    "abierta en segundo plano, los mensajes llegan igual.",
            )
            ProblemaAviso.SIN_TOKEN -> AvisoEstado(
                titulo = "Todavía no está registrado para avisos",
                texto = "Este teléfono no le dio su identificador de avisos al servidor.",
                accion = "Reintentar",
            ) { ambito.launch { app.push.poner(); vuelta++ } }
            ProblemaAviso.BATERIA_RESTRINGIDA -> AvisoEstado(
                titulo = "Android optimiza la batería de wtfuck",
                texto = "Con el teléfono suspendido puede retrasar o cortar los avisos.",
                accion = "Quitar la restricción",
            ) {
                abrir(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                )
            }
            ProblemaAviso.FABRICANTE_AGRESIVO -> AvisoEstado(
                titulo = "Tu ${Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }} " +
                    "tiene su propio gestor de batería",
                texto = (pasosDelFabricante(estado.fabricante) ?: "") +
                    " No hay forma de comprobarlo desde la app: si los avisos no llegan " +
                    "con el teléfono suspendido, es lo primero que hay que revisar.",
                accion = "Abrir los ajustes de wtfuck",
            ) { abrir(ajustesApp) }
        }
    }
}

@Composable
private fun AvisoEstado(
    titulo: String,
    texto: String,
    ok: Boolean = false,
    accion: String? = null,
    alTocar: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            null,
            tint = if (ok) Cian else Ambar,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = TextoPrimario)
            Text(texto, style = MaterialTheme.typography.bodySmall, color = TextoTerciario)
            if (accion != null && alTocar != null) {
                TextButton(onClick = alTocar, contentPadding = PaddingValues(0.dp)) {
                    Text(accion, color = Cian)
                }
            }
        }
    }
}

@Composable
private fun Encabezado(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.labelLarge,
        color = Cian,
        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
    )
}

/**
 * Una fila con interruptor.
 *
 * El `toggleable` va en la FILA y no en el `Switch`: un interruptor suelto es
 * un blanco de 32 dp sin etiqueta asociada, que los servicios de
 * accesibilidad marcan como elemento sin nombre. Con la fila entera, el
 * lector lee el titulo y el area tocable es toda la fila.
 */
@Composable
private fun FilaAviso(
    icono: ImageVector,
    titulo: String,
    detalle: String,
    marcado: Boolean,
    onCambio: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = marcado, role = Role.Switch, onValueChange = onCambio)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = if (marcado) Cian else Slate, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, color = TextoPrimario)
            Text(
                detalle,
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
            )
        }
        Switch(
            checked = marcado,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextoSobreAcento,
                checkedTrackColor = Cian,
                uncheckedThumbColor = TextoTerciario,
                uncheckedTrackColor = BgElev,
                uncheckedBorderColor = Slate,
            ),
        )
    }
}
