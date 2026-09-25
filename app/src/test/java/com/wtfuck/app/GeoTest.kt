package com.wtfuck.app

import com.wtfuck.app.datos.Geo
import com.wtfuck.protocol.ESTELA_MAX
import com.wtfuck.protocol.PuntoEstela
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * Las cuentas del mapa, con números escritos a mano.
 *
 * Todo esto se podría "probar" mirando el emulador, y por eso mismo está acá:
 * un mapa mal proyectado se ve perfectamente bien en Lima —cerca del ecuador
 * el error de confundir Mercator con una regla es de píxeles— y parte el
 * dibujo a la mitad en Oslo. Un teléfono en un escritorio de Lima nunca lo
 * iba a mostrar.
 */
class GeoTest {

    // ------------------------------------------------------------------
    // Proyección
    // ------------------------------------------------------------------

    @Test
    fun `el centro del mundo cae en el centro de la baldosa cero`() {
        assertEquals(0.5, Geo.columna(0.0, 0), 1e-9)
        assertEquals(0.5, Geo.fila(0.0, 0), 1e-9)
        // A zoom 1 el mundo son 2x2 baldosas y el cruce queda en (1,1).
        assertEquals(1.0, Geo.columna(0.0, 1), 1e-9)
        assertEquals(1.0, Geo.fila(0.0, 1), 1e-9)
    }

    @Test
    fun `una baldosa conocida de Lima`() {
        // -12.0464, -77.0428 a zoom 12. Calculado aparte con la fórmula
        // publicada de slippy map; si alguien cambia la proyección, esto grita.
        assertEquals(1171, floor(Geo.columna(-77.0428, 12)).toInt())
        assertEquals(2186, floor(Geo.fila(-12.0464, 12)).toInt())
    }

    @Test
    fun `Mercator no es una regla`() {
        // El error clásico: tratar los grados de latitud como una distancia
        // constante en pantalla. Si alguien reemplaza `fila` por una recta,
        // esto pasa a ser igualdad y la prueba cae.
        val z = 10
        val eq = Geo.fila(0.0, z)
        val a30 = eq - Geo.fila(30.0, z)
        val a60 = eq - Geo.fila(60.0, z)
        assertTrue("60 grados tiene que estirarse mas del doble que 30", a60 > a30 * 2.2)
    }

    @Test
    fun `los polos no rompen la cuenta`() {
        // Sin el recorte a LAT_MAX esto sería infinito o NaN, y el dibujo
        // entero se iría a negro.
        for (lat in listOf(90.0, -90.0, 89.9, -89.9)) {
            val f = Geo.fila(lat, 10)
            assertTrue("fila($lat) = $f", f.isFinite())
        }
    }

    @Test
    fun `la longitud da la vuelta`() {
        // 181 grados es lo mismo que -179: alguien cruzando el antimeridiano
        // no puede saltar al otro extremo del mundo.
        assertEquals(Geo.columna(-179.0, 8), Geo.columna(181.0, 8), 1e-9)
    }

    // ------------------------------------------------------------------
    // Distancias
    // ------------------------------------------------------------------

    @Test
    fun `un grado de latitud son unos ciento once kilometros`() {
        val m = Geo.metros(0.0, 0.0, 1.0, 0.0)
        assertTrue("$m", abs(m - 111_195.0) < 500.0)
    }

    @Test
    fun `un grado de longitud vale menos lejos del ecuador`() {
        val ecuador = Geo.metros(0.0, 0.0, 0.0, 1.0)
        val sesenta = Geo.metros(60.0, 0.0, 60.0, 1.0)
        // cos(60) = 0.5 exacto: a esa latitud un meridiano mide la mitad.
        assertTrue("$sesenta vs $ecuador", abs(sesenta - ecuador / 2) < 1000.0)
    }

    // ------------------------------------------------------------------
    // La estela
    // ------------------------------------------------------------------

    @Test
    fun `quedarse quieto no genera puntos`() {
        val uno = Geo.conPunto(emptyList(), -12.0464, -77.0428, 1L)
        assertEquals(1, uno.size)
        // Tres metros más allá: es ruido de GPS, no un movimiento.
        val igual = Geo.conPunto(uno, -12.04643, -77.0428, 2L)
        assertSame("tiene que devolver LA MISMA lista, no una copia", uno, igual)
    }

    @Test
    fun `moverse de verdad si genera un punto`() {
        val uno = Geo.conPunto(emptyList(), -12.0464, -77.0428, 1L)
        // ~110 m al norte.
        val dos = Geo.conPunto(uno, -12.0454, -77.0428, 2L)
        assertNotSame(uno, dos)
        assertEquals(2, dos.size)
        assertEquals(-12.0454, dos.last().lat, 1e-9)
    }

    @Test
    fun `la estela se recorta por el principio`() {
        var e = emptyList<PuntoEstela>()
        // Cada paso ~110 m, así que todos entran.
        for (i in 0 until ESTELA_MAX + 20) {
            e = Geo.conPunto(e, -12.0 - i * 0.001, -77.0, i.toLong())
        }
        assertEquals(ESTELA_MAX, e.size)
        // Lo que sobrevive es lo NUEVO: el último es el último que entró.
        assertEquals((ESTELA_MAX + 19).toLong(), e.last().en)
        assertEquals(20L, e.first().en)
    }

    // ------------------------------------------------------------------
    // Encuadre
    // ------------------------------------------------------------------

    @Test
    fun `un punto solo usa el zoom suelto`() {
        val uno = listOf(PuntoEstela(-12.0, -77.0, 0))
        assertEquals(16, Geo.zoomPara(uno, 600, 400))
    }

    @Test
    fun `el recorrido entra entero en la caja`() {
        val puntos = (0..20).map { PuntoEstela(-12.0 + it * 0.002, -77.0 + it * 0.002, it.toLong()) }
        val ancho = 600
        val alto = 400
        val z = Geo.zoomPara(puntos, ancho, alto)
        val xs = puntos.map { Geo.columna(it.lon, z) * Geo.LADO }
        val ys = puntos.map { Geo.fila(it.lat, z) * Geo.LADO }
        assertTrue("ancho", xs.max() - xs.min() <= ancho)
        assertTrue("alto", ys.max() - ys.min() <= alto)
    }

    @Test
    fun `un recorrido corto se ve mas de cerca que uno largo`() {
        val corto = (0..5).map { PuntoEstela(-12.0 + it * 0.0002, -77.0, it.toLong()) }
        val largo = (0..5).map { PuntoEstela(-12.0 + it * 0.05, -77.0, it.toLong()) }
        assertTrue(Geo.zoomPara(corto, 600, 400) > Geo.zoomPara(largo, 600, 400))
    }

    // ------------------------------------------------------------------
    // Escala
    // ------------------------------------------------------------------

    @Test
    fun `la barra dice numeros redondos`() {
        // Sin esto la barra diría "137 m" y nadie podría estimar nada con ella.
        for (mpp in listOf(0.3, 1.0, 2.7, 13.0, 140.0)) {
            val (metros, _) = Geo.escala(mpp, 120)
            val redondo = metros.toString().trimStart('1', '2', '5')
            assertTrue("$metros no es redondo", redondo.all { it == '0' } && metros > 0)
        }
    }

    @Test
    fun `la barra no es mas larga que lo que se le dio`() {
        val (_, px) = Geo.escala(2.0, 100)
        assertTrue("$px", px in 1..100)
    }

    @Test
    fun `un pixel vale menos metros cuanto mas cerca se mira`() {
        assertTrue(Geo.metrosPorPixel(0.0, 10) > Geo.metrosPorPixel(0.0, 14))
    }
}
