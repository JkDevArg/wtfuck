package com.wtfuck.app

import com.wtfuck.app.datos.EdicionFoto
import com.wtfuck.app.datos.Encuadre
import com.wtfuck.app.datos.FiltroFoto
import com.wtfuck.app.datos.Lienzo
import com.wtfuck.app.datos.Matrices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La geometria y los filtros del editor de estados.
 *
 * Importa que esten bien porque la vista previa y el archivo final usan estas
 * mismas cuentas: si fallan, lo publicado no es lo que se vio.
 */
class EdicionEstadoTest {

    private val W = Lienzo.ANCHO.toFloat()
    private val H = Lienzo.ALTO.toFloat()
    private val eps = 0.01f

    // --------------------------------------------------------- encuadre

    @Test
    fun `una foto apaisada cubre el lienzo por el alto`() {
        // 4000x3000 en 1080x1920: manda el alto (1920/3000 > 1080/4000).
        assertEquals(H / 3000f, Encuadre.escalaCubrir(4000, 3000, 0, W, H), 1e-5f)
    }

    @Test
    fun `una foto vertical justa cubre exacto`() {
        assertEquals(1f, Encuadre.escalaCubrir(1080, 1920, 0, W, H), 1e-5f)
    }

    @Test
    fun `girar 90 intercambia ancho y alto`() {
        assertEquals(1080 to 1920, Encuadre.dimensionesGiradas(1920, 1080, 90))
        assertEquals(1920 to 1080, Encuadre.dimensionesGiradas(1920, 1080, 180))
        // Una foto apaisada girada 90 queda vertical y cubre justo.
        assertEquals(1f, Encuadre.escalaCubrir(1920, 1080, 90, W, H), 1e-5f)
    }

    @Test
    fun `el giro se normaliza a pasos de 90`() {
        assertEquals(0, Encuadre.giroNormal(360))
        assertEquals(270, Encuadre.giroNormal(-90))
        assertEquals(90, Encuadre.giroNormal(450))
    }

    @Test
    fun `sin zoom ni desplazamiento el centro de la foto cae en el centro del lienzo`() {
        val t = Encuadre.transformacion(4000, 3000, EdicionFoto(), W, H)
        val (x, y) = t.aplicar(2000f, 1500f)
        assertEquals(W / 2, x, eps)
        assertEquals(H / 2, y, eps)
    }

    @Test
    fun `cubrir no deja huecos arriba ni abajo`() {
        // Foto apaisada: los bordes superior e inferior tocan el lienzo.
        val t = Encuadre.transformacion(4000, 3000, EdicionFoto(), W, H)
        assertEquals(0f, t.aplicar(2000f, 0f).second, eps)
        assertEquals(H, t.aplicar(2000f, 3000f).second, eps)
    }

    @Test
    fun `girada 90 la esquina de arriba a la izquierda va a la derecha`() {
        // Giro horario en coordenadas de pantalla (y hacia abajo).
        val t = Encuadre.transformacion(1920, 1080, EdicionFoto(giro = 90), W, H)
        val (x, y) = t.aplicar(0f, 0f)
        assertEquals(W, x, eps)
        assertEquals(0f, y, eps)
    }

    @Test
    fun `el desplazamiento va en fraccion del lienzo`() {
        val e = EdicionFoto(zoom = 2f, desplX = 0.1f)
        val t = Encuadre.transformacion(1080, 1920, e, W, H)
        assertEquals(W / 2 + 0.1f * W, t.aplicar(540f, 960f).first, eps)
    }

    @Test
    fun `la misma edicion da el mismo encuadre en pantalla y en el archivo`() {
        // Lo que importa del editor: una vista previa de 360x640 y el archivo
        // de 1080x1920 tienen que poner cada punto en el mismo lugar RELATIVO.
        val e = EdicionFoto(giro = 90, zoom = 1.7f, desplX = -0.08f, desplY = 0.05f)
        val chica = Encuadre.transformacion(3000, 2000, e, 360f, 640f)
        val grande = Encuadre.transformacion(3000, 2000, e, W, H)
        val (xc, yc) = chica.aplicar(700f, 1300f)
        val (xg, yg) = grande.aplicar(700f, 1300f)
        assertEquals(xc / 360f, xg / W, 1e-4f)
        assertEquals(yc / 640f, yg / H, 1e-4f)
    }

