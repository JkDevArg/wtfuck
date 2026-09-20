package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import com.wtfuck.app.datos.Hardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.*
import kotlinx.coroutines.launch

/**
 * Cuenta y seguridad.
 *
 * ## Lo que esta pantalla tiene que dejar claro
 *
 * Que sin correo verificado **olvidar la contrasena es perder la cuenta**. No
 * es una advertencia decorativa: hasta el modulo I era literalmente cierto y no
 * habia nada que hacer al respecto. Decirlo despues de que pase no sirve, asi
 * que se dice arriba y en ambar mientras falte.
 *
 * ## Por que el numero se pide "otra vez" al recuperar
 *
 * El servidor guarda solo el hash del numero, nunca el numero. Eso suena a que
 * no podria mandar nada, y se resuelve porque el numero siempre esta delante
 * cuando hace falta: aqui lo escribe el usuario. Vale explicarlo en la
 * pantalla, porque si no parece un olvido del sistema.
 *
 * ## El telefono no sirve para entrar
 *
 * El ingreso sigue siendo por usuario y contrasena. El numero es solo el canal
 * de recuperacion y de descubrimiento, y decirlo evita la pregunta obvia de
 * "¿entonces ahora entro con mi numero?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuentaPantalla(
    onAtras: () -> Unit,
    onCerrarSesion: () -> Unit,
    onDispositivos: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var estado by remember { mutableStateOf<EstadoCuenta?>(null) }
    var sesiones by remember { mutableStateOf<List<SesionActiva>>(emptyList()) }
    var cargando by remember { mutableStateOf(true) }
    var aviso by remember { mutableStateOf<String?>(null) }

    var verificandoTelefono by remember { mutableStateOf(false) }
    var activandoTotp by remember { mutableStateOf(false) }
    var eliminando by remember { mutableStateOf(false) }
    var apagandoTotp by remember { mutableStateOf(false) }

    suspend fun recargar() {
        estado = app.repo.estadoCuenta()
        sesiones = app.repo.sesiones()
        cargando = false
    }

    LaunchedEffect(Unit) { recargar() }

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
                title = { Text("Cuenta y seguridad", color = TextoPrimario) },
            )
        },
    ) { pad ->
        if (cargando) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        val e = estado
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (e == null) {
                Text("No se pudo leer el estado de la cuenta.", color = TextoSecundario)
                return@Column
            }

            if (e.eliminacionPedidaEn != null) {
                TarjetaEliminacionPendiente(e)
                Spacer(Modifier.height(16.dp))
            }

            // --- recuperacion -------------------------------------------
            if (!e.puedeRecuperarse) {
                Surface(
                    color = Ambar.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Ambar, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Si olvidas la contrasena, pierdes la cuenta",
                                style = MaterialTheme.typography.titleSmall,
                                color = Ambar,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tu cuenta es solo usuario y contrasena, asi que hoy no hay por " +
                                "donde devolverte el acceso. Verifica tu numero y eso deja de " +
                                "ser cierto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoSecundario,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Seccion("Numero de telefono", Icons.Filled.PhoneAndroid)

            if (e.telefonoVerificado) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Cian, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Verificado (+${e.telefonoPais})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoPrimario,
                        )
                        Text(
                            // Lo importante de esta linea: explica por que el
                            // numero no se muestra, en vez de dejar que parezca
                            // un dato que se perdio.
                            "Guardamos solo una huella de tu numero, no el numero. Por eso " +
                                "hay que escribirlo de nuevo al recuperar la cuenta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoTerciario,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { verificandoTelefono = true }) {
                        Text("Cambiarlo", color = Cian)
                    }
                    TextButton(onClick = {
                        ambito.launch {
                            app.repo.quitarTelefono()
                            recargar()
                        }
                    }) { Text("Quitarlo", color = Coral) }
                }
            } else {
                Text(
                    "Sirve para dos cosas: recuperar la cuenta si olvidas la contrasena, y " +
                        "que te encuentren quienes ya tienen tu numero en la agenda. " +
                        "No se usa para entrar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { verificandoTelefono = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                ) { Text("Verificar mi numero") }
            }

            if (e.telefonoVerificado) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = e.descubrible,
                        onCheckedChange = { v ->
                            ambito.launch { app.repo.ajustarCuenta(descubrible = v); recargar() }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextoSobreAcento,
                            checkedTrackColor = Cian,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Que me encuentren por mi numero",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextoPrimario,
                        )
                        Text(
                            // El motivo del valor por defecto, dicho en una
                            // linea: quien tiene tu numero ya te conoce.
                            "Solo aparece quien ya tiene tu numero guardado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoTerciario,
                        )
                    }
                }
            }

            Divisor()

            // --- 2FA ---------------------------------------------------
            Seccion("Verificacion en dos pasos", Icons.Filled.Shield)

            if (e.totpActivado) {
                Text(
                    "Activada. Al ingresar se pide un codigo de tu app de autenticacion.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoPrimario,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Te quedan ${e.codigosRespaldoSinUsar} codigos de respaldo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.codigosRespaldoSinUsar <= 2) Ambar else TextoTerciario,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { apagandoTotp = true }) {
                    Text("Desactivar", color = Coral)
                }
            } else {
                Text(
                    "Un codigo que cambia cada 30 segundos, ademas de tu contrasena.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { activandoTotp = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
                ) { Text("Activar") }
            }

            Divisor()

            // --- vinculo con el hardware --------------------------------
            //
            // Este bloque estaba en la pestaña de perfil, con la huella en
            // crudo, empujando los ajustes para abajo. Es un dato de auditoria:
            // se mira una vez para comprobar a que aparato quedo atada la
            // cuenta, y despues nunca mas. Su sitio es aqui, y en el perfil
            // quedo una linea que solo dice si el vinculo es fuerte.
            Seccion("Este aparato", Icons.Filled.Lock)
            TarjetaHardware(LocalContext.current)

            Divisor()

            // --- dispositivos ------------------------------------------
            //
            // Va ANTES de las sesiones y no despues, porque no son lo mismo y
            // el orden lo insinua: un dispositivo es un aparato con tus
            // mensajes descifrados; una sesion es un token abierto en uno de
            // ellos. Revocar un dispositivo mata sus sesiones; cerrar una
            // sesion no quita el dispositivo.
            Seccion("Mis dispositivos", Icons.Filled.PhoneAndroid)
            Text(
                "Cada dispositivo es una copia mas de tus mensajes. Ahi se agregan, se " +
                    "revocan, y se ve cual es el principal.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDispositivos,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cian),
            ) { Text("Ver mis dispositivos") }

            Divisor()

            // --- sesiones ----------------------------------------------
            Seccion("Sesiones abiertas", Icons.Filled.Devices)
            Text(
                "Si ves una que no reconoces, cierrala y cambia tu contrasena.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoTerciario,
            )
            Spacer(Modifier.height(10.dp))

            sesiones.forEach { s ->
                FilaSesion(s) {
                    ambito.launch {
                        app.repo.cerrarSesionRemota(s.id)
                            .onSuccess { recargar() }
                            .onFailure { aviso = it.message }
                    }
                }
            }

            if (sesiones.size > 1) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    ambito.launch {
                        app.repo.cerrarOtrasSesiones()
                            .onSuccess { n -> aviso = "Se cerraron $n sesiones."; recargar() }
                            .onFailure { aviso = it.message }
                    }
                }) { Text("Cerrar todas las demas", color = Coral) }
            }

            Divisor()

            // --- eliminar ----------------------------------------------
            Seccion("Eliminar la cuenta", Icons.Filled.DeleteForever)
            Text(
                "Hay $DIAS_GRACIA_ELIMINACION dias para cambiar de idea: entrar de nuevo la cancela.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { eliminando = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral),
            ) { Text("Eliminar mi cuenta") }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (verificandoTelefono) {
        DialogoVerificarTelefono(
            onCerrar = { verificandoTelefono = false },
            onListo = {
                verificandoTelefono = false
                ambito.launch { recargar() }
            },
        )
    }

    if (activandoTotp) {
        DialogoActivarTotp(
            onCerrar = { activandoTotp = false },
            onListo = { activandoTotp = false; ambito.launch { recargar() } },
        )
    }

    if (apagandoTotp) {
        DialogoClave(
            titulo = "Desactivar los dos pasos",
            explicacion = "Pedimos la contrasena porque, si no, una sesion robada podria " +
                "quitar el segundo factor y quedarse con la cuenta.",
            etiquetaBoton = "Desactivar",
            onCerrar = { apagandoTotp = false },
            onConfirmar = { clave, _ ->
                ambito.launch {
                    app.repo.apagarTotp(clave)
                        .onSuccess { apagandoTotp = false; recargar() }
                        .onFailure { aviso = it.message }
                }
            },
        )
    }

    if (eliminando) {
        DialogoEliminarCuenta(
            pideTotp = estado?.totpActivado == true,
            onCerrar = { eliminando = false },
            onPedida = { info ->
                eliminando = false
                ambito.launch { recargar() }
                aviso = "Tu cuenta se elimina en ${info.diasDeGracia} dias. " +
                    "Entrar de nuevo antes cancela la eliminacion."
                onCerrarSesion()
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("Listo", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Cerrar", color = Cian) } },
        )
    }
}

@Composable
private fun Seccion(titulo: String, icono: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icono, null, tint = Cian, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(titulo, style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
    }
}

@Composable
private fun Divisor() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = Slate.copy(alpha = 0.25f))
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun TarjetaEliminacionPendiente(e: EstadoCuenta) {
    Surface(
        color = Coral.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Tu cuenta se va a eliminar",
                style = MaterialTheme.typography.titleSmall,
                color = Coral,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                e.eliminacionSeEjecutaEn?.let { "Se ejecuta el ${fechaLarga(it)}." } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = TextoPrimario,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Ya la cancelaste al volver a entrar. Si no querias eliminarla, no hagas nada mas.",
                style = MaterialTheme.typography.bodySmall,
                color = TextoSecundario,
            )
        }
    }
}

@Composable
private fun FilaSesion(s: SesionActiva, onCerrar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.esLaActual) "Este dispositivo" else "Otra sesion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoPrimario,
                )
                if (s.esLaActual) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "actual",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cian,
                    )
                }
            }
            Text(
                buildString {
                    append(s.ip ?: "sin IP registrada")
                    // Ultimo uso y no solo apertura: es lo que distingue una
                    // sesion viva de una que quedo abierta hace meses, que es
                    // justamente lo que hace falta para decidir cual cerrar.
                    s.ultimoUsoEn?.let { append("  ·  activa ${fechaLarga(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextoTerciario,
            )
        }
        if (!s.esLaActual) {
            TextButton(onClick = onCerrar) { Text("Cerrar", color = Coral) }
        }
    }
    HorizontalDivider(color = Slate.copy(alpha = 0.2f))
}

// ============================================================
//  Verificar el telefono
// ============================================================

/**
 * Dos pasos en un solo dialogo.
 *
 * No se separan en dos pantallas porque el SMS llega en segundos y el numero
 * tiene que seguir a la vista: si el usuario se equivoco al escribirlo, tiene
 * que poder verlo sin volver atras.
 *
 * El prefijo por defecto se muestra en el texto de ayuda. Un numero sin `+` es
 * ambiguo, el servidor asume el prefijo local, y descubrirlo por el mensaje que
 * no llega seria la peor forma de enterarse.
 */
