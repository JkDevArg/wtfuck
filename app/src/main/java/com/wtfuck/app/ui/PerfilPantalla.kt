package com.wtfuck.app.ui

import androidx.compose.material.icons.filled.Image
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.datos.Media
import com.wtfuck.app.datos.Hardware
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.CapacidadesCuenta
import com.wtfuck.protocol.CategoriaEmpresa
import com.wtfuck.protocol.TamanoEmpresa
import com.wtfuck.protocol.FichaEmpresa
import com.wtfuck.protocol.TipoCuenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Ya no son un RECHAZO: son a donde se comprime. Ver `Media.paraSubir`.
//
// Una foto de la camara de cualquier telefono de hoy pasa los 3 MB, asi que
// el camino normal —elegir la foto que uno se acaba de sacar— fallaba
// siempre. El telefono sabe achicarla; negarse a hacerlo y pedirselo a la
// persona era pasarle un problema que no tiene como resolver.
private const val LIMITE_AVATAR = 512 * 1024
private const val LIMITE_PORTADA = 1024 * 1024

/**
 * El lado mayor al que se reduce cada una, en pixeles.
 *
 * El avatar se dibuja como mucho a 112 dp y la portada a lo ancho de la
 * pantalla. 1280 y 1920 dejan margen de sobra para pantallas densas y para
 * abrir la foto entera, sin subir una de 4000 px que nadie va a ver.
 */
private const val LADO_AVATAR = 1280
private const val LADO_PORTADA = 1920

