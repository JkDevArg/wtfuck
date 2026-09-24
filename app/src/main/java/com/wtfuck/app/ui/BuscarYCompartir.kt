package com.wtfuck.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch
import com.wtfuck.protocol.Contacto
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

// ============================================================
//  M.3 · Buscar dentro de la conversacion
// ============================================================

/**
 * La barra que reemplaza a la cabecera mientras se busca.
 *
 * Reemplaza en vez de agregarse abajo por una razon de espacio: en un telefono
 * de 5", con la cabecera del chat, una barra de busqueda y el teclado abierto,
 * quedan tres burbujas visibles. Y mientras se busca, el nombre y la presencia
 * de la otra persona no son lo que se necesita mirar.
 *
 * El contador "3 de 12" no es decoracion: sin el, tocar "siguiente" en un chat
 * largo no da ninguna referencia de donde se esta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarraBusquedaChat(
    consulta: String,
    onConsulta: (String) -> Unit,
    total: Int,
    cual: Int,
    onMover: (Int) -> Unit,
    onCerrar: () -> Unit,
) {
    val foco = remember { FocusRequester() }
    // El teclado se abre solo: nadie abre un buscador para no escribir.
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
        navigationIcon = {
            IconButton(onClick = onCerrar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar la busqueda", tint = TextoPrimario)
            }
        },
        title = {
            TextField(
                value = consulta,
                onValueChange = onConsulta,
                placeholder = { Text("Buscar en esta conversacion", color = TextoTerciario) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color_transparente,
                    unfocusedContainerColor = Color_transparente,
                    focusedIndicatorColor = Color_transparente,
                    unfocusedIndicatorColor = Color_transparente,
                    focusedTextColor = TextoPrimario,
                    unfocusedTextColor = TextoPrimario,
                    cursorColor = Cian,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(foco),
            )
        },
        actions = {
            if (consulta.trim().length >= 2) {
                Text(
                    if (total == 0) "sin resultados" else "${cual + 1} de $total",
                    color = if (total == 0) TextoTerciario else TextoSecundario,
                    fontSize = 12.sp,
                )
                // Las flechas solo con algo que recorrer: dos botones que no
                // hacen nada invitan a tocarlos y a dudar de la app.
                if (total > 0) {
                    IconButton(onClick = { onMover(-1) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Anterior", tint = TextoSecundario)
                    }
                    IconButton(onClick = { onMover(1) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Siguiente", tint = TextoSecundario)
                    }
                }
            }
        },
    )
}

/** Alias local: `Color.Transparent` sin arrastrar el import a este archivo. */
private val Color_transparente = androidx.compose.ui.graphics.Color.Transparent

// ============================================================
//  M.1 · Compartir ubicacion
// ============================================================

