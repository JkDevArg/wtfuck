package com.wtfuck.app.ui

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
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.*
import kotlinx.coroutines.launch

/**
 * Modulo P · Elegir el tipo de cuenta y llenar la ficha de empresa.
 *
 * ## Que dibuja, y que NO dibuja
 *
 * Los tres tipos se muestran siempre, incluido `desarrollador`. Es deliberado:
 * esconderlo haria creer que no existe, y lo que hay que entender es que
 * **existe y no se pide aqui**. Sale marcado como "lo asigna el equipo" y no se
 * puede tocar.
 *
 * ## La pantalla no decide nada
 *
 * Quien decide es el servidor: esta pantalla ni siquiera aparece si
 * `puedeElegirTipo` viene en false, y aun asi las rutas responden 404 a quien
 * no esta en la beta. Lo de aqui es para no ofrecer lo que van a negar; no es
 * el control de acceso. Ver `Cuentas.kt` en el servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipoCuentaPantalla(onAtras: () -> Unit) {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()

    var cap by remember { mutableStateOf<CapacidadesCuenta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var guardando by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cap = app.repo.capacidadesDeCuenta() }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            TopAppBar(
                title = { Text("Tipo de cuenta", color = TextoPrimario) },
                navigationIcon = {
                    IconButton(onClick = onAtras) {
                        // `AutoMirrored` y no `Filled` como el resto de la
                        // app: la flecha de volver apunta al otro lado cuando
                        // la escritura va de derecha a izquierda.
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Atras",
                            tint = TextoPrimario,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSurface),
            )
        },
    ) { pad ->
        val c = cap
        if (c == null) {
            Box(
                // A la vista la espera es un circulo que gira; sin vista la
                // pantalla esta sencillamente vacia y no hay forma de saber si
                // esta cargando o si no hay nada que mostrar.
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .semantics { contentDescription = "Cargando el tipo de cuenta" },
                Alignment.Center,
            ) {
                CircularProgressIndicator(color = Cian)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Qué clase de cuenta es esta. Cambiarlo no afecta a tus chats ni " +
                    "a tu privacidad: solo a lo que se muestra en tu perfil.",
                color = TextoTerciario,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            // A la vista, una opcion bloqueada se distingue porque esta
            // atenuada; eso no llega a un lector de pantalla y, aunque
            // llegara, "desactivado" no dice POR QUE. El motivo se calcula
            // aqui una sola vez porque es el mismo para las dos elegibles.
            val razonComun = when {
                c.tipo == TipoCuenta.DESARROLLADOR ->
                    "una cuenta de desarrollador no se cambia desde aquí"
                guardando -> "espera a que termine el cambio anterior"
                else -> null
            }

            OpcionTipo(
                icono = Icons.Filled.Person,
                titulo = "Personal",
                detalle = "Una cuenta como cualquier otra",
                elegido = c.tipo == TipoCuenta.NORMAL,
                habilitado = !guardando && c.tipo != TipoCuenta.DESARROLLADOR,
                razonBloqueo = razonComun,
                onElegir = {
                    ambito.launch {
                        guardando = true
                        app.repo.elegirTipoCuenta(TipoCuenta.NORMAL)
                            .onSuccess { cap = it; aviso = "Cuenta personal" }
                            .onFailure { error = it.message }
                        guardando = false
                    }
                },
            )

            OpcionTipo(
                icono = Icons.Filled.Business,
                titulo = "Empresa",
                detalle = "Añade una ficha pública: nombre comercial, rubro y sitio",
                elegido = c.tipo == TipoCuenta.EMPRESA,
                habilitado = !guardando && c.tipo != TipoCuenta.DESARROLLADOR,
                razonBloqueo = razonComun,
                onElegir = {
                    ambito.launch {
                        guardando = true
                        app.repo.elegirTipoCuenta(TipoCuenta.EMPRESA)
                            .onSuccess { cap = it }
                            .onFailure { error = it.message }
                        guardando = false
                    }
                },
            )

            // Se muestra aunque no se pueda elegir. Esconderlo haria creer que
            // no existe; lo que hay que entender es que existe y no se pide.
            OpcionTipo(
                icono = Icons.Filled.DeveloperMode,
                titulo = "Desarrollador",
                detalle = "Lo asigna el equipo. Añade herramientas de diagnóstico",
                elegido = c.tipo == TipoCuenta.DESARROLLADOR,
                habilitado = false,
                // El mismo motivo que a la vista explica el gris: existe, y no
                // se pide aqui. Sin esto la opcion suena igual que las otras
                // dos y parece que el toque simplemente no funciona.
                razonBloqueo = "lo asigna el equipo, no se pide desde aquí",
                onElegir = {},
            )

            if (c.tipo == TipoCuenta.EMPRESA) {
                Spacer(Modifier.height(22.dp))
                FichaEmpresaForm(
                    inicial = c.empresa ?: FichaEmpresa(),
                    guardando = guardando,
                    onGuardar = { req ->
                        ambito.launch {
                            guardando = true
                            app.repo.guardarFichaEmpresa(req)
                                .onSuccess {
                                    cap = c.copy(empresa = it)
                                    aviso = "Ficha guardada"
                                }
                                .onFailure { error = it.message }
                            guardando = false
                        }
                    },
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Entendido") } },
            title = { Text("No se pudo", color = TextoPrimario) },
            text = { Text(msg, color = TextoSecundario) },
            containerColor = BgSurface,
        )
    }
    aviso?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(1600); aviso = null }
        Box(Modifier.fillMaxSize().padding(bottom = 30.dp), Alignment.BottomCenter) {
            Text(
                msg,
                color = TextoSobreAcento,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Cian)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    // Aparece y se va sola en 1,6 s: nadie va a llegar a
                    // enfocarla a tiempo. Como region viva se anuncia al
                    // salir, que es la unica forma de que el aviso llegue.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Una opcion de tipo, con su marca cuando esta elegida.
 *
 * ## Por que la semantica no se deduce sola del `clickable`
 *
 * A la vista estas tres filas son un grupo de exclusion: el borde cian y el
 * check dicen cual esta puesta, y el gris dice cual no se puede tocar. Las dos
 * cosas se pierden juntas, y lo que queda de un `clickable` a secas es una fila
 * que se lee como tres textos sueltos y que, cuando esta bloqueada, ni siquiera
 * dice que lo esta.
 *
 * Por eso se declara a mano: rol de boton de opcion —una y solo una—, `selected`
 * para que "elegida" sea un estado y no un adorno, y el motivo del bloqueo en
 * palabras. "Desactivado" a secas obliga a adivinar si es un fallo de la app.
 */
