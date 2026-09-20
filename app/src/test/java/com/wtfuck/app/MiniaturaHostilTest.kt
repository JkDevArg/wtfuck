package com.wtfuck.app

import com.wtfuck.app.contenido.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La miniatura que mando otra persona.
 *
 * Un PNG de 30.000 x 30.000 de un solo color ocupa unos KB y pide 3,6 GB al
 * decodificarse. Lo que hay que evitar no es la excepcion —`runCatching` la
 * atrapa— sino **la reserva**: para cuando salta el `OutOfMemoryError`, el
 * telefono ya intento pedir esa memoria.
 *
 * Aqui se prueba la aritmetica de la decision. Las llamadas a `BitmapFactory`
 * necesitan el framework y viven en `Media`.
 */
class MiniaturaHostilTest {

    // ------------------------------------------------------------------
    //  Dimensiones
    // ------------------------------------------------------------------

    @Test
    fun `una miniatura de esta app es creible`() {
        assertTrue(miniaturaCreible(240, 240))
        assertTrue(miniaturaCreible(240, 135))
        assertTrue(miniaturaCreible(1, 1))
    }

    @Test
    fun `la bomba de 30000 por 30000 no lo es`() {
        assertFalse(miniaturaCreible(30_000, 30_000))
    }

    @Test
    fun `el desbordamiento de Int NO cuela`() {
        // Esta es la prueba que mas importa de la clase. `65536 * 65536` en Int
        // es exactamente 0, y `46341 * 46341` da negativo: una comprobacion
        // escrita con Int los daria por buenos a los dos. Por eso el producto
        // se hace en Long.
        assertFalse("65536x65536 desborda a 0", miniaturaCreible(65_536, 65_536))
        assertFalse("46341x46341 desborda a negativo", miniaturaCreible(46_341, 46_341))
    }

    @Test
    fun `una tira larguisima tampoco, aunque tenga pocos pixeles`() {
        // 100.000 x 1 son 100.000 pixeles -por debajo del tope de area- y sigue
        // siendo absurdo. De ahi que haya un tope de LADO aparte del de area.
        assertFalse(miniaturaCreible(100_000, 1))
        assertFalse(miniaturaCreible(1, 100_000))
    }

    @Test
    fun `un cero o un negativo significan que no se pudo medir`() {
        assertFalse(miniaturaCreible(0, 0))
        assertFalse(miniaturaCreible(240, 0))
        assertFalse(miniaturaCreible(-1, 240))
        assertFalse(miniaturaCreible(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test
    fun `justo en el tope de area se acepta y un pixel mas no`() {
        assertTrue(miniaturaCreible(1000, 1000))          // 1.000.000 exacto
        assertFalse(miniaturaCreible(1001, 1000))
    }

    @Test
    fun `justo en el tope de lado se acepta y uno mas no`() {
        // 8000 de lado con el otro chico: dentro de area y dentro de lado.
        assertTrue(miniaturaCreible(TOPE_LADO_MINIATURA, 100))
        assertFalse(miniaturaCreible(TOPE_LADO_MINIATURA + 1, 100))
    }

    // ------------------------------------------------------------------
    //  El muestreo
    // ------------------------------------------------------------------

    @Test
    fun `una imagen que ya entra no se muestrea`() {
        assertEquals(1, muestreoPara(240, 240))
        assertEquals(1, muestreoPara(1000, 1000))
    }

    @Test
    fun `una grande se reduce hasta entrar`() {
        val paso = muestreoPara(4000, 4000)
        assertTrue("deberia muestrear", paso > 1)
        // Y el resultado entra de verdad en el tope.
        val px = (4000L / paso) * (4000L / paso)
        assertTrue("$px pixeles sigue pasandose", px <= TOPE_PIXELES_MINIATURA)
    }

    @Test
    fun `el paso siempre es potencia de dos`() {
        // No es cosmetico: `BitmapFactory` redondea cualquier otro valor hacia
        // la potencia de dos anterior, y entonces el calculo no valdria.
        for (lado in listOf(500, 1500, 3000, 7000, 8000)) {
            val paso = muestreoPara(lado, lado)
            assertEquals("con lado $lado el paso $paso no es potencia de dos",
                0, paso and (paso - 1))
        }
    }

    @Test
    fun `no se queda colgado con dimensiones absurdas`() {
        // El bucle tiene techo: sin el, un lado enorme lo dejaria multiplicando.
        val paso = muestreoPara(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(paso >= 1)
        assertTrue(paso <= (1 shl 16))
    }

    @Test
    fun `dimensiones invalidas devuelven un paso inofensivo`() {
        assertEquals(1, muestreoPara(0, 0))
        assertEquals(1, muestreoPara(-5, 10))
    }

    // ------------------------------------------------------------------
    //  Los bytes, que es la comprobacion mas barata
    // ------------------------------------------------------------------

    @Test
    fun `una miniatura normal pasa por tamano`() {
        assertTrue(bytesDeMiniaturaCreibles(20 * 1024))
        assertTrue(bytesDeMiniaturaCreibles(1))
    }

    @Test
    fun `vacia o desmedida no`() {
        assertFalse(bytesDeMiniaturaCreibles(0))
        assertFalse(bytesDeMiniaturaCreibles(-1))
        assertFalse(bytesDeMiniaturaCreibles(TOPE_BYTES_MINIATURA + 1))
        // Un sobre admite 64 KB: una miniatura que se los coma no es miniatura.
        assertFalse(bytesDeMiniaturaCreibles(64 * 1024))
    }
}
