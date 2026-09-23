package com.wtfuck.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.wtfuck.app.ui.theme.*

/**
 * Módulo U · La pantalla que tapa la app mientras está bloqueada.
 *
 * ## Por qué es una capa y no un destino de navegación
 *
 * Porque tiene que tapar **lo que haya**, incluido un chat abierto o una
 * llamada en curso, y desaparecer sin dejar nada en el historial. Como
 * destino, `Atrás` podría sacar de ella, y una cerradura de la que se sale con
 * el botón de atrás no es una cerradura. Es la misma razón por la que la capa
 * de llamada se dibuja encima del `NavHost` y no dentro.
 *
 * ## Por qué no se pide la huella sola al aparecer
 *
 * Se pide **una vez** al entrar y después hay un botón. Un `LaunchedEffect`
 * que reabra el diálogo cada vez que se cierra deja a la persona atrapada:
 * cancelar no sirve de nada, no se puede leer lo que dice la pantalla, y si el
 * sensor falla el bucle no se corta nunca.
 */
@Composable
fun PantallaBloqueada(onDesbloquear: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var pidiendo by remember { mutableStateOf(false) }

    fun pedir() {
        if (pidiendo) return
        pidiendo = true
        pedirAutenticacion(
            ctx = ctx,
            onOk = { pidiendo = false; onDesbloquear() },
            onError = { msg -> pidiendo = false; error = msg },
        )
    }

    // Una sola vez: el resto de los intentos los pide la persona tocando.
    LaunchedEffect(Unit) { pedir() }

    Surface(Modifier.fillMaxSize(), color = BgBase) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(BgElev),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Lock, null, tint = Cian, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("wtfuck está bloqueado", color = TextoPrimario, fontSize = 20.sp,
                 fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                // Se dice QUÉ desbloquea, y no solo "autentícate": quien no
                // sabe si le van a pedir la huella o el PIN no sabe si puede
                // hacerlo ahora mismo.
                "Usa tu huella, tu rostro o el PIN del teléfono para entrar.",
                color = TextoSecundario,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            error?.let {
                Spacer(Modifier.height(18.dp))
                Text(
                    it,
                    color = Coral,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    // El motivo aparece después de que el diálogo se cierre y
                    // se va al reintentar: como región viva se anuncia solo,
                    // que es la única forma de que llegue sin vista.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { error = null; pedir() },
                enabled = !pidiendo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cian, contentColor = TextoSobreAcento,
                ),
            ) { Text("Desbloquear") }

            Spacer(Modifier.height(14.dp))
            Text(
                // Lo que esto NO hace, dicho donde se lee. Quien crea que el
                // bloqueo cifra algo va a tomar decisiones con esa idea.
                "Tus mensajes ya están cifrados en este aparato. Este bloqueo " +
                    "sólo impide que alguien con el teléfono en la mano los abra.",
                color = TextoTerciario,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Los métodos que aceptamos para desbloquear.
 *
 * **`BIOMETRIC_WEAK` y no `BIOMETRIC_STRONG`**, y la diferencia importa: en
 * muchos teléfonos el reconocimiento facial está clasificado como "weak", así
 * que exigir "strong" deja sin poder entrar —por su propia cara— a gente que
 * la tiene configurada y funcionando. Aquí no se desbloquea ninguna clave
 * criptográfica con el resultado, que es el caso en que "strong" es
 * obligatorio; se abre una pantalla. Para eso, "weak" es la clase correcta.
 *
 * **`DEVICE_CREDENTIAL` siempre incluido.** Sin él, un dedo mojado o un sensor
 * sucio dejan a alguien fuera de sus propios mensajes sin ninguna salida.
 */
private const val METODOS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * Si este aparato puede bloquear la app.
 *
 * Devuelve `false` cuando **no hay nada configurado**: ni huella, ni rostro, ni
 * PIN. Ofrecer el ajuste ahí sería ofrecer un interruptor que, al encenderse,
 * deja la app inaccesible o —peor— se abre sola y hace creer que protege.
 */
fun sePuedeBloquear(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(METODOS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Abre el diálogo del sistema.
 *
 * ## `FragmentActivity` y por qué se falla en vez de tragar
 *
 * `BiometricPrompt` necesita una `FragmentActivity`. Si el contexto no lo es
 * —porque alguien cambió la Activity de base— esto **no** puede devolver
 * "desbloqueado" en silencio: sería una cerradura que se abre sola por un
 * error de refactor. Devuelve un error visible, y la app se queda bloqueada.
 */
private fun pedirAutenticacion(ctx: Context, onOk: () -> Unit, onError: (String) -> Unit) {
    val actividad = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<FragmentActivity>()
        .firstOrNull()
    if (actividad == null) {
        onError("No se pudo abrir la verificación en este aparato.")
        return
    }

    val prompt = BiometricPrompt(
        actividad,
        androidx.core.content.ContextCompat.getMainExecutor(ctx),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()

            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // Cancelar no es un fallo y no merece un mensaje en rojo:
                // quien toca "cancelar" ya sabe lo que hizo. Los demás
                // códigos sí se dicen, con el texto del sistema, que explica
                // mejor que cualquier frase nuestra si el sensor está
                // bloqueado por intentos o si falta configurar algo.
                val cancelado = code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelado) "" else msg.toString())
            }

            // `onAuthenticationFailed` (una huella que no coincide) NO se
            // trata: el propio diálogo ya lo dice y sigue abierto. Cerrar aquí
            // convertiría un dedo mal puesto en tener que empezar de nuevo.
        },
    )

    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear wtfuck")
            .setSubtitle("Usa tu huella, tu rostro o el PIN del teléfono")
            .setAllowedAuthenticators(METODOS)
            // Sin `setNegativeButtonText`: con DEVICE_CREDENTIAL permitido, el
            // sistema pone ahí el botón de "usar PIN" y añadir uno propio es
            // un error en tiempo de ejecución, no un aviso.
            .build()
    )
}
