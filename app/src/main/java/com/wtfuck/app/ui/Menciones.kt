package com.wtfuck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wtfuck.app.ui.theme.*
import com.wtfuck.protocol.PATRON_MENCION

/**
 * Menciones: escribirlas y verlas.
 *
 * ## Que habia, y por que no alcanzaba
 *
 * Mencionar **ya funcionaba**: quien escribe `@tatiana` manda ese username en
 * `menciones`, el servidor lo resuelve contra los participantes reales y queda
 * registrado. Lo que no habia era nada de eso **a la vista**:
 *
 *  - Al escribir, hacia falta acordarse del username exacto. Un `@Tatiana` con
 *    mayuscula funciona, pero un `@taty` no menciona a nadie y nada lo dice.
 *  - Al leer, `@tatiana` se dibujaba como texto normal, asi que una mencion a
 *    uno mismo se perdia entre el resto del mensaje.
 *
 * O sea que la funcion estaba entera por dentro y era invisible por fuera, que
 * para quien la usa es lo mismo que no estar.
 */

// ===========================================================================
//  Leer: el texto con las menciones marcadas
// ===========================================================================

/**
 * El texto de un mensaje con las menciones resaltadas.
 *
 * ## Dos intensidades, y la diferencia importa
 *
 * Una mencion a **mi** va en negrita y con el fondo mas marcado: es la que
 * cambia si tengo que leer el mensaje ahora o luego. Las menciones a otros
 * llevan el mismo color con un fondo apenas insinuado, porque son informacion
 * sobre de quien se habla, no una llamada.
 *
 * Si todas se pintaran igual, un grupo donde se menciona a diez personas seria
 * un mensaje lleno de subrayados en el que el mio no se distingue: exactamente
 * el problema que la mencion viene a resolver.
 *
 * ## Por que usa `PATRON_MENCION` del contrato
 *
 * Porque lo que se resalta tiene que ser **lo mismo** que se registro al
 * enviar. Con una expresion propia aqui, la burbuja podria pintar como mencion
 * algo que no aviso a nadie: una promesa visual sin nada detras.
 *
 * @param miUsuario en minusculas. Vacio si no se sabe todavia.
 */
fun textoConMenciones(
    texto: String,
    miUsuario: String,
    colorNormal: Color,
    colorMencion: Color,
): AnnotatedString {
    // Sin ninguna mencion se devuelve el texto tal cual. Es el caso comun -la
    // mayoria de los mensajes no mencionan a nadie- y construir un
    // AnnotatedString con un solo tramo por cada mensaje de la lista es
    // trabajo por nada en la pantalla que mas se desplaza.
    //
    // **QUIEN LLAMA TIENE QUE PONER `color` EN SU `Text`.** Por este atajo,
    // lo que vuelve no lleva color propio, y sin el del `Text` cae al color de
    // contenido por defecto. Estuvo mal justo asi: el texto de las burbujas
    // propias salia claro sobre el cian, ilegible, y solo ahi — en las
    // recibidas el color por defecto coincide con el correcto, asi que el
    // defecto era invisible en media pantalla.
    val hallazgos = PATRON_MENCION.findAll(texto.lowercase()).toList()
    if (hallazgos.isEmpty()) return AnnotatedString(texto)

    val yo = miUsuario.lowercase()
    return buildAnnotatedString {
        var desde = 0
        for (h in hallazgos) {
            // Se recorta del texto ORIGINAL y no del que se paso a minusculas:
            // las posiciones coinciden porque `lowercase()` no cambia el largo
            // en este alfabeto, pero lo que se dibuja tiene que conservar las
            // mayusculas que la persona escribio.
            if (h.range.first > desde) {
                withStyle(SpanStyle(color = colorNormal)) {
                    append(texto.substring(desde, h.range.first))
                }
            }
            // `@todos` tambien es para mi: me incluye.
            val esParaMi = (yo.isNotBlank() && h.groupValues[1] == yo) ||
                h.groupValues[1] == com.wtfuck.protocol.MENCION_TODOS
            withStyle(
                // Las dos llevan fondo, y la diferencia es el peso y la
                // intensidad. Marcar las de otros SOLO con color no funciona
                // en la burbuja propia: ahi el color de la mencion tiene que
                // ser el mismo del texto -sobre el cian, el cian no se ve- y
                // entonces una mencion a otra persona quedaba idéntica al
                // resto de la frase, o sea invisible.
                if (esParaMi) {
                    SpanStyle(
                        color = colorMencion,
                        fontWeight = FontWeight.Bold,
                        background = colorMencion.copy(alpha = 0.22f),
                    )
                } else {
                    SpanStyle(color = colorMencion, background = colorMencion.copy(alpha = 0.10f))
                }
            ) {
                append(texto.substring(h.range.first, h.range.last + 1))
            }
            desde = h.range.last + 1
        }
        if (desde < texto.length) {
            withStyle(SpanStyle(color = colorNormal)) { append(texto.substring(desde)) }
        }
    }
}

