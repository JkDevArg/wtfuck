package com.wtfuck.app

import com.wtfuck.app.contenido.restante
import com.wtfuck.app.contenido.seguraViva
import com.wtfuck.app.datos.restanteCorto
import com.wtfuck.app.ui.etiquetaDuracion
import com.wtfuck.app.ui.haceCuanto
import com.wtfuck.protocol.Carga
import com.wtfuck.protocol.DuracionUbicacion
import org.junit.Assert.*
import org.junit.Test

/**
 * Módulo AM · Compartir la ubicación en tiempo real.
 *
 * ## Qué se prueba aquí y por qué no en el servidor
 *
 * Porque el servidor **no participa**. Las actualizaciones son sobres
 * cifrados como cualquier mensaje y el vencimiento viaja dentro de la carga:
 * no hay tabla de compartidos, ni caducidad en la base, ni nada que el
 * servidor pueda consultar. Lo único que cambió allí fue agregar dos nombres
 * a la lista de clases válidas.
 *
 * O sea que **toda la lógica de caducar está en el cliente, en los dos lados**,
 * y es exactamente lo que estas pruebas fijan. El reloj se inyecta porque el
 * único comportamiento que de verdad se rompe aquí es el cruce del
 * vencimiento, y con el reloj del sistema eso no se puede provocar.
 */
class UbicacionVivaTest {

    private fun carga(hasta: Long, seq: Int = 0) = Carga.UbicacionEnVivo(
        mensajeId = "m1", lat = -12.0464, lon = -77.0428,
        precisionM = 8, hasta = hasta, secuencia = seq,
    )

    // ----------------------------------------------------------- caducar

    @Test
    fun `mientras no venza, esta en vivo`() {
        val v = seguraViva(carga(hasta = 10_000), ahora = 0)
        assertTrue(v.enVivo)
        // Diez segundos redondean hacia arriba a UN minuto, y uno es singular.
        assertEquals("queda 1 min", v.queda)
    }

    /**
     * El caso que sostiene el diseño entero.
     *
     * Si el teléfono que comparte se queda sin batería, nadie manda el final.
     * Sin esto, la otra pantalla mostraría una posición de hace horas **como
     * si fuera de ahora** — que es la única forma en que esta función puede
     * hacer daño de verdad.
     */
    @Test
    fun `vencida es vencida aunque nadie haya avisado`() {
        val v = seguraViva(carga(hasta = 1_000), ahora = 2_000)
        assertFalse(v.enVivo)
        assertEquals("", v.queda)
    }

    /** Justo en el límite todavía no venció... */
    @Test
    fun `en el instante exacto del vencimiento ya no esta en vivo`() {
        assertFalse(seguraViva(carga(hasta = 5_000), ahora = 5_000).enVivo)
        assertTrue(seguraViva(carga(hasta = 5_001), ahora = 5_000).enVivo)
    }

    /**
     * `hasta = 0` es el sello de "se cortó a mano".
     *
     * Cero y no "ahora": una actualización que venía en camino puede llegar
     * después del final, y con `hasta = ahora` bastaría un reloj un segundo
     * atrasado para revivirla. Cero no es una fecha, es un estado.
     */
    @Test
    fun `cortada a mano no revive con ningun reloj`() {
        for (ahora in listOf(0L, 1L, Long.MAX_VALUE / 2)) {
            assertFalse("revivio con ahora=$ahora", seguraViva(carga(hasta = 0), ahora).enVivo)
        }
    }

    /** Y el aviso explícito la apaga aunque la fecha siga en pie. */
    @Test
    fun `terminada explicitamente no esta en vivo aunque falte tiempo`() {
        val v = seguraViva(carga(hasta = 999_999), ahora = 0, terminada = true)
        assertFalse(v.enVivo)
    }

    /** Coordenadas rotas no producen un `geo:` que otra app tendría que tragar. */
    @Test
    fun `sin coordenadas validas no hay mapa`() {
        val rota = Carga.UbicacionEnVivo(
            mensajeId = "m", lat = Double.NaN, lon = 0.0, hasta = 999_999,
        )
        assertNull(seguraViva(rota, ahora = 0).geoUri)
    }

    // -------------------------------------------------------- las palabras

    /**
     * Se redondea hacia ARRIBA.
     *
     * "queda 1 min" con cincuenta segundos por delante es mejor que "quedan
     * 0 min" con los mismos cincuenta: el cero dice que ya terminó y todavía
     * no.
     */
    @Test
    fun `el tiempo restante se redondea hacia arriba`() {
        assertEquals("queda 1 min", restante(1))
        assertEquals("queda 1 min", restante(60_000))
        assertEquals("quedan 2 min", restante(60_001))
    }

    @Test
    fun `de una hora en adelante se dicen horas`() {
        assertEquals("queda 1 h", restante(60 * 60_000L))
        assertEquals("quedan 2 h", restante(120 * 60_000L))
        assertEquals("quedan 7 h 43 min", restante((7 * 60 + 43) * 60_000L))
    }

    @Test
    fun `sin tiempo no se dice nada, en vez de un cero`() {
        assertEquals("", restante(0))
        assertEquals("", restante(-1))
    }

    /** El de la notificación es más corto: ahí sólo hay una línea. */
    @Test
    fun `el texto de la notificacion es mas corto que el de la burbuja`() {
        assertEquals("15 min", restanteCorto(15 * 60_000L))
        assertEquals("8 h", restanteCorto(8 * 60 * 60_000L))
        assertTrue(restanteCorto(90 * 60_000L).length < restante(90 * 60_000L).length)
    }

    /**
     * Por debajo del minuto no se dicen segundos.
     *
     * La burbuja late cada treinta segundos, así que con segundos a la vista
     * se vería saltar de 12 a 42 y parecería rota.
     */
    @Test
    fun `hace cuanto no muestra segundos`() {
        assertEquals("hace un momento", haceCuanto(0))
        assertEquals("hace un momento", haceCuanto(59_000))
        assertEquals("hace 1 min", haceCuanto(60_000))
        assertEquals("hace 20 min", haceCuanto(20 * 60_000L))
        assertEquals("hace 3 h", haceCuanto(3 * 60 * 60_000L))
    }

    // ---------------------------------------------------------- duraciones

    /** Las seis que se ofrecen, y en ese orden. */
    @Test
    fun `las duraciones son las seis declaradas, en orden`() {
        assertEquals(
            listOf(15L, 30L, 60L, 480L, 720L, 1440L),
            DuracionUbicacion.OPCIONES.map { it / 60_000 },
        )
    }

    /**
     * Veinticuatro horas es el techo a propósito.
     *
     * Más que eso deja de ser "comparte mientras llego" y pasa a ser
     * seguimiento, que es otra cosa.
     */
    @Test
    fun `no se puede compartir mas de un dia`() {
        assertTrue(DuracionUbicacion.OPCIONES.all { it <= DuracionUbicacion.HORAS_24 })
        assertFalse(DuracionUbicacion.valida(48 * 60 * 60_000L))
        assertFalse(DuracionUbicacion.valida(0))
        assertTrue(DuracionUbicacion.valida(DuracionUbicacion.MIN_15))
    }

    /** Y cada una cabe en un chip. */
    @Test
    fun `cada duracion tiene una etiqueta corta`() {
        val etiquetas = DuracionUbicacion.OPCIONES.map { etiquetaDuracion(it) }
        assertEquals(listOf("15 min", "30 min", "1 h", "8 h", "12 h", "24 h"), etiquetas)
        assertTrue("alguna etiqueta es larga", etiquetas.all { it.length <= 7 })
    }
}
