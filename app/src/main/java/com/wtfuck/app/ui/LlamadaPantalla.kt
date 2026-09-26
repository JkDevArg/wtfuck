package com.wtfuck.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.wtfuck.app.WtfuckApp
import com.wtfuck.app.datos.ESTADO_CAIDO
import com.wtfuck.app.datos.ESTADO_DENTRO
import com.wtfuck.app.datos.ESTADO_RECHAZO
import com.wtfuck.app.datos.ESTADO_SONANDO
import com.wtfuck.app.datos.EstadoLlamada
import com.wtfuck.app.datos.FabricaWebRtc
import com.wtfuck.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility

/**
 * La pantalla de una llamada: sonando, conectando, en curso.
 *
 * ## Por que es una capa encima de todo y no un destino de navegacion
 *
 * Una llamada entrante tiene que aparecer sobre lo que sea que haya en
 * pantalla, y tiene que irse sin dejar rastro en el historial de navegacion.
 * Como destino, "atras" desde la llamada devolveria a la pantalla anterior -o
 * peor, sacaria de la app- y una llamada entrante mientras se escribe un
 * mensaje pisaria el borrador. Como capa, la llamada aparece y desaparece con
 * su estado y no toca la navegacion de abajo.
 *
 * ## Lo que esta pantalla NO arregla
 *
 * Si Android mata el proceso, la llamada se corta: no hay servicio en primer
 * plano. Esta declarado en [com.wtfuck.app.datos.ServicioLlamadas] y sigue
 * siendo trabajo aparte.
 */
