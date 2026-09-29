package com.wtfuck.app

import androidx.compose.ui.graphics.Color
import com.wtfuck.app.ui.theme.mezclar
import com.wtfuck.app.ui.theme.mismaRafaga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las dos reglas del aspecto nuevo que se pueden probar sin pantalla.
 *
 * La forma de la burbuja NO se prueba aqui: un `Path` solo se juzga
 * mirandolo, y de hecho asi se encontro el fallo del primer intento —el pico
 * salia como una astilla sin relleno, y solo se vio ampliando una captura del
 * emulador—. Lo que si se prueba es la regla que decide CUANDO lleva pico, que
 * es logica y se puede equivocar en silencio.
 */
class AspectoChatTest {

    private val T = 1_700_000_000_000L
    private val MIN = 60_000L

    // ------------------------------------------------------- rafagas

    @Test
    fun `dos mensajes seguidos del mismo autor son una rafaga`() {
        assertTrue(mismaRafaga("ana", T, false, "ana", T + 30_000, false))
    }

    @Test
    fun `de autores distintos no`() {
        assertFalse(mismaRafaga("ana", T, false, "beto", T + 1000, false))
    }

    @Test
    fun `separados por mucho tiempo tampoco`() {
        // El autor no basta: dos mensajes de la misma persona con tres horas
        // en medio son dos conversaciones, y pegarlos haria parecer que el
        // segundo contesta a algo que ya nadie tiene en pantalla.
        assertFalse(mismaRafaga("ana", T, false, "ana", T + 3 * 60 * MIN, false))
    }

    @Test
    fun `justo en el tope cuenta como rafaga, y un segundo despues no`() {
        val tope = 5 * MIN
        assertTrue(mismaRafaga("ana", T, false, "ana", T + tope, false))
        assertFalse(mismaRafaga("ana", T, false, "ana", T + tope + 1, false))
    }

    @Test
    fun `un mensaje de sistema corta la rafaga`() {
        // "X salio del grupo" en medio de una tanda la parte en dos, y debe
        // hacerlo: lo de despues ya no es continuacion de lo de antes.
        assertFalse(mismaRafaga("ana", T, false, "", T + 1000, true))
        assertFalse(mismaRafaga("", T, true, "ana", T + 1000, false))
    }

    @Test
    fun `el orden de los dos no cambia el resultado`() {
        // Se usa mirando hacia atras y hacia adelante en la lista; si no
        // fuera simetrica, un mensaje podria "abrir" y "cerrar" a la vez o
        // ninguna de las dos.
        assertEquals(
            mismaRafaga("ana", T, false, "ana", T + 30_000, false),
            mismaRafaga("ana", T + 30_000, false, "ana", T, false),
        )
    }

    // -------------------------------------------------------- mezclar

    @Test
    fun `mezclar con 0 devuelve la base`() {
        val base = Color(0xFF101010)
        assertEquals(base.red, mezclar(base, Color.White, 0f).red, 0.001f)
    }

    @Test
    fun `mezclar con 1 devuelve el de encima`() {
        assertEquals(1f, mezclar(Color.Black, Color.White, 1f).red, 0.001f)
    }

    @Test
    fun `mezclar por la mitad queda en medio`() {
        assertEquals(0.5f, mezclar(Color.Black, Color.White, 0.5f).red, 0.01f)
    }

    @Test
    fun `mezclar siempre devuelve opaco`() {
        // El fondo del chat no puede ser semitransparente: dejaria ver lo que
        // haya detras y el contraste dejaria de estar bajo control.
        assertEquals(1f, mezclar(Color.Black, Color.White.copy(alpha = 0.2f), 0.5f).alpha, 0.001f)
    }

    @Test
    fun `un valor fuera de rango no rompe`() {
        // Un color no es sitio para una excepcion.
        assertEquals(0f, mezclar(Color.Black, Color.White, -3f).red, 0.001f)
        assertEquals(1f, mezclar(Color.Black, Color.White, 7f).red, 0.001f)
    }
}
