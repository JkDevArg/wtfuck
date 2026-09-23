package com.wtfuck.app

import com.wtfuck.app.datos.EsperaBloqueo
import com.wtfuck.app.datos.debeBloquear
import org.junit.Assert.*
import org.junit.Test

/**
 * Cuándo se cierra la puerta.
 *
 * ## Por qué esto se prueba y no se mira a ojo
 *
 * Un bloqueo se comprueba probándolo: se sale de la app, se vuelve, y o pide
 * la huella o no. Lo que **no** se puede comprobar así son los casos en que
 * falla abriéndose, que son los únicos que importan: un reinicio, un reloj
 * movido, un valor guardado que no existe. Cada uno de ellos deja la app
 * abierta a quien tenga el teléfono, y ninguno se ve usándola normalmente.
 *
 * El minuto es `elapsedRealtime`, que cuenta desde el arranque del sistema.
 */
class BloqueoTest {

    private val min = 60_000L

    /**
     * Un momento cualquiera del encendido, para usar como "último desbloqueo".
     *
     * **No se usa `0L`**, y no es un detalle: la función trata el cero como
     * "nunca se desbloqueó", así que pasarlo como si fuera un instante hace que
     * las pruebas midan otra cosa. Lo aprendí escribiéndolas mal: tres se
     * cayeron señalando esta misma línea.
     */
    private val base = 10 * min

    // ============================================================
    //  Lo normal
    // ============================================================

    @Test
    fun `desactivado no bloquea nunca`() {
        // Ni siquiera con el contador en cero, que es el caso "recién
        // instalado": quien no activó el bloqueo no puede encontrarse con una
        // pantalla de huella que no pidió.
        assertFalse(debeBloquear(EsperaBloqueo.NUNCA, 0L, 0L))
        assertFalse(debeBloquear(EsperaBloqueo.NUNCA, 1000L, 99_999_999L))
    }

    @Test
    fun `inmediato bloquea aunque acabe de desbloquearse`() {
        // "Al salir de la app" significa al salir, no "al salir y esperar".
        assertTrue(debeBloquear(EsperaBloqueo.INMEDIATO, 1000L, 1001L))
    }

    @Test
    fun `dentro de la espera no bloquea`() {
        // Volver a los treinta segundos con la espera en cinco minutos: la
        // gente sale a mirar una notificación y vuelve.
        assertFalse(debeBloquear(EsperaBloqueo.CINCO, 10 * min, 10 * min + 30_000L))
    }

    @Test
    fun `justo al cumplirse la espera, bloquea`() {
        // El límite es inclusivo. A los cinco minutos exactos ya pasaron cinco
        // minutos, y un `>` en vez de `>=` deja un hueco de un milisegundo que
        // nadie iba a notar, pero que convierte la prueba en un adorno.
        assertTrue(debeBloquear(EsperaBloqueo.CINCO, base, base + 5 * min))
    }

    @Test
    fun `un segundo antes, no`() {
        assertFalse(debeBloquear(EsperaBloqueo.CINCO, base, base + 5 * min - 1000L))
    }

    @Test
    fun `cada espera usa sus propios minutos`() {
        val fuera = base + 2 * min
        assertTrue("1 minuto ya pasó", debeBloquear(EsperaBloqueo.UN_MINUTO, base, fuera))
        assertFalse("5 minutos no", debeBloquear(EsperaBloqueo.CINCO, base, fuera))
        assertFalse("15 minutos tampoco", debeBloquear(EsperaBloqueo.QUINCE, base, fuera))
    }

    @Test
    fun `el cero significa NUNCA, no el instante cero`() {
        // Es la ambigüedad que destaparon las pruebas mal escritas. `0` como
        // marca de tiempo sería "el milisegundo en que arrancó el sistema", y
        // como valor guardado es "no hay nada guardado". Se resuelve del lado
        // seguro: el cero bloquea.
        //
        // El precio es que alguien que desbloqueara la app en el primer
        // milisegundo tras encender el teléfono tendría que hacerlo dos veces.
        assertTrue(debeBloquear(EsperaBloqueo.QUINCE, 0L, 1L))
    }

    // ============================================================
    //  Los casos en que fallaría ABRIÉNDOSE
    // ============================================================

    @Test
    fun `sin desbloqueo previo, bloquea`() {
        // Primera vez tras activarlo, o tras un arranque en frío de la app.
        // Si el cero se tratara como "hace muchísimo" en lugar de como "nunca",
        // daría igual; si se tratara como "recién", la app abriría sola.
        assertTrue(debeBloquear(EsperaBloqueo.QUINCE, 0L, 99 * min))
        assertTrue(debeBloquear(EsperaBloqueo.QUINCE, -5L, 99 * min))
    }

    @Test
    fun `tras un reinicio del telefono, bloquea`() {
        // LA prueba del archivo. `elapsedRealtime` vuelve a cero al arrancar,
        // así que lo guardado antes del reinicio queda EN EL FUTURO. Una resta
        // ingenua da un número negativo, negativo no es `>= 15 minutos`, y la
        // app se abriría sola justo después de un reinicio —que es cuando
        // menos motivos hay para suponer que es la misma persona—.
        val antesDelReinicio = 8 * 60 * min   // ocho horas de encendido
        val despues = 30_000L                 // treinta segundos de arranque
        assertTrue(debeBloquear(EsperaBloqueo.QUINCE, antesDelReinicio, despues))
        assertTrue(debeBloquear(EsperaBloqueo.UN_MINUTO, antesDelReinicio, despues))
    }

    @Test
    fun `una vez cerrada, el tiempo solo la mantiene cerrada`() {
        // La propiedad que hace que mover el reloj no sirva de nada: la
        // función es monótona en `ahora`. Si bloquea en un instante, bloquea
        // en todos los posteriores. Un `abs()` mal puesto en la resta —o
        // cualquier intento de "arreglar" el caso del reinicio restando al
        // revés— rompe esto, y el síntoma sería una app que se abre sola
        // pasado un rato.
        val espera = EsperaBloqueo.CINCO
        var yaBloqueo = false
        for (t in base until base + 20 * min step 30_000L) {
            val ahora = debeBloquear(espera, base, t)
            if (yaBloqueo) assertTrue("se reabrió en t=$t", ahora)
            if (ahora) yaBloqueo = true
        }
        assertTrue("nunca llegó a bloquear en 20 minutos", yaBloqueo)
    }

    // ============================================================
    //  La etiqueta
    // ============================================================

    @Test
    fun `solo NUNCA esta inactivo`() {
        // `activo` decide si se dibuja la pantalla de bloqueo. Una espera nueva
        // que cayera del lado equivocado dejaría un ajuste que se puede elegir
        // y no hace nada.
        EsperaBloqueo.entries.forEach {
            assertEquals("$it", it != EsperaBloqueo.NUNCA, it.activo)
        }
    }

    @Test
    fun `toda espera activa tiene minutos no negativos`() {
        EsperaBloqueo.entries.filter { it.activo }.forEach {
            assertTrue("$it tiene ${it.minutos}", it.minutos >= 0)
        }
    }

    @Test
    fun `las esperas van de menor a mayor`() {
        // El orden de la lista es el orden del desplegable, y uno desordenado
        // se lee como un error de la app.
        val activas = EsperaBloqueo.entries.filter { it.activo }.map { it.minutos }
        assertEquals(activas.sorted(), activas)
    }
}
