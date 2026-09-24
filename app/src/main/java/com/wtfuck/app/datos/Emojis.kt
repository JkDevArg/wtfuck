package com.wtfuck.app.datos

import java.text.Normalizer

/**
 * Módulo Z.1 · El catálogo de emojis, con sus palabras y sus tonos.
 *
 * ## Por qué el glifo y sus palabras van en la misma línea
 *
 * La versión anterior tenía la lista de emojis en un archivo de interfaz, como
 * cuatro cadenas separadas por espacios. Buscar exige una segunda estructura
 * —glifo a palabras— y en cuanto son dos estructuras **se desincronizan**:
 * alguien agrega un emoji al grupo y nadie le pone palabras, así que existe
 * pero no se puede encontrar, que es peor que no existir.
 *
 * Aquí cada emoji es **una línea**: el glifo y después sus palabras. Agregar
 * uno sin palabras se ve a simple vista, y `catalogo()` lo rechaza en las
 * pruebas.
 *
 * ## Por qué esto no está en la interfaz
 *
 * Porque buscar es lógica y la lógica se prueba. `buscar` y `aplicarTono` no
 * tocan el framework de Android y por eso corren en la JVM, que es la misma
 * razón por la que `Recorte` no es un `android.graphics.Rect`.
 */
data class Emoji(
    val glifo: String,
    /** Palabras por las que se lo encuentra, separadas por espacio, sin tildes. */
    val palabras: List<String>,
)

/**
 * Los cinco modificadores Fitzpatrick, en orden.
 *
 * `TONO_POR_DEFECTO` es la cadena vacía y **no** es "el primero": un emoji sin
 * modificador es amarillo, que es una opción distinta de las cinco y la que
 * elige quien no quiere elegir.
 */
const val TONO_POR_DEFECTO = ""
val TONOS = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

/**
 * Los que Unicode dice que admiten tono de piel.
 *
 * **Candidatos, no certezas.** Que Unicode defina la secuencia no significa
 * que la fuente del aparato sepa dibujarla; eso lo decide
 * `admiteTonoAqui`, que le pregunta a la fuente. Esta lista es la mitad
 * teórica de la respuesta.
 *
 * ## Las tres que estaban aquí y no correspondían
 *
 * Tenía 🫡, 🫢 y 🫣. Son **caras**, no manos, y Unicode no les da modificador
 * de tono aunque tengan una mano dibujada encima. Puestas aquí, el aparato
 * dibujaba la carita amarilla y el modificador **al lado**, como un
 * rectángulo de color suelto debajo.
 *
 * Lo encontré mirando la pantalla después de elegir un tono, no con una
 * prueba: el código hacía exactamente lo que le pedí. Y el comentario que
 * había aquí advertía de este error —"no es los que tienen una mano"— mientras
 * la lista lo cometía. Un comentario no valida nada.
 */
val SOPORTAN_TONO: Set<String> = setOf(
    "👍", "👎", "👌", "🤌", "🤏",
    "✌️", "🤞", "🤟", "🤘", "🤙",
    "👈", "👉", "👆", "👇", "☝️",
    "✋", "🤚", "🖐️", "🖖", "👋",
    "🙏", "✍️", "💪", "🙌", "👏",
    "🤦", "🤷",
    "💁", "🙋", "🙆", "🙅", "💅",
    "🤳",
)

/**
 * Le pone —o le cambia— el tono de piel a un emoji.
 *
 * Idempotente y reversible: aplicar dos tonos seguidos deja el segundo, no los
 * dos pegados. Sin esto, cambiar de opinión sobre el tono produce basura que
 * se manda y queda en el chat de la otra persona.
 *
 * A un emoji que no lo admite se lo devuelve tal cual. No es un silencio: es
 * que la pregunta no aplica, y la interfaz ni siquiera ofrece la opción.
 */
fun aplicarTono(glifo: String, tono: String): String {
    val base = quitarTono(glifo)
    if (base !in SOPORTAN_TONO || tono.isEmpty()) return base
    // El modificador va despues del glifo base y ANTES del selector de
    // variacion: con el VS16 en medio, media Android lo dibuja como dos cosas.
    return if (base.endsWith(VS16)) base.dropLast(1) + tono else base + tono
}

/**
 * El selector de variacion, U+FE0F.
 *
 * Por codigo y no escrito: **es invisible**. Un caracter que no se ve en el
 * editor es un caracter que alguien borra sin darse cuenta y despues nadie
 * encuentra por que un emoji dejo de dibujarse.
 */
private val VS16 = 0xFE0F.toChar().toString()

