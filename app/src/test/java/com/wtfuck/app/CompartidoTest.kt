package com.wtfuck.app

import com.wtfuck.app.datos.CLASE_ENLACE
import com.wtfuck.app.ui.etiquetaCompartido
import com.wtfuck.app.ui.ordenCompartido
import com.wtfuck.app.ui.primerEnlace
import com.wtfuck.protocol.ClaseAdjunto
import com.wtfuck.protocol.ClaseContenido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El recuento de lo compartido: cómo se llama, en qué orden y qué es un enlace. */
class CompartidoTest {

    // ------------------------------------------------------------------
    // Los nombres
    // ------------------------------------------------------------------

    @Test
    fun `uno va en singular`() {
        // "1 fotos" se lee como un error de la app, y una vez que se lee un
        // error se desconfía del número entero.
        assertEquals("1 foto", etiquetaCompartido(ClaseAdjunto.IMAGEN, 1))
        assertEquals("93 fotos", etiquetaCompartido(ClaseAdjunto.IMAGEN, 93))
        assertEquals("1 mensaje de voz", etiquetaCompartido(ClaseAdjunto.NOTA_VOZ, 1))
        assertEquals("4 mensajes de voz", etiquetaCompartido(ClaseAdjunto.NOTA_VOZ, 4))
        assertEquals("1 ubicación", etiquetaCompartido(ClaseContenido.UBICACION, 1))
        assertEquals("2 ubicaciones", etiquetaCompartido(ClaseContenido.UBICACION, 2))
    }

    @Test
    fun `una clase que no se conoce no rompe la pantalla`() {
        // Una clase nueva del contrato tiene que poder llegar acá antes de que
        // alguien se acuerde de ponerle nombre. Que se lea raro es aceptable;
        // que la pantalla se caiga, no.
        val e = etiquetaCompartido("clase_del_futuro", 3)
        assertTrue(e, e.startsWith("3 "))
    }

    // ------------------------------------------------------------------
    // El orden
    // ------------------------------------------------------------------

    @Test
    fun `el orden no depende de cuantos hay`() {
        // Es la diferencia entre una lista que se puede aprender y una que se
        // reacomoda sola cada vez que se manda algo.
        val pocas = mapOf(ClaseAdjunto.IMAGEN to 1, ClaseContenido.ENCUESTA to 99)
        val muchas = mapOf(ClaseAdjunto.IMAGEN to 99, ClaseContenido.ENCUESTA to 1)
        assertEquals(
            ordenCompartido(pocas).map { it.first },
            ordenCompartido(muchas).map { it.first },
        )
        assertEquals(ClaseAdjunto.IMAGEN, ordenCompartido(pocas).first().first)
    }

    @Test
    fun `una clase desconocida aparece igual, al final`() {
        val r = mapOf("rarisimo" to 2, ClaseAdjunto.IMAGEN to 5)
        val orden = ordenCompartido(r)
        assertEquals(2, orden.size)
        assertEquals(ClaseAdjunto.IMAGEN, orden.first().first)
        assertEquals("rarisimo", orden.last().first)
    }

    @Test
    fun `no se inventan filas que no estan`() {
        // Sólo lo que existe. Una fila "0 videos" ocuparía sitio para decir
        // que no hay nada que mirar.
        assertTrue(ordenCompartido(emptyMap()).isEmpty())
        assertEquals(1, ordenCompartido(mapOf(ClaseAdjunto.VIDEO to 3)).size)
    }

    // ------------------------------------------------------------------
    // Los enlaces
    // ------------------------------------------------------------------

    @Test
    fun `la puntuacion final no es parte de la direccion`() {
        // "mirá https://x.com." con el punto pegado da un 404, y el 404 se lee
        // como que el enlace estaba roto desde el principio.
        assertEquals("https://x.com", primerEnlace("mirá https://x.com."))
        assertEquals("https://x.com/a", primerEnlace("(https://x.com/a)"))
        assertEquals("http://n.pe", primerEnlace("http://n.pe, y después"))
    }

    @Test
    fun `se toma el primero y se corta donde termina`() {
        assertEquals(
            "https://uno.pe",
            primerEnlace("ver https://uno.pe y también https://dos.pe"),
        )
        assertEquals("https://uno.pe", primerEnlace("https://uno.pe\nsegunda línea"))
    }

    @Test
    fun `lo que no es una direccion no lo es`() {
        assertNull(primerEnlace("sin nada"))
        // "http" suelto en una palabra no es un enlace: abrirlo daría error y
        // además lo habría contado como si hubiera algo que abrir.
        assertNull(primerEnlace("hablamos de httpsomething"))
        assertNull(primerEnlace(""))
    }

    @Test
    fun `la clase de los enlaces no puede chocar con una del contrato`() {
        // Es un nombre inventado del lado de la vista. Si algún día el
        // contrato definiera una clase con ese nombre, las dos se mezclarían
        // en el mismo recuento sin que nada lo avisara.
        assertTrue(CLASE_ENLACE.startsWith("_"))
        assertTrue(CLASE_ENLACE !in ClaseAdjunto.TODAS)
        assertTrue(CLASE_ENLACE !in ClaseContenido.VALIDAS)
    }
}
