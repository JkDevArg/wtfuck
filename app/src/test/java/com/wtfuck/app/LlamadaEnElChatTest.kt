package com.wtfuck.app

import com.wtfuck.protocol.ClaseContenido
import com.wtfuck.protocol.duracionLegible
import com.wtfuck.protocol.llamadaEnElChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que queda escrito en el chat cuando una llamada termina.
 *
 * Son reglas, no dibujo: cuatro motivos por dos lados más el caso normal, y
 * la frase no es la misma según de qué lado se mire. Eso sí se puede probar.
 */
class LlamadaEnElChatTest {

    private fun saliente(motivo: String = "", seg: Long = 0, video: Boolean = false) =
        llamadaEnElChat(video, saliente = true, motivoFin = motivo, segundos = seg)

    private fun entrante(motivo: String = "", seg: Long = 0, video: Boolean = false) =
        llamadaEnElChat(video, saliente = false, motivoFin = motivo, segundos = seg)

    // ------------------------------------------------------------------
    // Lo que importa: perdida o no
    // ------------------------------------------------------------------

    @Test
    fun `una entrante que no se contesto es una perdida`() {
        // Es la única de la lista que pide hacer algo, y por eso es la única
        // que se pinta distinto.
        assertTrue(entrante().perdida)
        assertTrue(entrante("sin_respuesta").perdida)
        assertTrue(entrante("cancelada").perdida)
    }

    @Test
    fun `la que rechace yo NO es una perdida`() {
        // No hay nada que devolver: decidí no atender. Pintarla igual que una
        // perdida haría que el color dejara de querer decir "hay algo
        // pendiente".
        val r = entrante("rechazada")
        assertFalse(r.perdida)
        assertEquals("Llamada rechazada", r.titulo)
    }

    @Test
    fun `una llamada que hablo no es una perdida, la conteste quien la conteste`() {
        assertFalse(entrante(seg = 1).perdida)
        assertFalse(saliente(seg = 1).perdida)
    }

    @Test
    fun `ninguna saliente es una perdida`() {
        // Una llamada que YO hice y no entró no es algo que me haya perdido.
        for (m in listOf("", "rechazada", "sin_respuesta", "ocupado", "cancelada", "fallo_red")) {
            assertFalse("motivo '$m'", saliente(m).perdida)
        }
    }

    // ------------------------------------------------------------------
    // El mismo hecho, contado por cada lado
    // ------------------------------------------------------------------

    @Test
    fun `sin respuesta de un lado es perdida del otro`() {
        // Un solo hecho: sonó y nadie hablo. Quien llamó necesita saber que no
        // le contestaron; quien no atendió, que lo llamaron. No es la misma
        // frase y no puede serlo.
        assertEquals("Sin respuesta", saliente("sin_respuesta").detalle)
        assertEquals("Llamada perdida", entrante("sin_respuesta").titulo)
    }

    @Test
    fun `cuando conecto, el motivo del final da igual`() {
        // Colgar es como terminan todas: decirlo no agrega nada y alarga la
        // línea. Lo único que importa es cuánto duró.
        for (m in listOf("", "colgada", "fallo_red", "cualquier_cosa")) {
            assertEquals("motivo '$m'", "5:32", saliente(m, seg = 332).detalle)
        }
    }

    @Test
    fun `el video se dice, porque no es lo mismo`() {
        assertEquals("Videollamada", saliente(seg = 10, video = true).titulo)
        assertEquals("Llamada", saliente(seg = 10, video = false).titulo)
        assertEquals("Videollamada perdida", entrante(video = true).titulo)
    }

    @Test
    fun `un motivo que nadie previo no se dibuja como una perdida`() {
        // Un motivo nuevo del servidor o de una versión más nueva no puede
        // convertir una saliente en una perdida: sería inventarle a alguien
        // una llamada que no atendió.
        val r = saliente("motivo_del_futuro")
        assertFalse(r.perdida)
        assertEquals("No se establecio", r.detalle)
    }

    // ------------------------------------------------------------------
    // La duracion
    // ------------------------------------------------------------------

    @Test
    fun `la duracion se lee como en un telefono`() {
        assertEquals("0:00", duracionLegible(0))
        assertEquals("0:07", duracionLegible(7))
        assertEquals("1:00", duracionLegible(60))
        assertEquals("5:32", duracionLegible(332))
        assertEquals("59:59", duracionLegible(3599))
    }

    @Test
    fun `los minutos llevan cero delante SOLO si hay horas`() {
        // Sin esta regla, cinco minutos y medio se leen "05:32", que parece
        // una hora empezada en vez de una duración.
        assertEquals("5:32", duracionLegible(332))
        assertEquals("1:05:32", duracionLegible(3932))
        assertEquals("1:00:00", duracionLegible(3600))
        assertEquals("2:04:09", duracionLegible(7449))
    }

    @Test
    fun `una duracion imposible no rompe la linea`() {
        // `conectadaEn` sale de un reloj, y un reloj que se corrige hacia
        // atrás —cambio de hora, NTP— da una resta negativa. Escribir
        // "-1:-30" en el chat sería peor que escribir cero.
        assertEquals("0:00", duracionLegible(-1))
        assertEquals("0:00", duracionLegible(-99999))
    }

    // ------------------------------------------------------------------
    // Que no se pueda falsificar
    // ------------------------------------------------------------------

    @Test
    fun `el servidor NO acepta un resumen de llamada en un sobre`() {
        // Es la defensa entera de esto: el resumen lo escribe cada teléfono en
        // su propia base y no viaja. Si `LLAMADA` estuviera entre las clases
        // válidas, cualquiera podría meter en tu chat una llamada de dos horas
        // que nunca ocurrió — y un registro de llamadas que se puede
        // falsificar desde afuera vale menos que ninguno.
        assertFalse(ClaseContenido.LLAMADA in ClaseContenido.VALIDAS)
    }
}