@Composable
private fun DialogoVerificarTelefono(onCerrar: () -> Unit, onListo: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var telefono by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var pedido by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deprueba by remember { mutableStateOf<String?>(null) }

    // Se normaliza mientras escribe y se muestra el resultado. Asi la persona
    // VE que "987654321" se va a mandar como "+51987654321", en vez de tener
    // que confiar.
    val normalizado = remember(telefono) { Telefonos.normalizar(telefono) }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = { Text("Verificar mi numero", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = telefono,
                    onValueChange = { telefono = it; error = null },
                    label = { Text("Numero de telefono") },
                    placeholder = { Text("+51 987 654 321", color = TextoTerciario) },
                    enabled = !pedido,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        telefono.isBlank() ->
                            "Sin prefijo se asume +${Telefonos.PAIS_POR_DEFECTO}."
                        normalizado != null -> "Se enviara a $normalizado"
                        else -> "Ese numero no parece completo."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (normalizado == null && telefono.isNotBlank()) Ambar else TextoTerciario,
                )

                if (pedido) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = codigo,
                        onValueChange = { if (it.length <= 6) codigo = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Codigo de 6 digitos") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Solo aparece cuando el servidor no tiene pasarela de SMS.
                    // Que se vea en pantalla es a proposito: si alguien
                    // despliega sin SMS configurado, esto lo grita.
                    deprueba?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Servidor sin pasarela de SMS. Codigo: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ambar,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Coral)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !trabajando && (pedido || normalizado != null),
                onClick = {
                    trabajando = true
                    error = null
                    ambito.launch {
                        if (!pedido) {
                            app.repo.pedirCodigoTelefono(telefono.trim())
                                .onSuccess { pedido = true; deprueba = it.codigoDePrueba }
                                .onFailure { error = it.message }
                        } else {
                            app.repo.verificarTelefono(telefono.trim(), codigo)
                                .onSuccess { onListo() }
                                .onFailure { error = it.message }
                        }
                        trabajando = false
                    }
                },
            ) { Text(if (pedido) "Verificar" else "Enviarme el codigo", color = Cian) }
        },
        dismissButton = {
            TextButton(enabled = !trabajando, onClick = onCerrar) {
                Text("Cancelar", color = TextoSecundario)
            }
        },
    )
}

