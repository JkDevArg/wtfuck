package com.wtfuck.protocol

import kotlinx.serialization.Serializable

/**
 * Modulo P · Tres tipos de cuenta.
 *
 * ## Por que existe esto
 *
 * La plataforma tenia un solo tipo de cuenta y un eje aparte, `staff_nivel`,
 * para el poder sobre la plataforma. Faltaba responder a otra pregunta
 * distinta: **que clase de cuenta es esta**. Una empresa que quiere publicarse
 * como tal y una persona que quiere depurar la app no necesitan poder de
 * moderacion; necesitan que su cuenta *sea* otra cosa.
 *
 * Son dos ejes y siguen siendo dos columnas. Un moderador puede tener ficha de
 * empresa; una empresa no modera nada por serlo.
 *
 * ## Los tres, y quien los otorga
 *
 * | Tipo | Que añade | Quien lo pone |
 * |---|---|---|
 * | `normal` | nada, es el valor por defecto | cualquiera, siempre |
 * | `desarrollador` | herramientas tecnicas dentro de la app | **staff** |
 * | `empresa` | ficha publica: nombre comercial, categoria, sitio | la propia persona |
 *
 * `desarrollador` no se lo puede dar uno mismo, y esa es la unica decision de
 * seguridad real del modulo: un modo con capacidades tecnicas que cualquiera
 * pudiera activarse es una escalada de privilegio con otro nombre.
 *
 * `empresa` si, porque es una **declaracion sobre uno mismo**, como el estado o
 * la biografia. Lo que no se puede dar uno mismo es el **distintivo de
 * verificada**: cualquiera puede escribir el nombre de un banco en su ficha, y
 * sin esa separacion la ficha seria una herramienta de suplantacion.
 */
object TipoCuenta {
    const val NORMAL = "normal"
    const val DESARROLLADOR = "desarrollador"
    const val EMPRESA = "empresa"

    val TODOS = listOf(NORMAL, DESARROLLADOR, EMPRESA)

    /** Los que una persona puede elegir para si misma. Ver la nota de arriba. */
    val AUTOSERVICIO = listOf(NORMAL, EMPRESA)
}

/**
 * Las categorias de empresa.
 *
 * Lista cerrada a proposito. Con texto libre habria cuarenta formas de escribir
 * "educacion" y el directorio dejaria de servir para lo unico que sirve una
 * categoria: encontrar a alguien sin saber su nombre. Ademas, un campo libre en
 * un perfil publico es una superficie de spam mas.
 */
object CategoriaEmpresa {
    const val EDUCACION = "educacion"
    const val TECNOLOGIA = "tecnologia"
    const val SALUD = "salud"
    const val FINANZAS = "finanzas"
    const val COMERCIO = "comercio"
    const val SERVICIOS = "servicios"
    const val INDUSTRIA = "industria"
    const val CONSTRUCCION = "construccion"
    const val TRANSPORTE = "transporte"
    const val TURISMO = "turismo"
    const val MEDIOS = "medios"
    const val ONG = "ong"
    const val GOBIERNO = "gobierno"
    const val OTRA = "otra"

    val TODAS = listOf(
        EDUCACION, TECNOLOGIA, SALUD, FINANZAS, COMERCIO, SERVICIOS, INDUSTRIA,
        CONSTRUCCION, TRANSPORTE, TURISMO, MEDIOS, ONG, GOBIERNO, OTRA,
    )

    /** Como se escribe en pantalla. El valor guardado no lleva tildes. */
    fun legible(c: String): String = when (c) {
        EDUCACION -> "Educación"
        TECNOLOGIA -> "Tecnología"
        SALUD -> "Salud"
        FINANZAS -> "Finanzas"
        COMERCIO -> "Comercio"
        SERVICIOS -> "Servicios"
        INDUSTRIA -> "Industria"
        CONSTRUCCION -> "Construcción"
        TRANSPORTE -> "Transporte y logística"
        TURISMO -> "Turismo y hotelería"
        MEDIOS -> "Medios y comunicación"
        ONG -> "Organización sin fines de lucro"
        GOBIERNO -> "Sector público"
        else -> "Otra"
    }
}

/**
 * El tamano de una empresa, en rangos.
 *
 * Rangos y no un numero exacto porque nadie mantiene actualizado un numero
 * exacto, y el rango es lo que de verdad se mira.
 */
object TamanoEmpresa {
    const val UNO = "1"
    const val PEQUENA = "2-10"
    const val MEDIANA = "11-50"
    const val GRANDE = "51-200"
    const val MAYOR = "201-1000"
    const val CORPORACION = "1000+"

    val TODOS = listOf(UNO, PEQUENA, MEDIANA, GRANDE, MAYOR, CORPORACION)
}

