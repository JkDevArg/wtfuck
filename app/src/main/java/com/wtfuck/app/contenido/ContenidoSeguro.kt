package com.wtfuck.app.contenido

import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.FORMA_USERNAME
import com.wtfuck.protocol.TopesConsulta

/**
 * Lo que se hace con el contenido de un sobre **ajeno** antes de dibujarlo.
 *
 * ## Por que esto es el ultimo filtro y no uno mas
 *
 * El servidor es un buzon tonto: guarda bytes que no puede abrir. Eso es la
 * garantia central del producto y tambien su consecuencia incomoda — **no hay
 * nadie mas** entre quien escribe un sobre y quien lo dibuja. Un servidor
 * normal filtraria lo que entra; aqui, por diseno, no puede.
 *
 * Asi que el emisor de un sobre es una entrada no confiable, igual que lo es
 * una peticion HTTP para el servidor. No porque las personas del otro lado sean
 * sospechosas, sino porque **nada obliga a que del otro lado haya esta app**:
 * quien tenga las claves de una conversacion puede armar el JSON a mano.
 *
 * ## Que se hace y que no
 *
 * Se recorta lo largo y se comprueba la forma de lo que afirma ser una
 * identidad. No se "limpia" el texto: quitarle caracteres a lo que alguien
 * escribio es la app editando a una persona.
 *
 * La unica excepcion son los **controles de direccion bidireccional**, y solo
 * en los campos cortos. Ver [sinTrucosDeDireccion].
 *
 * Todo esto vive aparte de los composables a proposito: son decisiones, no
 * dibujo, y separadas se pueden probar sin levantar un emulador.
 */

/**
 * Recorta a [tope] y dice si recorto.
 *
 * Devolver el par en vez de solo el texto es lo que permite **decirlo** en
 * pantalla. Esconder que se recorto es peor que recortar: quien lee tiene que
 * poder saber que lo que ve no es todo.
 */
fun recortado(texto: String, tope: Int): Pair<String, Boolean> {
    val limpio = sinTrucosDeDireccion(texto)
    return if (limpio.length <= tope) limpio to false else limpio.take(tope) to true
}

/**
 * Quita los controles explicitos de direccion bidireccional.
 *
 * Son caracteres invisibles que reordenan lo que se ve sin cambiar lo que dice
 * la cadena: con `U+202E` delante, `gnp.exe` se **dibuja** como `exe.png`. Es
 * el truco clasico para que un nombre parezca otra cosa, y en una tarjeta de
 * contacto o el titulo de un evento no hay ningun motivo legitimo para usarlos.
 *
 * **No afecta al arabe ni al hebreo.** Esos idiomas se escriben de derecha a
 * izquierda por la direccion propia de sus caracteres, que es un atributo
 * Unicode de cada letra y sigue intacto. Lo que se quita son las marcas que
 * FUERZAN una direccion distinta de la natural, que es justo lo que no hace
 * falta para escribir un idioma y si hace falta para disfrazar un texto.
 *
 * Solo se aplica a los campos cortos —nombres, titulos, etiquetas, opciones—.
 * El cuerpo de un mensaje se deja **entero**: ahi el texto es de la persona que
 * escribe y no una etiqueta que la app presenta como si fuera suya.
 */
fun sinTrucosDeDireccion(texto: String): String =
    texto.filterNot { it in CONTROLES_DIRECCION }

private val CONTROLES_DIRECCION = setOf(
    // Van escapados y NO como caracteres literales: son invisibles, asi que en
    // el archivo se verian comillas vacias y en un diff no se veria nada. Un
    // fuente con overrides de direccion escondidos dentro es exactamente el
    // problema que esta lista existe para resolver.
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',   // embedding y override
    '\u2066', '\u2067', '\u2068', '\u2069',             // isolates
    '\u200E', '\u200F',                                   // marcas LTR y RTL
)

/**
 * Si eso que llego **podria** ser un username de esta plataforma.
 *
 * No dice que la cuenta exista —eso lo sabe el servidor— sino que tiene la
 * forma de una. Alcanza para lo que hace falta: decidir si se puede pintar un
 * `@` delante, que es una afirmacion sobre identidad.
 *
 * Sin esto, una tarjeta de contacto con
 * `username = "soporte · Administrador"` se lee como una cuenta oficial.
 */
fun pareceUsername(s: String): Boolean = FORMA_USERNAME.matches(s)