/** Le quita el modificador de tono, si lo tiene. Devuelve el glifo base. */
fun quitarTono(glifo: String): String {
    var s = glifo
    for (t in TONOS) if (s.contains(t)) s = s.replace(t, "")
    // Un glifo al que se le quitó el modificador puede haber perdido también su
    // selector de variación al ponerlo. Se lo devuelve si el base lo llevaba.
    if (s !in SOPORTAN_TONO && (s + VS16) in SOPORTAN_TONO) s += VS16
    return s
}

/**
 * Minúsculas y sin tildes.
 *
 * Buscar "corazon" tiene que encontrar ❤️ aunque su palabra sea "corazón".
 * Quien escribe rápido en un teclado de teléfono no pone tildes, y una búsqueda
 * que exige la tilde es una búsqueda que no encuentra nada.
 */
fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()

/**
 * Busca emojis por palabra.
 *
 * **Por prefijo y no por subcadena**, y es una decisión: con subcadena, "ojo"
 * trae "enojado" y "cerrojo", que no es lo que se pidió. Con prefijo, escribir
 * tres letras acota en vez de ensanchar, que es lo que uno espera de un
 * buscador mientras teclea.
 *
 * Se aceptan **varias palabras**: "cara feliz" exige que cada una de las dos
 * tenga su prefijo en el emoji. Así se afina en vez de acumular.
 */
fun buscarEmojis(consulta: String, catalogo: List<Emoji>): List<Emoji> {
    val partes = normalizar(consulta).split(" ").filter { it.isNotBlank() }
    if (partes.isEmpty()) return emptyList()
    return catalogo.filter { e ->
        partes.all { p -> e.palabras.any { it.startsWith(p) } }
    }
}

/**
 * El catálogo entero, sin grupos, para buscar.
 *
 * Ojo con los repetidos: un mismo emoji puede estar en dos grupos (✨ está en
 * Corazones y podría estar en Cosas) y la búsqueda no debe mostrarlo dos veces.
 */
fun catalogo(): List<Emoji> =
    GRUPOS_EMOJI.flatMap { it.second }.distinctBy { it.glifo }

/**
 * Modulo Z.4 - Si lo escrito es **un solo emoji**, cual es.
 *
 * Es lo que decide si se ofrecen stickers mientras alguien escribe. La regla
 * es estricta a proposito: un emoji y nada mas. Con "jaja 😂" la persona esta
 * escribiendo una frase y no buscando un sticker, y una tira que aparece a
 * mitad de una frase tapa el teclado por nada.
 *
 * Se exige que este **en el catalogo** en vez de adivinar por rango Unicode.
 * Con eso, ":)" o "..." no disparan nada, y lo que dispara es exactamente lo
 * que se puede haber usado como etiqueta.
 *
 * Devuelve el glifo **con su tono** si lo tenia: da igual para buscar -las
 * etiquetas no llevan tono- pero quien lo llame decide.
 */
fun emojiSolo(texto: String): String {
    val t = texto.trim()
    // 8 unidades UTF-16: un emoji con par suplente, modificador de tono y
    // selector de variacion no pasa de ahi.
    if (t.isEmpty() || t.length > 8) return ""
    return if (quitarTono(t) in glifos()) t else ""
}

private var cacheGlifos: Set<String>? = null

/** Los glifos del catalogo, para consultar. Se arma una vez. */
fun glifos(): Set<String> =
    cacheGlifos ?: catalogo().map { it.glifo }.toSet().also { cacheGlifos = it }

/**
 * Los emojis por grupo, cada uno con sus palabras.
 *
 * Formato de cada línea: el glifo, un espacio, y después las palabras con las
 * que se lo encuentra. **Sin tildes en las palabras** —`normalizar` las quita
 * de los dos lados, pero escribirlas ya normalizadas evita que alguien crea
 * que hay que ponerlas—.
 */
