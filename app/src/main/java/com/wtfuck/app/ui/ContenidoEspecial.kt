package com.wtfuck.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.datos.MensajeEnt
import com.wtfuck.app.datos.VotoEnt
import com.wtfuck.app.datos.jsonApp
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wtfuck.app.ui.theme.*
import com.wtfuck.app.contenido.*
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.TopesConsulta
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modulo M · Las burbujas del contenido con estructura.
 *
 * Una ubicacion, un contacto, una encuesta y un evento tienen algo en comun que
 * los separa de un mensaje de texto: el receptor no solo los LEE, los USA. Se
 * abre el mapa, se abre el chat, se vota. Por eso cada uno tiene su burbuja y
 * ninguno se dibuja como un parrafo.
 *
 * ## Donde se cuentan los votos
 *
 * Aqui. No hay otra opcion: el servidor no puede leer un voto, asi que no puede
 * sumarlos. Cada aparato recibe los votos de todos y cuenta. La consecuencia
 * esta declarada donde se define la carga: **una encuesta cifrada de extremo a
 * extremo no puede ser anonima**, porque cada voto llega firmado por la sesion
 * de quien lo mando. Esta app no ofrece la casilla "anonima" para no mentir en
 * una etiqueta.
 */

/** Lee la carga guardada en la fila. Si viene rota, devuelve null y no se cae. */
fun cargaEspecial(m: MensajeEnt): Carga? =
    if (m.especialJson.isEmpty()) null
    else runCatching { jsonApp.decodeFromString(Carga.serializer(), m.especialJson) }.getOrNull()

// ============================================================
//  Recuento
// ============================================================

/**
 * Cuantos votos tiene cada opcion.
 *
 * Los indices fuera de rango se descartan en silencio: un cliente de otra
 * version pudo mandar un voto a una opcion que aqui no existe, y eso no debe
 * hacer reventar la burbuja de nadie.
 */
fun contarVotos(votos: List<VotoEnt>, cuantasOpciones: Int): List<Int> {
    val cuenta = IntArray(cuantasOpciones)
    for (v in votos) {
        for (i in indicesDe(v.opciones)) {
            if (i in 0 until cuantasOpciones) cuenta[i]++
        }
    }
    return cuenta.toList()
}

/** Cuanta gente voto. No es la suma de las opciones: se puede elegir varias. */
fun cuantosVotaron(votos: List<VotoEnt>): Int = votos.count { it.opciones.isNotBlank() }

fun indicesDe(csv: String): List<Int> =
    csv.split(",").mapNotNull { it.trim().toIntOrNull() }

private fun miVotoEn(votos: List<VotoEnt>, yo: String): List<Int> =
    votos.firstOrNull { it.votante == yo }?.let { indicesDe(it.opciones) } ?: emptyList()

// ============================================================
//  Ubicacion
// ============================================================

/**
 * Recibe [UbicacionSegura] y no `Carga.Ubicacion` a proposito.
 *
 * El sobre lo escribio otra persona y el servidor no pudo mirarlo, asi que
 * todo campo que llega de afuera pasa por `ContenidoSeguro.kt` antes de
 * dibujarse. Tomando el tipo ya recortado, dibujar uno crudo deja de ser un
 * descuido posible: no esta aqui para dibujarlo.
 */
@Composable
fun BurbujaUbicacion(v: UbicacionSegura) {
    val ctx = LocalContext.current
    Column(Modifier.widthIn(max = 260.dp)) {
        if (v.etiqueta.isNotBlank()) {
            Text(v.etiqueta, color = TextoPrimario, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Cian.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Place, null, tint = Cian, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    v.coordenadas ?: "Posición no válida",
                    color = if (v.coordenadas != null) TextoPrimario else Coral,
                    fontSize = 13.sp,
                    style = estiloHuella,
                )
                Text(
                    // El margen se dice siempre que se sepa: "aqui, con 2 km de
                    // error" y "aqui, con 5 m" son dos mensajes distintos.
                    v.margen ?: "Margen desconocido",
                    color = TextoTerciario,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Sin coordenadas validas no hay boton: abrir el mapa con un
        // `geo:NaN,NaN` es pasarle basura a otra app del telefono, y ofrecer
        // el boton da a entender que hay un sitio al que ir.
        val geo = v.geoUri ?: return@Column
        TextButton(
            onClick = {
                // Se delega en el telefono con un `geo:` y NO se pide un mapa a
                // ningun servidor. Un mapa incrustado obligaria a pedirle las
                // baldosas a un tercero, es decir, a contarle a ese tercero
                // donde esta la persona justo cuando comparte donde esta.
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo))) }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Filled.Map, null, tint = Cian, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Abrir en el mapa", color = Cian, fontSize = 13.sp)
        }
    }
}

