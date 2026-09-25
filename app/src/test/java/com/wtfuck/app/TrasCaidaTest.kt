package com.wtfuck.app

import com.wtfuck.app.datos.Malla
import com.wtfuck.app.datos.TrasCaida
import org.junit.Assert.*
import org.junit.Test

/**
 * Qué hacer cuando se cae una conexión de la llamada.
 *
 * ## Los dos defectos que esto tapa
 *
 * **Uno.** La regla era `if (motores.isEmpty()) colgar()`. En una llamada de
 * dos está bien: si el único motor muere, no hay llamada. En una de grupo hay
 * motores que **nunca conectaron** —uno por cada persona que todavía suena,
 * creados al cerrar la malla—, así que el mapa no se vaciaba nunca. Un motor
 * que existe no es una conversación.
 *
 * **Dos.** WebRTC avisa `DISCONNECTED` antes de `FAILED`, y ese aviso no lo
 * escuchaba nadie: el motor sólo reportaba `CONNECTED` y `FAILED`. Se vio en
 * un emulador: se mató la app del otro lado y la pantalla siguió marcando
 * **0:54** y diciendo "con joaquin" contra un teléfono muerto.
 *
 * ## Y por qué una caída transitoria no puede colgar
 *
 * `DISCONNECTED` se recupera solo cuando el teléfono cambia de red. Colgar ahí
 * cortaría la llamada cada vez que se pasa de wifi a datos, que es justo lo
 * que el comentario del motor venía advirtiendo desde el módulo K. Dejar de
 * afirmar que hay conversación es distinto de darla por terminada.
 */
class TrasCaidaTest {

    /** Que se caiga uno no corta a los demás. */
    @Test
    fun `con alguien conectado, la llamada sigue`() {
        assertEquals(
            TrasCaida.SEGUIR,
            Malla.trasCaida(conectados = 2, sonando = 0, definitiva = true),
        )
        assertEquals(
            TrasCaida.SEGUIR,
            Malla.trasCaida(conectados = 1, sonando = 1, definitiva = false),
        )
    }

    /**
     * El caso que la regla vieja no sabía ver.
     *
     * Nadie conectado pero alguien todavía puede contestar: no se cuelga
     * —sería colgarle a quien está por entrar— y tampoco se sigue afirmando
     * que la llamada está en curso.
     */
    @Test
    fun `sin nadie conectado pero con alguien sonando, se espera`() {
        assertEquals(
            TrasCaida.ESPERAR,
            Malla.trasCaida(conectados = 0, sonando = 1, definitiva = true),
        )
        assertEquals(
            TrasCaida.ESPERAR,
            Malla.trasCaida(conectados = 0, sonando = 3, definitiva = true),
        )
    }

    /** Ni conectados ni sonando y la caída fue definitiva: no hay llamada. */
    @Test
    fun `sin nadie y con caida definitiva, se cuelga`() {
        assertEquals(
            TrasCaida.COLGAR,
            Malla.trasCaida(conectados = 0, sonando = 0, definitiva = true),
        )
    }

    /**
     * Lo que separa `DISCONNECTED` de `FAILED`.
     *
     * Con los mismos números —nadie conectado, nadie sonando— una caída
     * transitoria **espera** y una definitiva cuelga. Si las dos colgaran, un
     * cambio de wifi a datos cortaría la llamada.
     */
    @Test
    fun `una caida transitoria nunca cuelga, con los mismos numeros`() {
        val transitoria = Malla.trasCaida(conectados = 0, sonando = 0, definitiva = false)
        val definitiva = Malla.trasCaida(conectados = 0, sonando = 0, definitiva = true)
        assertEquals(TrasCaida.ESPERAR, transitoria)
        assertEquals(TrasCaida.COLGAR, definitiva)
        assertNotEquals("transitoria y definitiva no pueden decidir lo mismo", transitoria, definitiva)
    }

    /**
     * Nunca se cuelga mientras haya alguien sonando, por muchas conexiones que
     * hayan muerto.
     *
     * Es la propiedad que importa: el timbre dura cuarenta y cinco segundos y
     * decidirlo antes es colgarle a quien iba a contestar.
     */
    @Test
    fun `mientras alguien suene, nunca se cuelga`() {
        for (sonando in 1..4) {
            for (definitiva in listOf(true, false)) {
                assertNotEquals(
                    "se colgo con $sonando sonando (definitiva=$definitiva)",
                    TrasCaida.COLGAR,
                    Malla.trasCaida(conectados = 0, sonando = sonando, definitiva = definitiva),
                )
            }
        }
    }

    /**
     * Y una llamada de dos que se cae de verdad se cuelga en el acto.
     *
     * Es el comportamiento que YA existía y que no se podía perder al arreglar
     * el caso de grupo: arreglar una cosa sin romper la otra es la mitad del
     * trabajo.
     */
    @Test
    fun `una llamada de dos que falla sigue colgando en el acto`() {
        assertEquals(
            TrasCaida.COLGAR,
            Malla.trasCaida(conectados = 0, sonando = 0, definitiva = true),
        )
    }
}