/**
 * El texto de un mensaje con su formato (`*negrita*`, `_cursiva_`...) y sus
 * menciones. Ver `Formato` para las reglas.
 *
 * Las menciones se resaltan DENTRO de cada tramo: `*@ana*` es una mencion en
 * negrita. Menos dentro de un spoiler tapado, donde no se pinta nada: una
 * mencion resaltada dentro del bloque dejaria leer justo lo que se tapo.
 *
 * El spoiler se destapa tocandolo. Se usa un enlace clicable y no un toque
 * sobre toda la burbuja: la burbuja ya tiene sus propios gestos (mantener
 * para el menu, deslizar para responder), y un toque cualquiera no puede
 * destapar lo que alguien quiso esconder.
 */
fun textoDeMensaje(
    texto: String,
    miUsuario: String,
    colorNormal: Color,
    colorMencion: Color,
    spoilerVisible: Boolean,
    onVerSpoiler: () -> Unit,
): AnnotatedString {
    val tramos = com.wtfuck.app.datos.Formato.tramos(texto)
    // El atajo de siempre: sin formato ni enlaces, lo de antes.
    if (tramos.size == 1 && tramos[0].estilos.isEmpty() && "http" !in texto) {
        return textoConMenciones(texto, miUsuario, colorNormal, colorMencion)
    }
    return buildAnnotatedString {
        for (t in tramos) {
            val e = t.estilos
            val tapado = com.wtfuck.app.datos.Estilo.SPOILER in e && !spoilerVisible
            val estilo = SpanStyle(
                fontWeight = if (com.wtfuck.app.datos.Estilo.NEGRITA in e) FontWeight.Bold else null,
                fontStyle = if (com.wtfuck.app.datos.Estilo.CURSIVA in e) androidx.compose.ui.text.font.FontStyle.Italic else null,
                textDecoration = if (com.wtfuck.app.datos.Estilo.TACHADO in e) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                fontFamily = if (com.wtfuck.app.datos.Estilo.MONO in e) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                background = when {
                    tapado -> colorNormal
                    com.wtfuck.app.datos.Estilo.MONO in e -> colorNormal.copy(alpha = 0.12f)
                    com.wtfuck.app.datos.Estilo.SPOILER in e -> colorNormal.copy(alpha = 0.10f)
                    else -> Color.Unspecified
                },
                // Tapado: el texto del mismo color que el fondo del bloque.
                color = if (tapado) colorNormal else Color.Unspecified,
            )
            if (tapado) {
                withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("spoiler") { onVerSpoiler() }) {
                    withStyle(estilo) { append(t.texto) }
                }
            } else {
                withStyle(estilo) {
                    // Los enlaces, tocables y subrayados. Dentro de lo
                    // monoespaciado no: es codigo, y ahi una URL es texto.
                    val enlaces = if (com.wtfuck.app.datos.Estilo.MONO in e) emptyList()
                    else com.wtfuck.app.datos.VistaPreviaHtml.enlacesEn(t.texto)
                    var desde = 0
                    for ((rango, url) in enlaces) {
                        if (rango.first > desde) {
                            append(textoConMenciones(t.texto.substring(desde, rango.first), miUsuario, colorNormal, colorMencion))
                        }
                        withLink(
                            androidx.compose.ui.text.LinkAnnotation.Url(
                                url,
                                androidx.compose.ui.text.TextLinkStyles(
                                    SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                                ),
                            )
                        ) { append(t.texto.substring(rango)) }
                        desde = rango.last + 1
                    }
                    if (desde < t.texto.length) {
                        append(textoConMenciones(t.texto.substring(desde), miUsuario, colorNormal, colorMencion))
                    }
                }
            }
        }
    }
}

// ===========================================================================
//  Escribir: a quien se esta buscando
// ===========================================================================