/**
 * Deja un texto en condiciones de dibujarse como una etiqueta de la plataforma.
 *
 * ## Por que vive en el contrato y no en la app
 *
 * La limpieza la hace el **servidor** antes de guardar, no el cliente al
 * dibujar. Si la hiciera el cliente, cada app que consuma esta API tendria que
 * acordarse de repetirla, y la que se olvidara mostraria el nombre crudo. Un
 * dato que la plataforma presenta a terceros se limpia una vez, donde se
 * escribe.
 *
 * ## Que quita, y por que cada cosa
 *
 *  - **Controles de direccion** (U+202A..U+202E, isolates, marcas LTR/RTL):
 *    invierten el orden de lo que se ve sin cambiar lo que esta escrito. Con
 *    ellos, `"\u202Eacme@ocnaB lanoicaN"` se dibuja como "Banco Nacional
 *    @acme". En un nombre comercial eso no es un adorno: es la suplantacion
 *    entera.
 *  - **Controles C0/C1 y saltos de linea**: un `\n` en un nombre rompe la
 *    fila de la tarjeta y empuja el resto; los de ancho cero parten palabras
 *    para que un filtro no las reconozca.
 *  - **Espacios repetidos**: se colapsan, porque cuarenta espacios son una
 *    forma barata de empujar fuera de la vista lo que viene detras.
 *
 * Lo que NO hace es normalizar homoglifos (la "B" cirilica que parece latina).
 * Eso no se resuelve filtrando: se resuelve con la verificacion, que es
 * precisamente para lo que existe el distintivo.
 */
fun etiquetaLimpia(texto: String): String =
    texto
        .filterNot { c ->
            c in CONTROLES_DE_DIRECCION ||
                // C0 y C1 menos los espacios normales, que ya se colapsan abajo.
                (c.code in 0x00..0x1F) || (c.code in 0x7F..0x9F) ||
                c == '\u200B' || c == '\uFEFF'
        }
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Los caracteres que reordenan lo que se ve sin cambiar lo que dice.
 *
 * Van escapados y no literales a proposito: son invisibles, asi que en el
 * archivo se verian comillas vacias y en un diff no se veria nada. Un fuente
 * con overrides de direccion escondidos dentro es exactamente el problema que
 * esta lista existe para resolver.
 */
val CONTROLES_DE_DIRECCION = setOf(
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',   // embedding y override
    '\u2066', '\u2067', '\u2068', '\u2069',             // isolates
    '\u200E', '\u200F',                                   // marcas LTR y RTL
)

/** Topes de la ficha. Es un perfil publico: todo lo de aqui lo lee otra gente. */
object TopesEmpresa {
    const val NOMBRE = 80
    const val DESCRIPCION = 600
    const val SITIO = 200
    const val UBICACION = 90
    const val ANIO_MIN = 1800
}

// `RUTA_CUENTA` ya existe en Identidad.kt y vale "/v1/cuenta": estas
// cuelgan de ella a proposito, porque son la misma cuenta.
const val RUTA_TIPO_CUENTA = "/v1/cuenta/tipo"
const val RUTA_EMPRESA = "/v1/cuenta/empresa"

/** Lo que una persona pide para si misma. Solo admite [TipoCuenta.AUTOSERVICIO]. */
@Serializable
data class CambiarTipoReq(val tipo: String)

/** Lo que staff pide para otra persona. Admite los tres. */
@Serializable
data class AsignarTipoReq(val username: String, val tipo: String)

@Serializable
data class FichaEmpresaReq(
    val nombreComercial: String = "",
    val categoria: String = CategoriaEmpresa.OTRA,
    val descripcion: String = "",
    val sitioWeb: String = "",
    val tamano: String = "",
    val ubicacion: String = "",
    val fundadaEn: Int = 0,
)

/**
 * La ficha tal como la ve cualquiera.
 *
 * `verificada` viaja aparte del resto y no se deduce de que la ficha exista:
 * tener ficha es decir quien decis que sos; estar verificada es que alguien lo
 * comprobo. Juntarlos en un solo campo seria convertir lo primero en lo
 * segundo.
 */
@Serializable
data class FichaEmpresa(
    val nombreComercial: String = "",
    val categoria: String = CategoriaEmpresa.OTRA,
    val descripcion: String = "",
    val sitioWeb: String = "",
    val tamano: String = "",
    val ubicacion: String = "",
    val fundadaEn: Int = 0,
    val verificada: Boolean = false,
)

/**
 * Si esta cuenta puede ver las funciones de tipos de cuenta.
 *
 * **La decide el SERVIDOR**, no la app. Mientras el modulo este en pruebas, las
 * rutas responden 404 a quien no este en la lista de la beta; esconder el boton
 * sin cerrar la ruta seria el error que el §16 del brief prohibe explicitamente.
 * El cliente lee esto para no dibujar lo que de todos modos le van a negar.
 */
@Serializable
data class CapacidadesCuenta(
    val tipo: String = TipoCuenta.NORMAL,
    val puedeElegirTipo: Boolean = false,
    val esDesarrollador: Boolean = false,
    val empresa: FichaEmpresa? = null,
)
