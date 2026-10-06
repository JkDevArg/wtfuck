package com.wtfuck.app.datos

/**
 * Las partes puras de las listas de difusion: el tope y como se cuenta lo que
 * paso con un envio. Ver `DifusionEnt`.
 */
object Difusiones {

    /**
     * Tope de destinatarios. El servidor deja 30 mensajes por minuto: una
     * lista de 100 tarda unos cuatro minutos en salir entera -la cola espera
     * sola lo que el servidor pide-, y mas que eso ya es un canal.
     */
    const val MAX_MIEMBROS = 100

    data class Conteo(
        val total: Int,
        val pendientes: Int,
        val entregados: Int,
        val leidos: Int,
        val fallidos: Int,
    )

    /**
     * [estados]: el estado de cada mensaje que salio (`EstadoEnvio`). Leido
     * cuenta tambien como entregado: no se lee lo que no llego.
     */
    fun contar(estados: List<String>, total: Int): Conteo = Conteo(
        total = total,
        pendientes = estados.count { it == "PENDIENTE" },
        entregados = estados.count { it == "ENTREGADO" || it == "LEIDO" },
        leidos = estados.count { it == "LEIDO" },
        fallidos = estados.count { it == "FALLIDO" },
    )

    fun resumen(c: Conteo): String = buildString {
        if (c.pendientes > 0) append("Saliendo: ${c.total - c.pendientes} de ${c.total}")
        else append("Entregado a ${c.entregados} de ${c.total}")
        // Lo leido se dice aunque algo siga saliendo: uno atascado no tiene
        // por que tapar que los demas ya lo vieron.
        if (c.leidos > 0) append(" · leído por ${c.leidos}")
        if (c.fallidos > 0) append(" · ${c.fallidos} no ${if (c.fallidos == 1) "salió" else "salieron"}")
    }

    /**
     * La espera que pide un 429 ("Intenta de nuevo en 37 segundos."), o un
     * minuto si no la dice. Acotada: ni reintentar en el acto ni dormir una
     * hora por un texto raro.
     */
    fun esperaDe(mensaje: String?): Long =
        (Regex("""(\d+)\s*segundos""").find(mensaje.orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 60)
            .coerceIn(1, 15 * 60)
}