/**
 * Las coordenadas como texto, o `null` si no son un punto de la Tierra.
 *
 * `Double` admite `NaN`, infinitos y cualquier magnitud, y ninguno de los tres
 * se puede dibujar como una posicion. Sin la comprobacion, una ubicacion
 * fabricada muestra `NaN, NaN` y, peor, arma un `geo:NaN,NaN` que se le pasa a
 * otra app del telefono.
 *
 * Los rangos son los de siempre: latitud ±90, longitud ±180.
 */
fun coordenadasLegibles(lat: Double, lon: Double): String? {
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (lat < -90.0 || lat > 90.0) return null
    if (lon < -180.0 || lon > 180.0) return null
    // Seis decimales son ~11 cm: mas precision que la que tiene cualquier GPS
    // de telefono, y menos numeros que leer.
    return "%.6f, %.6f".format(java.util.Locale.US, lat, lon)
}

/**
 * El margen de error, o `null` si el numero no dice nada.
 *
 * Un margen negativo no existe, y uno de dos mil millones de metros tampoco:
 * los dos son ruido de un sobre armado a mano. El tope es el radio de la
 * Tierra, que es lo maximo que puede significar "estoy en algun sitio".
 */
fun margenLegible(precisionM: Int): String? =
    if (precisionM in 1..RADIO_TIERRA_M) "Margen de $precisionM m" else null

private const val RADIO_TIERRA_M = 6_371_000

/**
 * Los indices de una encuesta que caen dentro de lo que se dibuja.
 *
 * Un voto ajeno puede traer cualquier entero. No es solo un tema de no
 * reventar: un indice de mas contado como voto cambia los porcentajes que ve
 * todo el mundo.
 */
fun votosDentroDe(indices: List<Int>, cuantasOpciones: Int): List<Int> =
    indices.filter { it in 0 until cuantasOpciones }.distinct()

// ---------------------------------------------------------------------------
//  Las cargas, ya recortadas
// ---------------------------------------------------------------------------
//
//  Se devuelve una copia recortada en vez de recortar campo por campo en el
//  composable: asi un campo nuevo en `Carga` obliga a pasar por aqui, y no se
//  cuela sin tope por olvido. Fue exactamente lo que paso con las opciones de
//  la encuesta, que tuvieron techo mientras los otros cinco campos no.

/** Lo que se dibuja de una ubicacion ajena. */
data class UbicacionSegura(
    val etiqueta: String,
    val etiquetaRecortada: Boolean,
    /** `null` si las coordenadas no son un punto real: entonces no hay mapa. */
    val coordenadas: String?,
    val margen: String?,
    /**
     * El `geo:` listo, o `null`.
     *
     * Se arma aqui y no en el composable a proposito: es lo que se le entrega
     * a OTRA app del telefono, asi que es justo el sitio donde no se puede
     * colar un `NaN`. Armandolo aqui, el unico camino para tener una URI es
     * haber pasado por la validacion.
     */
    val geoUri: String?,
)

fun segura(u: Carga.Ubicacion): UbicacionSegura {
    val (etiqueta, cortada) = recortado(u.etiqueta, TopesConsulta.LARGO_LUGAR)
    val coords = coordenadasLegibles(u.lat, u.lon)
    return UbicacionSegura(
        etiqueta = etiqueta,
        etiquetaRecortada = cortada,
        coordenadas = coords,
        margen = margenLegible(u.precisionM),
        geoUri = coords?.let { "geo:${u.lat},${u.lon}?q=${u.lat},${u.lon}" },
    )
}

/** Lo que se dibuja de un contacto compartido. */
data class ContactoSeguro(
    val nombre: String,
    /**
     * `null` cuando lo que llego no tiene forma de username.
     *
     * La pantalla lo usa para dos cosas: no pintar el `@`, y no ofrecer el
     * boton de abrir conversacion. Ofrecerlo con una cuenta inventada manda a
     * quien toca a un 404 y, de paso, da a entender que esa cuenta existe.
     */
    val username: String?,
)

fun segura(c: Carga.Contacto): ContactoSeguro {
    val user = sinTrucosDeDireccion(c.username.trim())
    val nombre = recortado(c.nombre, TopesConsulta.LARGO_NOMBRE).first
    return ContactoSeguro(
        nombre = nombre.ifBlank { if (pareceUsername(user)) user else "Contacto" },
        username = user.takeIf { pareceUsername(it) },
    )
}

/** Lo que se dibuja de una encuesta ajena. */
data class EncuestaSegura(
    val pregunta: String,
    val preguntaRecortada: Boolean,
    val opciones: List<String>,
    /** Si sobraban opciones. Se dice en pantalla, no se esconde. */
    val opcionesRecortadas: Boolean,
    val multiple: Boolean,
    /**
     * Cuando cierra, o 0 si no cierra.
     *
     * Mismo trato que la fecha de un evento: una encuesta que "cierra" el ano
     * 292 millones no cierra, y dibujarlo lo unico que hace es poner una fecha
     * absurda en pantalla. Fuera de rango vale 0, o sea "no cierra".
     */
    val cierraEn: Long,
)