@Composable
private fun OpcionTipo(
    icono: ImageVector,
    titulo: String,
    detalle: String,
    elegido: Boolean,
    habilitado: Boolean,
    razonBloqueo: String?,
    onElegir: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BgSurface)
            .border(
                width = if (elegido) 1.5.dp else 1.dp,
                color = if (elegido) Cian else Slate.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = habilitado && !elegido, onClick = onElegir)
            .padding(14.dp)
            // Se fusiona porque a la vista es UN bloque: titulo y detalle no
            // son dos paradas, son una frase.
            .semantics(mergeDescendants = true) {
                contentDescription = "$titulo. $detalle"
                role = Role.RadioButton
                selected = elegido
                // El estado va en `stateDescription` y no pegado al texto: asi
                // TalkBack lo vuelve a anunciar cuando CAMBIA, que es justo el
                // momento en el que hace falta saberlo.
                stateDescription = when {
                    elegido -> "elegida"
                    razonBloqueo != null -> "no seleccionable: $razonBloqueo"
                    else -> "sin elegir"
                }
                if (!habilitado) disabled()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (elegido) Cian.copy(alpha = 0.18f) else BgElev),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icono, null,
                tint = if (habilitado || elegido) Cian else TextoTerciario,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                color = if (habilitado || elegido) TextoPrimario else TextoSecundario,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(detalle, color = TextoTerciario, fontSize = 12.5.sp)
        }
        if (elegido) {
            // Sin descripcion a proposito: al fusionar la fila, "Elegido" se
            // sumaria a la frase y se diria dos veces, porque el estado ya va
            // en el `stateDescription` de la fila.
            Icon(Icons.Filled.Check, null, tint = Cian, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * El formulario de la ficha.
 *
 * El aviso de que editar quita la verificación va **arriba y siempre**, no
 * después de guardar: quien tiene el distintivo tiene que poder decidir si le
 * compensa tocar algo antes de tocarlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FichaEmpresaForm(
    inicial: FichaEmpresa,
    guardando: Boolean,
    onGuardar: (FichaEmpresaReq) -> Unit,
) {
    var nombre by remember(inicial) { mutableStateOf(inicial.nombreComercial) }
    var categoria by remember(inicial) { mutableStateOf(inicial.categoria) }
    var descripcion by remember(inicial) { mutableStateOf(inicial.descripcion) }
    var sitio by remember(inicial) { mutableStateOf(inicial.sitioWeb) }
    var tamano by remember(inicial) { mutableStateOf(inicial.tamano) }
    var ubicacion by remember(inicial) { mutableStateOf(inicial.ubicacion) }
    var fundada by remember(inicial) {
        mutableStateOf(if (inicial.fundadaEn > 0) inicial.fundadaEn.toString() else "")
    }
    var abreCategoria by remember { mutableStateOf(false) }
    var abreTamano by remember { mutableStateOf(false) }

    Text("Ficha de empresa", color = TextoPrimario, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    Spacer(Modifier.height(4.dp))

    if (inicial.verificada) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cian.copy(alpha = 0.12f))
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Verified, null, tint = Cian, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Verificada. Si editás la ficha, la verificación se retira y hay " +
                    "que pedirla de nuevo.",
                color = Cian,
                fontSize = 12.sp,
            )
        }
    } else {
        Text(
            "Sin verificar. El distintivo lo pone el equipo, no se activa solo.",
            color = TextoTerciario,
            fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(12.dp))

    CampoFicha("Nombre comercial", nombre, TopesEmpresa.NOMBRE) { nombre = it }

    // Categoria y tamano son listas cerradas: un desplegable y no un campo
    // libre, porque el servidor solo acepta estos valores y un campo libre
    // seria pedirle a la persona que adivine cuales son.
    ExposedDropdownMenuBox(
        expanded = abreCategoria,
        onExpandedChange = { abreCategoria = !abreCategoria },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        OutlinedTextField(
            value = CategoriaEmpresa.legible(categoria),
            onValueChange = {},
            readOnly = true,
            label = { Text("Rubro", color = TextoTerciario) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = coloresCampo(),
        )
        ExposedDropdownMenu(
            expanded = abreCategoria,
            onDismissRequest = { abreCategoria = false },
            containerColor = BgSurface,
        ) {
            CategoriaEmpresa.TODAS.forEach { op ->
                DropdownMenuItem(
                    text = { Text(CategoriaEmpresa.legible(op), color = TextoPrimario) },
                    onClick = { categoria = op; abreCategoria = false },
                )
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = abreTamano,
        onExpandedChange = { abreTamano = !abreTamano },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        OutlinedTextField(
            value = TamanoEmpresa.legible(tamano),
            onValueChange = {},
            readOnly = true,
            label = { Text("Tamaño", color = TextoTerciario) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = coloresCampo(),
        )
        ExposedDropdownMenu(
            expanded = abreTamano,
            onDismissRequest = { abreTamano = false },
            containerColor = BgSurface,
        ) {
            (listOf("") + TamanoEmpresa.TODOS).forEach { op ->
                DropdownMenuItem(
                    text = { Text(TamanoEmpresa.legible(op), color = TextoPrimario) },
                    onClick = { tamano = op; abreTamano = false },
                )
            }
        }
    }

    CampoFicha("Ubicación", ubicacion, TopesEmpresa.UBICACION, arriba = 10.dp) { ubicacion = it }

    // Se deja escrito el https:// en el marcador porque el servidor rechaza
    // cualquier otra cosa: un enlace de perfil que pueda ser `javascript:` es
    // una trampa, no una pagina web.
    CampoFicha(
        "Sitio web", sitio, TopesEmpresa.SITIO, arriba = 10.dp,
        marcador = "https://…",
    ) { sitio = it }

    OutlinedTextField(
        value = fundada,
        onValueChange = { v -> fundada = v.filter { it.isDigit() }.take(4) },
        label = { Text("Año de fundación", color = TextoTerciario) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        colors = coloresCampo(),
    )

    CampoFicha(
        "Descripción", descripcion, TopesEmpresa.DESCRIPCION, arriba = 10.dp, lineas = 4,
    ) { descripcion = it }

    Spacer(Modifier.height(4.dp))
    Text(
        "${descripcion.length} / ${TopesEmpresa.DESCRIPCION}",
        color = TextoTerciario,
        fontSize = 11.sp,
        textAlign = TextAlign.End,
        modifier = Modifier
            .fillMaxWidth()
            // Leido tal cual, "137 / 300" es un acertijo. El contador solo
            // sirve para saber cuanto queda, asi que eso es lo que se dice.
            .semantics {
                contentDescription =
                    "${descripcion.length} de ${TopesEmpresa.DESCRIPCION} caracteres usados"
            },
    )

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = {
            onGuardar(
                FichaEmpresaReq(
                    nombreComercial = nombre.trim(),
                    categoria = categoria,
                    descripcion = descripcion.trim(),
                    sitioWeb = sitio.trim(),
                    tamano = tamano,
                    ubicacion = ubicacion.trim(),
                    fundadaEn = fundada.toIntOrNull() ?: 0,
                )
            )
        },
        enabled = nombre.isNotBlank() && !guardando,
        modifier = Modifier
            .fillMaxWidth()
            // El boton apagado solo dice "desactivado", y a la vista tampoco
            // dice mas: se queda gris. Quien no ve el formulario entero no
            // tiene como atar ese gris al campo que falta, asi que se nombra.
            .semantics(mergeDescendants = true) {
                if (nombre.isBlank()) {
                    stateDescription = "no disponible: falta el nombre comercial"
                }
            },
        colors = ButtonDefaults.buttonColors(containerColor = Cian, contentColor = TextoSobreAcento),
    ) {
        Text(if (guardando) "Guardando…" else "Guardar ficha")
    }
}

@Composable
private fun CampoFicha(
    etiqueta: String,
    valor: String,
    tope: Int,
    arriba: androidx.compose.ui.unit.Dp = 0.dp,
    lineas: Int = 1,
    marcador: String? = null,
    onCambio: (String) -> Unit,
) {
    OutlinedTextField(
        value = valor,
        // El tope se aplica AQUI ademas de en el servidor. No es una defensa
        // —el servidor tambien lo hace— sino cortesia: escribir 300 caracteres
        // para que te los rechacen al guardar es perder el texto.
        onValueChange = { onCambio(it.take(tope)) },
        label = { Text(etiqueta, color = TextoTerciario) },
        placeholder = marcador?.let { { Text(it, color = TextoTerciario) } },
        modifier = Modifier.fillMaxWidth().padding(top = arriba),
        singleLine = lineas == 1,
        minLines = lineas,
        colors = coloresCampo(),
    )
}

@Composable
private fun coloresCampo() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cian,
    unfocusedBorderColor = Slate,
    focusedTextColor = TextoPrimario,
    unfocusedTextColor = TextoPrimario,
)