// ============================================================
//  Activar el 2FA
// ============================================================

@Composable
private fun DialogoActivarTotp(onCerrar: () -> Unit, onListo: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var inicio by remember { mutableStateOf<TotpIniciado?>(null) }
    var codigo by remember { mutableStateOf("") }
    var respaldos by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.repo.iniciarTotp()
            .onSuccess { inicio = it }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = {
            Text(
                if (respaldos != null) "Guarda estos codigos" else "Verificacion en dos pasos",
                color = TextoPrimario,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = respaldos
                if (r != null) {
                    Text(
                        // Este texto importa: los codigos no se pueden volver a
                        // mostrar porque en el servidor estan hasheados, y un
                        // segundo factor sin salida de emergencia convierte
                        // perder el telefono en perder la cuenta.
                        "No se pueden volver a mostrar. Son tu salida si pierdes el telefono: " +
                            "cada uno sirve una vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(12.dp))
                    r.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = Cian,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                } else {
                    Text(
                        "Copia esta clave en tu app de autenticacion y escribe el codigo que te muestre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(color = BgSurface, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            inicio?.secretoBase32?.chunked(4)?.joinToString(" ") ?: "...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = TextoPrimario,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = codigo,
                        onValueChange = { if (it.length <= 6) codigo = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Codigo de la app") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Coral)
                }
            }
        },
        confirmButton = {
            if (respaldos != null) {
                TextButton(onClick = onListo) { Text("Ya los guarde", color = Cian) }
            } else {
                TextButton(
                    enabled = !trabajando && codigo.length == 6,
                    onClick = {
                        trabajando = true
                        error = null
                        ambito.launch {
                            app.repo.confirmarTotp(codigo)
                                .onSuccess { respaldos = it.codigosRespaldo }
                                .onFailure { error = it.message }
                            trabajando = false
                        }
                    },
                ) { Text("Activar", color = Cian) }
            }
        },
        dismissButton = {
            if (respaldos == null) {
                TextButton(enabled = !trabajando, onClick = onCerrar) {
                    Text("Cancelar", color = TextoSecundario)
                }
            }
        },
    )
}

// ============================================================
//  Dialogos que piden la contrasena
// ============================================================

@Composable
private fun DialogoClave(
    titulo: String,
    explicacion: String,
    etiquetaBoton: String,
    pideTotp: Boolean = false,
    onCerrar: () -> Unit,
    onConfirmar: (clave: String, totp: String?) -> Unit,
) {
    var clave by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        title = { Text(titulo, color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column {
                Text(explicacion, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    label = { Text("Tu contrasena") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cian,
                        unfocusedBorderColor = Slate,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pideTotp) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = totp,
                        onValueChange = { totp = it },
                        label = { Text("Codigo de dos pasos") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian,
                            unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = clave.isNotBlank(),
                onClick = { onConfirmar(clave, totp.ifBlank { null }) },
            ) { Text(etiquetaBoton, color = Coral) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
        },
    )
}

@Composable
private fun DialogoEliminarCuenta(
    pideTotp: Boolean,
    onCerrar: () -> Unit,
    onPedida: (EliminacionPedida) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var advertencias by remember { mutableStateOf<List<String>?>(null) }

    // Antes de pedir la contrasena se dice lo que NO se puede borrar. Al reves
    // seria pedir una decision sin la informacion que la cambia.
    if (advertencias == null) {
        AlertDialog(
            onDismissRequest = onCerrar,
            containerColor = BgElev,
            title = { Text("Antes de eliminar", color = TextoPrimario, fontSize = 18.sp) },
            text = {
                Column {
                    Text(
                        "Hay cosas que no podemos borrar, y conviene que las sepas:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoPrimario,
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Los mensajes que enviaste estan en los telefonos de las otras personas. " +
                            "El servidor no los tiene y nadie puede borrarlos de ahi.",
                        "Lo que publicaste en canales publicos no se borra: es de la audiencia " +
                            "del canal, no de tu cuenta.",
                        "Si hay una denuncia abierta sobre ti, sigue su curso.",
                    ).forEach {
                        Text(
                            "·  $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextoSecundario,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tienes $DIAS_GRACIA_ELIMINACION dias para cambiar de idea: entrar de nuevo cancela todo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ambar,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { advertencias = emptyList() }) {
                    Text("Entiendo, continuar", color = Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = onCerrar) { Text("Cancelar", color = TextoSecundario) }
            },
        )
        return
    }

    DialogoClave(
        titulo = "Eliminar mi cuenta",
        explicacion = error
            ?: "Es la accion mas destructiva que hay aqui, asi que pedimos la contrasena: " +
            "una sesion robada no deberia poder ejecutarla.",
        etiquetaBoton = "Eliminar",
        pideTotp = pideTotp,
        onCerrar = onCerrar,
        onConfirmar = { clave, totp ->
            ambito.launch {
                app.repo.pedirEliminacion(clave, totp)
                    .onSuccess { onPedida(it) }
                    .onFailure { error = it.message }
            }
        },
    )
}

/**
 * A que hardware quedo atada la cuenta, y con cuanta garantia.
 *
 * Lo que hace distinta a esta app no esta escondido en un menu: se puede ver.
 * `STRONGBOX` es un chip de seguridad aparte, `TEE` es la zona segura del
 * procesador, y cualquier otra cosa significa que la clave esta en software y
 * que el vinculo con el aparato es una promesa mas debil. Decirlo es la unica
 * manera de que la garantia signifique algo.
 */
@Composable
private fun TarjetaHardware(ctx: android.content.Context) {
    val identidad by produceState<Hardware.Identidad?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Hardware.identidad(ctx) }.getOrNull() }
    }
    val id = identidad
    if (id == null) {
        Text(
            "No se pudo leer la identidad de este aparato.",
            style = MaterialTheme.typography.bodySmall,
            color = Ambar,
        )
        return
    }
    val fuerte = id.nivel == "STRONGBOX" || id.nivel == "TEE"

    Surface(color = BgSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (fuerte) Icons.Filled.Verified else Icons.Filled.Warning,
                    null,
                    tint = if (fuerte) Cian else Ambar,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fuerte) "Hardware verificado (${id.nivel})" else "Sin enclave seguro (${id.nivel})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fuerte) TextoPrimario else Ambar,
                )
            }
            Spacer(Modifier.height(10.dp))
            FilaHardware("Cuentas", "Una por dispositivo")
            FilaHardware("Historial", "Cifrado en este telefono")
            Spacer(Modifier.height(10.dp))
            Text("Huella del dispositivo", style = MaterialTheme.typography.labelSmall, color = TextoTerciario)
            // Entera y en monoespaciada: si alguna vez hay que compararla con
            // la del servidor, recortarla la vuelve inutil justamente para eso.
            Text(id.hardwareHash, style = estiloHuella, color = TextoSecundario)
        }
    }
}

@Composable
private fun FilaHardware(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = TextoTerciario,
            modifier = Modifier.width(88.dp),
        )
        Text(valor, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
    }
}
