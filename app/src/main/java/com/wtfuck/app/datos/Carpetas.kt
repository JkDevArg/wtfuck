package com.wtfuck.app.datos

/**
 * Carpetas de chats: "Trabajo", "Familia", lo que cada quien quiera.
 *
 * ## Solo en este telefono
 *
 * Telegram las sincroniza por su servidor. Aqui no, a proposito: el nombre de
 * una carpeta y quien esta en ella dicen mucho de con quien habla alguien y
 * para que, y es justo lo que el servidor no tiene por que saber. El costo es
 * que en otro aparato vinculado hay que armarlas de nuevo.
 *
 * Esto es la parte pura: que nombres sirven. Las tablas estan en `BaseLocal`
 * y la pantalla en `ui/Carpetas.kt`.
 */
object Carpetas {

    /** Mas que esto ya no se distinguen en la barra de pestañas. */
    const val MAX = 10
    const val MAX_NOMBRE = 20

    /**
     * Por que no sirve un nombre, o null si sirve.
     *
     * [propio] es el nombre actual de la carpeta que se esta renombrando: no
     * choca consigo misma.
     */
    fun problemaCon(nombre: String, existentes: List<String>, propio: String? = null): String? {
        val n = nombre.trim()
        return when {
            n.isEmpty() -> "Ponle un nombre."
            n.length > MAX_NOMBRE -> "Hasta $MAX_NOMBRE caracteres."
            // Las tres de siempre ya tienen pestaña: una carpeta que se llame
            // igual dejaria dos "Grupos" en la barra.
            n.lowercase() in RESERVADOS -> "Ese nombre ya lo usa una pestaña."
            existentes.any { it.equals(n, ignoreCase = true) && !it.equals(propio, ignoreCase = true) } ->
                "Ya tienes una carpeta con ese nombre."
            else -> null
        }
    }

    fun caben(cuantas: Int): Boolean = cuantas < MAX

    private val RESERVADOS = setOf("todos", "no leidos", "no leídos", "grupos")
}
