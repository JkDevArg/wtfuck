package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiError
import com.wtfuck.app.datos.Hardware
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthPantalla(onListo: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    // Si llegue aqui por un cierre involuntario, la sesion guarda el motivo y
    // el username sobrevive (ver `Sesion.invalidar`).
    val cierre = remember { app.sesion.motivoCierre }
    LaunchedEffect(Unit) { app.sesion.olvidarMotivo() }

    // La pantalla arranca en "entrar" -y no en "crear cuenta"- en cuanto esta
    // instalacion recuerde un usuario. Ofrecer "crear cuenta" a quien ya tuvo
    // sesion en este aparato es empujarlo a un error garantizado: el hardware
    // ya tiene cuenta y el registro va a fallar por eso, con un mensaje que no
    // explica que lo que hacia falta era entrar.
    var esRegistro by rememberSaveable { mutableStateOf(app.sesion.username == null) }
    var recuperando by remember { mutableStateOf(false) }
    var vinculando by remember { mutableStateOf(false) }
    // Aparte de `error` a proposito: un mensaje de exito en rojo y con un signo
    // de admiracion se lee como una falla.
    var exito by remember { mutableStateOf<String?>(null) }
    // El campo del segundo factor aparece solo cuando el servidor contesta que
    // esta cuenta lo pide. Mostrarlo siempre haria pensar que toda cuenta lo
    // necesita, y pedirlo antes de la contrasena seria un oraculo de "esta
    // cuenta tiene 2FA".
    var pideTotp by remember { mutableStateOf(false) }
    var totp by remember { mutableStateOf("") }
    var usuario by rememberSaveable { mutableStateOf(app.sesion.username.orEmpty()) }
    var clave by rememberSaveable { mutableStateOf("") }
    // El codigo de invitacion, y si este servidor lo pide.
    //
    // Se pregunta al servidor en vez de compilarlo en la app porque el mismo
    // APK sirve a despliegues distintos: el publico no pide codigo y el de un
    // equipo si. Un campo fijo obligaria a compilar dos versiones.
    var codigo by rememberSaveable { mutableStateOf("") }
    var pideInvitacion by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }

    // El modo se pregunta una vez, al abrir la pantalla, y no al pulsar "crear
    // cuenta": asi el campo ya esta cuando hace falta, en vez de aparecer de
    // golpe debajo del dedo.
    LaunchedEffect(Unit) { pideInvitacion = app.repo.registroPideInvitacion() }

    // La identidad de hardware se calcula una vez: genera el par de claves en el
    // Keystore si aun no existe.
    val identidad by produceState<Hardware.Identidad?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { Hardware.identidad(ctx) }.getOrNull()
        }
    }

    fun enviar() {
        val id = identidad ?: return
        val u = usuario.trim().lowercase()
        error = null

        if (u.length < 3) { error = "El usuario necesita al menos 3 caracteres."; return }
        if (!Regex("^[a-z0-9_]+$").matches(u)) { error = "Solo letras, números y guion bajo."; return }
        if (clave.length < 8) { error = "La contraseña necesita al menos 8 caracteres."; return }
        // Se comprueba aqui y no solo en el servidor para no gastar un viaje
        // -y un intento del limitador- en algo que ya se sabe que va a fallar.
        if (esRegistro && pideInvitacion && codigo.isBlank()) {
            error = "Este servidor necesita un código de invitación."
            return
        }

        cargando = true
        ambito.launch {
            val r = runCatching {
                if (esRegistro) app.repo.registrar(u, clave, id, android.os.Build.MODEL ?: "dispositivo", codigo)
                else app.repo.login(u, clave, id, totp.trim().ifBlank { null })
            }
            cargando = false
            r.onSuccess { onListo() }
                .onFailure { e ->
                    val msg = (e as? ApiError)?.message ?: "No se pudo conectar con el servidor."
                    // El servidor pide el segundo factor con un 401 y un mensaje
                    // concreto. Se reconoce por el mensaje y no por el codigo
                    // porque un 401 tambien es "contrasena incorrecta", y los
                    // dos casos necesitan pantallas distintas.
                    if (msg.contains("dos pasos", true)) {
                        pideTotp = true
                        error = if (totp.isBlank()) null else msg
                    } else {
                        error = msg
                    }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("wtfuck", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Cian)
        Spacer(Modifier.height(6.dp))
        Text(
            "Sin número de teléfono. Solo tu usuario.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextoSecundario,
        )

        if (cierre != null) {
            Spacer(Modifier.height(28.dp))
            Surface(
                color = Ambar.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Sesión cerrada",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ambar,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cierre,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tus chats de este aparato siguen aquí. Vuelve a entrar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(40.dp))
        }

        OutlinedTextField(
            value = usuario,
            onValueChange = { usuario = it.filter { c -> !c.isWhitespace() }; exito = null },
            label = { Text("Usuario") },
            prefix = { Text("@", color = TextoTerciario) },
            singleLine = true,
            enabled = !cargando,
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it },
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !cargando,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        // El campo del codigo: solo al crear cuenta y solo si el servidor lo
        // pide. A quien entra no le hace falta, y en un servidor abierto no
        // existe.
        if (esRegistro && pideInvitacion) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = codigo,
                // Se normaliza mientras se escribe -mayusculas, sin espacios-
                // porque el codigo se copia de una captura o se dicta por
                // telefono. El servidor tambien lo limpia; hacerlo aqui
                // ademas es para que se VEA igual al que le pasaron, y no
                // parezca que escribio otra cosa.
                onValueChange = { codigo = it.uppercase().filter { c -> !c.isWhitespace() && c != '-' } },
                label = { Text("Código de invitación") },
                supportingText = {
                    Text(
                        "Este servidor es cerrado. Te lo tiene que dar alguien que ya esté dentro.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                },
                singleLine = true,
                enabled = !cargando,
                isError = error != null,
                keyboardOptions = KeyboardOptions(
                    // Sin autocorreccion ni mayuscula automatica: son doce
                    // caracteres sin sentido y el teclado los "arreglaria".
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (pideTotp) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = totp,
                onValueChange = { totp = it },
                label = { Text("Código de dos pasos") },
                supportingText = {
                    Text(
                        "De tu app de autenticación, o uno de respaldo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoTerciario,
                    )
                },
                singleLine = true,
                enabled = !cargando,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("!", color = Coral, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(error!!, color = Coral, style = MaterialTheme.typography.bodyMedium)
            }
        }

        exito?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(msg, color = Cian, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { enviar() },
            enabled = !cargando && identidad != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (cargando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = TextoSobreAcento,
                )
            } else {
                Text(
                    if (esRegistro) "Crear cuenta" else "Entrar",
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { esRegistro = !esRegistro; error = null; pideTotp = false; totp = "" },
            enabled = !cargando,
        ) {
            Text(
                if (esRegistro) "Ya tengo cuenta" else "Crear una cuenta nueva",
                color = Cian,
            )
        }

        // Solo al ingresar: en el registro no hay nada que recuperar todavia.
        if (!esRegistro) {
            TextButton(onClick = { recuperando = true; error = null }, enabled = !cargando) {
                Text("Olvide mi contraseña", color = TextoSecundario)
            }
        }

        // El tercer camino, y el que la gente no busca hasta que lo necesita:
        // este aparato es NUEVO y la cuenta ya existe en otro. No es registrar
        // -eso crearia una cuenta aparte- ni ingresar -el hardware no esta
        // vinculado y el servidor lo rechaza-.
        TextButton(onClick = { vinculando = true; error = null }, enabled = !cargando) {
            Text("Vincular a una cuenta que ya tengo", color = TextoSecundario)
        }

        Spacer(Modifier.height(32.dp))

        // Transparencia deliberada: el usuario ve a que dispositivo queda atada
        // la cuenta y con que nivel de garantia. Ver docs/04-DEVICE-BINDING.md
        identidad?.let { id ->
            Surface(
                color = BgElev,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = nivelColor(id.nivel), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Una cuenta por dispositivo",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextoPrimario,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        nivelTexto(id.nivel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Huella: " + id.hardwareHash.take(24) + "...",
                        style = estiloHuella,
                        color = TextoTerciario,
                    )
                }
            }
        }
    }

    if (vinculando) {
        DialogoVincular(
            usuarioInicial = usuario,
            identidad = identidad,
            onCerrar = { vinculando = false },
            onVinculado = { vinculando = false; onListo() },
        )
    }

    if (recuperando) {
        DialogoRecuperar(
            usuarioInicial = usuario,
            onCerrar = { recuperando = false },
            onRecuperada = {
                recuperando = false
                pideTotp = false
                // Se limpia el error y se deja el usuario puesto: lo que sigue
                // es entrar con la contrasena nueva, y hacerle volver a
                // escribir el usuario seria friccion sin motivo.
                exito = "Contraseña cambiada. Ya puedes entrar."
                error = null
            },
        )
    }
}

private fun nivelColor(nivel: String) = when (nivel) {
    "STRONGBOX", "TEE" -> Cian
    else -> Ambar
}

private fun nivelTexto(nivel: String) = when (nivel) {
    "STRONGBOX" -> "Tu clave vive en un chip de seguridad dedicado. Es el nivel más alto."
    "TEE" -> "Tu clave vive en el enclave seguro del procesador y no puede salir de ahi."
    else -> "Este dispositivo no tiene enclave seguro (es un emulador o build de prueba). " +
        "Se permite solo en desarrollo."
}



/**
 * Recuperar la cuenta.
 *
 * ## Lo que hay que decir aqui, y no despues
 *
 * Que esto **no devuelve el acceso desde otro telefono**. El vinculo con el
 * hardware se comprueba al ingresar y no cambia al recuperar la contrasena, asi
 * que quien perdio el telefono va a recuperar la clave y seguir sin poder
 * entrar. Decirlo al final seria hacerle pasar por tres pasos para llegar a un
 * "no". Se dice arriba.
 *
 * ## Por que vuelve a pedir el correo
 *
 * El servidor guarda solo una huella de la direccion, nunca la direccion. Es lo
 * que hace que una fuga de la base no entregue ni un correo, y el precio es que
 * hay que escribirla de nuevo. Sin explicarlo parece que el sistema la perdio.
 */
@Composable
private fun DialogoRecuperar(
    usuarioInicial: String,
    onCerrar: () -> Unit,
    onRecuperada: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var usuario by remember { mutableStateOf(usuarioInicial) }
    var telefono by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var claveNueva by remember { mutableStateOf("") }
    var pedido by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deprueba by remember { mutableStateOf<String?>(null) }

    /**
     * El codigo de dos pasos, solo si la cuenta lo tiene.
     *
     * No se pregunta de entrada: la mayoria no tiene dos pasos, y un campo mas
     * en una pantalla que ya pide cuatro cosas solo estorba. Aparece cuando el
     * servidor responde que hace falta -un 401 despues de canjear el SMS-, y
     * entonces se reintenta con el MISMO codigo del SMS: el servidor deshace la
     * transaccion al rechazar, asi que el SMS no se quemo.
     */
    var pideDosPasos by remember { mutableStateOf(false) }
    var totp by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = { Text("Recuperar mi cuenta", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Te enviamos un código por SMS al número que verificaste, y con el " +
                        "cambias la contraseña.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Ambar.copy(alpha = 0.10f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Esto NO sirve si cambiaste de teléfono: la cuenta sigue atada al " +
                            "dispositivo donde se creo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ambar,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it.filter { c -> !c.isWhitespace() } },
                    label = { Text("Usuario") },
                    prefix = { Text("@", color = TextoTerciario) },
                    enabled = !pedido,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = telefono,
                    onValueChange = { telefono = it },
                    label = { Text("El número que verificaste") },
                    placeholder = { Text("+51 987 654 321", color = TextoTerciario) },
                    enabled = !pedido,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = {
                        Text(
                            "Guardamos solo una huella, no el número. Por eso hay que escribirlo.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextoTerciario,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (pedido) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = codigo,
                        onValueChange = { if (it.length <= 6) codigo = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Código de 6 digitos") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    deprueba?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Servidor sin pasarela de SMS. Código: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ambar,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = claveNueva,
                        onValueChange = { claveNueva = it },
                        label = { Text("Contraseña nueva") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                "Al menos 10 caracteres. Se cierran todas tus sesiones.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextoTerciario,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Solo cuando el servidor dijo que hace falta. Ver
                    // `pideDosPasos`.
                    if (pideDosPasos) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = totp,
                            onValueChange = { totp = it.filter { c -> !c.isWhitespace() } },
                            label = { Text("Código de dos pasos") },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    "El de tu app de autenticación, o uno de respaldo si " +
                                        "perdiste el teléfono.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextoTerciario,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
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
                enabled = !trabajando,
                onClick = {
                    trabajando = true
                    error = null
                    ambito.launch {
                        if (!pedido) {
                            app.repo.pedirCodigoRecuperar(usuario.trim().lowercase(), telefono.trim())
                                .onSuccess {
                                    pedido = true
                                    deprueba = it.codigoDePrueba
                                    // Si los datos no coincidian, el servidor
                                    // responde igual y sin codigo: no se puede
                                    // usar esta pantalla para averiguar de quien
                                    // es un numero.
                                    if (it.codigoDePrueba == null) {
                                        error = "Si los datos son correctos, el código ya va en camino."
                                    }
                                }
                                .onFailure { error = it.message }
                        } else {
                            app.repo.recuperarCuenta(
                                usuario.trim().lowercase(), telefono.trim(), codigo, claveNueva,
                                totp = totp.trim().takeIf { it.isNotEmpty() },
                            )
                                .onSuccess { onRecuperada() }
                                .onFailure { e ->
                                    // 401 aqui no es "mal codigo": es "esta
                                    // cuenta tiene dos pasos y no lo mandaste".
                                    // Se abre el campo en vez de dar un error
                                    // que no dice que hacer.
                                    if (e is ApiError && e.codigo == 401 && !pideDosPasos) {
                                        pideDosPasos = true
                                        error = "Esta cuenta tiene verificación en dos pasos. " +
                                            "Escribe el código de tu app, o uno de respaldo."
                                    } else {
                                        error = e.message
                                    }
                                }
                        }
                        trabajando = false
                    }
                },
            ) { Text(if (pedido) "Cambiar la contraseña" else "Enviarme el código", color = Cian) }
        },
        dismissButton = {
            TextButton(enabled = !trabajando, onClick = onCerrar) {
                Text("Cancelar", color = TextoSecundario)
            }
        },
    )
}


/**
 * Vincular ESTE aparato a una cuenta que ya existe.
 *
 * ## El tercer camino, y por que hace falta explicarlo
 *
 * Con varios dispositivos hay tres formas de llegar a una cuenta y la gente
 * solo conoce dos. "Crear cuenta" haria una cuenta nueva; "Entrar" falla
 * porque este hardware todavia no esta vinculado y el servidor lo rechaza con
 * un 403 que suena a contrasena equivocada. El camino correcto es este, y sin
 * nombrarlo nadie lo encuentra.
 *
 * ## El codigo se genera en el OTRO aparato
 *
 * No aqui. Es la direccion que importa: para meter un dispositivo hay que
 * tener en la mano el que ya esta dentro. Al reves seria el patron de la
 * estafa de WhatsApp Web, donde el atacante manda su codigo y convence a la
 * victima de aprobarlo.
 *
 * ## Lo que este aparato NO va a ver
 *
 * El historial anterior. El servidor nunca lo tuvo, asi que solo puede
 * llegarle desde otro dispositivo de la misma persona, y solo si ese esta
 * encendido. Se dice ANTES de vincular, no despues de que el usuario mire una
 * lista de chats vacia y crea que algo se rompio.
 */
@Composable
private fun DialogoVincular(
    usuarioInicial: String,
    identidad: Hardware.Identidad?,
    onCerrar: () -> Unit,
    onVinculado: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var usuario by remember { mutableStateOf(usuarioInicial) }
    var codigo by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var escaneando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sincronizando by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = {
            Text(
                if (resultado != null) "Dispositivo vinculado" else "Vincular este dispositivo",
                color = TextoPrimario,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val res = resultado
                if (res != null) {
                    Text(res, style = MaterialTheme.typography.bodyMedium, color = TextoPrimario)
                    if (sincronizando) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Cian, modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Pidiendo historial a tu otro dispositivo...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextoSecundario,
                            )
                        }
                    }
                } else {
                    Text(
                        "Genera un código en el dispositivo donde ya tienes la cuenta, en " +
                            "Perfil → Cuenta y seguridad → Mis dispositivos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = Ambar.copy(alpha = 0.10f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Este aparato arranca sin historial: el servidor no lo tiene. " +
                                "Si tu otro dispositivo esta encendido, te manda los mensajes " +
                                "recientes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ambar,
                            modifier = Modifier.padding(10.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = usuario,
                        onValueChange = { usuario = it.filter { c -> !c.isWhitespace() } },
                        label = { Text("Tu usuario") },
                        prefix = { Text("@", color = TextoTerciario) },
                        singleLine = true,
                        enabled = !trabajando,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = codigo,
                        onValueChange = { if (it.length <= 9) codigo = it.uppercase() },
                        label = { Text("Código de vinculacion") },
                        placeholder = { Text("XXXX-XXXX", color = TextoTerciario) },
                        singleLine = true,
                        enabled = !trabajando,
                        // J.6: escanear rellena este mismo campo en vez de
                        // llevar a otro flujo. Asi un escaneo que lee mal se
                        // corrige a mano sin volver a empezar, y el codigo
                        // queda a la vista antes de enviarlo.
                        trailingIcon = {
                            IconButton(onClick = { escaneando = true }, enabled = !trabajando) {
                                Icon(
                                    Icons.Filled.QrCodeScanner,
                                    "Escanear el código",
                                    tint = Cian,
                                )
                            }
                        },
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
            if (resultado != null) {
                TextButton(enabled = !sincronizando, onClick = onVinculado) {
                    Text(if (sincronizando) "Espera..." else "Entrar", color = Cian)
                }
            } else {
                TextButton(
                    enabled = !trabajando && identidad != null &&
                        usuario.isNotBlank() && codigo.length >= 8,
                    onClick = {
                        val id = identidad ?: return@TextButton
                        trabajando = true
                        error = null
                        ambito.launch {
                            app.repo.vincularEsteDispositivo(
                                username = usuario.trim().lowercase(),
                                codigo = codigo.trim(),
                                id = id,
                                etiqueta = android.os.Build.MODEL ?: "dispositivo",
                            )
                                .onSuccess { r ->
                                    // El orden importa: primero el socket y las
                                    // claves, porque sin claves publicadas los
                                    // demas no tienen con que cifrarle y el
                                    // pedido de historial no llegaria a nada.
                                    app.repo.iniciar()
                                    sincronizando = true
                                    resultado = "Este es el dispositivo ${r.dispositivos} de tu cuenta."
                                    app.repo.sincronizar()
                                    val h = app.repo.pedirHistorial()
                                    sincronizando = false
                                    if (h?.hayQuienResponda == false) {
                                        resultado = "Este es el dispositivo ${r.dispositivos} de tu " +
                                            "cuenta. Tu otro dispositivo no esta conectado, así que " +
                                            "no hay de donde traer el historial: empiezas desde aquí."
                                    }
                                    trabajando = false
                                }
                                .onFailure {
                                    trabajando = false
                                    error = (it as? ApiError)?.message
                                        ?: "No se pudo conectar con el servidor."
                                }
                        }
                    },
                ) { Text(if (trabajando) "Vinculando..." else "Vincular", color = Cian) }
            }
        },
        dismissButton = {
            if (resultado == null) {
                TextButton(enabled = !trabajando, onClick = onCerrar) {
                    Text("Cancelar", color = TextoSecundario)
                }
            }
        },
    )


    if (escaneando) {
        EscanerQr(
            onCodigo = { texto ->
                // Se rellena el campo y se cierra el escaner: NO se envia
                // solo. Un escaneo que leyo mal -o el QR de otra cosa- tiene
                // que poder corregirse antes de gastar uno de los cinco
                // intentos del codigo.
                codigo = texto.trim().uppercase().take(9)
                escaneando = false
            },
            onCerrar = { escaneando = false },
        )
    }
}