fun segura(e: Carga.Encuesta): EncuestaSegura {
    val (pregunta, cortada) = recortado(e.pregunta, TopesConsulta.LARGO_PREGUNTA)
    val opciones = e.opciones.take(TopesConsulta.OPCIONES)
        .map { recortado(it, TopesConsulta.LARGO_OPCION).first }
    return EncuestaSegura(
        pregunta = pregunta,
        preguntaRecortada = cortada,
        opciones = opciones,
        opcionesRecortadas = e.opciones.size > opciones.size,
        multiple = e.multiple,
        cierraEn = if (e.cierraEn in 1..TOPE_FECHA_MS) e.cierraEn else 0L,
    )
}

/** Lo que se dibuja de un evento ajeno. */
data class EventoSeguro(
    val titulo: String,
    val lugar: String,
    val nota: String,
    val notaRecortada: Boolean,
    /**
     * `null` si la fecha no se puede creer.
     *
     * Un `Long` admite fechas del ano 292 millones. No revienta al formatearla
     * —`Date` la acepta— pero "Sabado, 15 de agosto del 292278994" no es una
     * fecha, es un sobre armado a mano. Mas util es decir que no se sabe.
     */
    val cuandoMs: Long?,
)

fun segura(ev: Carga.Evento): EventoSeguro {
    val (nota, cortada) = recortado(ev.nota, TopesConsulta.LARGO_NOTA)
    return EventoSeguro(
        titulo = recortado(ev.titulo, TopesConsulta.LARGO_TITULO).first,
        lugar = recortado(ev.lugar, TopesConsulta.LARGO_LUGAR).first,
        nota = nota,
        notaRecortada = cortada,
        cuandoMs = ev.cuandoMs.takeIf { it in 1..TOPE_FECHA_MS },
    )
}

/**
 * El 1 de enero de 2200, en epoch ms.
 *
 * Un evento mas alla de eso no es un evento. El limite es generoso a proposito:
 * la pregunta no es "cuando es razonable quedar" sino "a partir de donde esto
 * claramente no lo escribio una persona".
 */
private const val TOPE_FECHA_MS = 7_258_118_400_000L

/**
 * El nombre de un archivo adjunto, como para mostrarlo.
 *
 * ## El ataque concreto
 *
 * Un documento llamado `factura\u202Egpj.exe` se **dibuja** como
 * `factura exe.jpg`. Es el uso canonico del override de direccion, y el nombre
 * de un adjunto en un mensajero es justo donde sirve: quien lee decide si abrir
 * o reenviar mirando ese nombre.
 *
 * En este cliente el archivo se guarda en disco como `<idDelMensaje>.<ext>`
 * —ver `ArchivosLocales.archivoDe`—, asi que el disfraz no cambia lo que se
 * ejecuta. Pero si cambia lo que una persona **cree** que esta recibiendo, y
 * eso alcanza: lo siguiente que hace es reenviarlo a alguien con otro cliente.
 *
 * ## Se saca en la ingesta
 *
 * El nombre pasa por aqui al guardarse, no al dibujarse, porque tiene cuatro
 * consumidores —la burbuja, la insignia de extension, la linea de la lista de
 * chats y la notificacion— y hacerlo en cada uno es pedir que alguno se olvide.
 * Es el mismo error que ya aparecio con `resumenDe`.
 */
fun nombreDeArchivoSeguro(nombre: String): String {
    val limpio = sinTrucosDeDireccion(nombre)
        // Los saltos de linea parten la etiqueta en dos y dejan escribir una
        // segunda linea que parece de la app.
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    return limpio.take(TOPE_NOMBRE_ARCHIVO)
}

/**
 * Lo que se muestra de un nombre de archivo.
 *
 * El servidor ya lo corta en 200 al reservar; esto es lo que cabe en una
 * burbuja sin empujar el resto fuera de la pantalla.
 */
const val TOPE_NOMBRE_ARCHIVO = 120

/**
 * Lo que se guarda de la historia citada en una respuesta.
 *
 * Es una CITA, no la historia: lo justo para reconocer cual era. Sin tope, una
 * "previa" de cien mil caracteres entraria entera en la burbuja y empujaria el
 * mensaje de verdad fuera de la pantalla -y ese texto lo eligio otra persona-.
 */
const val TOPE_CITA_HISTORIA = 120
