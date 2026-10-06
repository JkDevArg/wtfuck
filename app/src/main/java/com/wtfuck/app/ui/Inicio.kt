package com.wtfuck.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*

/**
 * Las pestañas de arranque.
 *
 * Antes todo colgaba de la lista de chats y los canales estaban escondidos en
 * el menú de tres puntos. Un canal no es una opción de configuración: es un
 * lugar al que se entra, igual que los chats, y el menú lo enterraba.
 *
 * Por qué una barra de abajo y no pestañas arriba: arriba ya viven el buscador
 * y los filtros de la lista, y el pulgar no llega. WhatsApp hizo el mismo
 * cambio por el mismo motivo cuando los teléfonos crecieron.
 */
private enum class Pestana(val etiqueta: String, val icono: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    // Social reemplazo a Contactos. Los estados salieron de la lista de chats
    // -donde empujaban las conversaciones hacia abajo- y tienen pantalla
    // propia, junto al directorio de Usuarios. La libreta paso al menu
    // "Nuevo": se usa para empezar algo, que es justo lo que hace ese menu.
    SOCIAL("Social", Icons.Filled.Groups),
    CANALES("Canales", Icons.Filled.Campaign),
    PERFIL("Perfil", Icons.Filled.AccountCircle),
}

@Composable
fun Inicio(
    onAbrirChat: (String) -> Unit,
    onAbrirCanal: (String) -> Unit,
    onPrivacidad: () -> Unit,
    onComunidades: () -> Unit,
    onAlmacenamiento: () -> Unit,
    onMiCuenta: () -> Unit,
    onPanel: () -> Unit,
    onNovedades: () -> Unit,
    onCopiaSeguridad: () -> Unit,
    onSeguridad: () -> Unit,
    onMiEnlace: () -> Unit = {},
    /** Abrir un chat saltando a un mensaje: lo pide el aviso de "no se envio". */
    onAbrirEnMensaje: (conversacionId: String, mensajeId: String) -> Unit,
    onLlamadas: () -> Unit,
    onNotificaciones: () -> Unit,
    onTipoCuenta: () -> Unit,
    onDiagnostico: () -> Unit,
    /** La libreta, desde el menu "Nuevo" de Chats. */
    onContactos: () -> Unit,
    /** El perfil de alguien por su usuario: lo abre el directorio. */
    onVerPersona: (String) -> Unit,
    onCerrarSesion: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    var pestana by rememberSaveable { mutableStateOf(Pestana.CHATS) }
    // Crear un canal se pide desde Chats y se hace en Canales. El aviso viaja
    // aqui porque es lo unico que ven las dos pestañas.
    var pedirNuevoCanal by rememberSaveable { mutableStateOf(false) }

    // Cada pestaña se guarda su propio estado: el scroll de la lista, lo que
    // había escrito en el buscador, el filtro elegido. Sin esto, mirar tu
    // perfil un segundo te devolvía al principio de la lista de chats.
    val estados = rememberSaveableStateHolder()

    val chats by app.repo.conversaciones.collectAsStateWithLifecycle(emptyList())

    // El globo cuenta CONVERSACIONES con algo sin leer, no mensajes. Es lo que
    // se necesita saber desde la barra: a cuánta gente le debes respuesta.
    // Los silenciados no cuentan; para eso los silenciaste.
    val sinLeer = remember(chats) { chats.count { it.sinLeer && !it.silenciado } }

    // Atrás en cualquier pestaña vuelve a Chats en vez de cerrar la app. Salir
    // de la app desde "Perfil" era fácil de hacer sin querer.
    BackHandler(enabled = pestana != Pestana.CHATS) { pestana = Pestana.CHATS }

    Scaffold(
        containerColor = BgBase,
        bottomBar = {
            NavigationBar(containerColor = BgSurface, tonalElevation = 0.dp) {
                Pestana.entries.forEach { p ->
                    NavigationBarItem(
                        selected = pestana == p,
                        onClick = { pestana = p },
                        icon = {
                            if (p == Pestana.CHATS && sinLeer > 0) {
                                BadgedBox(badge = {
                                    Badge(containerColor = Cian, contentColor = TextoSobreAcento) {
                                        Text(
                                            if (sinLeer > 99) "99+" else "$sinLeer",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }) { Icon(p.icono, null) }
                            } else {
                                Icon(p.icono, null)
                            }
                        },
                        label = { Text(p.etiqueta, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextoSobreAcento,
                            selectedTextColor = Cian,
                            indicatorColor = Cian,
                            unselectedIconColor = TextoTerciario,
                            unselectedTextColor = TextoTerciario,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        // La barra de abajo la pone este Scaffold; el de arriba lo pone cada
        // pantalla. Por eso solo se baja el hueco de abajo: si se pasara el
        // padding completo, el título quedaría flotando.
        val hueco = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())

        estados.SaveableStateProvider(pestana.name) {
            when (pestana) {
                Pestana.CHATS -> ChatsPantalla(
                    onAbrir = { id, tipo -> if (tipo == "canal") onAbrirCanal(id) else onAbrirChat(id) },
                    onAbrirEnMensaje = onAbrirEnMensaje,
                    onPerfil = { pestana = Pestana.PERFIL },
                    onCanales = { pestana = Pestana.CANALES },
                    onNuevoCanal = { pedirNuevoCanal = true; pestana = Pestana.CANALES },
                    onContactos = onContactos,
                    modifier = hueco,
                )

                Pestana.SOCIAL -> SocialPantalla(
                    onVerPersona = onVerPersona,
                    onPrivacidad = onPrivacidad,
                    modifier = hueco,
                )

                Pestana.CANALES -> DescubrirCanales(
                    onAbrirCanal = onAbrirCanal,
                    // Sin flecha de volver: es una pestaña, no una pantalla a
                    // la que se entró. La flecha prometería un atrás que no hay.
                    onAtras = null,
                    abrirCreacion = pedirNuevoCanal,
                    onCreacionAbierta = { pedirNuevoCanal = false },
                    modifier = hueco,
                )

                Pestana.PERFIL -> PerfilPantalla(
                    onAtras = null,
                    onPrivacidad = onPrivacidad,
                    onComunidades = onComunidades,
                    onAlmacenamiento = onAlmacenamiento,
                    onMiCuenta = onMiCuenta,
                    onPanel = onPanel,
                    onNovedades = onNovedades,
                    onCopiaSeguridad = onCopiaSeguridad,
                    onSeguridad = onSeguridad,
                    onLlamadas = onLlamadas,
                    onNotificaciones = onNotificaciones,
                    onTipoCuenta = onTipoCuenta,
                    onDiagnostico = onDiagnostico,
                    onCerrarSesion = onCerrarSesion,
                    modifier = hueco,
                    onMiEnlace = onMiEnlace,
                )
            }
        }
    }
}
