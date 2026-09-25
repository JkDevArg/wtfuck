package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ApiCliente
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.*
import kotlinx.coroutines.launch

/**
 * Administracion de un grupo.
 *
 * Los controles se muestran segun la jerarquia de quien mira, pero eso es solo
 * para no ofrecer lo que va a fallar: cada accion la vuelve a autorizar el
 * servidor. Si alguien fuerza la peticion, recibe 403 igual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrupoPantalla(
    conversacionId: String,
    onAtras: () -> Unit,
    onSalio: () -> Unit,
    onVerificarCifrado: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current

    var cfg by remember { mutableStateOf<ConfigGrupo?>(null) }
    var miembros by remember { mutableStateOf<List<MiembroDetalle>>(emptyList()) }
    var roles by remember { mutableStateOf<List<RolDetalle>>(emptyList()) }
    var solicitudes by remember { mutableStateOf<List<Solicitud>>(emptyList()) }
    var invitacion by remember { mutableStateOf<Invitacion?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }

    var accionesDe by remember { mutableStateOf<MiembroDetalle?>(null) }
    var agregando by remember { mutableStateOf(false) }
    var editandoInfo by remember { mutableStateOf(false) }
    var confirmarSalir by remember { mutableStateOf(false) }

    // Mi jerarquia sale de la lista de miembros: es la misma que usa el servidor.
    val miUsuario = app.sesion.username.orEmpty()
    val yo = miembros.firstOrNull { it.usuario.username == miUsuario }
    val miJerarquia = yo?.jerarquia ?: 10

    // Si no aparezco en la lista de miembros, es que ya no pertenezco. Se usa
    // eso como verdad y no un flag local: la lista viene del servidor.
    val soyMiembro = yo != null
    val puedoAdministrar = soyMiembro && miJerarquia >= 80
    val puedoModerar = soyMiembro && miJerarquia >= 50

    suspend fun recargar() {
        runCatching {
            cfg = app.repo.configGrupo(conversacionId)
            miembros = app.repo.miembros(conversacionId)
            roles = app.repo.rolesDe(conversacionId)
            // Las solicitudes solo las ve quien puede aprobarlas; un 403 aqui es
            // normal para un miembro y no debe verse como error.
            solicitudes = runCatching { app.repo.solicitudes(conversacionId) }.getOrDefault(emptyList())
        }.onFailure { aviso = it.message }
        cargando = false
    }

    LaunchedEffect(conversacionId) { recargar() }

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
                title = { Text("Info del grupo", color = TextoPrimario) },
                actions = {
                    if (soyMiembro) {
                        IconButton(onClick = { confirmarSalir = true }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, "Salir del grupo", tint = Coral)
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (cargando) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            // --- cabecera -------------------------------------------------
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(cfg?.nombre.orEmpty(), null, 88.dp, esGrupo = true)
                Spacer(Modifier.height(14.dp))
                Text(
                    cfg?.nombre.orEmpty(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextoPrimario,
                )
                Text(
                    "${miembros.size} ${if (miembros.size == 1) "miembro" else "miembros"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoSecundario,
                )
                if (!cfg?.alias.isNullOrBlank()) {
                    Text("@${cfg?.alias}", style = MaterialTheme.typography.bodyMedium, color = Cian)
                }
                if (!soyMiembro) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Coral.copy(alpha = 0.14f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Block, null, tint = Coral, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Ya no eres miembro de este grupo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Coral,
                        )
                    }
                }

                if (!cfg?.descripcion.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        cfg?.descripcion.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextoSecundario,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
                if (puedoAdministrar) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { editandoInfo = true }) {
                        Text("Editar información", color = Cian)
                    }
                }
            }

            // --- solicitudes pendientes -----------------------------------
            if (solicitudes.isNotEmpty()) {
                Seccion("Solicitudes de ingreso (${solicitudes.size})")
                solicitudes.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(s.usuario.username, null, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "@${s.usuario.username}",
                            color = TextoPrimario,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            ambito.launch {
                                runCatching { app.repo.resolverSolicitud(conversacionId, s.usuario.usuarioId, false) }
                                    .onFailure { aviso = it.message }
                                recargar()
                            }
                        }) { Text("Rechazar", color = Coral) }
                        TextButton(onClick = {
                            ambito.launch {
                                runCatching { app.repo.resolverSolicitud(conversacionId, s.usuario.usuarioId, true) }
                                    .onFailure { aviso = it.message }
                                recargar()
                            }
                        }) { Text("Aprobar", color = Cian) }
                    }
                }
            }

            // --- configuracion --------------------------------------------
            if (puedoAdministrar) {
                cfg?.let { c ->
                    Seccion("Configuración")
                    Interruptor("Grupo publico", "Cualquiera con el enlace puede entrar", c.publico) { v ->
                        guardar(app, ambito, conversacionId, c.copy(publico = v), { cfg = it }, { aviso = it })
                    }
                    Interruptor("Modo anuncio", "Solo los mandos pueden escribir", c.soloAdmins) { v ->
                        guardar(app, ambito, conversacionId, c.copy(soloAdmins = v), { cfg = it }, { aviso = it })
                    }
                    Interruptor("Aprobar ingresos", "Revisas cada solicitud antes de admitirla", c.aprobarIngreso) { v ->
                        guardar(app, ambito, conversacionId, c.copy(aprobarIngreso = v), { cfg = it }, { aviso = it })
                    }
                    Interruptor("Permitir multimedia", "Fotos, videos, audios y archivos", c.permitirMedia) { v ->
                        guardar(app, ambito, conversacionId, c.copy(permitirMedia = v), { cfg = it }, { aviso = it })
                    }
                    Interruptor("Permitir enlaces", "Mensajes con direcciones web", c.permitirEnlaces) { v ->
                        guardar(app, ambito, conversacionId, c.copy(permitirEnlaces = v), { cfg = it }, { aviso = it })
                    }
                }

                // --- verificacion de identidad ----------------------------
                //
                // E.7. En un grupo hay una huella por PERSONA y por aparato, y
                // hasta aqui la pantalla de verificacion no tenia como
                // abrirse desde un grupo: solo desde una directa. El texto de
                // esa pantalla decia "en grupos se verifica desde la lista de
                // miembros", que era una promesa que nadie cumplia.
                Seccion("Seguridad")
                FilaAccion("Verificar cifrado", Icons.Filled.VerifiedUser, onVerificarCifrado)

                // --- enlace de invitacion ---------------------------------
                Seccion("Enlace de invitacion")
                invitacion?.let { inv ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgElev)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            inv.codigo,
                            style = estiloHuella,
                            color = TextoSecundario,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            portapapeles.setText(AnnotatedString("wtfuck.com/i/${inv.codigo}"))
                        }) { Icon(Icons.Filled.ContentCopy, "Copiar", tint = Cian) }
                    }
                }
                FilaAccion("Crear enlace nuevo", Icons.Filled.Link) {
                    ambito.launch {
                        runCatching { app.repo.crearInvitacion(conversacionId, 0, 0) }
                            .onSuccess { invitacion = it }
                            .onFailure { aviso = it.message }
                    }
                }
            }

            if (puedoModerar) {
                FilaAccion("Agregar miembros", Icons.Filled.PersonAdd) { agregando = true }
            }

            // --- mensajes temporales --------------------------------------
            if (puedoAdministrar) {
                Seccion("Mensajes temporales")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    listOf("Off" to null, "1 dia" to 86_400, "1 semana" to 604_800).forEach { (etq, segs) ->
                        OutlinedButton(
                            onClick = {
                                ambito.launch {
                                    runCatching { app.repo.configurarTemporales(conversacionId, segs) }
                                        .onFailure { aviso = it.message }
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Filled.Timer, null, Modifier.size(15.dp), tint = Cian)
                            Spacer(Modifier.width(5.dp))
                            Text(etq, color = TextoPrimario, fontSize = 13.sp)
                        }
                    }
                }
            }

            // --- miembros -------------------------------------------------
            Seccion("Miembros")
            miembros.forEach { m ->
                FilaMiembro(
                    m = m,
                    esYo = m.usuario.username == miUsuario,
                    // Solo se ofrecen acciones sobre quien esta por debajo.
                    puedeActuar = puedoModerar && m.jerarquia < miJerarquia,
                    onClick = { accionesDe = m },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // --- hojas y dialogos ---------------------------------------------

    accionesDe?.let { m ->
        HojaMiembro(
            miembro = m,
            roles = roles.filter { it.jerarquia < miJerarquia },
            puedeAdministrarRoles = puedoAdministrar,
            onCerrar = { accionesDe = null },
            onRol = { clave ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.cambiarRol(conversacionId, m.usuario.usuarioId, clave) }
                        .onFailure { aviso = it.message }
                    recargar()
                }
            },
            onSilenciar = { min ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.silenciarMiembro(conversacionId, m.usuario.usuarioId, min) }
                        .onFailure { aviso = it.message }
                    recargar()
                }
            },
            onExpulsar = { vetar ->
                accionesDe = null
                ambito.launch {
                    runCatching { app.repo.expulsar(conversacionId, m.usuario.usuarioId, vetar) }
                        .onFailure { aviso = it.message }
                    recargar()
                }
            },
        )
    }

    if (agregando) {
        var texto by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { agregando = false },
            containerColor = BgElev,
            title = { Text("Agregar miembros", color = TextoPrimario) },
            text = {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    label = { Text("Usuarios, separados por coma") },
                    prefix = { Text("@", color = TextoTerciario) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val us = texto.split(",").map { it.trim().removePrefix("@").lowercase() }
                        .filter { it.isNotEmpty() }
                    agregando = false
                    if (us.isNotEmpty()) ambito.launch {
                        runCatching { app.repo.agregarMiembros(conversacionId, us) }
                            .onFailure { aviso = it.message }
                        recargar()
                    }
                }) { Text("Agregar", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { agregando = false }) { Text("Cancelar", color = TextoSecundario) }
            },
        )
    }

    if (editandoInfo) {
        cfg?.let { c ->
            var nombre by remember { mutableStateOf(c.nombre) }
            var desc by remember { mutableStateOf(c.descripcion) }
            var alias by remember { mutableStateOf(c.alias) }
            AlertDialog(
                onDismissRequest = { editandoInfo = false },
                containerColor = BgElev,
                title = { Text("Editar información", color = TextoPrimario) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = nombre, onValueChange = { if (it.length <= 64) nombre = it },
                            label = { Text("Nombre") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = desc, onValueChange = { if (it.length <= 500) desc = it },
                            label = { Text("Descripción") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = alias, onValueChange = { alias = it },
                            label = { Text("Alias publico") },
                            prefix = { Text("@", color = TextoTerciario) },
                            supportingText = { Text("4-32 caracteres. Vacio = sin alias.", color = TextoTerciario) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        editandoInfo = false
                        guardar(
                            app, ambito, conversacionId,
                            c.copy(nombre = nombre, descripcion = desc, alias = alias),
                            { cfg = it }, { aviso = it },
                        )
                    }) { Text("Guardar", color = Cian) }
                },
                dismissButton = {
                    TextButton(onClick = { editandoInfo = false }) { Text("Cancelar", color = TextoSecundario) }
                },
            )
        }
    }

    if (confirmarSalir) {
        AlertDialog(
            onDismissRequest = { confirmarSalir = false },
            containerColor = BgElev,
            title = { Text("Salir del grupo", color = TextoPrimario) },
            text = {
                Text(
                    "Dejaras de recibir mensajes. Tu historial local se conserva en este dispositivo.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmarSalir = false
                    ambito.launch {
                        runCatching { app.repo.salirDe(conversacionId) }
                        onSalio()
                    }
                }) { Text("Salir", color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalir = false }) { Text("Cancelar", color = TextoSecundario) }
            },
        )
    }

    aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { aviso = null },
            containerColor = BgElev,
            title = { Text("No se pudo completar", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            confirmButton = { TextButton(onClick = { aviso = null }) { Text("Entendido", color = Cian) } },
        )
    }
}

private fun guardar(
    app: WtfuckApp,
    ambito: kotlinx.coroutines.CoroutineScope,
    convId: String,
    nueva: ConfigGrupo,
    onOk: (ConfigGrupo) -> Unit,
    onError: (String?) -> Unit,
) {
    ambito.launch {
        runCatching { app.repo.guardarConfigGrupo(convId, nueva) }
            .onSuccess(onOk)
            .onFailure { onError(it.message) }
    }
}

@Composable
private fun Seccion(titulo: String) {
    Column {
        HorizontalDivider(color = Slate.copy(alpha = 0.25f))
        Text(
            titulo,
            style = MaterialTheme.typography.labelSmall,
            color = Cian,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun Interruptor(titulo: String, detalle: String, valor: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge, color = TextoPrimario)
            Text(detalle, style = MaterialTheme.typography.bodyMedium, color = TextoTerciario)
        }
        Switch(
            checked = valor,
            onCheckedChange = onCambio,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextoSobreAcento,
                checkedTrackColor = Cian,
                uncheckedTrackColor = BgElev,
                uncheckedBorderColor = Slate,
            ),
        )
    }
}

@Composable
private fun FilaAccion(texto: String, icono: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Cian, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(18.dp))
        Text(texto, style = MaterialTheme.typography.bodyLarge, color = Cian)
    }
}

@Composable
private fun FilaMiembro(m: MiembroDetalle, esYo: Boolean, puedeActuar: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (puedeActuar) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            nombre = m.usuario.nombreMostrado.ifBlank { m.usuario.username },
            url = ApiCliente.urlImagen(m.usuario.username, "avatar", m.usuario.avatarVersion),
            tamano = 42.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "@${m.usuario.username}" + if (esYo) " (tu)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextoPrimario,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            m.restriccion?.let { r ->
                Text(
                    if (r == "silenciado") "Silenciado" else "Restringido",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ambar,
                )
            }
        }
        // El rol solo se destaca cuando no es "miembro": si no, todo grita.
        if (m.rolClave != "miembro") {
            Text(
                m.rolNombre,
                style = MaterialTheme.typography.labelSmall,
                color = if (m.jerarquia >= 80) Cian else TextoSecundario,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (m.jerarquia >= 80) Cian.copy(alpha = 0.18f) else Slate.copy(alpha = 0.25f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaMiembro(
    miembro: MiembroDetalle,
    roles: List<RolDetalle>,
    puedeAdministrarRoles: Boolean,
    onCerrar: () -> Unit,
    onRol: (String) -> Unit,
    onSilenciar: (Int) -> Unit,
    onExpulsar: (Boolean) -> Unit,
) {
    var confirmando by remember { mutableStateOf<Boolean?>(null) }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(miembro.usuario.username, null, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("@${miembro.usuario.username}", color = TextoPrimario,
                        style = MaterialTheme.typography.titleMedium)
                    Text(miembro.rolNombre, color = TextoTerciario,
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

            if (puedeAdministrarRoles && roles.isNotEmpty()) {
                Text(
                    "Cambiar rol",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cian,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
                roles.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onRol(r.clave) }
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.nombre, color = TextoPrimario, modifier = Modifier.weight(1f))
                        if (r.clave == miembro.rolClave) {
                            Icon(Icons.Filled.Check, null, tint = Cian, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))
            }

            if (miembro.restriccion == "silenciado") {
                FilaAccion("Quitar silencio", Icons.Filled.Check) { onSilenciar(0) }
            } else {
                FilaAccion("Silenciar 1 hora", Icons.Filled.Timer) { onSilenciar(60) }
                FilaAccion("Silenciar 1 dia", Icons.Filled.Timer) { onSilenciar(1440) }
            }

            HorizontalDivider(color = Slate.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

            Row(
                Modifier.fillMaxWidth().clickable { confirmando = false }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Coral, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(18.dp))
                Text("Expulsar", color = Coral, style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                Modifier.fillMaxWidth().clickable { confirmando = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Coral, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(18.dp))
                Text("Expulsar y vetar", color = Coral, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    confirmando?.let { vetar ->
        AlertDialog(
            onDismissRequest = { confirmando = null },
            containerColor = BgElev,
            title = { Text(if (vetar) "Expulsar y vetar" else "Expulsar", color = TextoPrimario) },
            text = {
                Text(
                    if (vetar)
                        "@${miembro.usuario.username} sale del grupo y no podrá volver, ni siquiera con un enlace valido."
                    else
                        "@${miembro.usuario.username} sale del grupo, pero podrá volver a entrar con un enlace.",
                    color = TextoSecundario,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmando = null; onExpulsar(vetar) }) {
                    Text("Expulsar", color = Coral, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmando = null }) { Text("Cancelar", color = TextoSecundario) }
            },
        )
    }
}