/**
 * El trozo de `@algo` que se esta escribiendo justo antes del cursor.
 *
 * Devuelve `null` cuando no hay ninguno, que es casi siempre. Lo que hay que
 * acertar aqui son los casos en que **no** debe ofrecerse nada:
 *
 *  - Un `@` en medio de una palabra (`correo@ejemplo`) no es una mencion: es
 *    una direccion. Se exige que delante haya un espacio o el principio.
 *  - Un `@` ya cerrado por un espacio (`@tatiana hola`) esta terminado: quien
 *    sigue escribiendo la frase no quiere un desplegable tapandole el texto.
 *  - Lo escrito no puede pasar de 24 caracteres, que es el largo de un
 *    username: mas alla no hay nada que sugerir.
 *
 * @return lo escrito tras el `@`, en minusculas, o null.
 */
fun mencionEnCurso(texto: String, cursor: Int): String? {
    if (cursor <= 0 || cursor > texto.length) return null
    val hasta = texto.substring(0, cursor)
    val arroba = hasta.lastIndexOf('@')
    if (arroba < 0) return null
    // Delante del "@" tiene que haber un hueco, no una letra.
    if (arroba > 0 && !hasta[arroba - 1].isWhitespace()) return null
    val escrito = hasta.substring(arroba + 1)
    if (escrito.length > 24) return null
    // Un espacio cierra la mencion; tambien un salto de linea.
    if (escrito.any { it.isWhitespace() }) return null
    return escrito.lowercase()
}

/**
 * Los candidatos que encajan con lo que se esta escribiendo.
 *
 * Con el prefijo vacio —recien tecleado el `@`— se devuelven todos: es cuando
 * mas util es la lista, porque quien no recuerda el username no puede escribir
 * ni la primera letra.
 *
 * Busca por username **y** por el nombre con el que se ve a la persona, porque
 * quien escribe piensa en el nombre que tiene delante y no en el identificador.
 * Lo que se inserta siempre es el username, que es lo unico que el servidor
 * resuelve.
 */
fun candidatosDeMencion(
    prefijo: String,
    participantes: List<CandidatoMencion>,
    tope: Int = 8,
): List<CandidatoMencion> =
    participantes
        .filter {
            prefijo.isBlank() ||
                it.username.startsWith(prefijo, true) ||
                it.nombre.contains(prefijo, true)
        }
        // Los que empiezan por lo escrito, antes que los que solo lo
        // contienen: escribir "ta" y que salga primero alguien que se llama
        // "Marta" obliga a leer la lista entera.
        .sortedByDescending { it.username.startsWith(prefijo, true) }
        .take(tope)

/** Alguien a quien se puede mencionar. [nombre] es solo para buscar y mostrar. */
data class CandidatoMencion(val username: String, val nombre: String)

/**
 * Sustituye el `@loquesea` que hay antes del cursor por el username elegido.
 *
 * Deja un espacio detras: sin el, seguir escribiendo pega la siguiente palabra
 * al username y la mencion deja de serlo. Devuelve tambien donde queda el
 * cursor, porque un selector que inserta texto y manda el cursor al final
 * rompe la frase de quien estaba editando por el medio.
 */
fun insertarMencion(texto: String, cursor: Int, username: String): Pair<String, Int> {
    val hasta = texto.substring(0, cursor)
    val arroba = hasta.lastIndexOf('@')
    if (arroba < 0) return texto to cursor
    val nuevo = "@$username "
    return (texto.substring(0, arroba) + nuevo + texto.substring(cursor)) to (arroba + nuevo.length)
}

/**
 * La tira de candidatos sobre el campo de escritura.
 *
 * Horizontal y no una lista vertical: vertical taparia el chat justo mientras
 * se escribe, y con ocho candidatos como maximo no hace falta tanto sitio.
 */
@Composable
fun TiraDeMenciones(candidatos: List<CandidatoMencion>, onElegir: (CandidatoMencion) -> Unit) {
    if (candidatos.isEmpty()) return
    LazyRow(
        Modifier
            .fillMaxWidth()
            .background(BgElev)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(candidatos, key = { it.username }) { c ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(BgSurface)
                    .clickable { onElegir(c) }
                    .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Mencionar a ${c.nombre}"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(nombre = c.nombre, url = null, tamano = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(c.nombre, color = TextoPrimario, fontSize = 14.sp)
            }
        }
    }
}
