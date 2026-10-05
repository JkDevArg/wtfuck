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

    // ------------------------------------------------------ proporciones

    @Test
    fun `un estado sale a 1080x1920`() {
        assertEquals(1080 to 1920, com.wtfuck.app.datos.Proporcion.salida(9f / 16f, 1920))
    }

    @Test
    fun `cuadrada y apaisada`() {
        assertEquals(2560 to 2560, com.wtfuck.app.datos.Proporcion.salida(1f, 2560))
        assertEquals(2560 to 1440, com.wtfuck.app.datos.Proporcion.salida(16f / 9f, 2560))
    }

    @Test
    fun `los lados salen pares`() {
        val (w, h) = com.wtfuck.app.datos.Proporcion.salida(4f / 5f, 1001)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `la original es la de la foto, y cambia al girarla`() {
        val p = com.wtfuck.app.datos.Proporcion.ORIGINAL
        assertEquals(4000f / 3000f, p.efectiva(4000, 3000, 0), 1e-5f)
        assertEquals(3000f / 4000f, p.efectiva(4000, 3000, 90), 1e-5f)
    }

    @Test
    fun `una fija no depende de la foto`() {
        assertEquals(1f, com.wtfuck.app.datos.Proporcion.CUADRADA.efectiva(4000, 3000, 90), 0f)
    }

    @Test
    fun `con la original y sin zoom no se recorta nada`() {
        // La foto entera cabe justo: sus cuatro esquinas caen en las del lienzo.
        val p = com.wtfuck.app.datos.Proporcion.ORIGINAL.efectiva(4000, 3000, 0)
        val w = 1000f
        val h = w / p
        val t = Encuadre.transformacion(4000, 3000, EdicionFoto(), w, h)
        assertEquals(0f, t.aplicar(0f, 0f).first, eps)
        assertEquals(0f, t.aplicar(0f, 0f).second, eps)
        assertEquals(w, t.aplicar(4000f, 3000f).first, eps)
        assertEquals(h, t.aplicar(4000f, 3000f).second, eps)
    }

    @Test
    fun `un recorte cuadrado no se agranda mas alla de sus pixeles reales`() {
        // 2400x1500: el cuadrado mas grande que cabe mide 1500.
        assertEquals(1500, com.wtfuck.app.datos.Proporcion.ladoSinAgrandar(1f, 2400, 1500, 0, 1f))
    }

    @Test
    fun `con la proporcion de la foto sale a su tamano`() {
        assertEquals(2400, com.wtfuck.app.datos.Proporcion.ladoSinAgrandar(2400f / 1500f, 2400, 1500, 0, 1f))
    }

    @Test
    fun `acercar 2x deja la mitad de pixeles`() {
        assertEquals(750, com.wtfuck.app.datos.Proporcion.ladoSinAgrandar(1f, 2400, 1500, 0, 2f))
    }

    @Test
    fun `girada cuenta con los lados intercambiados`() {
        // 2400x1500 girada es 1500x2400: un 9:16 tiene de alto lo que da el ancho.
        val lado = com.wtfuck.app.datos.Proporcion.ladoSinAgrandar(9f / 16f, 2400, 1500, 90, 1f)
        assertEquals(2400, lado)
    }

    /** Aplica una matriz 4x5 a un color opaco. */
    private fun aplicar(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(
            m[0] * r + m[1] * g + m[2] * b + m[3] * 255f + m[4],
            m[5] * r + m[6] * g + m[7] * b + m[8] * 255f + m[9],
            m[10] * r + m[11] * g + m[12] * b + m[13] * 255f + m[14],
        )
}
