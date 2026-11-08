package com.wtfuck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Reseteo de contrasena desde el enlace de un administrador (wtfuck://reseteo).
 *
 * Funciona SIN sesion: es justo cuando la persona no puede entrar. Solo cambia
 * la contrasena; despues ingresa normal en su telefono, que sigue vinculado (el
 * reseteo no toca el device binding ni descifra nada). Es la version en-app del
 * mismo formulario que sirve la pagina web del enlace.
 */
@Composable
fun DialogoReseteoClave(token: String, onCerrar: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var listo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ok = p1.length >= 10 && p1 == p2

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = {
            Text(
                if (listo) "Listo" else "Recuperar contraseña",
                color = TextoPrimario, fontSize = 18.sp,
            )
        },
        text = {
            if (listo) {
                Text(
                    "Ya podés entrar a la app con tu contraseña nueva.",
                    color = TextoSecundario,
                )
            } else {
                Column {
                    Text(
                        "Elegí una contraseña nueva para tu cuenta.",
                        style = MaterialTheme.typography.bodySmall, color = TextoSecundario,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(color = Ambar.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Importante: después entrá con la contraseña nueva en ESTE teléfono. " +
                                "No desinstales la app ni uses \"Perdí mi teléfono\": tus mensajes " +
                                "viejos viven solo acá y se perderían.",
                            style = MaterialTheme.typography.labelSmall, color = Ambar,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = p1,
                        onValueChange = { p1 = it; error = null },
                        label = { Text("Contraseña nueva (mínimo 10)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = p2,
                        onValueChange = { p2 = it; error = null },
                        label = { Text("Repetila") },
                        singleLine = true,
                        isError = p2.isNotEmpty() && p2 != p1,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Coral, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (listo) {
                TextButton(onClick = onCerrar) { Text("Entendido", color = Cian) }
            } else {
                TextButton(
                    enabled = ok && !trabajando,
                    onClick = {
                        trabajando = true; error = null
                        ambito.launch {
                            app.repo.resetearConToken(token, p1)
                                .onSuccess { listo = true }
                                .onFailure { error = it.message ?: "No se pudo." }
                            trabajando = false
                        }
                    },
                ) { Text("Guardar", color = Cian) }
            }
        },
        dismissButton = {
            if (!listo) {
                TextButton(enabled = !trabajando, onClick = onCerrar) {
                    Text("Cancelar", color = TextoSecundario)
                }
            }
        },
    )
}
