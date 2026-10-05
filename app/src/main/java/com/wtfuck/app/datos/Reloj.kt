package com.wtfuck.app.datos

import kotlin.math.abs

/**
 * La hora de este telefono, corregida contra la del servidor.
 *
 * ## Para que
 *
 * Un mensaje se fecha con la hora de AUTORIA, y tiene que ser asi: es lo que
 * permite que uno escrito sin red a las 8 y entregado a las 18 se muestre de
 * las 8 (msg off). Pero entonces la fecha la pone el reloj de quien escribe, y
 * un telefono con la hora quince minutos atrasada metia sus respuestas quince
 * minutos arriba en el chat del otro, entre mensajes ya leidos. Se vio en dos
 * emuladores, con uno atrasado a proposito.
 *
 * El servidor no puede arreglarlo solo: desde alli, "reloj atrasado" y
 * "escrito sin red hace un rato" son el mismo numero. El telefono SI puede,
 * porque conoce su desfase: cada respuesta del servidor trae su hora
 * (`X-Hora`), y cada mensaje aceptado tambien (`Aceptado.servidorEn`).
 *
 * ## Por que se ignoran los desfases chicos
 *
 * La medida incluye medio viaje de red. Corregir por 300 ms de latencia
 * moveria las horas de todos los telefonos bien puestos para no arreglar
 * nada; el problema que se ataca son minutos. Por debajo de [UMBRAL_MS] el
 * reloj local se da por bueno.
 *
 * Sin ninguna medida todavia -primer arranque sin red-, el desfase es cero:
 * es lo mismo que pasaba antes, y nunca peor.
 */
object Reloj {

    const val UMBRAL_MS = 2_000L

    @Volatile private var desfase = 0L

    /** La hora corregida. Es la que se usa para fechar un mensaje propio. */
    fun ahora(): Long = System.currentTimeMillis() + desfase

    /** El desfase vigente, para mostrarlo o probarlo. */
    fun desfaseMs(): Long = desfase

    /**
     * Anota una hora del servidor recien leida.
     *
     * @param local la hora local en el momento de leerla; inyectable para
     *   las pruebas.
     */
    fun observar(servidor: Long, local: Long = System.currentTimeMillis()) {
        // Una hora imposible no borra la medida buena que hubiera.
        if (servidor <= 0) return
        desfase = desfaseDe(servidor, local)
    }

    /** La regla, sin estado. */
    fun desfaseDe(servidor: Long, local: Long): Long {
        // Una hora del servidor imposible -cero, negativa- no se aplica: una
        // cabecera rota no puede mover todos los mensajes.
        if (servidor <= 0) return 0
        val d = servidor - local
        return if (abs(d) < UMBRAL_MS) 0 else d
    }
}
