package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.VersionResp
import kotlinx.coroutines.launch

/**
 * El aviso de versión nueva.
 *
 * ## Por qué es un diálogo y no una tarjeta en ajustes
 *
 * Porque una tarjeta en ajustes la ve quien entra a ajustes, y a ajustes no
 * entra casi nadie. El problema que esto resuelve es justamente que la gente
 * no se entera; resolverlo en un sitio donde hay que ir a mirar sería no
 * resolverlo.
 *
 * ## Y por qué se puede cerrar
 *
 * Un diálogo que no se puede cerrar en mitad de una conversación es una app
 * que deja de funcionar porque el servidor decidió que sí. Se cierra, y no
 * vuelve a salir **para esa versión** — por número y no con un booleano, para
 * que decir "ahora no" a la 4 no silencie también la 5.
 *
 * La excepción es [VersionResp.minima]: ahí la app ya no puede hablar con el
 * servidor, así que cerrar el aviso no dejaría nada usable detrás. Ese no se
 * cierra, y lo dice.
 */
@Composable
fun ActualizacionDialogo(v: VersionResp, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val act = app.actualizador
    val ambito = rememberCoroutineScope()

    val obligatoria = act.estaObsoleta(v)
    var bajando by remember { mutableStateOf(false) }
    var avance by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // El receptor se registra mientras el diálogo está en pantalla y se quita
    // al salir. Si la instalación va bien, este proceso muere y se reemplaza
    // por la versión nueva, así que el `onTerminar` de éxito casi nunca llega
    // a ejecutarse — está para el caso en que Android decida no matar el
    // proceso, no porque se espere.
    DisposableEffect(Unit) {
        val r = act.escucharResultado { ok, msg ->
            bajando = false
            if (!ok) error = msg
        }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }

    AlertDialog(
        containerColor = BgElev,
        // Un diálogo obligatorio que se cierra tocando fuera no es obligatorio.
        onDismissRequest = { if (!obligatoria && !bajando) onCerrar() },
        title = {
            Text(
                if (obligatoria) "Tienes que actualizar" else "Hay una versión nueva",
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Column {
                Text(
                    "Versión ${v.versionName}. Tienes la ${act.instalada}.",
                    color = TextoSecundario,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (obligatoria) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Esta versión ya no funciona con el servidor.",
                        color = Ambar,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (v.notas.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(v.notas, color = TextoSecundario, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                // Se dice ANTES de pulsar, no después.
                //
                // Android va a sacar su propio diálogo de confirmación y no
                // hay forma de saltárselo. Quien no lo espera cree que algo
                // falló y cancela; avisarlo cuesta una línea.
                Text(
                    "Se descarga aquí y Android te pide confirmar la instalación. " +
                        "Tus chats no se tocan.",
                    color = TextoTerciario,
                    style = MaterialTheme.typography.labelSmall,
                )

                if (bajando) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { avance / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Cian,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Descargando… $avance %", color = TextoTerciario,
                         style = MaterialTheme.typography.labelSmall)
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Coral, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Siempre puedes bajarla a mano desde la página de descarga.",
                        color = TextoTerciario,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !bajando,
                onClick = {
                    bajando = true
                    error = null
                    avance = 0
                    ambito.launch {
                        act.bajar(v) { avance = it }
                            .onSuccess { apk ->
                                act.instalar(apk).onFailure {
                                    bajando = false
                                    error = it.message ?: "No se pudo abrir el instalador."
                                }
                            }
                            .onFailure {
                                bajando = false
                                error = it.message ?: "No se pudo descargar."
                            }
                    }
                },
            ) { Text(if (bajando) "Descargando…" else "Actualizar", color = Cian) }
        },
        dismissButton = {
            if (!obligatoria) {
                TextButton(enabled = !bajando, onClick = {
                    act.recordarMasTarde(v)
                    onCerrar()
                }) { Text("Ahora no", color = TextoSecundario) }
            }
        },
    )
}

/**
 * Engancha la consulta a la pantalla principal.
 *
 * Pregunta en dos momentos, y entre los dos no queda nadie fuera:
 *
 *  1. **Al abrir la app**, que cubre a quien la tenía cerrada.
 *  2. **Cuando el socket reconecta**, que cubre a quien la tiene abierta
 *     desde hace horas. Publicar una versión exige reiniciar el servidor, un
 *     reinicio corta todos los sockets, y la reconexión llega justo después.
 *
 * Por eso no hace falta un push. Un mensaje silencioso por Firebase haría lo
 * mismo, a cambio de una dependencia de Google y de contarle a un tercero cada
 * vez que se publica un build — que en una app de mensajería privada no es un
 * detalle menor.
 *
 * La consulta al reconectar va sin `forzar`, así que respeta el intervalo: en
 * una red mala el socket reconecta muchas veces y no tiene sentido preguntar
 * en cada una.
 */
@Composable
fun AvisoDeActualizacion() {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var version by remember { mutableStateOf<VersionResp?>(null) }

    fun considerar(v: VersionResp?) {
        if (v == null) return
        // Obligatoria gana sobre "ya lo descarté": si la app dejó de
        // funcionar, haberla descartado antes no cambia nada.
        if (app.actualizador.estaObsoleta(v) || !app.actualizador.yaSeAviso(v)) version = v
    }

    LaunchedEffect(Unit) { considerar(app.actualizador.consultar(forzar = true)) }

    LaunchedEffect(Unit) {
        app.repo.reconectado.collect { considerar(app.actualizador.alReconectar()) }
    }

    version?.let { ActualizacionDialogo(it) { version = null } }
}
