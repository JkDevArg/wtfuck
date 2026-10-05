package com.wtfuck.app.datos

/**
 * El formato de texto de un mensaje: `*negrita*`, `_cursiva_`, `~tachado~`,
 * `` `monoespaciado` `` y `||spoiler||`.
 *
 * ## Por que viaja como texto y no como algo aparte
 *
 * Porque las marcas SON el formato: el mensaje se manda tal cual se escribio
 * y quien lo recibe lo dibuja. No hace falta tocar el protocolo, un cliente
 * viejo muestra los asteriscos -que se leen igual- y el texto exportado de una
 * conversacion conserva lo que la persona escribio. Es como lo hace WhatsApp.
 *
 * ## Las reglas, que son las que evitan sorpresas
 *
 *  - Una marca solo ABRE si delante no hay letra ni numero: `2*3*4` es una
 *    cuenta y `juan_perez_x` es un usuario, no cursiva.
 *  - Solo CIERRA si detras no hay letra ni numero, y si lo de dentro no
 *    empieza ni termina con un espacio: `* algo *` no es negrita.
 *  - No cruza saltos de linea: un asterisco suelto al final de un parrafo no
 *    convierte en negrita lo que venga tres lineas despues.
 *  - Lo monoespaciado no se vuelve a formatear por dentro: es para pegar
 *    codigo, y en el codigo los asteriscos son asteriscos.
 *
 * Funcion pura a proposito: la burbuja la convierte en estilos de Compose, y
 * la regla se prueba sin pantalla.
 */
enum class Estilo { NEGRITA, CURSIVA, TACHADO, MONO, SPOILER }

/** Un trozo de texto, ya sin las marcas, con los estilos que le tocan. */
data class Tramo(val texto: String, val estilos: Set<Estilo> = emptySet())

object Formato {

    /** En orden de busqueda: `||` antes que cualquier marca de un caracter. */
    private val MARCAS = listOf(
        "||" to Estilo.SPOILER,
        "`" to Estilo.MONO,
        "*" to Estilo.NEGRITA,
        "_" to Estilo.CURSIVA,
        "~" to Estilo.TACHADO,
    )

    /** Si hay alguna marca. El caso comun es que no: atajo para la lista. */
    fun tieneMarcas(texto: String): Boolean =
        texto.any { it == '*' || it == '_' || it == '~' || it == '`' || it == '|' }

    fun tramos(texto: String): List<Tramo> {
        if (!tieneMarcas(texto)) return listOf(Tramo(texto))
        return fusionar(analizar(texto, emptySet()))
    }

    /**
     * El texto sin las marcas. Para la lista de chats y las citas, donde no
     * hay formato: los asteriscos ahi solo ensucian.
     *
     * Un spoiler se TAPA aqui tambien: si la lista lo mostrara, el spoiler no
     * serviria para nada. Es lo que hace Telegram.
     */
    fun plano(texto: String, spoiler: String = "▒▒▒"): String =
        tramos(texto).joinToString("") { if (Estilo.SPOILER in it.estilos) spoiler else it.texto }

    private fun abreAqui(t: String, i: Int): Boolean = i == 0 || !t[i - 1].isLetterOrDigit()

    /**
     * Donde cierra la marca `m` que abrio en `desde`, o -1. No cruza saltos de
     * linea y exige que detras del cierre no haya letra ni numero.
     */
    private fun cierre(t: String, desde: Int, m: String): Int {
        var j = desde
        while (j < t.length) {
            if (t[j] == '\n') return -1
            if (t.startsWith(m, j)) {
                val fin = j + m.length
                val detrasOk = fin >= t.length || !t[fin].isLetterOrDigit()
                if (detrasOk && j > desde) return j
            }
            j++
        }
        return -1
    }

    private fun analizar(t: String, activos: Set<Estilo>): List<Tramo> {
        val out = mutableListOf<Tramo>()
        val suelto = StringBuilder()
        var i = 0
        while (i < t.length) {
            val marca = MARCAS.firstOrNull { (m, _) -> t.startsWith(m, i) }
            if (marca != null && abreAqui(t, i)) {
                val (m, estilo) = marca
                val c = cierre(t, i + m.length, m)
                if (c > 0 && estilo !in activos) {
                    val dentro = t.substring(i + m.length, c)
                    if (dentro.isNotEmpty() && !dentro.first().isWhitespace() && !dentro.last().isWhitespace()) {
                        if (suelto.isNotEmpty()) {
                            out += Tramo(suelto.toString(), activos)
                            suelto.clear()
                        }
                        out += if (estilo == Estilo.MONO) listOf(Tramo(dentro, activos + estilo))
                        else analizar(dentro, activos + estilo)
                        i = c + m.length
                        continue
                    }
                }
            }
            suelto.append(t[i])
            i++
        }
        if (suelto.isNotEmpty()) out += Tramo(suelto.toString(), activos)
        return out
    }

    /** Une tramos seguidos con los mismos estilos: menos piezas que dibujar. */
    private fun fusionar(ts: List<Tramo>): List<Tramo> {
        val out = mutableListOf<Tramo>()
        for (t in ts) {
            val ultimo = out.lastOrNull()
            if (ultimo != null && ultimo.estilos == t.estilos) {
                out[out.lastIndex] = ultimo.copy(texto = ultimo.texto + t.texto)
            } else {
                out += t
            }
        }
        return out
    }
}