/**
 * Una ubicación que se sigue moviendo.
 *
 * ## Lo que la distingue de [BurbujaUbicacion], y no es el mapa
 *
 * Es **el tiempo**. Una ubicación normal dice dónde estuviste y no cambia
 * nunca; ésta afirma dónde estás *ahora*, y esa afirmación caduca. Así que la
 * burbuja tiene que decir tres cosas que la otra no necesita:
 *
 *  - que está en vivo, y cuánto le queda;
 *  - **cuándo se actualizó por última vez** —quien está quieto no genera
 *    posiciones nuevas, así que una posición de hace veinte minutos puede ser
 *    perfectamente correcta, y quien mira tiene derecho a saberlo—;
 *  - y, si es propia, cómo cortarla.
 *
 * ## Por qué se repinta sola
 *
 * El texto depende del reloj, no sólo del dato. Sin un latido, una burbuja
 * abierta se quedaría diciendo "quedan 15 min" media hora después. Late cada
 * treinta segundos: los minutos no cambian más rápido que eso.
 */
@Composable
fun BurbujaUbicacionViva(
    carga: Carga.UbicacionEnVivo,
    esMia: Boolean,
    ultimaActualizacion: Long,
    /** De quién es la posición: su cara es el marcador. */
    autor: String,
    /** Su foto, o `null` para caer a las iniciales de color. */
    fotoAutor: String?,
    onCortar: () -> Unit,
) {
    val ctx = LocalContext.current

    // El latido. `ahora` es estado, así que cambiarlo recompone la burbuja.
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(carga.hasta) {
        while (carga.hasta > System.currentTimeMillis()) {
            kotlinx.coroutines.delay(30_000)
            ahora = System.currentTimeMillis()
        }
        // Un último repintado al vencer: si no, la burbuja se queda con el
        // "quedan 1 min" del último latido y nunca dice que terminó.
        ahora = System.currentTimeMillis()
    }

    val v = remember(carga, ahora) { seguraViva(carga, ahora) }

    Column(Modifier.widthIn(max = 260.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // El marcador es LA PERSONA, no un icono genérico.
            //
            // Un punto igual para todos obliga a leer el nombre para saber de
            // quién es la posición; una cara se reconoce sin leer. Y en un
            // grupo con dos compartidos a la vez, el icono genérico los hacía
            // indistinguibles de un vistazo.
            //
            // Sin foto queda el [Avatar] con las iniciales y su color derivado
            // del nombre, que **también** identifica a la persona: el color es
            // siempre el mismo para el mismo nombre.
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(nombre = autor, url = fotoAutor, tamano = 40.dp)
                // La antena encima, pequeña. Es lo que distingue esta burbuja
                // de una foto de perfil cualquiera, y va sobre su propia base
                // porque encima de una foto clara un icono claro desaparece.
                Box(
                    Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(if (v.enVivo) Cian else Slate),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Sensors,
                        null,
                        tint = if (v.enVivo) TextoSobreAcento else TextoTerciario,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (v.enVivo) "En tiempo real" else "Ubicación en tiempo real",
                    color = TextoPrimario,
                    fontSize = 14.sp,
                )
                Text(
                    // Terminó y sigue siendo el mismo dato: no se borra la
                    // posición, se deja de afirmar que es la de ahora.
                    if (v.enVivo) v.queda else "Terminó",
                    color = if (v.enVivo) Cian else TextoTerciario,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            v.coordenadas ?: "Posición no válida",
            color = if (v.coordenadas != null) TextoPrimario else Coral,
            fontSize = 13.sp,
            style = estiloHuella,
        )
        Text(v.margen ?: "Margen desconocido", color = TextoTerciario, fontSize = 11.sp)

        // Cuándo se supo esto. Es el dato que impide confundir "está quieto"
        // con "dejó de funcionar", y no lo puede dar el reloj de la burbuja:
        // sale de cuándo llegó la última posición.
        if (v.enVivo && ultimaActualizacion > 0) {
            Text(
                "actualizado " + haceCuanto(ahora - ultimaActualizacion),
                color = TextoTerciario,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val geo = v.geoUri
            if (geo != null) {
                TextButton(
                    onClick = {
                        // Igual que la ubicación normal: se delega en el
                        // teléfono con un `geo:` y no se le pide el mapa a
                        // ningún servidor. Un mapa incrustado obligaría a
                        // contarle a un tercero dónde está la persona justo
                        // mientras comparte dónde está — y aquí durante horas.
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Filled.Map, null, tint = Cian, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Abrir en el mapa", color = Cian, fontSize = 13.sp)
                }
            }
            // Cortar sólo lo puede hacer quien comparte, y sólo mientras esté
            // en vivo: un botón que no hace nada es peor que no tenerlo.
            if (esMia && v.enVivo) {
                TextButton(
                    onClick = onCortar,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Dejar de compartir", color = Coral, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Hace cuánto, en palabras cortas.
 *
 * "hace un momento" por debajo del minuto: con los segundos a la vista, una
 * burbuja que late cada treinta segundos se vería saltar de 12 a 42 y parece
 * rota.
 */
internal fun haceCuanto(ms: Long): String {
    val minutos = (ms / 60_000).toInt()
    return when {
        minutos < 1 -> "hace un momento"
        minutos == 1 -> "hace 1 min"
        minutos < 60 -> "hace $minutos min"
        minutos < 120 -> "hace 1 h"
        else -> "hace ${minutos / 60} h"
    }
}

// ============================================================
//  Contacto
// ============================================================

@Composable
fun BurbujaContacto(v: ContactoSeguro, onAbrir: (String) -> Unit) {
    // El `@` de abajo es una AFIRMACION sobre identidad, y lo que va detras lo
    // eligio quien mando el sobre. Ver `ContenidoSeguro.kt`.
    Column(Modifier.widthIn(max = 250.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(nombre = v.nombre, url = null, tamano = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    v.nombre,
                    color = TextoPrimario,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (v.username != null) {
                    Text("@${v.username}", color = TextoSecundario, fontSize = 12.sp)
                } else {
                    // Se dice lo que pasa en vez de callarlo: quien recibe una
                    // tarjeta rara tiene que poder saber que es rara.
                    Text(
                        "Sin una cuenta valida",
                        color = Ambar,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        // Sin cuenta no hay boton: mandaria a un 404 y, de paso, daria a
        // entender que esa cuenta existe.
        if (v.username == null) return@Column
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { onAbrir(v.username) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Cian, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Abrir conversación", color = Cian, fontSize = 13.sp)
        }
    }
}

// ============================================================
//  Encuesta
// ============================================================

@Composable
fun BurbujaEncuesta(
    v: EncuestaSegura,
    votos: List<VotoEnt>,
    yo: String,
    onVotar: (List<Int>) -> Unit,
) {
    val cerrada = v.cierraEn in 1..System.currentTimeMillis()
    val opciones = v.opciones
    val recortada = v.opcionesRecortadas

    val cuenta = contarVotos(votos, opciones.size)
    val votantes = cuantosVotaron(votos)
    val mio = miVotoEn(votos, yo)

    Column(Modifier.widthIn(min = 230.dp, max = 290.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Poll, null, tint = Cian, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    cerrada -> "Encuesta cerrada"
                    v.multiple -> "Varias respuestas"
                    else -> "Una respuesta"
                },
                color = TextoTerciario,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(v.pregunta, color = TextoPrimario, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (v.preguntaRecortada) {
            Text(
                "La pregunta es más larga de lo que se puede mostrar.",
                style = MaterialTheme.typography.labelSmall,
                color = Ambar,
            )
        }
        Spacer(Modifier.height(10.dp))

        opciones.forEachIndexed { i, opcion ->
            OpcionVotable(
                // Ya viene recortada de `segura(e)`: una "opcion" de cuatro mil
                // caracteres es un mensaje disfrazado de boton.
                etiqueta = opcion,
                votos = cuenta.getOrElse(i) { 0 },
                total = votantes,
                elegida = i in mio,
                habilitada = !cerrada,
                multiple = v.multiple,
                onClick = {
                    // Una sola respuesta: elegir reemplaza. Varias: alterna.
                    // Volver a tocar lo que ya estaba elegido lo desmarca, y
                    // quedarse sin ninguna es retirar el voto.
                    val nuevo = when {
                        !v.multiple -> if (i in mio) emptyList() else listOf(i)
                        i in mio -> mio - i
                        else -> (mio + i).sorted()
                    }
                    onVotar(nuevo)
                },
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(2.dp))
        if (recortada) {
            // Se dice en vez de esconderlo: si alguien ve menos opciones de
            // las que le mandaron, tiene que saber que falta algo y no creer
            // que la encuesta era asi.
            Text(
                "Esta encuesta trae más opciones de las que se pueden mostrar.",
                style = MaterialTheme.typography.labelSmall,
                color = Ambar,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            when (votantes) {
                0 -> "Todavía no voto nadie"
                1 -> "1 voto"
                else -> "$votantes votos"
            },
            color = TextoTerciario,
            fontSize = 11.sp,
        )
        if (!cerrada && v.cierraEn > 0) {
            Text("Cierra el ${fechaLarga(v.cierraEn)}", color = TextoTerciario, fontSize = 11.sp)
        }
        // Se dice en la burbuja y no solo en la documentacion: quien vota tiene
        // que saber que su voto no es secreto ANTES de votar, no despues.
        Text("Los demas pueden ver tu voto", color = TextoTerciario, fontSize = 10.sp)
    }
}

// ============================================================
//  Evento
// ============================================================

/** Las tres respuestas de un evento. El orden es el indice del voto. */
val RESPUESTAS_EVENTO = listOf("Voy" to Cian, "Quizas" to Ambar, "No voy" to Coral)

@Composable
fun BurbujaEvento(
    v: EventoSeguro,
    votos: List<VotoEnt>,
    yo: String,
    onVotar: (List<Int>) -> Unit,
) {
    val cuenta = contarVotos(votos, RESPUESTAS_EVENTO.size)
    val votantes = cuantosVotaron(votos)
    val mio = miVotoEn(votos, yo).firstOrNull()
    val pasado = v.cuandoMs != null && v.cuandoMs <= System.currentTimeMillis()

    Column(Modifier.widthIn(min = 230.dp, max = 290.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Event, null, tint = if (pasado) TextoTerciario else Cian,
                modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (pasado) "Evento pasado" else "Evento", color = TextoTerciario, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(v.titulo, color = TextoPrimario, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = TextoTerciario, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                v.cuandoMs?.let { fechaLarga(it) } ?: "Fecha no valida",
                color = if (v.cuandoMs != null) TextoSecundario else Coral,
                fontSize = 12.sp,
            )
        }
        if (v.lugar.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Filled.Place, null, tint = TextoTerciario, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(v.lugar, color = TextoSecundario, fontSize = 12.sp)
            }
        }
        if (v.nota.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(v.nota, color = TextoSecundario, fontSize = 13.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RESPUESTAS_EVENTO.forEachIndexed { i, (etiqueta, color) ->
                val elegida = mio == i
                val n = cuenta.getOrElse(i) { 0 }
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (elegida) color.copy(alpha = 0.22f) else BgBase)
                        // §15 · Las tres respuestas son excluyentes, asi que
                        // son radios y no botones. Volver a tocar la propia la
                        // retira, y eso se anuncia por el estado.
                        .selectable(
                            selected = elegida,
                            role = Role.RadioButton,
                            onClick = { onVotar(if (elegida) emptyList() else listOf(i)) },
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = descripcionDeOpcion(etiqueta, n, votantes)
                        }
                        // Tres controles en una fila de 290 dp ya son
                        // angostos; el area tactil llega a 48 aunque se dibujen
                        // mas bajos.
                        .minimumInteractiveComponentSize()
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        etiqueta,
                        color = if (elegida) color else TextoSecundario,
                        fontSize = 12.sp,
                        fontWeight = if (elegida) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (n > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("$n", color = if (elegida) color else TextoTerciario, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ============================================================
//  Pieza compartida: una opcion con su barra
// ============================================================

@Composable
private fun OpcionVotable(
    etiqueta: String,
    votos: Int,
    total: Int,
    elegida: Boolean,
    habilitada: Boolean,
    /** Para el lector: una encuesta de una respuesta es radio, la de varias casilla. */
    multiple: Boolean,
    onClick: () -> Unit,
) {
    val fraccion = if (total > 0) votos.toFloat() / total else 0f
    val ancho by animateFloatAsState(fraccion, label = "barra")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(BgBase)
            // §15 · Es un control de eleccion y hay que decir cual. Sin rol,
            // TalkBack lee el texto y nada mas: ni que se puede elegir, ni si
            // esta elegida, ni que elegir una desmarca las otras.
            //
            // Y la descripcion lleva el PORCENTAJE porque a la vista la barra
            // de fondo es exactamente esa proporcion: con los votos crudos se
            // tienen los numeros pero no la comparacion, que es lo que la
            // barra comunica de un vistazo.
            .then(
                if (habilitada) {
                    Modifier.selectable(
                        selected = elegida,
                        role = if (multiple) Role.Checkbox else Role.RadioButton,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = descripcionDeOpcion(etiqueta, votos, total)
            }
            // La opcion se DIBUJA de 34 dp porque una encuesta de doce
            // opciones con 48 cada una no entra en la pantalla. Lo que sube a
            // 48 es el area que responde al dedo, no el dibujo: para eso
            // existe `minimumInteractiveComponentSize`, y medirlo en el
            // volcado fue la unica forma de ver que estaba en 34.
            .minimumInteractiveComponentSize(),
    ) {
        // La barra va DETRAS del texto y no al lado: al lado obligaria a
        // reservarle un ancho fijo, y con 30 caracteres de opcion no queda.
        Box(
            Modifier
                .fillMaxWidth(ancho)
                .height(34.dp)
                .background(if (elegida) Cian.copy(alpha = 0.28f) else Slate.copy(alpha = 0.22f)),
        )
        Row(
            Modifier.height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (elegida) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                null,
                tint = if (elegida) Cian else TextoTerciario,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                etiqueta,
                color = TextoPrimario,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            if (votos > 0) {
                Spacer(Modifier.width(6.dp))
                Text("$votos", color = if (elegida) Cian else TextoSecundario, fontSize = 12.sp)
            }
        }
    }
}

// ============================================================
//  Hoja: crear una encuesta
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaEncuesta(
    onCrear: (String, List<String>, Boolean) -> Unit,
    onCerrar: () -> Unit,
) {
    var pregunta by remember { mutableStateOf("") }
    // Se arranca con dos casillas porque una encuesta de una opcion no es una
    // encuesta, y con tres ya hay una vacia que parece un error.
    var opciones by remember { mutableStateOf(listOf("", "")) }
    var multiple by remember { mutableStateOf(false) }

    val validas = opciones.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val puede = pregunta.isNotBlank() && validas.size >= 2

    ModalBottomSheet(
        // **Se abre ENTERA, no a media altura.**
        //
        // `ModalBottomSheet` arranca "parcialmente expandido" por defecto, o
        // sea ocupando la mitad de la pantalla, y **no desplaza su contenido**:
        // lo que no entra simplemente no esta. En una hoja que es un
        // formulario, lo que no entra es el boton del final, asi que la
        // funcion entera queda inalcanzable sin que nada lo indique. Fue
        // exactamente lo que paso al publicar una historia.
        //
        // Ninguna hoja de la app lo declaraba: el defecto estaba en las seis
        // que son formularios, y solo se notaba en las mas altas.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),

        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Nueva encuesta", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = pregunta,
                onValueChange = { if (it.length <= 200) pregunta = it },
                label = { Text("Pregunta", color = TextoTerciario) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            opciones.forEachIndexed { i, valor ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = valor,
                        onValueChange = { nuevo ->
                            if (nuevo.length <= 100) {
                                opciones = opciones.toMutableList().also { it[i] = nuevo }
                            }
                        },
                        label = { Text("Opcion ${i + 1}", color = TextoTerciario) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                            focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    // Quitar solo aparece con mas de dos: sin eso se puede
                    // dejar la encuesta en una sola opcion y el boton de crear
                    // se apaga sin explicar por que.
                    if (opciones.size > 2) {
                        IconButton(onClick = {
                            opciones = opciones.toMutableList().also { it.removeAt(i) }
                        }) {
                            Icon(Icons.Filled.Close, "Quitar opcion", tint = TextoTerciario)
                        }
                    }
                }
            }

            if (opciones.size < 12) {
                TextButton(onClick = { opciones = opciones + "" }) {
                    Icon(Icons.Filled.Add, null, tint = Cian, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar opcion", color = Cian)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Varias respuestas", color = TextoPrimario, fontSize = 14.sp)
                    Text(
                        "Cada persona puede marcar más de una",
                        color = TextoTerciario,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = multiple,
                    onCheckedChange = { multiple = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextoSobreAcento,
                        checkedTrackColor = Cian,
                    ),
                )
            }

            Spacer(Modifier.height(6.dp))
            // No hay casilla de "encuesta anonima", y se explica en vez de
            // dejar el hueco: con cifrado de extremo a extremo el voto llega
            // firmado por la sesion de quien vota, asi que los clientes -que
            // son los que cuentan- ven quien voto que. Ofrecer "anonima"
            // seria una etiqueta falsa.
            Text(
                "Los votos no son anonimos: los cuentan los teléfonos, no el servidor, " +
                    "así que cada participante ve quien voto que.",
                color = TextoTerciario,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onCrear(pregunta.trim(), validas, multiple) },
                enabled = puede,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cian, contentColor = TextoSobreAcento,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crear encuesta") }
            Spacer(Modifier.height(22.dp))
        }
    }
}

// ============================================================
//  Hoja: crear un evento
// ============================================================

private val fmtDia = SimpleDateFormat("dd/MM/yyyy", Locale("es", "PE"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaEvento(
    onCrear: (String, Long, String, String) -> Unit,
    onCerrar: () -> Unit,
) {
    var titulo by remember { mutableStateOf("") }
    var lugar by remember { mutableStateOf("") }
    var nota by remember { mutableStateOf("") }
    // Por defecto, manana a la misma hora: un evento en el pasado no sirve y
    // "ahora mismo" tampoco es un evento.
    var cuando by remember { mutableStateOf(System.currentTimeMillis() + 86_400_000L) }
    var eligiendoDia by remember { mutableStateOf(false) }
    var eligiendoHora by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),

        onDismissRequest = onCerrar,
        containerColor = BgElev,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Slate) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Nuevo evento", style = MaterialTheme.typography.titleMedium, color = TextoPrimario)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = titulo,
                onValueChange = { if (it.length <= 120) titulo = it },
                label = { Text("Titulo", color = TextoTerciario) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { eligiendoDia = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, tint = Cian, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(fmtDia.format(Date(cuando)), color = TextoPrimario, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { eligiendoHora = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Schedule, null, tint = Cian, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(hora(cuando), color = TextoPrimario, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = lugar,
                onValueChange = { if (it.length <= 120) lugar = it },
                label = { Text("Lugar (opcional)", color = TextoTerciario) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nota,
                onValueChange = { if (it.length <= 280) nota = it },
                label = { Text("Nota (opcional)", color = TextoTerciario) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cian, unfocusedBorderColor = Slate,
                    focusedTextColor = TextoPrimario, unfocusedTextColor = TextoPrimario,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onCrear(titulo.trim(), cuando, lugar.trim(), nota.trim()) },
                enabled = titulo.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cian, contentColor = TextoSobreAcento,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crear evento") }
            Spacer(Modifier.height(22.dp))
        }
    }

    if (eligiendoDia) {
        val estado = rememberDatePickerState(initialSelectedDateMillis = cuando)
        DatePickerDialog(
            onDismissRequest = { eligiendoDia = false },
            colors = DatePickerDefaults.colors(containerColor = BgElev),
            confirmButton = {
                TextButton(onClick = {
                    estado.selectedDateMillis?.let { dia ->
                        // El selector devuelve medianoche UTC. Se conserva la
                        // hora que ya estaba elegida en vez de pisarla con las
                        // 00:00, que es lo que pasa si se toma el valor crudo.
                        val cal = java.util.Calendar.getInstance()
                        val antes = java.util.Calendar.getInstance().apply { timeInMillis = cuando }
                        cal.timeInMillis = dia
                        cal.timeZone = java.util.TimeZone.getDefault()
                        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = dia }
                        cal.set(java.util.Calendar.YEAR, utc.get(java.util.Calendar.YEAR))
                        cal.set(java.util.Calendar.MONTH, utc.get(java.util.Calendar.MONTH))
                        cal.set(java.util.Calendar.DAY_OF_MONTH, utc.get(java.util.Calendar.DAY_OF_MONTH))
                        cal.set(java.util.Calendar.HOUR_OF_DAY, antes.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, antes.get(java.util.Calendar.MINUTE))
                        cal.set(java.util.Calendar.SECOND, 0)
                        cuando = cal.timeInMillis
                    }
                    eligiendoDia = false
                }) { Text("Listo", color = Cian) }
            },
        ) { DatePicker(state = estado, colors = DatePickerDefaults.colors(containerColor = BgElev)) }
    }

    if (eligiendoHora) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = cuando }
        val estado = rememberTimePickerState(
            initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(java.util.Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { eligiendoHora = false },
            containerColor = BgElev,
            text = { TimePicker(state = estado) },
            confirmButton = {
                TextButton(onClick = {
                    cuando = java.util.Calendar.getInstance().apply {
                        timeInMillis = cuando
                        set(java.util.Calendar.HOUR_OF_DAY, estado.hour)
                        set(java.util.Calendar.MINUTE, estado.minute)
                        set(java.util.Calendar.SECOND, 0)
                    }.timeInMillis
                    eligiendoHora = false
                }) { Text("Listo", color = Cian) }
            },
            dismissButton = {
                TextButton(onClick = { eligiendoHora = false }) {
                    Text("Cancelar", color = TextoSecundario)
                }
            },
        )
    }
}

// ============================================================
//  Punto de entrada desde la burbuja
// ============================================================

/**
 * Dibuja el contenido con estructura de un mensaje, cualquiera sea su clase.
 *
 * Es una sola puerta a proposito: la burbuja del chat no tiene que saber que
 * clases existen ni de donde salen los votos. Lo que necesita saber -que este
 * mensaje NO se pinta como texto- lo decide `m.especial`.
 *
 * Los votos se leen aqui, con un flujo por consulta. Se podrian haber cargado
 * todos de una vez en la pantalla, pero entonces cada voto que llega recompone
 * la lista entera; asi solo se recompone la encuesta que cambio.
 */
@Composable
fun ContenidoEspecialBurbuja(
    m: MensajeEnt,
    onAbrirContacto: (String) -> Unit,
) {
    val ctxBurbuja = LocalContext.current
    val app = ctxBurbuja.applicationContext as com.wtfuck.app.WtfuckApp
    val ambito = rememberCoroutineScope()
    val carga = remember(m.especialJson) { cargaEspecial(m) }
    val yo = app.sesion.username.orEmpty()

    when (carga) {
        // El unico sitio donde una `Carga` cruda se convierte en algo
        // dibujable. Todo lo que llega de un sobre ajeno pasa por aqui.
        is Carga.Ubicacion -> BurbujaUbicacion(segura(carga))

        is Carga.UbicacionEnVivo -> BurbujaUbicacionViva(
            carga = carga,
            esMia = m.esMio,
            // De la carga y no de `creadoEn`: la fila conserva la hora en
            // que EMPEZO el compartido —moverla reordenaria el chat y la
            // burbuja saltaria al final cada medio minuto—, asi que la hora
            // de la ultima posicion va dentro, escrita por el reloj de quien
            // la esta leyendo.
            ultimaActualizacion = carga.recibidaEn,
            autor = if (m.esMio) yo else m.autor,
            fotoAutor = fotoDe(if (m.esMio) yo else m.autor, m, app),
            onCortar = {
                // Las dos cosas, y en este orden: el estado primero -para que
                // la burbuja cambie ya aunque el servicio tarde- y despues el
                // servicio, que tambien se lleva su notificacion.
                ambito.launch {
                    runCatching { app.repo.terminarUbicacionEnVivo(m.conversacionId, m.id) }
                    com.wtfuck.app.datos.ServicioUbicacionViva.cortarSiEs(ctxBurbuja, m.id)
                }
            },
        )
        is Carga.Contacto -> BurbujaContacto(segura(carga), onAbrirContacto)

        is Carga.Encuesta -> {
            val votos by app.repo.votos(m.id).collectAsState(initial = emptyList())
            BurbujaEncuesta(segura(carga), votos, yo) { elegidas ->
                ambito.launch {
                    runCatching { app.repo.votar(m.conversacionId, m.id, elegidas) }
                }
            }
        }

        is Carga.Evento -> {
            val votos by app.repo.votos(m.id).collectAsState(initial = emptyList())
            BurbujaEvento(segura(carga), votos, yo) { elegidas ->
                ambito.launch {
                    runCatching { app.repo.votar(m.conversacionId, m.id, elegidas) }
                }
            }
        }

        // Una clase que este cliente no conoce: se dice, en vez de dibujar una
        // burbuja vacia que parece un error de la otra persona.
        else -> Text(
            "Este mensaje necesita una version más nueva de la app.",
            color = TextoTerciario,
            fontSize = 13.sp,
        )
    }
}

/**
 * La foto de alguien, si este teléfono la tiene **sin preguntar a nadie**.
 *
 * ## Por qué no siempre hay foto
 *
 * Porque la URL de un avatar necesita su `version` —es el truco que invalida
 * la caché— y este teléfono sólo la conoce en dos casos: la propia, del perfil
 * cargado, y la del otro lado de una directa, que viaja con la conversación.
 * En un grupo haría falta una consulta por autor, y una consulta de red por
 * burbuja para decorar un icono no vale lo que cuesta.
 *
 * Cuando no hay, el [Avatar] cae a las iniciales con su color derivado del
 * nombre — que identifica igual, porque el color es siempre el mismo para el
 * mismo nombre.
 */
@Composable
private fun fotoDe(
    autor: String,
    m: com.wtfuck.app.datos.MensajeEnt,
    app: com.wtfuck.app.WtfuckApp,
): String? {
    val mio by app.repo.miPerfil.collectAsState()
    if (m.esMio) {
        return com.wtfuck.app.datos.ApiCliente.urlImagen(
            autor, "avatar", mio?.avatarVersion ?: 0L,
        )
    }
    // De la conversación, y **sólo si es de este autor**: en un grupo el avatar
    // de la conversación es el del grupo, y ponérselo a una persona diría que
    // esa posición es del grupo entero.
    //
    // Sale de la lista que la app ya tiene cargada, no de una consulta nueva:
    // es la misma de la que se dibuja la pantalla de chats.
    val chats by app.repo.conversaciones.collectAsState(emptyList())
    val c = chats.firstOrNull { it.id == m.conversacionId } ?: return null
    if (c.avatarUsername != autor) return null
    return com.wtfuck.app.datos.ApiCliente.urlImagen(autor, "avatar", c.avatarVersion)
}
