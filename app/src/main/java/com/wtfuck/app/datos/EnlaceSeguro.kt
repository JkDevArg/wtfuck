package com.wtfuck.app.datos

/**
 * Lo que se le muestra a la persona antes de abrir un enlace del chat.
 *
 * ## Por que se pregunta antes de abrir
 *
 * Un enlace de un mensaje lo escribio otra persona, y abrirlo saca a quien lo
 * toca de la app: el sitio ve su IP, puede ser una pagina que imita a su banco
 * y un toque sin querer -al hacer scroll- basta. Preguntar cuesta un toque y
 * deja ver A DONDE va antes de ir.
 *
 * ## Que se mira
 *
 * Lo que el texto de una URL puede disimular:
 *
 *  - **El sitio real.** En `https://banco.com@malo.com/` el sitio es
 *    `malo.com`: lo de antes de la `@` son "credenciales" que el navegador
 *    ignora. Se muestra el host de verdad, no el principio del texto.
 *  - **Letras que parecen otras.** `xn--` (punycode) o letras de otro
 *    alfabeto: `аpple.com` con una "а" cirilica no es apple.com.
 *  - **Sin cifrado.** `http://` viaja en claro.
 *
 * Puro, para probarlo sin telefono.
 */
object EnlaceSeguro {

    data class Analisis(
        /** El sitio de verdad, en minusculas y legible (punycode decodificado). */
        val sitio: String,
        val cifrado: Boolean,
        /** Hay algo antes de una `@` en la direccion: esconde el sitio real. */
        val disfrazado: Boolean,
        /** Punycode o letras fuera del ASCII: puede imitar a otro sitio. */
        val letrasRaras: Boolean,
    ) {
        val avisos: List<String>
            get() = buildList {
                if (disfrazado) add("La dirección esconde el sitio real detrás de una @. El sitio de verdad es el de arriba.")
                if (letrasRaras) add("El nombre del sitio usa letras de otros alfabetos: puede estar imitando a otro.")
                if (!cifrado) add("Sin cifrado (http): lo que hagas en ese sitio viaja a la vista.")
            }
    }

    private val ESQUEMA = Regex("^(https?)://", RegexOption.IGNORE_CASE)

    /** null si no es http/https: eso no se abre desde un mensaje. */
    fun analizar(url: String): Analisis? {
        val esquema = ESQUEMA.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        val resto = url.substring(ESQUEMA.find(url)!!.range.last + 1)
        // La autoridad termina en la primera / ? o #.
        val autoridad = resto.takeWhile { it != '/' && it != '?' && it != '#' }
        val disfrazado = '@' in autoridad
        // El host es lo que va DESPUES de la ultima @, sin el puerto.
        var host = autoridad.substringAfterLast('@')
        host = if (host.startsWith("[")) host.substringBefore("]") + "]" else host.substringBefore(':')
        host = host.trimEnd('.').lowercase()
        if (host.isBlank()) return null
        val legible = runCatching { java.net.IDN.toUnicode(host) }.getOrDefault(host)
        val letrasRaras = "xn--" in host || host.any { it.code > 0x7F }
        return Analisis(
            sitio = legible,
            cifrado = esquema == "https",
            disfrazado = disfrazado,
            letrasRaras = letrasRaras,
        )
    }
}