/**
 * Pide UNA posicion y la muestra antes de mandarla.
 *
 * ## Por que se muestra antes
 *
 * Porque compartir donde estas es de las cosas mas dificiles de deshacer que
 * ofrece un mensajero, y el GPS se equivoca: en interiores puede devolver la
 * antena de la esquina con 2 km de margen. Ver las coordenadas y el margen
 * ANTES de enviar es lo que evita mandar una posicion que no es.
 *
 * ## Por que no hay "compartir en vivo"
 *
 * Seguir mandando la posicion cada pocos segundos exige un servicio en primer
 * plano con su propio tipo de permiso, una sesion que caduque sola, y una
 * manera visible de cortarla desde cualquier pantalla. Es un modulo, no un
 * boton. No ofrecerlo es mejor que ofrecer algo que se queda encendido.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaUbicacion(
    onEnviar: (Double, Double, Int, String) -> Unit,
    onCerrar: () -> Unit,
) {
    val ctx = LocalContext.current
    var lugar by remember { mutableStateOf<Location?>(null) }
    var buscando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var etiqueta by remember { mutableStateOf("") }
    var conPermiso by remember {
        mutableStateOf(tienePermisoUbicacion(ctx))
    }
    // Un contador y no un booleano: hay que poder reintentar muchas veces, y
    // un booleano que ya esta en `true` no vuelve a disparar el efecto.
    var relanzar by remember { mutableIntStateOf(0) }

    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { conPermiso = it.values.any { ok -> ok } }

    suspend fun localizar() {
        buscando = true
        error = null
        val r = runCatching { posicionActual(ctx) }
        buscando = false
        r.onSuccess { p ->
            if (p == null) {
                error = "No se pudo obtener la posicion. Prueba al aire libre."
            } else {
                lugar = p
            }
        }.onFailure { error = it.message ?: "No se pudo obtener la posicion." }
    }

    LaunchedEffect(conPermiso, relanzar) { if (conPermiso) localizar() }

    ModalBottomSheet(
        // **Se abre ENTERA, no a media altura.**
        //
        // `ModalBottomSheet` arranca "parcialmente expandido" por defecto, o
        // sea ocupando la mitad de la pantalla, y **no desplaza su contenido**:
        // lo que no entra simplemente no esta. En una hoja que es un
        // formulario, lo que no entra es el boton del final, asi que la
        // funcion entera queda inalcanzable sin que nada lo indique. Fue
        // exactamente lo que paso al publicar una historia.
        //
        // Ninguna hoja de la app lo declaraba: el defecto estaba en las seis
        // que son formularios, y solo se notaba en las mas altas.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),

        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Compartir ubicacion", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(12.dp))

            if (!conPermiso) {
                Text(
                    "Para compartir donde estas hace falta el permiso de ubicacion. " +
                        "Se usa una sola vez, cuando toques enviar: esta app no sigue tu posicion.",
                    color = TextoSecundario,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        pedirPermiso.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cian, contentColor = TextoSobreAcento,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Dar permiso") }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            when {
                buscando -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Cian, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Buscando senal...", color = TextoSecundario)
                }

                error != null -> Text(error!!, color = Coral, style = MaterialTheme.typography.bodyMedium)

                lugar != null -> Surface(
                    color = BgBase,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "%.6f, %.6f".format(Locale.US, lugar!!.latitude, lugar!!.longitude),
                            color = TextoPrimario,
                            style = estiloHuella,
                        )
                        val margen = lugar!!.accuracy.toInt()
                        Text(
                            if (margen > 0) "Margen de $margen m" else "Margen desconocido",
                            // El margen grande se pinta en ambar: a 500 m la
                            // posicion puede ser de otra manzana, y eso hay que
                            // verlo antes de enviar, no despues.
                            color = if (margen in 1..150) TextoTerciario else Ambar,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = etiqueta,
                onValueChange = { if (it.length <= 80) etiqueta = it },
                label = { Text("Nota (opcional)", color = TextoTerciario) },
                placeholder = { Text("Estoy aca", color = TextoTerciario) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Reintentar hace falta de verdad: la primera lectura dentro de
                // un edificio suele volver con 500 m de margen, y a los pocos
                // segundos el GPS ya tiene senal. Sin este boton la unica salida
                // era cerrar la hoja y volver a abrirla.
                OutlinedButton(onClick = { relanzar++ }, enabled = !buscando) {
                    Icon(Icons.Filled.Refresh, "Reintentar", tint = Cian, modifier = Modifier.size(18.dp))
                }
                Button(
                    onClick = {
                        lugar?.let {
                            onEnviar(it.latitude, it.longitude, it.accuracy.toInt(), etiqueta)
                        }
                    },
                    enabled = lugar != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cian, contentColor = TextoSobreAcento,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Place, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enviar ubicacion")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun tienePermisoUbicacion(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Una posicion, con el `LocationManager` del sistema.
 *
 * Sin servicios de Google a proposito: la app no depende de ellos en ningun
 * otro sitio, y agregarlos por esto ataria una funcion opcional a un
 * componente propietario que ademas no existe en muchos aparatos.
 *
 * Se devuelve la ultima conocida si es RECIENTE -menos de dos minutos- y si no
 * se pide una nueva. La ultima conocida sola no alcanza: puede ser de ayer y de
 * otra ciudad, y compartirla seria peor que no compartir nada.
 */