@Composable
fun CapaLlamada() {
    val app = LocalContext.current.applicationContext as WtfuckApp
    val servicio = app.repo.llamadas
    val estado by servicio.estado.collectAsState()
    val videosRemotos by servicio.videosRemotos.collectAsState()
    // Para la ventana flotante y el fondo de una llamada de dos: la unica que
    // hay. Con mas de una se dibuja la rejilla.
    val videoRemoto = videosRemotos.values.firstOrNull()
    val videoLocal by servicio.videoLocal.collectAsState()
    val ambito = rememberCoroutineScope()

    val e = estado ?: return

    // Minimizada o completa.
    //
    // La clave es el `llamadaId` y no `Unit`: cada llamada empieza completa.
    // Con `Unit`, minimizar una llamada dejaba la SIGUIENTE entrando en
    // miniatura, y una llamada entrante que aparece como una ventanita de 128
    // dp en una esquina es una llamada que nadie ve.
    var minimizada by remember(e.llamadaId) { mutableStateOf(false) }

    /**
     * Si se ven los botones y la cabecera.
     *
     * Sólo se esconden cuando hay **algo que mirar**: alguien presentando su
     * pantalla. En una llamada normal se quedan puestos, porque no tapan nada
     * y esconderlos obligaría a tocar para colgar.
     *
     * Es el comportamiento de cualquier reproductor, y aquí importa más: la
     * cabecera y los cuatro botones se comían justo el centro de una película.
     */
    var controles by remember(e.llamadaId) { mutableStateOf(true) }

    /** Viendo lo de otro. Quien presenta está en otra app, no mirando esto. */
    val mirando = e.presentando != null && !e.presentoYo

    // Se apartan solos a los pocos segundos, y no de golpe: da tiempo a ver
    // quién empezó a presentar antes de que el aviso se vaya.
    //
    // El temporizador se reinicia en cada toque porque depende de `controles`:
    // volver a mostrarlos arranca una cuenta nueva.
    LaunchedEffect(mirando, controles) {
        if (mirando && controles) {
            kotlinx.coroutines.delay(3_500)
            controles = false
        }
    }

    // Y vuelven al tocar en cualquier sitio. Al dejar de mirar vuelven solos:
    // si no, al terminar la presentación quedaría una llamada sin controles y
    // sin nada que explique por qué.
    LaunchedEffect(mirando) { if (!mirando) controles = true }

    // ---------------------------------------------------------------
    // Modo cine: el permiso lo da el sistema, no esta app
    // ---------------------------------------------------------------
    //
    // `createScreenCaptureIntent()` abre un diálogo del sistema donde la
    // persona elige **qué** compartir —una sola app o la pantalla entera— y
    // confirma. Esta app no ve esa elección, no puede preseleccionar nada y no
    // puede saltársela: leer la pantalla de alguien no puede pasar sin que lo
    // autorice ahí.
    //
    // Y una advertencia que conviene tener presente: una app con contenido
    // protegido —HBO, Netflix, Disney+— marca su ventana con `FLAG_SECURE` y
    // el sistema entrega **negro** en su lugar. Es el DRM funcionando, no un
    // fallo, y no hay nada que hacer del lado de acá.
    val contexto = LocalContext.current
    val proyector = remember(contexto) {
        contexto.getSystemService(android.media.projection.MediaProjectionManager::class.java)
    }
    val pedirPantalla = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        // Cancelar es una respuesta válida y frecuente: se abre el diálogo,
        // se lee lo que pide y se dice que no. No hay nada que avisar.
        val datos = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && datos != null) {
            servicio.iniciarCine(datos)
        }
    }

    // Mientras suena NO se puede minimizar, y al terminar se vuelve a abrir:
    // las dos son pantallas que existen para decir algo -"contesta" y "te
    // rechazaron"- y en una ventanita de una esquina no se dicen.
    LaunchedEffect(e.fase) {
        if (e.fase == EstadoLlamada.Fase.SONANDO || e.fase == EstadoLlamada.Fase.TERMINADA) {
            minimizada = false
        }
    }

    if (minimizada) {
        VentanaFlotante(
            e = e,
            videoRemoto = videoRemoto,
            onExpandir = { minimizada = false },
            onColgar = { ambito.launch { servicio.colgar() } },
        )
        return
    }

    Surface(color = BgBase, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // El toque va en el Box de fuera y no en una capa encima.
                //
                // En Compose el evento baja primero a los hijos: un botón lo
                // consume y aquí no llega, y un toque en el hueco entre
                // botones no lo consume nadie y sí llega. Una capa propia
                // habría tenido que elegir entre tapar los botones o no
                // recibir nada.
                .pointerInput(mirando) {
                    if (mirando) detectTapGestures { controles = !controles }
                },
        ) {

            // Con UNA persona al otro lado, su video ocupa el fondo: es la
            // llamada que existe desde el modulo K y sigue siendo el caso
            // normal.
            //
            // Con VARIAS, una rejilla. No es una preferencia: el servidor
            // admite hasta cuatro desde el modulo K y la app dibujaba una
            // sola pista, asi que en una llamada de tres se veia a uno -y
            // cambiaba sin motivo, porque la ultima pista en llegar pisaba a
            // la anterior-.
            //
            // Y el MODO CINE manda sobre las dos. Cuando alguien presenta, lo
            // que se vino a mirar es eso: una rejilla que le da a la pantalla
            // compartida el mismo cuarto que a cada cara convierte la pelicula
            // en un sello de correos. Las caras siguen, pero abajo y chicas.
            val pistaCine = e.presentando
                ?.takeIf { !e.presentoYo }
                ?.let { quien ->
                    e.participantes.entries
                        .firstOrNull { it.value == quien }
                        ?.let { videosRemotos[it.key] }
                }

            if (pistaCine != null) {
                VistaVideo(
                    track = pistaCine,
                    espejo = false,
                    completo = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (e.conVideo && videosRemotos.size > 1) {
                RejillaVideos(
                    videos = videosRemotos,
                    nombres = e.participantes,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (e.conVideo && videoRemoto != null) {
                VistaVideo(
                    track = videoRemoto,
                    espejo = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }


            AnimatedVisibility(visible = controles, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = 72.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Con video de fondo el texto necesita su propia base oscura o
                // se vuelve ilegible sobre una imagen clara.
                // Hay imagen detrás, y por tanto el texto necesita su base
                // oscura o se vuelve ilegible sobre una pantalla clara.
                //
                // Antes decía sólo `e.conVideo`, y con el modo cine eso dejó
                // de alcanzar: una llamada de AUDIO con alguien presentando
                // tiene imagen de fondo —una página web blanca, por ejemplo—
                // y el nombre y el cronómetro quedaban blancos sobre blanco.
                // Es el mismo defecto que el texto de las burbujas: el color
                // se decidía por el tipo de llamada en vez de por lo que de
                // verdad hay debajo.
                val conFondo = (e.conVideo && videosRemotos.isNotEmpty()) ||
                    e.presentando != null
                // Con video, el encabezado se va a la IZQUIERDA.
                //
                // Centrado se metia debajo de la ventanita del video propio,
                // que flota arriba a la derecha: se leia "@Equipo seguri" y el
                // resto quedaba tapado por la cara de uno. Recortarlo y
                // dejarlo centrado lo apretaba contra el boton de minimizar,
                // que esta arriba a la izquierda.
                //
                // A la izquierda no compite con nada: el boton de minimizar
                // deja su hueco al principio y la ventanita ocupa el final.
                // Es ademas donde lo pone cualquier pantalla de video.
                Column(
                    horizontalAlignment = if (conFondo) {
                        Alignment.Start
                    } else {
                        Alignment.CenterHorizontally
                    },
                    modifier = if (conFondo) {
                        Modifier
                            .align(Alignment.Start)
                            .padding(start = 32.dp, end = 124.dp)
                            // 0.82 y no 0.55. Sobre un video CLARO —una cara
                            // con la luz de frente, una pared blanca— un 55%
                            // de un color oscuro da un gris medio, y encima de
                            // ese gris el texto secundario desaparecia. Se vio
                            // en el emulador, cuya camara de mentira es casi
                            // blanca: el nombre se leia y el pie no.
                            .background(BgBase.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp)
                    },
                ) {
                    if (!conFondo) {
                        // El tercer sitio donde se dibujaba una cosa como
                        // otra: en una llamada de grupo `conQuien` es el
                        // nombre del GRUPO, y salian sus iniciales como si
                        // fuera una persona.
                        AvatarDeChat(
                            nombre = e.conQuien,
                            url = null,
                            clase = claseDeLlamada(e),
                            tamano = 112.dp,
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                    // Una linea y con puntos suspensivos si no entra.
                    //
                    // Sin esto, un nombre largo —el de un grupo, casi
                    // siempre— se metia DEBAJO de la ventanita del video
                    // propio, que flota en la misma banda: se leia
                    // "@Equipo seguri" y la palabra cortada quedaba tapada
                    // por la cara de uno.
                    Text(
                        "@${e.conQuien}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextoPrimario,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (conFondo) TextAlign.Start else TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextoDeFase(e)

                    // Quien presenta, dicho.
                    //
                    // Va DENTRO de la cabecera y no suelto arriba: puesto por
                    // su cuenta en el borde superior se montaba encima del
                    // nombre, porque los dos empiezan donde termina la barra
                    // de estado. Aquí no puede pisar nada por construcción.
                    //
                    // Y hace falta: una pantalla ajena que aparece de golpe sin
                    // decir de quién es se lee como un fallo de la app.
                    e.presentando?.let { quien ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (e.presentoYo) "Estás presentando tu pantalla"
                            else "@$quien está presentando",
                            color = TextoPrimario,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(Cian.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    // En una llamada de grupo, de donde sale.
                    //
                    // Quien recibe veia solo "@tatiana" y no tenia como saber
                    // que era una llamada de grupo ni de que grupo: contestar
                    // sin saber quien mas esta del otro lado es distinto de
                    // contestar una llamada de una persona.
                    // La tercera linea dice cosas DISTINTAS segun de que lado
                    // se mire, porque la pregunta es distinta:
                    //
                    //  - Quien llama ya sabe a que grupo: el titulo ES el
                    //    grupo. Lo que no ve es a quien eligio, que es lo unico
                    //    que cambia entre una llamada y otra. Repetir ahi el
                    //    nombre del grupo era decir dos veces lo mismo.
                    //  - Quien recibe ve "@tatiana" y no tiene como saber que
                    //    es una llamada de grupo ni de cual. Contestar sin
                    //    saber quien mas esta del otro lado no es lo mismo que
                    //    contestarle a una persona.
                    //
                    // No se condiciona al numero de participantes: quien recibe
                    // tiene esa lista vacia mientras suena, o sea justo cuando
                    // el dato hace falta.
                    // No se condiciona a `saliente`. Quien contesta tambien
                    // necesita ver quien mas esta —entrar a una llamada de
                    // grupo sin saber con quien es raro—, y desde que el
                    // servidor manda la foto al contestar, ese lado tiene el
                    // dato. Mientras suena sigue sin tenerlo, y ahi la frase
                    // del grupo es la util.
                    val pie = when {
                        e.estadoDe.isNotEmpty() -> pieDeParticipantes(e.estadoDe)
                        e.grupo.isNotBlank() -> "llamada de grupo · ${e.grupo}"
                        else -> ""
                    }
                    if (pie.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            pie,
                            // Secundario y no terciario: dice con quien se
                            // esta hablando, que no es un adorno.
                            fontSize = 12.sp,
                            color = if (conFondo) TextoSecundario else TextoTerciario,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (conFondo) TextAlign.Start else TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                when {
                    // Entrante y sin contestar: dos acciones, no una. Colgar y
                    // contestar tienen que estar lejos y de colores distintos,
                    // porque un toque equivocado aqui es irreversible.
                    e.fase == EstadoLlamada.Fase.SONANDO && !e.saliente ->
                        BotonesEntrante(
                            conVideo = e.conVideo,
                            onContestar = { ambito.launch { servicio.contestar() } },
                            onRechazar = { ambito.launch { servicio.rechazar() } },
                        )

                    e.fase == EstadoLlamada.Fase.TERMINADA -> Unit

                    else -> BotonesEnCurso(
                        e = e,
                        onSilenciar = servicio::silenciar,
                        onCamara = servicio::camara,
                        onAltavoz = servicio::altavoz,
                        onCine = {
                            if (e.presentoYo) {
                                ambito.launch { servicio.detenerCine() }
                            } else {
                                // Esto abre el diálogo del SISTEMA, que es
                                // donde se elige qué mostrar: una app sola o
                                // la pantalla entera. La app no ve esa
                                // elección ni puede influir en ella, y así
                                // tiene que ser — es el único punto en el que
                                // la persona autoriza que se lea su pantalla.
                                pedirPantalla.launch(
                                    proyector.createScreenCaptureIntent(),
                                )
                            }
                        },
                        onColgar = { ambito.launch { servicio.colgar() } },
                    )
                }
            }
            }

            // Minimizar solo aparece cuando hay algo que minimizar: una
            // llamada en curso. Mientras suena, el boton seria una trampa.
            //
            // Y se va con los demas controles mientras se mira algo: dejarlo
            // solo en una esquina seria el unico resto de interfaz sobre la
            // pelicula, que es peor que esconderlo todo o no esconder nada.
            if (controles && (e.fase == EstadoLlamada.Fase.EN_CURSO ||
                e.fase == EstadoLlamada.Fase.CONECTANDO)
            ) {
                IconButton(
                    onClick = { minimizada = true },
                    // `statusBarsPadding` no es cosmetico: la capa va de borde
                    // a borde, asi que sin esto el boton queda DEBAJO de la
                    // barra de estado, que se traga el toque. Se veia como un
                    // boton que esta ahi y no hace nada.
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp)
                        // Su propia base, por lo mismo que el encabezado:
                        // sobre un video claro un icono claro no se ve.
                        .then(
                            if (e.conVideo) {
                                Modifier.background(BgBase.copy(alpha = 0.6f), CircleShape)
                            } else Modifier
                        ),
                ) {
                    Icon(
                        Icons.Filled.CloseFullscreen,
                        "Minimizar la llamada",
                        tint = TextoPrimario,
                    )
                }
            }

            // El video propio, chico y arriba. Va en espejo porque es lo que
            // la persona espera de su propia camara: un espejo, no una foto.
            // La ventanita propia tambien se aparta: es chica pero esta
            // justo sobre una esquina de lo que se esta mirando.
            if (controles && e.conVideo && videoLocal != null && e.camaraActiva) {
                VistaVideo(
                    track = videoLocal,
                    espejo = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 16.dp)
                        .size(width = 108.dp, height = 168.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/** El texto que dice en que va la llamada. Un estado, una frase. */
@Composable
private fun TextoDeFase(e: EstadoLlamada) {
    val texto = when (e.fase) {
        EstadoLlamada.Fase.SONANDO ->
            if (e.saliente) "Llamando..." else if (e.conVideo) "Videollamada entrante" else "Llamada entrante"
        EstadoLlamada.Fase.CONECTANDO -> "Conectando..."
        EstadoLlamada.Fase.EN_CURSO -> Cronometro(e.conectadaEn)
        EstadoLlamada.Fase.TERMINADA -> when (e.motivoFin) {
            "rechazada" -> "Llamada rechazada"
            "sin_respuesta" -> "Sin respuesta"
            "ocupado" -> "Ocupado"
            "cancelada" -> "Llamada cancelada"
            "fallo_red" -> "Se corto la conexión"
            else -> "Llamada terminada"
        }
    }
    Text(
        texto,
        style = MaterialTheme.typography.bodyLarge,
        color = if (e.fase == EstadoLlamada.Fase.EN_CURSO) Cian else TextoSecundario,
    )
}

/**
 * El cronometro de la llamada.
 *
 * Cuenta desde `conectadaEn` y no desde que se monto la pantalla: si la
 * pantalla se recompone -girar el telefono, por ejemplo- el tiempo tiene que
 * seguir donde estaba, no volver a cero.
 */
@Composable
private fun Cronometro(desde: Long): String {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(desde) {
        while (true) {
            ahora = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (desde == 0L) return "00:00"
    val s = ((ahora - desde) / 1000).coerceAtLeast(0)
    return duracionHabla(s.toInt())
}

/** `m:ss` hasta la hora, `h:mm:ss` despues. */
fun duracionHabla(segundos: Int): String {
    val h = segundos / 3600
    val m = (segundos % 3600) / 60
    val s = segundos % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun BotonesEntrante(conVideo: Boolean, onContestar: () -> Unit, onRechazar: () -> Unit) {
    val ctx = LocalContext.current
    // Contestar necesita el microfono -y la camara si es video- ANTES de
    // contestar. Pedirlo despues dejaria una llamada conectada y muda, que es
    // peor que no haber contestado.
    val necesarios = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (conVideo) add(Manifest.permission.CAMERA)
    }
    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> if (res.values.all { it }) onContestar() else onRechazar() }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BotonGrande(Icons.Filled.CallEnd, "Rechazar", Coral, onRechazar)
        BotonGrande(Icons.Filled.Phone, "Contestar", Cian) {
            val faltan = necesarios.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltan) lanzador.launch(necesarios.toTypedArray()) else onContestar()
        }
    }
}

@Composable
private fun BotonesEnCurso(
    e: EstadoLlamada,
    onSilenciar: () -> Unit,
    onCamara: () -> Unit,
    onAltavoz: () -> Unit,
    onCine: () -> Unit,
    onColgar: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BotonChico(
                if (e.silenciado) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (e.silenciado) "Activar microfono" else "Silenciar microfono",
                activo = e.silenciado,
                onClick = onSilenciar,
            )
            BotonChico(
                Icons.AutoMirrored.Filled.VolumeUp,
                if (e.altavoz) "Quitar altavoz" else "Poner altavoz",
                activo = e.altavoz,
                onClick = onAltavoz,
            )
            if (e.conVideo) {
                BotonChico(
                    if (e.camaraActiva) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    if (e.camaraActiva) "Apagar cámara" else "Encender cámara",
                    activo = !e.camaraActiva,
                    onClick = onCamara,
                )
            }
            // Modo cine. Está también en una llamada de sólo audio: la pista
            // de vídeo existe desde el principio justamente para eso.
            //
            // No aparece cuando **otra persona** ya está presentando. Dos
            // pantallas a la vez en una llamada de cuatro es una rejilla de
            // recuadros ilegibles, y el caso real es que uno muestra y los
            // demás miran.
            if (e.presentando == null || e.presentoYo) {
                BotonChico(
                    if (e.presentoYo) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                    if (e.presentoYo) "Dejar de presentar" else "Modo cine",
                    activo = e.presentoYo,
                    onClick = onCine,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        BotonGrande(Icons.Filled.CallEnd, "Colgar", Coral, onColgar)
    }
}

@Composable
private fun BotonGrande(icono: ImageVector, desc: String, color: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = color, contentColor = TextoSobreAcento,
        ),
    ) { Icon(icono, desc, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun BotonChico(icono: ImageVector, desc: String, activo: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (activo) Cian else BgElev,
            contentColor = if (activo) TextoSobreAcento else TextoPrimario,
        ),
    ) { Icon(icono, desc, modifier = Modifier.size(24.dp)) }
}

/**
 * Que clase de conversacion es una llamada.
 *
 * Se deduce de que haya nombre de grupo, que es el unico dato que la llamada
 * lleva del otro lado: `EstadoLlamada` no guarda el `tipo` de la conversacion
 * porque no lo necesita para nada mas. Un canal no puede tener llamada —el
 * servidor lo rechaza con "No se puede llamar a un canal"— asi que las dos
 * clases posibles aqui son las dos que se contemplan.
 */
private fun claseDeLlamada(e: EstadoLlamada): ClaseDeChat =
    if (e.grupo.isNotBlank()) ClaseDeChat.GRUPO else ClaseDeChat.DIRECTA

/**
 * La linea que dice en que anda cada persona de una llamada de grupo.
 *
 * ## Por que no basta con "con joaquin, rocio"
 *
 * Esa linea decia a quien se habia llamado, no quien estaba. Desde el modulo
 * AF un rechazo ya no corta la llamada, asi que "rocio" podia seguir en la
 * lista habiendo dicho que no. La pantalla nombraba gente que no estaba.
 *
 * ## Tres grupos, y el orden no es alfabetico
 *
 * Primero quien esta —es lo que se quiere saber—, despues quien todavia suena
 * —lo que puede cambiar—, despues quien se cayo y puede volver, y al final
 * quien no va a entrar. Dentro de cada
 * grupo, alfabetico: sin eso, la linea se reordena sola cada vez que llega un
 * aviso y la pantalla parpadea sin que haya pasado nada.
 */
internal fun pieDeParticipantes(estadoDe: Map<String, String>): String {
    fun losDe(estado: String) = estadoDe.filterValues { it == estado }.keys.sorted()
    val dentro = losDe(ESTADO_DENTRO)
    val sonando = losDe(ESTADO_SONANDO)
    // `cayo` no es lo mismo que `fuera`: quien se cayo sigue en la llamada
    // para el servidor —no colgo— y puede volver cuando su red vuelva. Decir
    // que "no entro" seria mentir, y no decir nada era peor: la pantalla
    // afirmaba "con joaquin" con su telefono muerto medio minuto.
    val caidos = losDe(ESTADO_CAIDO)
    // Rechazar y colgar se juntan: los dos significan "esta persona ya no
    // esta", y separarlos daria tres frases donde la diferencia no cambia
    // nada de lo que se puede hacer ahora.
    val fuera = (losDe(ESTADO_RECHAZO) + losDe("fuera")).sorted()

    val partes = buildList {
        if (dentro.isNotEmpty()) add("con " + dentro.joinToString(", "))
        if (sonando.isNotEmpty()) add("llamando a " + sonando.joinToString(", "))
        if (caidos.isNotEmpty()) {
            add(
                caidos.joinToString(", ") +
                    if (caidos.size == 1) " se desconectó" else " se desconectaron",
            )
        }
        if (fuera.isNotEmpty()) {
            add(fuera.joinToString(", ") + if (fuera.size == 1) " no entró" else " no entraron")
        }
    }
    return partes.joinToString(" · ")
}

/**
 * Los videos de una llamada de grupo, en rejilla.
 *
 * ## Por que dos columnas y no "las que quepan"
 *
 * Porque el techo son cuatro. La malla actual abre N-1 conexiones por aparato
 * —esta declarado en el modulo K— y con cinco personas cada telefono subiria
 * su video cuatro veces. Con un maximo de cuatro recuadros, dos columnas dan
 * 1x1, 2x1, 2x2 y nada mas: no hace falta un calculo para tres casos.
 *
 * Cada recuadro lleva **el nombre encima**. Cuatro videos sin rotular no
 * dicen quien es quien, y en una llamada de trabajo eso es justo lo que hace
 * falta saber.
 */
@Composable
private fun RejillaVideos(
    videos: Map<String, VideoTrack>,
    nombres: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val filas = videos.entries.toList().chunked(2)
    Column(modifier) {
        filas.forEach { fila ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                fila.forEach { (dispositivo, pista) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(1.dp)
                            .background(BgElev),
                    ) {
                        VistaVideo(
                            track = pista,
                            espejo = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // El nombre sobre su propia base: encima de un video
                        // claro, el texto solo desaparece.
                        Text(
                            nombres[dispositivo] ?: "",
                            color = TextoPrimario,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(BgBase.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                // Con un numero impar, el ultimo recuadro NO se estira a todo
                // el ancho: quedaria uno el doble de grande que los demas sin
                // que eso signifique nada.
                if (fila.size == 1 && videos.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Un `SurfaceViewRenderer` de WebRTC dentro de Compose.
 *
 * Los dos detalles que importan:
 *
 *  - El sink se **quita** del track anterior antes de poner el nuevo. Sin eso,
 *    cambiar de track deja dos fuentes pintando en la misma superficie.
 *  - `release()` va en el `onRelease` de la vista, no en un `DisposableEffect`
 *    del composable: liberar un renderer que la vista todavia tiene montada
 *    deja la superficie negra hasta que se recrea.
 */
@Composable
private fun VistaVideo(
    track: VideoTrack?,
    espejo: Boolean,
    modifier: Modifier = Modifier,
    /**
     * `true` para una pantalla compartida: se ve ENTERA, con bandas si hace
     * falta.
     *
     * Una cara aguanta el recorte —lo que se pierde son los bordes y la cara
     * esta en el medio—, y por eso el modo normal es llenar. Una pantalla no:
     * lo que se recorta son justamente los bordes, que es donde estan los
     * controles, la barra de progreso y los subtitulos. Recortar una pantalla
     * compartida es quitarle lo que se vino a mirar.
     */
    completo: Boolean = false,
) {
    val egl = FabricaWebRtc.egl ?: return
    var vista by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(
                    if (completo) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
                setEnableHardwareScaler(true)
                setMirror(espejo)
                vista = this
            }
        },
        onRelease = { v ->
            runCatching { v.release() }
            vista = null
        },
    )

    // El modo de escalado cambia EN VIVO: la misma pista pasa de cara a
    // pantalla cuando alguien enciende el modo cine, y la vista no se recrea.
    // Sin esto, la primera pantalla compartida de la llamada se veria
    // recortada hasta que algo mas forzara a rehacer la vista.
    LaunchedEffect(vista, completo) {
        vista?.setScalingType(
            if (completo) RendererCommon.ScalingType.SCALE_ASPECT_FIT
            else RendererCommon.ScalingType.SCALE_ASPECT_FILL
        )
    }

    DisposableEffect(track, vista) {
        val v = vista
        if (v != null && track != null) runCatching { track.addSink(v) }
        onDispose { if (v != null && track != null) runCatching { track.removeSink(v) } }
    }
}

/**
 * La llamada en una ventanita movible, encima de la app.
 *
 * ## Por que existe
 *
 * Una videollamada a pantalla completa secuestra la app: mientras hablas no
 * podes mirar un mensaje, buscar un dato ni copiar una direccion. Es lo que
 * hace WhatsApp con su ventanita, y por eso mismo.
 *
 * ## Por que NO es un `Dialog` ni una ventana del sistema
 *
 * Un `Dialog` de Compose vive en su propia ventana y se traga los toques de
 * todo lo que tiene debajo: con el abierto, la app deja de responder, que es
 * exactamente lo contrario de lo que se busca. Una ventana flotante del
 * sistema -`TYPE_APPLICATION_OVERLAY`- funcionaria incluso fuera de la app,
 * pero exige el permiso `SYSTEM_ALERT_WINDOW`, que es de los que asustan y de
 * los que Google revisa. Asi que es una capa dentro de la propia jerarquia:
 * un `Box` que ocupa toda la pantalla pero **no dibuja nada ni escucha nada**
 * fuera de la ventanita. Compose solo entrega los toques a quien los pide, asi
 * que todo lo de abajo sigue funcionando.
 *
 * Que la llamada no sobreviva a salir de la app es una limitacion conocida y
 * declarada -no hay servicio en primer plano-, y no la arregla esta ventana.
 */
@Composable
private fun VentanaFlotante(
    e: EstadoLlamada,
    videoRemoto: VideoTrack?,
    onExpandir: () -> Unit,
    onColgar: () -> Unit,
) {
    val d = LocalDensity.current
    val conVideo = e.conVideo && videoRemoto != null
    val ancho = if (conVideo) 128.dp else 190.dp
    val alto = if (conVideo) 190.dp else 64.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margen = with(d) { 12.dp.toPx() }
        // Arriba hay barra de estado y abajo botonera del sistema: la ventana
        // arranca lejos de las dos.
        val techo = with(d) { 72.dp.toPx() }
        val piso = with(d) { 96.dp.toPx() }
        val w = with(d) { ancho.toPx() }
        val h = with(d) { alto.toPx() }
        val maxX = (constraints.maxWidth - w - margen).coerceAtLeast(margen)
        val maxY = (constraints.maxHeight - h - piso).coerceAtLeast(techo)

        // Arranca arriba a la derecha, que es donde no tapa el contenido de
        // una lista de chats ni el campo de escribir.
        var pos by remember { mutableStateOf<Offset?>(null) }
        val actual = pos ?: Offset(maxX, techo)

        Box(
            Modifier
                .offset { IntOffset(actual.x.roundToInt(), actual.y.roundToInt()) }
                .size(width = ancho, height = alto)
                .clip(RoundedCornerShape(14.dp))
                .background(BgElev)
                .border(1.dp, Cian.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                // El arrastre se registra ANTES del clic: si fuera al revés,
                // un arrastre que empieza sobre la ventana se leería como un
                // toque y la llamada se abriría a pantalla completa cada vez
                // que se la intenta mover.
                .pointerInput(maxX, maxY) {
                    detectDragGestures { _, delta ->
                        val base = pos ?: Offset(maxX, techo)
                        pos = Offset(
                            (base.x + delta.x).coerceIn(margen, maxX),
                            (base.y + delta.y).coerceIn(techo, maxY),
                        )
                    }
                }
                .clickable(onClick = onExpandir),
        ) {
            if (conVideo) {
                VistaVideo(track = videoRemoto, espejo = false, modifier = Modifier.fillMaxSize())
                // El nombre y el reloj sobre una base oscura: encima de un
                // video cualquiera, un texto suelto no se lee.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(BgBase.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.conQuien,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextoPrimario,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        TextoCorto(e),
                        style = MaterialTheme.typography.labelSmall,
                        color = Cian,
                    )
                }
                IconButton(
                    onClick = onColgar,
                    modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        "Colgar",
                        tint = Coral,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // Sin video la ventanita es una barra: avatar, nombre, reloj y
                // colgar. Una miniatura cuadrada con un avatar gigante seria
                // ocupar la mitad de la pantalla para no decir nada mas.
                Row(
                    Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarDeChat(
                        nombre = e.conQuien,
                        url = null,
                        clase = claseDeLlamada(e),
                        tamano = 34.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.conQuien,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextoPrimario,
                            maxLines = 1,
                        )
                        Text(
                            TextoCorto(e),
                            style = MaterialTheme.typography.labelSmall,
                            color = Cian,
                        )
                    }
                    IconButton(onClick = onColgar, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.CallEnd,
                            "Colgar",
                            tint = Coral,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Lo mismo que [TextoDeFase] pero para una ventana de 128 dp: el reloj. */
@Composable
private fun TextoCorto(e: EstadoLlamada): String = when (e.fase) {
    EstadoLlamada.Fase.EN_CURSO -> Cronometro(e.conectadaEn)
    EstadoLlamada.Fase.CONECTANDO -> "conectando"
    EstadoLlamada.Fase.SONANDO -> "llamando"
    EstadoLlamada.Fase.TERMINADA -> "fin"
}

/**
 * Empieza una llamada pidiendo antes los permisos.
 *
 * Devuelve la funcion que la interfaz llama al tocar el boton. Existe como
 * helper porque el mismo baile -mirar el permiso, pedirlo si falta, y solo
 * entonces llamar- hace falta desde el chat, desde el historial y desde el
 * perfil, y repetirlo tres veces es garantia de que en alguno se olvide.
 */
@Composable
fun recordarInicioLlamada(onError: (String) -> Unit): IniciadorLlamada {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WtfuckApp
    val ambito = rememberCoroutineScope()
    var pendiente by remember { mutableStateOf<PeticionLlamada?>(null) }

    fun arrancar(p: PeticionLlamada) {
        ambito.launch {
            app.repo.llamadas.llamar(p.convId, p.conQuien, p.conVideo, p.invitados)
                .onFailure { onError(it.message ?: "No se pudo llamar.") }
        }
    }

    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val p = pendiente
        pendiente = null
        when {
            p == null -> Unit
            res.values.all { it } -> arrancar(p)
            // Sin micro no hay llamada que valga la pena: es mas honesto
            // decirlo que abrir una llamada donde el otro no te oye.
            else -> onError("Sin permiso de microfono no se puede llamar.")
        }
    }

    return object : IniciadorLlamada {
        override fun invoke(
            convId: String,
            conQuien: String,
            conVideo: Boolean,
            invitados: List<String>,
        ) {
            val peticion = PeticionLlamada(convId, conQuien, conVideo, invitados)
            val necesarios = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (conVideo) add(Manifest.permission.CAMERA)
            }
            val faltan = necesarios.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltan) {
                pendiente = peticion
                lanzador.launch(necesarios.toTypedArray())
            } else {
                arrancar(peticion)
            }
        }
    }
}

/** Lo que hace falta para empezar una llamada, junto. */
data class PeticionLlamada(
    val convId: String,
    val conQuien: String,
    val conVideo: Boolean,
    val invitados: List<String> = emptyList(),
)

/**
 * El disparador de llamadas que devuelve [recordarInicioLlamada].
 *
 * Es una interfaz y no un `(String, String, Boolean, List<String>) -> Unit`
 * para poder darle un **valor por defecto a `invitados`**: los tres sitios que
 * ya llamaban —chat directo, historial, perfil— siguen escribiendo tres
 * argumentos, y solo el grupo, que es el unico que elige, pasa el cuarto.
 *
 * Y una `interface` normal y no una `fun interface`, porque el metodo de una
 * `fun interface` no admite valores por defecto —que es justo lo unico que se
 * queria de ella—.
 */
interface IniciadorLlamada {
    operator fun invoke(
        convId: String,
        conQuien: String,
        conVideo: Boolean,
        invitados: List<String> = emptyList(),
    )
}
