package com.wtfuck.app.datos

/**
 * El enlace de contacto, del lado del telefono: como se escribe y como se lee.
 *
 * El enlace es `<servidor>/c/<codigo>`: una URL normal para que cualquier
 * camara la abra y cualquier app la pueda mandar. Si se toca fuera de la app
 * cae en una pagina del servidor con un boton que abre `wtfuck://c/<codigo>`.
 * Ver `EnlaceContacto` en el servidor.
 *
 * Aparte y puro para poder probar lo que importa: que de un QR cualquiera -que
 * puede traer lo que sea, de quien sea- solo salga un codigo con la forma
 * exacta, o nada.
 */
object EnlaceDeContacto {

    private val CODIGO = Regex("^[A-Za-z0-9_-]{22}$")

    // `https://host/c/X`, `http://127.0.0.1:8088/c/X` (desarrollo) o
    // `wtfuck://c/X`. Con barra final, consulta o ancla, que algunas apps
    // agregan al compartir.
    private val URL = Regex("""^(?:https?://[^/\s?#]+|wtfuck:/)/c/([A-Za-z0-9_-]{22})/?(?:[?#]\S*)?$""")

    fun url(servidor: String, codigo: String): String = servidor.trimEnd('/') + "/c/" + codigo

    /** El codigo que trae lo escaneado, pegado o abierto; null si no es un enlace de contacto. */
    fun codigoDe(texto: String): String? {
        val t = texto.trim()
        if (CODIGO.matches(t)) return t
        return URL.matchEntire(t)?.groupValues?.get(1)
    }
}
