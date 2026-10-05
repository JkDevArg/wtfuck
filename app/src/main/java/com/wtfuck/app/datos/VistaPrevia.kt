package com.wtfuck.app.datos

/**
 * Las partes puras de la vista previa de enlaces: encontrar el enlace en un
 * texto y sacar titulo, descripcion e imagen del HTML de la pagina.
 *
 * ## Quien arma la vista previa, y por que
 *
 * La arma el telefono que ENVIA y viaja dentro del sobre cifrado, como en
 * Signal. Las otras dos opciones eran peores:
 *
 *  - que la arme quien recibe: abrir un chat le contaria al sitio la IP de
 *    cada persona que lo lee, sin que nadie haya tocado nada;
 *  - que la arme nuestro servidor, como los GIFs: el servidor veria que
 *    enlaces se mandan DENTRO de conversaciones cifradas. Con los GIFs se
 *    acepto que viera busquedas; un enlace dice mucho mas de una charla.
 *
 * El costo de esta, que se dice en el ajuste: el sitio ve la IP de quien
 * envia, igual que si lo abriera. Se puede apagar.
 *
 * Sin dependencias: se lee con expresiones, no con un parser de HTML. Se
 * busca un punado de etiquetas en la cabecera, y meter una libreria entera
 * por eso es superficie de ataque sobre contenido que viene de cualquier sitio.
 */
object VistaPreviaHtml {

    data class Metadatos(
        val titulo: String = "",
        val descripcion: String = "",
        val sitio: String = "",
        /** Absoluta, ya resuelta contra la URL de la pagina. */
        val imagen: String = "",
    )

    /**
     * Los enlaces del texto, con su posicion. Solo http y https: un
     * `javascript:` o un `file:` no son enlaces que alguien quiera abrir.
     * Se recorta la puntuacion final -"mira https://a.com."- que casi nunca
     * es parte de la direccion.
     */
    fun enlacesEn(texto: String): List<Pair<IntRange, String>> {
        val out = mutableListOf<Pair<IntRange, String>>()
        for (m in PATRON.findAll(texto)) {
            var url = m.value
            while (url.isNotEmpty() && url.last() in ".,;:!?)]}»\"'") url = url.dropLast(1)
            if (url.length > "https://".length) out += (m.range.first until m.range.first + url.length) to url
        }
        return out
    }

    fun primerEnlace(texto: String): String? = enlacesEn(texto).firstOrNull()?.second

    private val PATRON = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** Lee los metadatos de la cabecera. Prefiere Open Graph; si no, `<title>`. */
    fun extraer(html: String, urlPagina: String): Metadatos {
        // Solo la cabecera: los metadatos estan ahi, y mirar una pagina entera
        // con expresiones es lento y le da a una pagina rara mas formas de
        // confundir al lector.
        val cabeza = html.substringBefore("</head>", html.take(200_000)).take(200_000)
        fun meta(vararg nombres: String): String {
            for (n in nombres) {
                val v = valorMeta(cabeza, n)
                if (v.isNotBlank()) return v
            }
            return ""
        }
        val titulo = meta("og:title", "twitter:title").ifBlank {
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(cabeza)?.groupValues?.get(1).orEmpty()
        }
        val descripcion = meta("og:description", "twitter:description", "description")
        val sitio = meta("og:site_name").ifBlank { dominio(urlPagina) }
        val imagen = meta("og:image", "og:image:url", "twitter:image")
        return Metadatos(
            titulo = limpiar(titulo).take(200),
            descripcion = limpiar(descripcion).take(300),
            sitio = limpiar(sitio).take(80),
            imagen = if (imagen.isBlank()) "" else resolver(urlPagina, decodificar(imagen.trim())),
        )
    }

    /**
     * El `content` de un `<meta>` por su `property` o `name`, en cualquier
     * orden de atributos y con cualquier comilla.
     */
    private fun valorMeta(html: String, nombre: String): String {
        val etiquetas = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
        for (e in etiquetas) {
            val t = e.value
            val clave = atributo(t, "property").ifBlank { atributo(t, "name") }
            if (clave.equals(nombre, ignoreCase = true)) return atributo(t, "content")
        }
        return ""
    }

    private fun atributo(etiqueta: String, nombre: String): String {
        val r = Regex("""\b$nombre\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        val m = r.find(etiqueta) ?: return ""
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
    }

    /** Entidades HTML, espacios repetidos y saltos de linea fuera. */
    fun limpiar(s: String): String =
        decodificar(s).replace(Regex("\\s+"), " ").trim()

    fun decodificar(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val c = m.groupValues[1]
            when {
                c.startsWith("#x") || c.startsWith("#X") ->
                    c.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                c.startsWith("#") -> c.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> when (c.lowercase()) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                    "nbsp" -> " "; "ntilde" -> "ñ"; "aacute" -> "á"; "eacute" -> "é"
                    "iacute" -> "í"; "oacute" -> "ó"; "uacute" -> "ú"
                    else -> m.value
                }
            }
        }
    }

    /** El dominio, sin `www.`. Es lo que se le muestra a la persona para saber a donde va. */
    fun dominio(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfter('@').substringBefore(':')
            .removePrefix("www.").lowercase()

    /** Resuelve una ruta relativa de imagen contra la pagina. */
    fun resolver(base: String, ruta: String): String = when {
        ruta.startsWith("http://") || ruta.startsWith("https://") -> ruta
        ruta.startsWith("//") -> base.substringBefore(':') + ":" + ruta
        ruta.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ruta
        else -> base.substringBeforeLast('/') + "/" + ruta
    }
}
