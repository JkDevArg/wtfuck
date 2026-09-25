package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.EventoSeguridad
import com.wtfuck.protocol.MiAdvertencia
import com.wtfuck.protocol.MiEstadoModeracion
import kotlinx.coroutines.launch

/**
 * Mi cuenta: advertencias, sanciones y accesos.
 *
 * Existe por una razon concreta: **una sancion que no se explica no corrige
 * nada.** Sin esta pantalla, un usuario advertido solo nota que algo cambio, y
 * un suspendido solo nota que dejo de poder escribir. Ninguno de los dos sabe
 * que hacer distinto, que es justamente el unico punto de advertir.
 *
 * Lo de "accesos" va en la MISMA pantalla y no en otra a proposito: las dos
 * preguntas que trae aqui un usuario preocupado son "¿por que no puedo
 * escribir?" y "¿entro alguien a mi cuenta?". Separarlas en dos sitios obliga a
 * adivinar cual de las dos tenia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeracionPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var estado by remember { mutableStateOf<MiEstadoModeracion?>(null) }
    /**
     * Modulo Z.5: `null` = no se pudo preguntar. Vacia = no paso nada.
     *
     * "Todavia no hay nada registrado" es una afirmacion sobre la seguridad de
     * la cuenta -ningun ingreso raro, ningun intento fallido-. No se puede
     * decir sin haberlo preguntado.
     */
    var eventos by remember { mutableStateOf<List<EventoSeguridad>?>(null) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        estado = app.repo.miEstadoModeracion()
        eventos = app.repo.misEventosSeguridad()
        cargando = false
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
                title = { Text("Mi cuenta", color = TextoPrimario) },
            )
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
            contentPadding = PaddingValues(14.dp),
        ) {
            val e = estado

            if (e?.suspendido == true) {
                item {
                    TarjetaSuspension(e)
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Advertencias",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextoPrimario,
                    )
                    Spacer(Modifier.weight(1f))
                    if (e != null && e.topeAdvertencias > 0) {
                        // Se ensena el tope, no solo la cuenta: saber que van
                        // dos de tres es informacion util; saber que van dos,
                        // no.
                        Text(
                            "${e.advertenciasVigentes} de ${e.topeAdvertencias}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (e.advertenciasVigentes > 0) Ambar else TextoTerciario,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (e == null) {
                /*
                 * Modulo Z.5. Este `if` decia `e == null || e.advertencias.isEmpty()`
                 * y las juntaba en una sola rama: con el servidor caido, la
                 * pantalla afirmaba "No tienes advertencias. Nada que corregir."
                 *
                 * Es la peor version del mismo error que en `dispositivos` y
                 * `sesiones`, por lo que el proyecto ya dice de las
                 * advertencias: no se pueden silenciar porque una advertencia
                 * que no llega no cumple su unica funcion, que es dar la
                 * oportunidad de corregir antes de la sancion. Una advertencia
                 * que la pantalla NIEGA es lo mismo con un paso mas.
                 *
                 * Lo encontre en una captura, mirando la pantalla que acababa
                 * de arreglar dos bloques mas abajo. El defecto estaba seis
                 * lineas arriba del que estaba corrigiendo.
                 */
                item {
                    EstadoDeError(
                        titulo = "No se pudo comprobar",
                        detalle = "No pudimos leer el estado de tu cuenta. Esto NO " +
                            "quiere decir que no tengas advertencias.",
                        onReintentar = {
                            cargando = true
                            ambito.launch {
                                estado = app.repo.miEstadoModeracion()
                                eventos = app.repo.misEventosSeguridad()
                                cargando = false
                            }
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            } else if (e.advertencias.isEmpty()) {
                item {
                    Text(
                        "No tienes advertencias. Nada que corregir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            } else {
                items(e.advertencias, key = { it.id }) { a ->
                    FilaAdvertencia(a) {
                        ambito.launch {
                            app.repo.reconocerAdvertencia(a.id)
                            estado = app.repo.miEstadoModeracion()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Una advertencia caduca a los 90 dias. Al llegar al tope, la cuenta " +
                            "queda suspendida por un tiempo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                    Spacer(Modifier.height(22.dp))
                }
            }

            item {
                Text(
                    "Actividad de la cuenta",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextoPrimario,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Si ves un ingreso que no reconoces, cambia tu contraseña.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoTerciario,
                )
                Spacer(Modifier.height(10.dp))
            }

            val registro = eventos
            if (registro == null) {
                item {
                    EstadoDeError(
                        titulo = "No se pudo comprobar",
                        detalle = "No pudimos leer el registro de seguridad de tu " +
                            "cuenta. Esto NO quiere decir que no haya nada.",
                        onReintentar = {
                            cargando = true
                            ambito.launch {
                                estado = app.repo.miEstadoModeracion()
                                eventos = app.repo.misEventosSeguridad()
                                cargando = false
                            }
                        },
                    )
                }
            } else if (registro.isEmpty()) {
                item {
                    Text(
                        "Todavía no hay nada registrado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoTerciario,
                    )
                }
            } else {
                items(registro) { ev -> FilaEvento(ev) }
            }
        }
    }
}

@Composable
private fun TarjetaSuspension(e: MiEstadoModeracion) {
    Surface(
        color = Coral.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Block, null, tint = Coral, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Tu cuenta esta suspendida",
                    style = MaterialTheme.typography.titleSmall,
                    color = Coral,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                e.suspensionMotivo ?: "Sin motivo registrado.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextoPrimario,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Una suspension sin fecha es un baneo, y conviene que se lea
                // distinto de una que vence el viernes.
                e.suspendidoHasta?.let { "Se levanta el ${fechaLarga(it)}." }
                    ?: "No tiene fecha de fin.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Puedes leer, pero no enviar mensajes.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
            )
        }
    }
}

@Composable
private fun FilaAdvertencia(a: MiAdvertencia, onReconocer: () -> Unit) {
    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Ambar, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    com.wtfuck.protocol.MotivoDenuncia.etiqueta(a.motivo),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextoPrimario,
                )
            }
            if (a.detalle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(a.detalle, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                fechaLarga(a.creadaEn) + (a.venceEn?.let { "  ·  caduca el ${fechaLarga(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )
            if (!a.reconocida) {
                Spacer(Modifier.height(4.dp))
                // Reconocer no la borra: solo deja constancia de que se leyo.
                // Si la borrara, nadie tendria motivo para no reconocerla y el
                // boton seria un "deshacer" disfrazado.
                TextButton(onClick = onReconocer, contentPadding = PaddingValues(0.dp)) {
                    Text("Entendido", color = Cian, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FilaEvento(ev: EventoSeguridad) {
    val (icono, tinte, texto) = describir(ev.tipo)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = tinte, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(texto, style = MaterialTheme.typography.bodyMedium, color = TextoPrimario)
            Text(
                fechaLarga(ev.cuando) + (ev.ip?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )
        }
    }
    HorizontalDivider(color = Slate.copy(alpha = 0.2f))
}

private fun describir(tipo: String): Triple<ImageVector, Color, String> = when (tipo) {
    "registro" -> Triple(Icons.Filled.DevicesOther, Cian, "Cuenta creada")
    "ingreso" -> Triple(Icons.AutoMirrored.Filled.Login, Cian, "Ingreso")
    "ingreso_fallido" -> Triple(Icons.Filled.Block, Coral, "Intento de ingreso fallido")
    "sesion_cerrada" -> Triple(Icons.AutoMirrored.Filled.Login, Slate, "Sesión cerrada")
    "dispositivo_nuevo" -> Triple(Icons.Filled.DevicesOther, Ambar, "Dispositivo nuevo")
    "dispositivo_revocado" -> Triple(Icons.Filled.DevicesOther, Slate, "Dispositivo revocado")
    "clave_identidad_cambiada" -> Triple(Icons.Filled.Key, Ambar, "Cambio de clave de cifrado")
    "limite_excedido" -> Triple(Icons.Filled.Speed, Ambar, "Se alcanzo un limite de uso")
    "denuncia_creada" -> Triple(Icons.Filled.Warning, Slate, "Enviaste una denuncia")
    "advertencia_recibida" -> Triple(Icons.Filled.Warning, Ambar, "Recibiste una advertencia")
    "restriccion_aplicada" -> Triple(Icons.Filled.Block, Ambar, "Te restringieron en una conversación")
    "cuenta_suspendida" -> Triple(Icons.Filled.Block, Coral, "Tu cuenta fue suspendida")
    "cuenta_restaurada" -> Triple(Icons.Filled.Block, Cian, "Tu cuenta fue restaurada")
    "staff_otorgado" -> Triple(Icons.Filled.Key, Cian, "Se te dio un rol de plataforma")
    "staff_retirado" -> Triple(Icons.Filled.Key, Slate, "Se te retiro el rol de plataforma")
    else -> Triple(Icons.Filled.Speed, Slate, tipo)
}
