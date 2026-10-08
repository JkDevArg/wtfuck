package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.protocol.CodigoRecuperacion
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Crear -o rotar- el codigo de recuperacion.
 *
 * ## Aqui la interfaz ES la seguridad
 *
 * El codigo mas fuerte del mundo no sirve si la persona cierra la pantalla sin
 * anotarlo. Y eso es lo que pasa por defecto: un dialogo que muestra algo y
 * tiene un boton "Entendido" se cierra sin leer, siempre.
 *
 * Por eso son **dos pasos** y el segundo hace teclear el codigo:
 *
 *  1. Se muestra, se puede copiar.
 *  2. Hay que escribirlo para seguir.
 *
 * No es un trámite de mas: es la unica forma de comprobar que existe fuera de
 * esta pantalla. Quien lo copie al portapapeles y lo pegue se salta la
 * comprobacion, y se acepta — pegar tambien significa que salio de aqui.
 *
 * ## Lo que NO se hace
 *
 * No se guarda el codigo en el telefono. Ni en la base cifrada, ni en
 * preferencias, ni en un archivo. Guardarlo alli lo ataria al aparato, que es
 * exactamente el aparato que la persona va a perder — y entonces no seria una
 * salida, seria un adorno.
 */
@Composable
fun DialogoCodigoRecuperacion(
    /** true si ya habia uno: el texto cambia, porque rotar invalida el viejo. */
    rotando: Boolean,
    pideTotp: Boolean,
    onCerrar: () -> Unit,
    onListo: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current

    // Se genera UNA vez y se recuerda: regenerarlo en cada recomposicion
    // mostraria un codigo distinto cada vez que se toca la pantalla, y la
    // persona anotaria uno que no es el que se va a fijar.
    val codigo = remember { CodigoRecuperacion.generar() }

    var paso by remember { mutableStateOf(1) }
    var escrito by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copiado by remember { mutableStateOf(false) }

    // Se compara NORMALIZADO, no como cadena: quien lo teclee en minusculas o
    // sin guiones lo escribio bien, y rechazarselo seria castigar por el
    // formato en vez de comprobar lo que importa.
    val coincide = remember(escrito) {
        CodigoRecuperacion.normalizar(escrito) == CodigoRecuperacion.normalizar(codigo)
    }

    AlertDialog(
        onDismissRequest = { if (!trabajando) onCerrar() },
        containerColor = BgElev,
        title = {
            Text(
                if (paso == 1) "Tu código de recuperación" else "Confírmalo",
                color = TextoPrimario, fontSize = 18.sp,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (paso == 1) {
                    Text(
                        "Es lo único que te devuelve la cuenta si pierdes el teléfono. " +
                            "Escríbelo en papel y guárdalo donde guardarías una llave.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(14.dp))

                    // Monoespaciada y en dos lineas: es lo que hace que se
                    // pueda copiar a mano sin perder el sitio.
                    Surface(
                        color = BgSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            codigo.replace("-", "-​"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 17.sp,
                            letterSpacing = 1.5.sp,
                            color = Cian,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            portapapeles.setText(AnnotatedString(codigo))
                            copiado = true
                        }) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = Cian)
                            Spacer(Modifier.width(6.dp))
                            Text("Copiar", color = Cian)
                        }
                        if (copiado) {
                            Text(
                                "copiado",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextoTerciario,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Aviso(
                        if (rotando) {
                            "Al guardar este, el código anterior deja de servir. " +
                                "Si tenías copias de seguridad hechas con el viejo, " +
                                "su identidad ya no se podrá recuperar con este."
                        } else {
                            "No se guarda en el teléfono ni en el servidor. Si lo " +
                                "pierdes y pierdes el teléfono, no hay forma de " +
                                "devolverte la cuenta: nadie puede."
                        }
                    )
                } else {
                    Text(
                        "Escríbelo tal como lo anotaste. Así comprobamos que de " +
                            "verdad lo tienes fuera de este teléfono.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoSecundario,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = escrito,
                        onValueChange = { escrito = it; error = null },
                        label = { Text("El código") },
                        singleLine = false,
                        isError = escrito.isNotBlank() && !coincide,
                        supportingText = {
                            Text(
                                when {
                                    escrito.isBlank() -> "Da igual si lo escribes en minúsculas o sin guiones."
                                    coincide -> "Coincide."
                                    else -> "Todavía no coincide."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (coincide) Cian else TextoTerciario,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = clave,
                        onValueChange = { clave = it; error = null },
                        label = { Text("Tu contraseña") },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                "Se pide porque guardar un código nuevo anula el anterior.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextoTerciario,
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (pideTotp) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = totp,
                            onValueChange = { totp = it; error = null },
                            label = { Text("Código de dos pasos") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                            ),
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
            if (paso == 1) {
                TextButton(onClick = { paso = 2 }) {
                    Text("Ya lo anoté", color = Cian, fontWeight = FontWeight.Medium)
                }
            } else {
                TextButton(
                    enabled = coincide && clave.isNotBlank() && !trabajando,
                    onClick = {
                        trabajando = true
                        error = null
                        ambito.launch {
                            app.repo.fijarRecuperacion(codigo, clave, totp.ifBlank { null })
                                .onSuccess { onListo() }
                                .onFailure { error = it.message ?: "No se pudo guardar." }
                            trabajando = false
                        }
                    },
                ) { Text("Guardar", color = Cian, fontWeight = FontWeight.Medium) }
            }
        },
        dismissButton = {
            TextButton(enabled = !trabajando, onClick = {
                if (paso == 2) paso = 1 else onCerrar()
            }) {
                Text(if (paso == 2) "Atrás" else "Cancelar", color = TextoSecundario)
            }
        },
    )
}

@Composable
private fun Aviso(texto: String) {
    Surface(
        color = Ambar.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.labelSmall,
            color = Ambar,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Pide el codigo de recuperacion, ya existente, para usarlo.
 *
 * Lo usan la copia de seguridad -para sellar o abrir la identidad- y la
 * pantalla de recuperar la cuenta. Valida el byte de control ANTES de mandar
 * nada: asi una errata se dice aqui, y no vuelve del servidor como un
 * "no autorizado" que hace pensar que el codigo era el equivocado.
 */
@Composable
fun CampoCodigoRecuperacion(
    valor: String,
    onCambio: (String) -> Unit,
    etiqueta: String = "Código de recuperación",
    ayuda: String? = null,
    modifier: Modifier = Modifier,
) {
    val valido = valor.isBlank() || CodigoRecuperacion.valido(valor)
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        label = { Text(etiqueta) },
        singleLine = false,
        isError = !valido,
        supportingText = {
            Text(
                when {
                    valor.isBlank() -> ayuda ?: "Los 28 caracteres que anotaste."
                    !valido -> "Ese código no está completo o tiene una errata."
                    else -> "Tiene buena pinta."
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !valido -> Coral
                    valor.isNotBlank() -> Cian
                    else -> TextoTerciario
                },
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