/**
 * La pestaña de perfil.
 *
 * ## Que estaba mal en la version anterior
 *
 * Cuatro cosas, y ninguna era de gusto:
 *
 *  1. **El nombre estaba abajo de los ajustes.** Se entraba al perfil y lo
 *     primero despues de la foto era "Cuenta y seguridad", "Notificaciones",
 *     "Llamadas", "Mi cuenta"; el nombre, el usuario y el estado aparecian
 *     recien despues de scrollear. En una pantalla que se llama "perfil", lo
 *     primero tiene que ser de quien es el perfil.
 *  2. **Siete tarjetas flotando con el mismo hueco entre todas.** La
 *     separacion, que es lo que agrupa, estaba repartida en partes iguales
 *     entre cosas relacionadas y cosas que no, asi que no agrupaba nada. Ver
 *     [SeccionAjustes].
 *  3. **La huella del hardware, en crudo, en la pantalla principal.** Es un
 *     dato de auditoria, no algo que se mire todos los dias. Bajaba el resto
 *     de la pantalla y encima competia por atencion con los ajustes. Ahora
 *     arriba hay una linea que dice si el vinculo es fuerte, y el detalle vive
 *     donde corresponde: en Cuenta y seguridad.
 *  4. **Cerrar sesion era un icono coral, sin texto, en la esquina, y sin
 *     preguntar.** Un toque accidental cerraba la sesion. Y en esta app eso no
 *     es inocuo: el historial vive solo en el telefono, asi que conviene decir
 *     que se queda antes de que la persona lo suponga al revES. Ahora es una
 *     fila con su nombre, abajo, y pregunta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerfilPantalla(
    /** Nulo cuando esta pantalla es una pestaña: no hay atras que ofrecer. */
    onAtras: (() -> Unit)?,
    onPrivacidad: () -> Unit,
    onComunidades: () -> Unit,
    onAlmacenamiento: () -> Unit,
    onMiCuenta: () -> Unit,
    onPanel: () -> Unit,
    onNovedades: () -> Unit,
    onCopiaSeguridad: () -> Unit,
    onSeguridad: () -> Unit,
    onLlamadas: () -> Unit,
    onNotificaciones: () -> Unit,
    onTipoCuenta: () -> Unit,
    onDiagnostico: () -> Unit,
    onCerrarSesion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    val perfil by app.repo.miPerfil.collectAsStateWithLifecycle()
    val usuario = app.sesion.username.orEmpty()

    // Modulo P. Se pide al servidor y no se guarda local: el tipo lo puede
    // cambiar staff desde el panel, y una copia local convertiria "ya no sos
    // desarrollador" en algo que la app tarda en enterarse.
    var cuenta by remember { mutableStateOf(CapacidadesCuenta()) }
    LaunchedEffect(usuario) { cuenta = app.repo.capacidadesDeCuenta() }

    var editando by remember { mutableStateOf(false) }
    var nombre by remember(perfil) { mutableStateOf(perfil?.nombreMostrado.orEmpty()) }
    var estado by remember(perfil) { mutableStateOf(perfil?.estadoTexto.orEmpty()) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var subiendo by remember { mutableStateOf(false) }
    var confirmandoSalida by remember { mutableStateOf(false) }
    var eligiendoTema by remember { mutableStateOf(false) }

    // Cuantas advertencias tengo, y si soy staff. Las dos cosas cambian lo
    // que se muestra abajo, y ninguna se puede deducir de la sesion.
    var advertencias by remember { mutableIntStateOf(0) }
    var nivelStaff by remember { mutableIntStateOf(0) }

    // Si la cuenta no tiene forma de recuperarse, eso se avisa DESDE el perfil
    // y no solo dentro de la pantalla de seguridad: quien no entra ahi nunca se
    // entera, y enterarse despues de olvidar la contrasena no sirve de nada.
    var sinRecuperacion by remember { mutableStateOf(false) }

    val identidad by produceState<Hardware.Identidad?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Hardware.identidad(ctx) }.getOrNull() }
    }

    LaunchedEffect(Unit) {
        app.repo.cargarMiPerfil()
        advertencias = app.repo.miEstadoModeracion()?.advertenciasVigentes ?: 0
        nivelStaff = app.repo.miNivelStaff()
        sinRecuperacion = app.repo.estadoCuenta()?.puedeRecuperarse == false
    }

    /** Comprime la imagen elegida hasta que entra, y la sube. */
    fun subir(campo: String, uri: Uri?, lado: Int, tope: Int) {
        if (uri == null) return
        subiendo = true
        ambito.launch {
            val r = runCatching {
                // En IO: decodificar y comprimir una foto grande tarda lo
                // suficiente como para congelar la pantalla si se hace en el
                // hilo principal, y la barra de "subiendo" no llegaria a
                // dibujarse.
                val bytes = withContext(Dispatchers.IO) { Media.paraSubir(ctx, uri, lado, tope) }
                app.repo.subirImagen(campo, bytes)
            }
            subiendo = false
            r.onFailure { aviso = it.message ?: "No se pudo subir la imagen." }
        }
    }

    val elegirAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { subir("avatar", it, LADO_AVATAR, LIMITE_AVATAR) }

    val elegirPortada = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { subir("portada", it, LADO_PORTADA, LIMITE_PORTADA) }

    Scaffold(
        modifier = modifier,
        containerColor = BgBase,
        topBar = {
            // Sin barra propia cuando es pestaña: la portada empieza arriba de
            // todo y una barra vacia encima solo le robaria 56 dp.
            if (onAtras != null) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onAtras) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atras", tint = TextoPrimario)
                        }
                    },
                    title = {},
                )
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = pad.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
        ) {
            Cabecera(
                usuario = usuario,
                nombreMostrado = perfil?.nombreMostrado.orEmpty(),
                avatarVersion = perfil?.avatarVersion ?: 0L,
                portadaVersion = perfil?.portadaVersion ?: 0L,
                onCambiarAvatar = {
                    elegirAvatar.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onCambiarPortada = {
                    elegirPortada.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            if (subiendo) {
                LinearProgressIndicator(
                    color = Cian,
                    trackColor = BgElev,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // --- identidad: lo primero, que es de quien es esto -----------
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                if (editando) {
                    FormularioIdentidad(
                        nombre = nombre,
                        estado = estado,
                        onNombre = { nombre = it },
                        onEstado = { estado = it },
                        onGuardar = {
                            ambito.launch {
                                runCatching { app.repo.guardarPerfil(nombre, estado) }
                                    .onSuccess { editando = false }
                                    .onFailure { aviso = it.message }
                            }
                        },
                        onCancelar = {
                            nombre = perfil?.nombreMostrado.orEmpty()
                            estado = perfil?.estadoTexto.orEmpty()
                            editando = false
                        },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                perfil?.nombreMostrado?.ifBlank { null } ?: usuario,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextoPrimario,
                            )
                            Text(
                                "@$usuario",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cian,
                            )
                        }
                        // Editar como icono al lado del nombre y no como boton
                        // suelto debajo: es una accion sobre ESTE bloque, y
                        // ponerla aqui la ata a lo que modifica.
                        IconButton(
                            onClick = { editando = true },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(BgSurface),
                        ) {
                            Icon(
                                Icons.Filled.Edit, "Editar perfil",
                                tint = Cian, modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        perfil?.estadoTexto?.ifBlank { null } ?: "Sin estado todavía",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (perfil?.estadoTexto.isNullOrBlank()) TextoTerciario else TextoSecundario,
                    )
                }
            }

            // --- la ficha de empresa, si la cuenta es de ese tipo ----------
            //
            // Va arriba, pegada al nombre, y no en una seccion de ajustes: es
            // parte de quien es esta cuenta, no una preferencia. Quien mire el
            // perfil tiene que ver el rubro sin desplazarse.
            cuenta.empresa?.let { e -> TarjetaEmpresa(e) }

            // --- el vinculo con el hardware, en una linea ------------------
            identidad?.let { id ->
                val fuerte = id.nivel == "STRONGBOX" || id.nivel == "TEE"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (fuerte) Cian.copy(alpha = 0.10f) else Ambar.copy(alpha = 0.10f))
                        // Toca y lleva al detalle. La huella completa vive en
                        // Cuenta y seguridad: aqui solo hace falta saber si el
                        // vinculo es fuerte, no compararlo caracter por caracter.
                        .clickable(onClick = onSeguridad)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (fuerte) Icons.Filled.Verified else Icons.Filled.Lock,
                        null,
                        tint = if (fuerte) Cian else Ambar,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (fuerte) {
                            "Cuenta atada a este aparato · ${id.nivel}"
                        } else {
                            "Sin enclave seguro · aparato de desarrollo"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fuerte) Cian else Ambar,
                        fontSize = 12.5.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- que sale de este telefono y hacia quien -------------------
            SeccionAjustes("Privacidad y avisos") {
                FilaAjuste(
                    icono = Icons.Filled.Shield,
                    titulo = "Privacidad",
                    detalle = "Quien ve tu foto, tu estado, quien te escribe",
                    onClick = onPrivacidad,
                )
                FilaAjuste(
                    icono = Icons.Filled.Notifications,
                    titulo = "Notificaciones",
                    detalle = "Que avisa este aparato y que se ve",
                    onClick = onNotificaciones,
                )
                // Modulo AD. Va aqui y no en la pestaña de canales porque una
                // comunidad no es un canal que se descubre: es una forma de
                // agrupar TUS grupos, y se llega desde tus cosas.
                FilaAjuste(
                    icono = Icons.Filled.Campaign,
                    titulo = "Comunidades",
                    detalle = "Varios grupos bajo un nombre, con anuncios comunes",
                    conDivisor = false,
                    onClick = onComunidades,
                )
            }

            Spacer(Modifier.height(18.dp))

            // --- la cuenta en si ------------------------------------------
            SeccionAjustes("Cuenta") {
                // Solo si el servidor dice que puede. No es el control de
                // acceso —las rutas responden 404 igual— sino no ofrecer una
                // puerta cerrada. Ver `Cuentas.kt`.
                if (cuenta.puedeElegirTipo) {
                    FilaAjuste(
                        icono = Icons.Filled.Badge,
                        titulo = "Tipo de cuenta",
                        detalle = when (cuenta.tipo) {
                            TipoCuenta.EMPRESA ->
                                cuenta.empresa?.nombreComercial?.ifBlank { null }
                                    ?.let { "Empresa · $it" } ?: "Empresa · ficha sin llenar"
                            TipoCuenta.DESARROLLADOR -> "Desarrollador"
                            else -> "Personal"
                        },
                        onClick = onTipoCuenta,
                    )
                }

                // Solo para cuentas de tipo desarrollador. La pantalla no
                // muestra datos de nadie mas -es estado de este telefono- asi
                // que la puerta puede ser el tipo de cuenta y no `staff_nivel`.
                // Ver `DiagnosticoPantalla`.
                if (cuenta.esDesarrollador) {
                    FilaAjuste(
                        icono = Icons.Filled.DeveloperMode,
                        titulo = "Diagnóstico",
                        detalle = "Estado de este aparato, cola y almacenamiento",
                        onClick = onDiagnostico,
                    )
                }
                FilaAjuste(
                    icono = Icons.Filled.Key,
                    titulo = "Cuenta y seguridad",
                    detalle = if (sinRecuperacion) {
                        "Sin teléfono verificado no se puede recuperar"
                    } else {
                        "Teléfono, dos pasos, sesiones abiertas"
                    },
                    tinte = if (sinRecuperacion) Ambar else Cian,
                    onClick = onSeguridad,
                )
                FilaAjuste(
                    icono = Icons.Filled.Gavel,
                    titulo = "Mi cuenta",
                    detalle = if (advertencias > 0) {
                        "$advertencias ${if (advertencias == 1) "advertencia" else "advertencias"} · accesos recientes"
                    } else {
                        "Advertencias, sanciones y accesos"
                    },
                    tinte = if (advertencias > 0) Ambar else Cian,
                    conDivisor = false,
                    onClick = onMiCuenta,
                )
            }

            Spacer(Modifier.height(18.dp))

            SeccionAjustes("Este aparato") {
                FilaAjuste(
                    icono = when (app.ajustes.tema) {
                        Tema.CLARO -> Icons.Filled.LightMode
                        Tema.OSCURO -> Icons.Filled.DarkMode
                        Tema.SISTEMA -> Icons.Filled.Contrast
                    },
                    titulo = "Apariencia",
                    detalle = app.ajustes.tema.etiqueta,
                    onClick = { eligiendoTema = true },
                )
                FilaAjuste(
                    icono = Icons.Filled.Phone,
                    titulo = "Llamadas",
                    detalle = "Historial de llamadas y videollamadas",
                    onClick = onLlamadas,
                )
                FilaAjuste(
                    icono = Icons.Filled.Storage,
                    titulo = "Almacenamiento y datos",
                    detalle = "Descarga automatica, calidad, espacio usado",
                    onClick = onAlmacenamiento,
                )
                // El detalle avisa en ambar cuando la copia esta vencida o no
                // existe: es el recordatorio que llega sin tener que entrar.
                val copiaDias = app.ajustes.ultimaCopia.let {
                    if (it == 0L) -1 else ((System.currentTimeMillis() - it) / 86_400_000L).toInt()
                }
                val copiaVencida = copiaDias < 0 || copiaDias >= 30
                FilaAjuste(
                    icono = Icons.Filled.Backup,
                    titulo = "Copia de seguridad",
                    detalle = when {
                        copiaDias < 0 -> "Nunca has hecho una copia"
                        copiaDias >= 30 -> "Última: hace $copiaDias días — conviene una nueva"
                        else -> "Guarda tus chats cifrados para no perderlos"
                    },
                    tinte = if (copiaVencida) Ambar else Cian,
                    conDivisor = false,
                    onClick = onCopiaSeguridad,
                )
            }

            // La seccion "Plataforma" siempre aparece, pero su contenido cambia
            // con quien mira:
            //
            //  - "Novedades" la ve TODO EL MUNDO: un registro de cambios que solo
            //    viera el staff no cumpliria su unico proposito, que es contarle
            //    a quien usa la app que cambio en la version que acaba de tener.
            //  - "Moderacion" solo si soy staff. Quien autoriza sigue siendo el
            //    servidor en cada peticion; esto solo evita ofrecer una puerta
            //    que daria 404.
            Spacer(Modifier.height(18.dp))
            SeccionAjustes("Plataforma") {
                if (nivelStaff > 0) {
                    FilaAjuste(
                        icono = Icons.Filled.Shield,
                        titulo = "Moderacion",
                        detalle = when (nivelStaff) {
                            100 -> "Propietario de la plataforma"
                            80 -> "Administrador"
                            else -> "Moderador"
                        },
                        tinte = Ambar,
                        onClick = onPanel,
                    )
                }
                FilaAjuste(
                    icono = Icons.Filled.NewReleases,
                    titulo = "Novedades",
                    detalle = "Qué cambió en cada versión",
                    conDivisor = false,
                    onClick = onNovedades,
                )
            }

            Spacer(Modifier.height(18.dp))

            SeccionAjustes {
                FilaAjuste(
                    icono = Icons.AutoMirrored.Filled.Logout,
                    titulo = "Cerrar sesión",
                    detalle = "@$usuario",
                    tinte = Coral,
                    conDivisor = false,
                    onClick = { confirmandoSalida = true },
                )
            }

            // La version, al fondo del todo.
            //
            // Va aqui y no en un "Acerca de" aparte porque es lo primero que
            // se pregunta cuando algo falla -"que version tienes"- y el sitio
            // donde la gente ya baja a mirar es el final del perfil. Centrada
            // y en gris: esta para cuando se busca, no para leerse sola.
            Spacer(Modifier.height(24.dp))
            Text(
                "wtfuck ${com.wtfuck.app.BuildConfig.VERSION_NAME} (${com.wtfuck.app.BuildConfig.VERSION_CODE})",
                color = TextoTerciario,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(pad.calculateBottomPadding()))
        }
    }

    if (eligiendoTema) {
        AlertDialog(
            onDismissRequest = { eligiendoTema = false },
            containerColor = BgElev,
            title = { Text("Apariencia", color = TextoPrimario) },
            text = {
                Column {
                    Tema.entries.forEach { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { app.ajustes.fijarTema(t); eligiendoTema = false }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = app.ajustes.tema == t,
                                onClick = { app.ajustes.fijarTema(t); eligiendoTema = false },
                                colors = RadioButtonDefaults.colors(selectedColor = Cian),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(t.etiqueta, color = TextoPrimario)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // Se dice porque es una diferencia real y no una opinion:
                    // el tema claro tiene su propia escala de acentos, medida
                    // contra las superficies claras. Ver theme/Color.kt.
                    Text(
                        "El tema claro usa su propia escala de colores, no el " +
                            "oscuro invertido: el cian de marca es ilegible sobre blanco.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )

                    HorizontalDivider(
                        color = Slate.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 14.dp),
                    )

                    Text("Color de acento", color = TextoPrimario)
                    Spacer(Modifier.height(10.dp))
                    // Círculos y no una lista con nombres: el color ES la
                    // etiqueta. Leer "violeta" para elegir un color es dar un
                    // rodeo por las palabras.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val enClaro = claroAhora(app.ajustes.tema)
                        Paleta.entries.forEach { pal ->
                            val elegida = app.ajustes.paleta == pal
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(pal.muestra(enClaro))
                                    // El borde marca la elegida. Va del color
                                    // del texto y no del acento: sobre su
                                    // propio color no se veria.
                                    .border(
                                        width = if (elegida) 3.dp else 0.dp,
                                        color = if (elegida) TextoPrimario else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable { app.ajustes.fijarPaleta(pal) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (elegida) {
                                    Icon(
                                        Icons.Filled.Check,
                                        pal.etiqueta,
                                        tint = pal.tinta(enClaro),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Solo cambia el color principal. El ámbar de \"pendiente\" y el " +
                            "coral de \"error\" no se tocan: son significado, no decoración.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextoTerciario,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { eligiendoTema = false }) {
                    Text("Listo", color = Cian)
                }
            },
        )
    }

    if (confirmandoSalida) {
        AlertDialog(
            onDismissRequest = { confirmandoSalida = false },
            containerColor = BgElev,
            title = { Text("Cerrar sesión", color = TextoPrimario) },
            text = {
                Text(
                    // Lo que la gente teme al cerrar sesion es perder las
                    // conversaciones, y en esta app conviene decir que no pasa:
                    // el historial esta cifrado en este telefono y sigue aca.
                    // Lo que SI hay que decir es lo otro: entrar con otra cuenta
                    // en este aparato si lo borra, porque no puede quedar el
                    // historial de una persona a la vista de la siguiente.
                    "Tus conversaciones quedan guardadas y cifradas en este teléfono. " +
                        "Vuelve a entrar con @$usuario y siguen acá.\n\n" +
                        "Si en cambio entras con OTRA cuenta en este aparato, el historial " +
                        "local se borra.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmandoSalida = false
                    ambito.launch {
                        // Modulo N: la baja del token va ANTES de tirar la
                        // sesion, porque despues ya no habria con que
                        // autenticar la peticion. Sin esto, este telefono
                        // seguiria recibiendo el aviso de que la cuenta tiene
                        // algo nuevo, y en un aparato prestado eso es
                        // exactamente lo que no debe pasar.
                        app.push.quitar()
                        app.repo.cerrarSesion()
                        onCerrarSesion()
                    }
                }) { Text("Cerrar sesión", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmandoSalida = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("No se pudo guardar", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

// ============================================================
//  Piezas
// ============================================================

/**
 * Portada y avatar.
 *
 * La caja mide exactamente lo que ocupa -portada mas la mitad del avatar que
 * sobresale- y no 230 dp fijos con 180 de contenido, que era lo que habia antes
 * y dejaba 50 dp de nada debajo.
 */
@Composable
private fun Cabecera(
    usuario: String,
    nombreMostrado: String,
    avatarVersion: Long,
    portadaVersion: Long,
    onCambiarAvatar: () -> Unit,
    onCambiarPortada: () -> Unit,
) {
    // 132 dp y no 156: con la portada mas alta quedaba un tercio de pantalla
    // de degradado sin nada, y lo que interesa -el nombre y los ajustes-
    // empezaba mas abajo de lo necesario.
    val altoPortada = 132.dp
    val avatar = 92.dp
    // El visor vive AQUI y no arriba: es de la cabecera, se abre y se cierra
    // sin que el resto de la pantalla se entere, y asi no hay que pasar un
    // callback mas por una firma que ya tiene seis.
    var verFoto by remember { mutableStateOf(false) }
    var verPortada by remember { mutableStateOf(false) }
    val urlAvatar = ApiCliente.urlImagen(usuario, "avatar", avatarVersion)
    val urlPortada = ApiCliente.urlImagen(usuario, "portada", portadaVersion)
    val titulo = nombreMostrado.ifBlank { "@$usuario" }

    if (verFoto) VisorDeFoto(urlAvatar, titulo) { verFoto = false }
    // La portada se abre con su propio rotulo. "Portada de Joaquin" y no solo
    // el nombre: abierta a pantalla completa y sin el circulo del avatar al
    // lado, una portada y una foto de perfil se parecen demasiado, y el visor
    // es el unico sitio donde se puede decir cual se esta mirando.
    if (verPortada) VisorDeFoto(urlPortada, "Portada de $titulo") { verPortada = false }

    Box(Modifier.fillMaxWidth().height(altoPortada + avatar / 2)) {
        if (urlPortada != null) {
            AsyncImage(
                model = urlPortada,
                contentDescription = "Portada",
                contentScale = ContentScale.Crop,
                // Se recorta con `Crop` para la banda de 132 dp, asi que lo
                // que se ve aqui NO es la foto: es una franja del medio.
                // Abrirla es la unica forma de ver lo que se subio.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(altoPortada)
                    .clickable { verPortada = true },
            )
        } else {
            // Sin portada, un degradado con el color de la persona: se ve
            // intencional en vez de un hueco vacio.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(altoPortada)
                    .background(
                        Brush.verticalGradient(
                            listOf(colorDeNombre(usuario).copy(alpha = 0.38f), BgBase)
                        )
                    )
            )
        }

        // Velo inferior: garantiza que el avatar y su borde se separen de
        // cualquier foto, por clara que sea.
        Box(
            Modifier
                .fillMaxWidth()
                .height(altoPortada)
                .background(
                    Brush.verticalGradient(
                        // Arriba, para que la hora y los iconos del sistema se
                        // lean sobre cualquier foto; abajo, para separar el
                        // avatar y su borde de la imagen.
                        0f to BgBase.copy(alpha = 0.55f),
                        0.3f to Color.Transparent,
                        1f to BgBase.copy(alpha = 0.9f),
                    )
                )
        )

        BotonCamara(
            onClick = onCambiarPortada,
            descripcion = "Cambiar portada",
            // Secundario: ver la nota de `BotonCamara`.
            principal = false,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // `statusBarsPadding` y no un margen fijo: la portada se dibuja
                // por debajo de la barra de estado a proposito, y sin esto el
                // boton queda tapado por la hora en los aparatos con muesca.
                .statusBarsPadding()
                .padding(top = 8.dp, end = 14.dp),
        )

        Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp)) {
            Avatar(
                nombre = nombreMostrado.ifBlank { usuario },
                url = urlAvatar,
                tamano = avatar,
                // Tocar la foto la abre; el boton de camara, que esta encima
                // en una esquina, sigue sirviendo para cambiarla. Son dos
                // cosas distintas y por eso son dos toques distintos: abrir
                // para mirar es lo que se hace mas seguido, asi que se lleva
                // el area grande.
                modifier = Modifier
                    .border(3.dp, BgBase, CircleShape)
                    .clip(CircleShape)
                    // Solo si HAY foto. Sin ella el avatar son unas iniciales
                    // dibujadas, y abrir un visor para mostrar "no se pudo
                    // abrir la foto" es peor que no reaccionar al toque.
                    .then(
                        if (urlAvatar != null) Modifier.clickable { verFoto = true }
                        else Modifier
                    ),
            )
            BotonCamara(
                onClick = onCambiarAvatar,
                descripcion = "Cambiar foto de perfil",
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * El boton de cambiar una imagen del perfil.
 *
 * ## Por que hay dos formas y no una
 *
 * Arriba hay dos de estos: cambiar la portada y cambiar la foto. La primera
 * version los dibujaba **identicos** -mismo icono, mismo color, mismo tamano- y
 * el de la portada, flotando sobre la franja sin estar pegado a nada, se leia
 * como el mismo boton repetido por error.
 *
 * Que las descripciones para el lector de pantalla si los distinguieran no
 * arregla nada para quien mira: dos controles que hacen cosas distintas tienen
 * que **verse** distintos.
 *
 * La diferencia no es decorativa, es de jerarquia:
 *
 * - **La foto es la accion principal.** Va en acento solido y pegada al avatar,
 *   que es lo que deja claro sobre que actua.
 * - **La portada es secundaria.** Va en un chip oscuro translucido, que ademas
 *   es lo unico legible encima de una imagen cualquiera, y con icono de imagen
 *   en vez de camara: no se hace una foto, se elige uno.
 */
@Composable
private fun BotonCamara(
    onClick: () -> Unit,
    descripcion: String,
    modifier: Modifier = Modifier,
    principal: Boolean = true,
) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (principal) Cian else Color.Black.copy(alpha = 0.42f))
            .then(
                // El borde separa el circulo del avatar que tiene detras. Sobre
                // la portada no hace falta y ademas lo empeora: dibuja un aro
                // claro sobre una imagen que no se sabe de que color es.
                if (principal) Modifier.border(2.dp, BgBase, CircleShape) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (principal) Icons.Filled.CameraAlt else Icons.Filled.Image,
            descripcion,
            tint = if (principal) TextoSobreAcento else Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun FormularioIdentidad(
    nombre: String,
    estado: String,
    onNombre: (String) -> Unit,
    onEstado: (String) -> Unit,
    onGuardar: () -> Unit,
    onCancelar: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = nombre,
            onValueChange = { if (it.length <= 48) onNombre(it) },
            label = { Text("Nombre", color = TextoTerciario) },
            supportingText = { Text("${nombre.length}/48", color = TextoTerciario) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = estado,
            onValueChange = { if (it.length <= 140) onEstado(it) },
            label = { Text("Estado", color = TextoTerciario) },
            supportingText = { Text("${estado.length}/140", color = TextoTerciario) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = onGuardar,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cian, contentColor = TextoSobreAcento,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Guardar")
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onCancelar) { Text("Cancelar", color = TextoSecundario) }
        }
    }
}

/**
 * La ficha de una empresa, tal como se ve en el perfil.
 *
 * ## El distintivo dice una cosa muy concreta
 *
 * "Verificada" significa que **alguien del equipo lo comprobo**, no que la
 * empresa exista ni que la cuenta sea suya. Por eso el texto de al lado lo dice
 * con esas palabras y no con un simbolo suelto: un check azul sin explicacion
 * se lee como "es de fiar", que es mas de lo que nadie comprobo.
 *
 * Y cuando NO esta verificada se dice tambien. Un perfil de empresa sin marca
 * alguna es indistinguible de uno verificado para quien no sabe que la marca
 * existe, y ahi es donde la ficha se volveria util para suplantar a alguien.
 *
 * ## Por que no es privada
 *
 * La dibuja tambien el chat, al abrir el contacto de la otra persona, y ese es
 * el lado que importa: una ficha que solo ve su dueno es un formulario. El
 * margen lateral es un parametro porque en el perfil la tarjeta vive dentro de
 * una columna a pantalla completa y en un dialogo ya viene con margen propio;
 * duplicarlo la dejaba con la mitad del ancho.
 */
@Composable
internal fun TarjetaEmpresa(e: FichaEmpresa, margenLateral: androidx.compose.ui.unit.Dp = 16.dp) {
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = margenLateral)
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Business, null,
                tint = Cian, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                e.nombreComercial.ifBlank { "Empresa sin nombre" },
                color = TextoPrimario,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (e.verificada) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Verified, "Verificada por el equipo",
                    tint = Cian, modifier = Modifier.size(17.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append(CategoriaEmpresa.legible(e.categoria))
                if (e.tamano.isNotBlank()) append(" \u00b7 ${TamanoEmpresa.legible(e.tamano)}")
                if (e.ubicacion.isNotBlank()) append(" \u00b7 ${e.ubicacion}")
                if (e.fundadaEn > 0) append(" \u00b7 desde ${e.fundadaEn}")
            },
            color = TextoSecundario,
            fontSize = 13.sp,
        )

        if (e.descripcion.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(e.descripcion, color = TextoSecundario, fontSize = 13.sp)
        }

        if (e.sitioWeb.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            // Se dibuja como texto y no como enlace tocable a proposito por
            // ahora: abrir un navegador desde un dato que escribio otra persona
            // merece su propia decision, y el servidor ya garantiza que es
            // https. Ver `Cuentas.sitioValido`.
            Text(e.sitioWeb, color = Cian, fontSize = 13.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (e.verificada) {
                "Verificada por el equipo de la plataforma."
            } else {
                "Sin verificar. Los datos los escribió esta cuenta."
            },
            color = if (e.verificada) Cian else TextoTerciario,
            fontSize = 11.5.sp,
        )
    }
}
