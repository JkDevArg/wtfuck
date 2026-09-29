package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiError
import com.wtfuck.app.datos.Hardware
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * "Perdi el telefono": dar de alta ESTE aparato con el codigo de recuperacion.
 *
 * ## Los tres caminos de esta pantalla, y por que este es distinto
 *
 * - **Ingresar**: la cuenta existe y este aparato ya esta vinculado.
 * - **Vincular**: la cuenta existe, este aparato es nuevo, y el ANTERIOR
 *   sigue en la mano para autorizar. Es el camino normal del segundo telefono.
 * - **Esto**: la cuenta existe, este aparato es nuevo, y el anterior **ya no
 *   esta**. Nadie puede autorizar desde dentro, y por eso hace falta el codigo
 *   que la persona guardo en papel.
 *
 * Antes este tercer camino no existia y la cuenta se perdia para siempre.
 *
 * ## El orden importa y la pantalla lo impone
 *
 * Se avisa de restaurar la copia ANTES de recuperar la cuenta. No es un
 * consejo de estilo: al darse de alta, este aparato publica su identidad, y si
 * la copia todavia no se restauro publicara una identidad NUEVA — y entonces a
 * todos los contactos les saltara el aviso de que la clave cambio. Eso no se
 * puede deshacer despues.
 */
@Composable
fun DialogoRecuperarCuenta(
    usuarioInicial: String,
    identidad: Hardware.Identidad?,
    onCerrar: () -> Unit,
    onRecuperada: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var usuario by remember { mutableStateOf(usuarioInicial) }
    var telefono by remember { mutableStateOf("") }
    var pedido by remember { mutableStateOf(false) }
    var sms by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var claveNueva by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var pideDosPasos by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deprueba by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = { Text("Perdí mi teléfono", color = TextoPrimario, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Con tu código de recuperación puedes dar de alta este teléfono. " +
                        "Hace falta también un SMS al número que verificaste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextoSecundario,
                )

                Spacer(Modifier.height(10.dp))
                // El aviso que hay que dar ANTES, no despues: publicar una
                // identidad nueva no se deshace.
                Surface(
                    color = Ambar.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Si tienes copia de seguridad, restáurala ANTES de esto. Así " +
                            "conservas tu número de seguridad y a tus contactos no les " +
                            "salta ninguna alarma. Después ya no se puede.",
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
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = telefono,
                    onValueChange = { telefono = it },
                    label = { Text("El número que verificaste") },
                    enabled = !pedido,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (pedido) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sms,
                        onValueChange = { sms = it.filter { c -> c.isDigit() } },
                        label = { Text("Código del SMS") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
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

                    Spacer(Modifier.height(12.dp))
                    CampoCodigoRecuperacion(
                        valor = codigo,
                        onCambio = { codigo = it; error = null },
                        ayuda = "Los 28 caracteres que anotaste al crearlo.",
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = claveNueva,
                        onValueChange = { claveNueva = it },
                        label = { Text("Contraseña nueva") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                "Al menos 10 caracteres. Se cierran todas tus sesiones y " +
                                    "se desvinculan tus otros aparatos.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextoTerciario,
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (pideDosPasos) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = totp,
                            onValueChange = { totp = it.filter { c -> !c.isWhitespace() } },
                            label = { Text("Código de dos pasos") },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    "El de tu app de autenticación, o uno de respaldo.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextoTerciario,
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
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
                enabled = !trabajando && usuario.isNotBlank() && telefono.isNotBlank() &&
                    (!pedido || (sms.isNotBlank() && codigo.isNotBlank() && claveNueva.isNotBlank())),
                onClick = {
                    trabajando = true
                    error = null
                    ambito.launch {
                        if (!pedido) {
                            app.repo.pedirCodigoRecuperar(usuario.trim().lowercase(), telefono.trim())
                                .onSuccess {
                                    pedido = true
                                    deprueba = it.codigoDePrueba
                                    // Mismo cuidado que en "olvide mi
                                    // contrasena": la respuesta no dice si la
                                    // cuenta existe, para que esto no sirva
                                    // para averiguar de quien es un numero.
                                    if (it.codigoDePrueba == null) {
                                        error = "Si los datos son correctos, el código ya va en camino."
                                    }
                                }
                                .onFailure { error = it.message }
                        } else {
                            val id = identidad
                            if (id == null) {
                                error = "Este teléfono todavía no pudo acreditar su hardware."
                            } else {
                                app.repo.recuperarDispositivo(
                                    username = usuario.trim().lowercase(),
                                    telefono = telefono.trim(),
                                    codigoSms = sms.trim(),
                                    codigoRecuperacion = codigo,
                                    passwordNueva = claveNueva,
                                    id = id,
                                    etiqueta = android.os.Build.MODEL ?: "dispositivo",
                                    totp = totp.trim().takeIf { it.isNotEmpty() },
                                )
                                    .onSuccess { onRecuperada() }
                                    .onFailure { e ->
                                        // 401 aqui es "falta el segundo factor",
                                        // no "codigo malo": se abre el campo en
                                        // vez de dar un error que no dice que
                                        // hacer. El SMS no se quemo -el
                                        // servidor deshace la transaccion- asi
                                        // que el reintento usa el mismo.
                                        if (e is ApiError && e.codigo == 401 && !pideDosPasos) {
                                            pideDosPasos = true
                                            error = "Esta cuenta tiene verificación en dos pasos. " +
                                                "Escribe el código de tu app, o uno de respaldo."
                                        } else {
                                            error = e.message
                                        }
                                    }
                            }
                        }
                        trabajando = false
                    }
                },
            ) {
                Text(if (pedido) "Recuperar mi cuenta" else "Enviarme el código", color = Cian)
            }
        },
        dismissButton = {
            TextButton(enabled = !trabajando, onClick = onCerrar) {
                Text("Cancelar", color = TextoSecundario)
            }
        },
    )
}
