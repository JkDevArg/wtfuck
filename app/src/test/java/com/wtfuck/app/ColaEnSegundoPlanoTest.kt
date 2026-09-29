package com.wtfuck.app

import com.wtfuck.app.datos.ColaEnSegundoPlano
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La regla del reintento en segundo plano.
 *
 * Es una funcion de una linea y tiene prueba a proposito: la mitad que
 * importa no es "programar cuando quedan mensajes" —eso se nota si falla,
 * porque el mensaje no sale— sino **cancelar cuando no quedan**. Un trabajo
 * que sigue programado sin nada que hacer despierta el telefono para nada, y
 * eso no se nota nunca: se nota semanas despues, en la lista de aplicaciones
 * que mas bateria gastan, y para entonces nadie lo relaciona con esto.
 */
class ColaEnSegundoPlanoTest {

    @Test
    fun `con mensajes pendientes se programa el reintento`() {
        assertEquals(
            ColaEnSegundoPlano.Decision.PROGRAMAR,
            ColaEnSegundoPlano.decidir(quedanPendientes = true),
        )
    }

    @Test
    fun `sin nada pendiente se CANCELA`() {
        // La mitad que no se nota si falla.
        assertEquals(
            ColaEnSegundoPlano.Decision.CANCELAR,
            ColaEnSegundoPlano.decidir(quedanPendientes = false),
        )
    }

    @Test
    fun `la decision solo depende de si queda algo`() {
        // Sin estado oculto: llamarla dos veces con lo mismo da lo mismo. Si
        // algun dia dependiera de algo mas -la hora, un contador de intentos-
        // esta prueba obligaria a pensarlo antes de meterlo.
        repeat(5) {
            assertEquals(
                ColaEnSegundoPlano.Decision.PROGRAMAR,
                ColaEnSegundoPlano.decidir(true),
            )
            assertEquals(
                ColaEnSegundoPlano.Decision.CANCELAR,
                ColaEnSegundoPlano.decidir(false),
            )
        }
    }
}