val GRUPOS_EMOJI: List<Pair<String, List<Emoji>>> = listOf(
    "Caras" to grupo(
        "😀 sonrisa feliz contento alegre",
        "😃 sonrisa feliz alegre abierta",
        "😄 sonrisa feliz risa ojos",
        "😁 sonrisa dientes radiante",
        "😆 risa carcajada apretados",
        "😅 risa sudor nervioso alivio",
        "🤣 carcajada suelo risa muerto",
        "😂 llorar risa lagrimas",
        "🙂 sonrisa leve tranquilo",
        "🙃 reves invertida ironia sarcasmo",
        "😉 guino ojo complice",
        "😊 sonrojo timido dulce feliz",
        "😇 angel santo inocente aureola",
        "🥰 amor enamorado corazones carino",
        "😍 enamorado corazones ojos amor",
        "🤩 estrellas asombro flipando",
        "😘 beso amor mandando",
        "😗 beso boca",
        "😚 beso ojos cerrados",
        "😙 beso sonrisa",
        "😋 rico sabroso lengua delicioso",
        "😛 lengua burla",
        "😜 lengua guino loco",
        "🤪 loco chiflado bizco",
        "😝 lengua ojos cerrados burla",
        "🤑 dinero plata billete codicia",
        "🤗 abrazo manos carino",
        "🤭 ups tapando boca vergüenza",
        "🤫 silencio callar shh secreto",
        "🤔 pensando duda pensativo mmm",
        "🤐 cremallera boca cerrada callado",
        "🤨 ceja duda escepticismo sospecha",
        "😐 neutral seria sin expresion",
        "😑 inexpresiva harta",
        "😶 sin boca muda",
        "😏 picara suficiencia listo",
        "😒 fastidio hartazgo desagrado",
        "🙄 ojos blanco fastidio hartazgo",
        "😬 mueca incomodo tension",
        "🤥 mentira nariz pinocho",
        "😌 alivio calma paz",
        "😔 triste pensativa decaida",
        "😪 sueno cansado dormido",
        "🤤 baba antojo hambre",
        "😴 dormido sueno zzz",
        "😷 mascarilla enfermo cubrebocas",
        "🤒 fiebre enfermo termometro",
        "🤕 herido venda golpe",
        "🤢 asco nausea mareo",
        "🤮 vomito asco devolver",
        "🥵 calor sofoco sudor",
        "🥶 frio helado congelado",
        "🥴 mareado borracho aturdido",
        "😵 mareado aturdido ko",
        "🤯 explota cabeza asombro shock",
        "🤠 vaquero sombrero",
        "🥳 fiesta celebracion cumpleanos",
        "😎 gafas genial cool sol",
        "🤓 nerd lentes empollon",
        "🧐 monoculo examinar curioso",
        "😕 confundido duda",
        "😟 preocupado inquieto",
        "🙁 triste leve",
        "😮 sorpresa boca abierta",
        "😯 sorpresa asombro",
        "😲 asombro impacto",
        "😳 sonrojo verguenza impacto",
        "🥺 suplica pena ojitos porfa",
        "😦 angustia boca abierta",
        "😧 angustia dolor",
        "😨 miedo susto",
        "😰 angustia sudor miedo",
        "😥 decepcion alivio triste",
        "😢 llorar lagrima triste",
        "😭 llorar mucho llanto triste",
        "😱 grito panico miedo terror",
        "😖 frustracion confusion",
        "😣 perseverancia esfuerzo dolor",
        "😞 decepcion desilusion triste",
        "😓 sudor frio derrota",
        "😩 cansancio hartazgo",
        "😫 cansado agotado harto",
        "🥱 bostezo sueno aburrido",
        "😤 enojo vapor nariz orgullo",
        "😡 enojo furia rojo rabia",
        "😠 enojo molesto",
        "🤬 puteada groseria enojo censura",
        "😈 diablo travieso malvado",
        "👿 diablo enojo demonio",
        "💀 calavera muerte muerto",
        "💩 caca mierda popo",
        "🤡 payaso broma",
        "👹 ogro monstruo",
        "👻 fantasma boo susto",
        "👽 alien extraterrestre marciano",
        "🤖 robot bot maquina",
    ),
    "Gestos" to grupo(
        "👍 pulgar arriba bien ok aprobar like",
        "👎 pulgar abajo mal rechazo dislike",
        "👌 ok perfecto correcto",
        "🤌 dedos italiano que",
        "🤏 poquito pellizco pequeno",
        "✌️ paz victoria dos",
        "🤞 suerte dedos cruzados",
        "🤟 te quiero amor senas",
        "🤘 cuernos rock metal",
        "🤙 llamame hang loose",
        "👈 izquierda senala dedo",
        "👉 derecha senala dedo",
        "👆 arriba senala dedo",
        "👇 abajo senala dedo",
        "☝️ arriba indice uno atencion",
        "✋ mano alto parar palma",
        "🤚 mano dorso alto",
        "🖐️ mano dedos abiertos",
        "🖖 vulcano spock saludo",
        "👋 hola chau saludo adios mano",
        "🤝 acuerdo trato apreton manos",
        "🙏 gracias porfavor rezar suplica",
        "✍️ escribir mano lapiz firma",
        "💪 fuerza musculo biceps",
        "🦾 brazo mecanico fuerza protesis",
        "🙌 celebracion manos arriba alegria",
        "👏 aplauso palmas bravo",
        "🫡 saludo militar respeto",
        "🫢 sorpresa tapar boca ups",
        "🫣 espiar verguenza ojo",
        "🤦 facepalm palma cara increible",
        "🤷 encogerse hombros ni idea",
        "💁 informacion mano actitud",
        "🙋 levantar mano yo pregunta",
        "🙆 ok brazos bien",
        "🙅 no negar prohibido brazos",
        "💅 unas manicura actitud",
        "🤳 selfie foto telefono",
    ),
    "Corazones" to grupo(
        "❤️ corazon amor rojo",
        "🧡 corazon naranja amor",
        "💛 corazon amarillo amor amistad",
        "💚 corazon verde amor",
        "💙 corazon azul amor",
        "💜 corazon morado violeta amor",
        "🖤 corazon negro luto",
        "🤍 corazon blanco",
        "🤎 corazon marron",
        "💔 corazon roto partido pena",
        "❣️ corazon exclamacion",
        "💕 corazones dos amor",
        "💞 corazones giran amor",
        "💓 corazon latido palpitar",
        "💗 corazon creciendo",
        "💖 corazon brillante destello",
        "💘 corazon flecha cupido",
        "💝 corazon regalo lazo",
        "✨ brillo destellos magia nuevo",
        "💫 mareo estrella destello",
        "⭐ estrella favorito",
        "🌟 estrella brillante destacar",
        "🔥 fuego llama genial arde",
        "💥 explosion choque boom",
        "💯 cien perfecto total",
        "✅ listo hecho bien check verde",
        "❌ error mal no cruz",
        "⚠️ advertencia cuidado alerta",
        "❓ pregunta duda interrogacion",
        "❗ exclamacion importante atencion",
    ),
    "Cosas" to grupo(
        "🎉 fiesta celebracion confeti",
        "🎊 fiesta confeti bola",
        "🎁 regalo obsequio caja",
        "🎂 torta pastel cumpleanos",
        "🍰 torta porcion postre",
        "☕ cafe taza",
        "🍵 te mate infusion",
        "🍺 cerveza chela birra",
        "🍻 cerveza brindis salud",
        "🥂 brindis copas champan",
        "🍕 pizza comida",
        "🍔 hamburguesa comida",
        "🍟 papas fritas comida",
        "🌮 taco comida mexicano",
        "🍿 pop maiz cine cancha",
        "🍎 manzana fruta",
        "🍌 platano banana fruta",
        "🍇 uvas fruta",
        "🍉 sandia fruta",
        "🥑 palta aguacate",
        "⚽ futbol pelota balon deporte",
        "🏀 basquet pelota deporte",
        "🎮 videojuego control juego gamer",
        "🎧 auriculares musica audifonos",
        "🎸 guitarra musica rock",
        "🎤 microfono cantar karaoke",
        "📱 celular telefono movil",
        "💻 laptop computadora portatil",
        "⌨️ teclado escribir",
        "🖥️ monitor computadora pantalla",
        "📷 camara foto",
        "🔒 candado cerrado seguro privado",
        "🔑 llave clave acceso",
        "💰 dinero plata bolsa",
        "📈 subir grafico crecer alza",
        "📉 bajar grafico caer baja",
        "⏰ reloj alarma hora despertador",
        "📌 chinche fijar marcar",
        "📎 clip adjuntar sujetapapeles",
        "✂️ tijera cortar",
        "🚗 auto carro coche",
        "✈️ avion vuelo viaje",
        "🚀 cohete lanzar despegue rapido",
        "🏠 casa hogar",
        "🌍 mundo tierra planeta global",
        "☀️ sol dia calor",
        "🌙 luna noche",
        "☁️ nube nublado",
        "🌧️ lluvia llover",
        "⛈️ tormenta rayo lluvia",
        "🌈 arcoiris colores",
        "❄️ nieve copo frio",
        "🐶 perro perrito can",
        "🐱 gato gatito minino",
        "🐭 raton",
        "🦊 zorro",
        "🐻 oso",
        "🐼 panda oso",
        "🦁 leon",
        "🐷 cerdo chancho",
    ),
)

/**
 * Convierte las líneas del catálogo en emojis.
 *
 * Falla en voz alta si una línea no tiene palabras: un emoji sin palabras
 * existe en la rejilla y **no aparece nunca** al buscar, que es la clase de
 * defecto que nadie reporta porque nadie sabe que falta.
 */
private fun grupo(vararg lineas: String): List<Emoji> = lineas.map { linea ->
    val partes = linea.trim().split(" ")
    require(partes.size >= 2) { "Emoji sin palabras en el catalogo: $linea" }
    Emoji(partes[0], partes.drop(1).map { normalizar(it) })
}
