package com.wtfuck.app

import com.wtfuck.protocol.Onda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * La silueta de una nota de voz.
 *
 * Es la única parte de esto que se puede probar sin un micrófono: el dibujo y
 * la grabación necesitan un aparato, pero qué sale de unas muestras y qué pasa
 * con una cadena que escribió otra persona, no.
 */
class OndaTest {

    // ------------------------------------------------------------------
    // La forma
    // ------------------------------------------------------------------

    @Test
    fun `siempre salen las mismas barras, dure lo que dure`() {
        // Una nota de dos segundos y una de diez tienen que dibujarse con el
        // mismo ancho: la barra ocupa lo que ocupa en la burbuja.
        for (n in listOf(1, 7, 40, 41, 120, 5000)) {
            assertEquals("con $n muestras", Onda.BARRAS, Onda.codificar(List(n) { 0.5f }).length)
        }
    }

    @Test
    fun `el silencio no tiene figura`() {
        // Cadena vacía y no cuarenta ceros: "no hay figura" y "hay una figura
        // plana" son cosas distintas, y quien dibuja tiene que poder
        // distinguirlas para usar la de siempre.
        assertEquals("", Onda.codificar(emptyList()))
        assertEquals("", Onda.codificar(List(50) { 0f }))
    }

    @Test
    fun `se normaliza a la propia nota`() {
        // Sin esto casi todas las notas se verían planas: hablar normal no
        // satura el micrófono ni de lejos. Una nota grabada bajito tiene que
        // dibujarse con la misma altura que una grabada fuerte.
        val bajito = Onda.codificar(listOf(0.01f, 0.02f, 0.01f, 0.02f))
        val fuerte = Onda.codificar(listOf(0.50f, 1.00f, 0.50f, 1.00f))
        assertEquals(fuerte, bajito)
    }

    @Test
    fun `el pico de un tramo no se promedia hasta desaparecer`() {
        // Una sílaba corta entre dos silencios. Promediando el tramo, se
        // borraría justo lo que se quiere ver y la figura quedaría lisa.
        val muestras = MutableList(400) { 0f }
        muestras[200] = 1f
        val forma = Onda.codificar(muestras)

        val niveles = Onda.decodificar(forma)!!
        assertEquals("el pico tiene que llegar arriba del todo", 1f, niveles.max(), 0.001f)
        assertTrue("y el resto tiene que quedar abajo", niveles.count { it > 0.5f } <= 2)
    }

    @Test
    fun `el final de la nota no se pierde`() {
        // Los bordes se calculan multiplicando y no acumulando un paso: con
        // pocas muestras, acumular deja el último cubo fuera de rango por
        // redondeo y la última palabra desaparece del dibujo.
        val muestras = MutableList(83) { 0.1f }
        muestras[82] = 1f
        val niveles = Onda.decodificar(Onda.codificar(muestras))!!
        assertEquals("la ultima barra tiene que ser la alta", 1f, niveles.last(), 0.001f)
    }

    // ------------------------------------------------------------------
    // La ida y la vuelta
    // ------------------------------------------------------------------

    @Test
    fun `lo que se codifica se puede leer`() {
        val muestras = List(500) { Random(it).nextFloat() }
        val niveles = Onda.decodificar(Onda.codificar(muestras))
        assertNotNull(niveles)
        assertEquals(Onda.BARRAS, niveles!!.size)
        assertTrue("todo entre 0 y 1", niveles.all { it in 0f..1f })
    }

    @Test
    fun `la cadena no lleva nada que haya que escapar`() {
        // Pasa por JSON y podría pasar por una URL. Base 32 sin `+`, `/` ni
        // `=` evita tener que pensar en escapes cada vez.
        val forma = Onda.codificar(List(300) { Random(it).nextFloat() })
        assertTrue(forma.all { it in '0'..'9' || it in 'a'..'v' })
    }

    // ------------------------------------------------------------------
    // Lo que manda otra persona
    // ------------------------------------------------------------------

    @Test
    fun `una cadena que no es una figura se rechaza`() {
        // Esto llega dentro de un sobre que escribió otra persona. Lo único
        // sensato con algo que no se entiende es no usarlo: quien dibuja ya
        // sabe qué hacer sin figura.
        for (basura in listOf(
            "",
            "corta",
            "z".repeat(Onda.BARRAS),          // fuera del alfabeto
            "A".repeat(Onda.BARRAS),          // mayúsculas, que no se usan
            "0".repeat(Onda.BARRAS - 1),      // una de menos
            "0".repeat(Onda.BARRAS + 1),      // una de más
            "0".repeat(100_000),              // y una absurda
            "0123456789 bcdefghijklmnopqrstuv0123456",  // un espacio en medio
        )) {
            assertNull("'${basura.take(20)}' no tendria que valer", Onda.decodificar(basura))
        }
    }

    @Test
    fun `los dos extremos del alfabeto llegan a 0 y a 1`() {
        // Si el mapeo no tocara los extremos, ninguna nota usaría todo el alto
        // y todas se verían recortadas por arriba.
        assertEquals(0f, Onda.decodificar("0".repeat(Onda.BARRAS))!!.first(), 0.0001f)
        assertEquals(1f, Onda.decodificar("v".repeat(Onda.BARRAS))!!.first(), 0.0001f)
    }
}
