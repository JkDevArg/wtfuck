package com.wtfuck.app.datos

/**
 * Las reglas puras del envio por el modo cerca: que mensaje puede salir hacia
 * quien esta enfrente, y la lista de aparatos que ya lo recibieron asi.
 *
 * Aparte para probarlas sin radio. Ver `Repositorio.despacharPorCerca`.
 */
object EnvioCerca {

    /**
     * Si un mensaje de esta conversacion puede salir hacia quien esta enfrente.
     *
     *  - **Mi otro aparato:** todo. Es una copia mas de mi propio historial.
     *  - **Una directa:** solo la que es con esa persona.
     *  - **Un grupo:** si esa persona es del grupo. Le llega a ella sola; al
     *    resto, cuando vuelva la red.
     *  - **Canales y lo demas:** no. Un canal lo reparte el servidor.
     */
    fun va(
        tipo: String,
        nombre: String,
        participantes: String,
        miUsuarioId: String,
        parUsuarioId: String,
        parUsername: String,
    ): Boolean = when {
        parUsuarioId.isNotBlank() && parUsuarioId == miUsuarioId -> true
        tipo == "directa" -> nombre.equals(parUsername, ignoreCase = true)
        tipo == "grupo" -> participantes.split(',').any { it.trim().equals(parUsername, ignoreCase = true) }
        else -> false
    }

    /**
     * Solo texto, por ahora. Un adjunto vive en el almacen del servidor: lo
     * que viaja en el sobre es su llave, y sin red quien lo recibe no podria
     * bajar el archivo. Saldra entero cuando vuelva la red.
     */
    fun esTexto(adjuntoId: String?, adjuntoClase: String, especialJson: String): Boolean =
        adjuntoId == null && adjuntoClase.isBlank() && especialJson.isBlank()

    /** Los aparatos que ya lo recibieron por cerca, separados por coma. */
    fun tiene(lista: String, dispositivo: String): Boolean =
        lista.split(',').any { it == dispositivo }

    fun con(lista: String, dispositivo: String): String =
        if (tiene(lista, dispositivo)) lista
        else lista.split(',').filter { it.isNotBlank() }.plus(dispositivo).joinToString(",")
}
