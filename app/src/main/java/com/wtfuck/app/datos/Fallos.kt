package com.wtfuck.app.datos

/**
 * El informe de un cierre inesperado, saneado para que no lleve contenido.
 *
 * ## Por que esto existe
 *
 * La app se reparte fuera de una tienda. Hoy, si se cierra sola en el telefono
 * de alguien, **no queda ningun rastro**: ni traza, ni contador, ni fecha. La
 * persona dice "se me cerro" y ahi acaba la informacion. Con un historial
 * cifrado que solo vive en el telefono, un cierre en el sitio equivocado puede
 * ademas perder cosas, y no habria forma de saber que paso.
 *
 * ## Por que NO es Crashlytics
 *
 * Porque manda las trazas a Google, y esta es una app cuya premisa entera es
 * que nada sale del telefono sin querer. Un mensajero privado que instala
 * telemetria de terceros se contradice a si mismo, y ademas la traza es
 * exactamente el sitio donde se cuela lo que no debe.
 *
 * Asi que el informe se guarda **en el telefono**, y solo sale si la persona
 * decide compartirlo, viendolo antes.
 *
 * ## Que se guarda y que se tacha
 *
 * Las trazas son nombres de clase, metodo y linea: eso no es contenido de
 * nadie. El peligro esta en el **mensaje** de la excepcion, donde si se cuela
 * lo que la aplicacion estaba manejando — un `IllegalArgumentException` al
 * parsear puede traerse el texto entero del mensaje que fallo.
 *
 * Por eso [sanear] recorta y tacha antes de escribir nada. La regla es dejar
 * pasar lo que ayuda a encontrar el fallo y tachar lo que solo puede ser dato:
 * cadenas largas, base64, rutas con nombres de archivo, numeros de telefono,
 * lo que parezca una direccion. Ante la duda, se tacha: un informe algo peor
 * es mucho mejor que un informe que filtra una conversacion.
 */
object Fallos {

    /** Cuantos informes se conservan. Los mas viejos se descartan. */
    const val MAXIMO = 5

    /**
     * Tope de cada mensaje de excepcion.
     *
     * 200 caracteres dan de sobra para "no se pudo abrir el archivo X" y se
     * quedan muy cortos para un mensaje de chat, que es justo la linea que se
     * quiere trazar.
     */
    const val TOPE_MENSAJE = 200

    /** Cuantos marcos de la pila se guardan por excepcion. */
    const val MARCOS = 24

    private const val TACHADO = "[…]"

    /**
     * Cosas que NO deben aparecer en un informe, con lo que se pone en su
     * lugar.
     *
     * El orden importa: lo mas especifico primero, porque el tachado de
     * cadenas largas se comeria a los demas si fuera antes.
     */
    private val REGLAS: List<Pair<Regex, String>> = listOf(
        // Un numero de telefono. Va primero porque es corto y se escaparia.
        Regex("""\+?\d[\d\s\-().]{7,}\d""") to "[telefono]",
        // Base64 largo: claves, cuerpos de sobre, miniaturas. El material mas
        // sensible de la app viaja asi.
        Regex("""[A-Za-z0-9+/]{40,}={0,2}""") to "[base64]",
        // Un correo.
        Regex("""[\w.\-+]+@[\w\-]+\.[\w.\-]+""") to "[correo]",
        // Una ruta de archivo con nombre: el nombre puede ser el del adjunto
        // que alguien mando, y eso ya es contenido.
        Regex("""(/[\w .\-]+){2,}""") to "[ruta]",
        // Cualquier cosa entre comillas de mas de 30: casi siempre es un valor
        // interpolado, y casi nunca hace falta para localizar el fallo.
        Regex(""""[^"]{30,}"""") to "\"$TACHADO\"",
    )

    /**
     * Tacha de un texto lo que pueda ser contenido.
     *
     * Publica porque es la parte que hay que poder probar sola: es la unica
     * pieza de esto que, si falla, falla en silencio y con consecuencias —
     * nadie revisa un informe de fallo buscando datos personales.
     */
    fun sanear(texto: String?): String {
        if (texto.isNullOrBlank()) return ""
        var s: String = texto
        for ((patron, porQue) in REGLAS) s = patron.replace(s, porQue)
        return if (s.length > TOPE_MENSAJE) s.take(TOPE_MENSAJE) + TACHADO else s
    }

    /**
     * Una traza en texto, ya saneada y recortada.
     *
     * Recorre la cadena de causas, que es donde suele estar el fallo de
     * verdad: la de arriba muchas veces es solo el envoltorio.
     */
    fun formatear(e: Throwable, marcos: Int = MARCOS): String {
        val sb = StringBuilder()
        var actual: Throwable? = e
        var nivel = 0
        val vistos = HashSet<Throwable>()
        while (actual != null && nivel < 5 && vistos.add(actual)) {
            if (nivel > 0) sb.append("Causado por: ")
            sb.append(actual.javaClass.name)
            sanear(actual.message).takeIf { it.isNotEmpty() }?.let { sb.append(": ").append(it) }
            sb.append('\n')
            // Los marcos se saneen tambien: un nombre de clase generado puede
            // llevar texto, y no cuesta nada.
            actual.stackTrace.take(marcos).forEach { m ->
                sb.append("    en ").append(sanear(m.toString())).append('\n')
            }
            val restantes = actual.stackTrace.size - marcos
            if (restantes > 0) sb.append("    ... $restantes mas\n")
            actual = actual.cause
            nivel++
        }
        return sb.toString().trimEnd()
    }

    /**
     * El informe completo: cabecera con el aparato y la traza.
     *
     * La version, el modelo y el Android SI van: sin ellos un informe casi no
     * sirve -"se cierra al abrir una foto" en un Android 9 concreto es una
     * pista, sin eso es una anecdota- y ninguno identifica a nadie.
     *
     * Lo que NO va, y hubo que decidirlo: el **username**. Tentaba, porque
     * ayuda a preguntar. Pero entonces el informe deja de ser anonimo y quien
     * lo comparta en un grupo estaria diciendo quien es. Si hace falta saberlo,
     * lo dice la persona al mandarlo.
     */
    fun informe(
        e: Throwable,
        cuando: Long,
        version: String,
        modelo: String,
        android: String,
        hilo: String,
    ): String = buildString {
        append("wtfuck ").append(version).append('\n')
        append("cuando: ").append(java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date(cuando))).append('\n')
        append("aparato: ").append(sanear(modelo)).append(" · Android ").append(android).append('\n')
        append("hilo: ").append(sanear(hilo)).append('\n')
        append('\n')
        append(formatear(e))
    }

    /**
     * Deja solo los [MAXIMO] informes mas nuevos.
     *
     * Se recorta al ESCRIBIR y no al leer, porque leer puede no pasar nunca:
     * quien nunca abre la pantalla de diagnostico acumularia un archivo por
     * cada cierre hasta llenarle el telefono. Es el tipo de fuga que solo
     * aparece en el aparato de quien mas problemas tiene.
     */
    fun recortar(informes: List<String>, maximo: Int = MAXIMO): List<String> =
        informes.takeLast(maximo)
}