    @Test
    fun `el zoom no baja de cubrir ni pasa del maximo`() {
        assertEquals(1f, Encuadre.zoomValido(0.3f), 0f)
        assertEquals(Lienzo.ZOOM_MAX, Encuadre.zoomValido(99f), 0f)
    }

    @Test
    fun `sin zoom no se puede mover la foto en el lado justo`() {
        // Vertical justa: no sobra nada por ningun lado.
        val (mx, my) = Encuadre.margen(1080, 1920, EdicionFoto(), W, H)
        assertEquals(0f, mx, 1e-5f)
        assertEquals(0f, my, 1e-5f)
    }

    @Test
    fun `acotar impide destapar el borde`() {
        val e = Encuadre.acotar(1080, 1920, EdicionFoto(zoom = 2f, desplX = 0.9f), W, H)
        // Con zoom 2 la foto mide dos lienzos de ancho: sobra medio lienzo de
        // cada lado, y eso es lo que se puede mover.
        assertEquals(0.5f, e.desplX, 1e-4f)
        // Y entonces el borde izquierdo de la foto queda justo en el del lienzo.
        val t = Encuadre.transformacion(1080, 1920, e, W, H)
        assertEquals(0f, t.aplicar(0f, 960f).first, eps)
    }

    // ---------------------------------------------------------- filtros

    @Test
    fun `el original no toca nada`() {
        val m = FiltroFoto.ORIGINAL.matriz
        assertTrue(m.contentEquals(Matrices.identidad()))
    }

    @Test
    fun `blanco y negro da el mismo valor en los tres canales`() {
        val m = FiltroFoto.BN.matriz
        val (r, g, b) = aplicar(m, 200f, 40f, 90f)
        assertEquals(r, g, 0.01f)
        assertEquals(g, b, 0.01f)
    }

    @Test
    fun `blanco y negro conserva el blanco`() {
        val (r, g, b) = aplicar(FiltroFoto.BN.matriz, 255f, 255f, 255f)
        assertEquals(255f, r, 0.5f); assertEquals(255f, g, 0.5f); assertEquals(255f, b, 0.5f)
    }

    @Test
    fun `calido sube el rojo y baja el azul`() {
        val (r, _, b) = aplicar(FiltroFoto.CALIDO.matriz, 100f, 100f, 100f)
        assertTrue(r > 100f && b < 100f)
    }

    @Test
    fun `ningun filtro toca la transparencia`() {
        FiltroFoto.entries.forEach { f ->
            val m = f.matriz
            assertEquals(f.name, 1f, m[18], 1e-5f)
            assertEquals(f.name, 0f, m[15] + m[16] + m[17] + m[19], 1e-5f)
        }
    }

    @Test
    fun `componer con la identidad no cambia nada`() {
        val m = FiltroFoto.SEPIA.matriz
        assertTrue(Matrices.producto(m, Matrices.identidad()).contentEquals(m))
        assertTrue(Matrices.producto(Matrices.identidad(), m).contentEquals(m))
    }

    @Test
    fun `el producto aplica primero uno y despues el otro`() {
        val a = Matrices.escalaCanales(2f, 1f, 1f)
        val b = Matrices.desvanecido(1f, 10f)
        // primero x2, despues +10: 50 -> 110 (y no 120, que seria al reves).
        assertEquals(110f, aplicar(Matrices.producto(a, b), 50f, 0f, 0f).first, 0.01f)
    }

    /** Aplica una matriz 4x5 a un color opaco. */
    private fun aplicar(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(
            m[0] * r + m[1] * g + m[2] * b + m[3] * 255f + m[4],
            m[5] * r + m[6] * g + m[7] * b + m[8] * 255f + m[9],
            m[10] * r + m[11] * g + m[12] * b + m[13] * 255f + m[14],
        )
}
