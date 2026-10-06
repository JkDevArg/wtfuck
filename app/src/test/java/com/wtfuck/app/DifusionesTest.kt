package com.wtfuck.app

import com.wtfuck.app.datos.Difusiones
import org.junit.Assert.assertEquals
import org.junit.Test

/** Como se cuenta lo que paso con un envio a una lista, y la espera de un 429. */
class DifusionesTest {

    @Test
    fun `mientras sale, dice cuantos van`() {
        val c = Difusiones.contar(listOf("ENVIADO", "PENDIENTE", "PENDIENTE"), 3)
        assertEquals("Saliendo: 1 de 3", Difusiones.resumen(c))
    }

    @Test
    fun `lo leido se dice aunque otro siga saliendo`() {
        val c = Difusiones.contar(listOf("LEIDO", "PENDIENTE"), 2)
        assertEquals("Saliendo: 1 de 2 · leído por 1", Difusiones.resumen(c))
    }

    @Test
    fun `leido cuenta tambien como entregado`() {
        val c = Difusiones.contar(listOf("LEIDO", "ENTREGADO", "ENVIADO"), 3)
        assertEquals(2, c.entregados)
        assertEquals("Entregado a 2 de 3 · leído por 1", Difusiones.resumen(c))
    }

    @Test
    fun `los que fallaron se dicen aparte, en singular y en plural`() {
        assertEquals(
            "Entregado a 1 de 2 · 1 no salió",
            Difusiones.resumen(Difusiones.contar(listOf("ENTREGADO", "FALLIDO"), 2)),
        )
        assertEquals(
            "Entregado a 0 de 3 · 2 no salieron",
            Difusiones.resumen(Difusiones.contar(listOf("FALLIDO", "FALLIDO", "ENVIADO"), 3)),
        )
    }

    @Test
    fun `un mensaje borrado no cuenta para nada pero el total se mantiene`() {
        // Tres destinatarios, pero de uno ya no queda el mensaje.
        assertEquals("Entregado a 2 de 3", Difusiones.resumen(Difusiones.contar(listOf("ENTREGADO", "ENTREGADO"), 3)))
    }

    @Test
    fun `la espera de un 429 sale del texto del servidor`() {
        assertEquals(37, Difusiones.esperaDe("Vas muy rapido. Intenta de nuevo en 37 segundos."))
    }

    @Test
    fun `sin numero, un minuto, y nunca mas de quince`() {
        assertEquals(60, Difusiones.esperaDe("Vas muy rapido."))
        assertEquals(60, Difusiones.esperaDe(null))
        assertEquals(900, Difusiones.esperaDe("Intenta de nuevo en 99999 segundos."))
        assertEquals(1, Difusiones.esperaDe("Intenta de nuevo en 0 segundos."))
    }
}
