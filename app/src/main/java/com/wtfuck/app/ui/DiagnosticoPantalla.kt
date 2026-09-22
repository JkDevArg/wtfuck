package com.wtfuck.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.BuildConfig
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.TipoCuenta
import kotlinx.coroutines.launch

/**
 * Modulo P · Diagnostico, para cuentas de tipo `desarrollador`.
 *
 * ## Que muestra, y por que no hace falta autorizacion del servidor
 *
 * Todo lo de aqui es **estado de este telefono**: a que servidor apunta, si el
 * socket esta abierto, cuanto hay en la cola de salida, cuantas filas tiene la
 * base local, cuanto ocupan los archivos. Nada de esto es informacion de otra
 * persona ni sale de este aparato, asi que no hay nada que autorizar: es lo
 * mismo que se veria con el telefono en la mano.
 *
 * Eso es precisamente lo que hace que el modo desarrollador sea seguro de
 * repartir. Si esta pantalla mostrara datos de **otras cuentas**, la puerta no
 * podria ser un tipo de cuenta: tendria que ser `staff_nivel` y una consulta
 * autorizada en el servidor, como el panel de moderacion.
 *
 * ## Por que existe
 *
 * Hasta ahora, para saber por que un mensaje no salia habia que enchufar el
 * telefono y leer `logcat`. Eso funciona en un escritorio con el SDK al lado;
 * no funciona cuando el aparato que falla esta en otra ciudad. Estos seis
 * numeros son los que se miran primero, siempre, y ahora se pueden pedir por
 * chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticoPantalla(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    val cola by app.repo.tamanoCola.collectAsStateWithLifecycle(0)
    val fallidos by app.repo.tamanoFallidos.collectAsStateWithLifecycle(0)

    var bytes by remember { mutableStateOf(0L) }
    var archivos by remember { mutableStateOf(0) }
    var aviso by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bytes = app.archivos.bytesUsados()
        archivos = app.archivos.cuantosArchivos()
    }

    val lineas = remember(cola, fallidos, bytes, archivos) {
        listOf(
            "Version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            "Servidor" to BuildConfig.SERVIDOR,
            "WebSocket" to BuildConfig.SERVIDOR_WS,
            "Usuario" to "@${app.sesion.username.orEmpty()}",
            "Dispositivo" to app.sesion.dispositivoId.orEmpty().take(8),
            "Cola de salida" to "$cola pendientes",
            "Envios fallidos" to "$fallidos",
            "Archivos locales" to "$archivos ($bytes bytes)",
            "Hardware dev" to BuildConfig.PERMITIR_SOFTWARE_DEV.toString(),
        )
    }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico", color = TextoPrimario) },
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        // `AutoMirrored` y no `Filled` como el resto de la
                        // app: la flecha de volver apunta al otro lado cuando
                        // la escritura va de derecha a izquierda.
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Atras",
                            tint = TextoPrimario,
                        )
                    }
                },
                actions = {
                    // Copiar entero y no campo por campo: esto se pega en un
                    // chat para que alguien lo mire, y nueve toques para
                    // reunir nueve datos es como se pierde la mitad.
                    IconButton(onClick = {
                        copiar(ctx, lineas.joinToString("\n") { "${it.first}: ${it.second}" })
                        aviso = "Copiado"
                    }) {
                        Icon(Icons.Filled.ContentCopy, "Copiar el diagnóstico", tint = Cian)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Estado de este teléfono. Nada de esto sale del aparato ni " +
                    "muestra datos de otras cuentas.",
                color = TextoTerciario,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .padding(vertical = 4.dp),
            ) {
                lineas.forEach { (k, v) -> FilaDato(k, v) }
            }

            Spacer(Modifier.height(18.dp))
            // A la vista esto separa la tabla de los botones porque esta en
            // versalitas y despegado; como encabezado de verdad, TalkBack
            // permite saltar hasta aqui en vez de recorrer las nueve filas.
            Text(
                "ACCIONES", color = TextoTerciario, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    ambito.launch {
                        app.repo.sincronizar()
                        aviso = "Sincronizado"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Volver a sincronizar con el servidor", color = Cian)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    ambito.launch {
                        app.repo.despachar()
                        aviso = "Cola empujada"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Empujar la cola de salida", color = Cian)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Este modo lo asigna el equipo y queda registrado en la bitácora. " +
                    "No da acceso a datos de nadie más: para eso está el panel de " +
                    "moderación, que exige nivel de plataforma.",
                color = TextoTerciario,
                fontSize = 11.5.sp,
            )
        }
    }

    aviso?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(1500); aviso = null }
        Box(Modifier.fillMaxSize().padding(bottom = 30.dp), Alignment.BottomCenter) {
            Text(
                msg,
                color = TextoSobreAcento,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Cian)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    // "Copiado" y "Sincronizado" son el UNICO acuse de que el
                    // toque hizo algo, y duran 1,5 s. Sin region viva, quien
                    // no ve la pantalla toca el boton y no se entera de nada.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Una fila clave-valor de la tabla.
 *
 * A la vista la clave y el valor estan en la misma linea y se leen juntos; sin
 * fusionar eran dos paradas, y la segunda —"0 pendientes"— no dice de que. Peor
 * todavia con los valores cortos: "8" sin su clave delante no es un dato, es
 * ruido. Se fusiona y se dice entera.
 */
@Composable
private fun FilaDato(clave: String, valor: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$clave: $valor" }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clave, color = TextoSecundario, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            valor,
            color = TextoPrimario,
            fontSize = 12.5.sp,
            // Monoespaciada: aqui se leen ids y URLs, y con proporcional un
            // 0 y una O se confunden justo cuando importan.
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun copiar(ctx: Context, texto: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("diagnostico wtfuck", texto))
}