private suspend fun posicionActual(ctx: Context): Location? {
    if (!tienePermisoUbicacion(ctx)) error("Sin permiso de ubicacion.")
    val lm = ctx.getSystemService(LocationManager::class.java)
        ?: error("Este aparato no tiene servicio de ubicacion.")

    val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (proveedores.isEmpty()) error("La ubicacion del telefono esta apagada.")

    val ahora = System.currentTimeMillis()
    val reciente = proveedores
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { ahora - it.time < 120_000 }
        .minByOrNull { it.accuracy }
    if (reciente != null) return reciente

    return suspendCancellableCoroutine { cont ->
        val oyente = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                // Se desengancha ANTES de responder: si el proveedor emite dos
                // veces, `resume` sobre una corrutina ya terminada revienta.
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(l)
            }

            @Deprecated("Requerido en API < 30")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) {
                runCatching { lm.removeUpdates(this) }
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(oyente) } }
        runCatching {
            lm.requestLocationUpdates(proveedores.first(), 0L, 0f, oyente, Looper.getMainLooper())
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }
}

// ============================================================
//  M.1 · Compartir un contacto
// ============================================================

/**
 * Elige a alguien de la libreta de esta app para mandarlo como tarjeta.
 *
 * Solo gente de aqui, y no la agenda del telefono. Mandar el numero de un
 * tercero es entregar el dato personal de alguien que no esta en la
 * conversacion y no dio permiso; un `@usuario` de esta plataforma ya es visible
 * dentro de la app, y si esa persona no acepta mensajes de desconocidos, el
 * receptor se choca con su privacidad al intentar abrir el chat, que es donde
 * corresponde comprobarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaContacto(
    onEnviar: (String, String) -> Unit,
    onCerrar: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var lista by remember { mutableStateOf<List<Contacto>?>(null) }
    var filtro by remember { mutableStateOf("") }

    // Modulo Z.5: un fallo no se convierte en "no tenes contactos".
    // `lista` nula ya significaba "todavia cargando", asi que el fallo
    // necesita su propia bandera; usar la nula para las dos cosas deja la
    // hoja girando para siempre, que es otra forma de no decir lo que paso.
    var fallo by remember { mutableStateOf(false) }
    val ambito = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { app.repo.contactos() }
            .onSuccess { lista = it; fallo = false }
            .onFailure { fallo = true }
    }

    val visibles = remember(lista, filtro) {
        val q = filtro.trim().lowercase()
        lista.orEmpty().filter {
            q.isEmpty() || it.username.contains(q) ||
                it.nombreMostrado.lowercase().contains(q) ||
                it.alias.orEmpty().lowercase().contains(q)
        }
    }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),

        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Compartir contacto", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(4.dp))
            Text(
                "Solo se comparte el usuario de esta app, nunca un telefono.",
                color = TextoTerciario,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))

            // El buscador aparece solo si hay suficientes contactos para que
            // buscar tenga sentido. Con cuatro, filtrar es mas trabajo que mirar.
            if (lista.orEmpty().size > 6) {
                OutlinedTextField(
                    value = filtro,
                    onValueChange = { filtro = it },
                    placeholder = { Text("Buscar", color = TextoTerciario) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextoTerciario) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }

            when {
                // El fallo va ANTES del vacio y antes de la carga: al fallar,
                // `lista` sigue nula y sin esta rama la hoja gira sin fin.
                fallo -> EstadoDeError(
                    titulo = "No se pudo cargar tu libreta",
                    detalle = "Esto NO quiere decir que no tengas contactos.",
                    onReintentar = {
                        ambito.launch {
                            fallo = false
                            runCatching { app.repo.contactos() }
                                .onSuccess { lista = it }
                                .onFailure { fallo = true }
                        }
                    },
                )

                lista == null -> Box(
                    Modifier.fillMaxWidth().height(120.dp), Alignment.Center,
                ) { CircularProgressIndicator(color = Cian) }

                lista!!.isEmpty() -> Text(
                    "Todavia no tienes contactos guardados. Se agregan desde la " +
                        "pestana Contactos.",
                    color = TextoSecundario,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 20.dp),
                )

                else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(visibles, key = { it.username }) { c ->
                        val nombre = c.alias?.ifBlank { null }
                            ?: c.nombreMostrado.ifBlank { c.username }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onEnviar(c.username, nombre) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                nombre = nombre,
                                url = ApiCliente.urlImagen(c.username, "avatar", c.avatarVersion),
                                tamano = 40.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nombre, color = TextoPrimario, fontSize = 15.sp)
                                Text("@${c.username}", color = TextoTerciario, fontSize = 12.sp)
                            }
                            if (c.favorito) {
                                Icon(
                                    Icons.Filled.Star, null, tint = Ambar,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
