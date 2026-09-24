package com.wtfuck.app

import android.graphics.Bitmap
import android.os.Build
import com.wtfuck.app.datos.Stickers
import org.junit.Assert.*
import org.junit.Test

/**
 * La aritmética del recorte de un sticker.
 *
 * ## Por qué esto se prueba
 *
 * Porque son cuentas con enteros sobre tamaños que vienen de fuera —una foto
 * apaisada, una vertical, una cuadrada, una de un píxel— y las cuentas con
 * enteros fallan en los extremos, que es justo donde nadie prueba a mano.
 * Recortar mal no lanza ninguna excepción: produce un sticker torcido, o uno
 * que no se crea y nadie sabe por qué.
 */
class StickersTest {

    // ============================================================
    //  El cuadrado de partida
    // ============================================================

    @Test
    fun `de una foto apaisada sale la franja central`() {
        val r = Stickers.cuadradoCentrado(1000, 600)
        assertEquals(600, r.ancho)
        assertEquals(600, r.alto)
        // Centrado: sobran 400 de ancho, 200 a cada lado.
        assertEquals(200, r.izq)
        assertEquals(800, r.der)
        assertEquals(0, r.arriba)
    }

    @Test
    fun `de una vertical, igual pero por arriba y abajo`() {
        val r = Stickers.cuadradoCentrado(600, 1000)
        assertEquals(600, r.ancho)
        assertEquals(200, r.arriba)
        assertEquals(0, r.izq)
    }

    @Test
    fun `una foto ya cuadrada se queda entera`() {
        val r = Stickers.cuadradoCentrado(512, 512)
        assertEquals(0, r.izq)
        assertEquals(0, r.arriba)
        assertEquals(512, r.ancho)
        assertEquals(512, r.alto)
    }

    @Test
    fun `el recorte siempre es cuadrado, mida lo que mida la foto`() {
        // La invariante. Un `-1` de más en cualquiera de las dos cuentas
        // produce un rectángulo de 599x600 que al escalarse a 512x512
        // deforma la imagen: nadie lo ve en una foto, y se nota en una cara.
        listOf(
            1 to 1, 3 to 4, 4 to 3, 1001 to 999, 999 to 1001,
            4032 to 3024, 2 to 5000,
        ).forEach { (a, b) ->
            val r = Stickers.cuadradoCentrado(a, b)
            assertEquals("${a}x$b", r.ancho, r.alto)
            assertEquals("${a}x$b", minOf(a, b), r.ancho)
        }
    }

    @Test
    fun `el recorte nunca se sale de la imagen`() {
        // Si se saliera, `drawBitmap` lanza y el síntoma sería "crear sticker
        // no hace nada".
        listOf(1000 to 600, 600 to 1000, 7 to 7, 1 to 4096).forEach { (a, b) ->
            val r = Stickers.cuadradoCentrado(a, b)
            assertTrue("${a}x$b left", r.izq >= 0)
            assertTrue("${a}x$b top", r.arriba >= 0)
            assertTrue("${a}x$b right", r.der <= a)
            assertTrue("${a}x$b bottom", r.abajo <= b)
        }
    }

    @Test
    fun `un tamano imposible no revienta`() {
        // Pasa con una URI que ya no existe: el decodificador devuelve 0x0 y
        // esto se llama igual antes de comprobarlo.
        val r = Stickers.cuadradoCentrado(0, 0)
        assertEquals(0, r.ancho)
        val n = Stickers.cuadradoCentrado(-5, 100)
        assertEquals(0, n.ancho)
    }

    // ============================================================
    //  Cuánto se decodifica
    // ============================================================

    @Test
    fun `una foto enorme se decodifica reducida`() {
        // 4000 px de lado con objetivo 512: 4000/8 = 500 < 512, así que el
        // paso correcto es 4 (queda en 1000) y no 8.
        assertEquals(4, Stickers.muestreoPara(4000, 512))
    }

    @Test
    fun `el muestreo se queda POR ENCIMA del objetivo, nunca por debajo`() {
        // La razón: `inSampleSize` sólo admite potencias de dos. Pasarse hacia
        // abajo obliga a escalar hacia arriba después, que es emborronar.
        listOf(513, 1024, 1025, 2000, 4000, 8000, 12000).forEach { lado ->
            val paso = Stickers.muestreoPara(lado, 512)
            assertTrue("$lado -> paso $paso deja ${lado / paso}", lado / paso >= 512)
        }
    }

    @Test
    fun `una foto ya chica no se toca`() {
        assertEquals(1, Stickers.muestreoPara(512, 512))
        assertEquals(1, Stickers.muestreoPara(300, 512))
        assertEquals(1, Stickers.muestreoPara(0, 512))
    }

    // ============================================================
    //  El formato, que decide si la transparencia sobrevive
    // ============================================================

    @Test
    fun `en Android moderno se usa WebP sin perdida`() {
        assertEquals(
            Bitmap.CompressFormat.WEBP_LOSSLESS,
            Stickers.formatoPara(Build.VERSION_CODES.R),
        )
        assertEquals("webp", Stickers.extensionPara(Build.VERSION_CODES.R))
    }

    @Test
    fun `por debajo de API 30 se cae a PNG y NO al WebP viejo`() {
        // El `WEBP` antiguo es **con pérdida**, y con pérdida los bordes de un
        // recorte transparente salen con halo. PNG pesa más y se ve bien; un
        // sticker feo no sirve.
        assertEquals(
            Bitmap.CompressFormat.PNG,
            Stickers.formatoPara(Build.VERSION_CODES.P),
        )
        assertEquals("png", Stickers.extensionPara(Build.VERSION_CODES.O))
    }

    @Test
    fun `la extension coincide siempre con el formato`() {
        // De la extensión sale el MIME cuando el archivo se lee por file://, y
        // un .webp que por dentro es PNG se abre mal en el otro aparato.
        listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.P, Build.VERSION_CODES.R, 36)
            .forEach { sdk ->
                val esWebp = Stickers.formatoPara(sdk) != Bitmap.CompressFormat.PNG
                assertEquals("sdk $sdk", if (esWebp) "webp" else "png", Stickers.extensionPara(sdk))
            }
    }

    @Test
    fun `el lado es 512, que es lo que espera todo el mundo`() {
        assertEquals(512, Stickers.LADO)
    }

    // ============================================================
    //  Detectar movimiento: se mira la CABECERA, no la extensión
    // ============================================================

    /** RIFF + tamaño + WEBP + el trozo que marca la animación. */
    private fun webpAnimado(): ByteArray =
        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8X".toByteArray() +
            ByteArray(10) + "ANIM".toByteArray() + ByteArray(32)

    private fun webpFijo(): ByteArray =
        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(64)

    private fun gif(fotogramas: Int): ByteArray {
        var b = "GIF89a".toByteArray() + ByteArray(7)
        repeat(fotogramas) { b += byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0) + ByteArray(20) }
        return b
    }

    @Test
    fun `un WebP animado se reconoce`() {
        assertTrue(Stickers.esAnimado(webpAnimado()))
    }

    @Test
    fun `un WebP fijo NO se reconoce como animado`() {
        // El caso que importa: **la extensión es la misma**. Un `.webp` puede
        // ser una imagen quieta o una animación, así que mirar el nombre no
        // distingue nada y hay que mirar dentro.
        assertFalse(Stickers.esAnimado(webpFijo()))
    }

    @Test
    fun `un GIF de varios fotogramas es animado`() {
        assertTrue(Stickers.esAnimado(gif(3)))
    }

    @Test
    fun `un GIF de un solo fotograma no lo es`() {
        // Existen y son comunes: un GIF quieto es una imagen. Tratarlo como
        // animado lo dejaría sin recortar por nada.
        assertFalse(Stickers.esAnimado(gif(1)))
    }

    @Test
    fun `un archivo que no es ninguna de las dos cosas no revienta`() {
        assertFalse(Stickers.esAnimado(ByteArray(0)))
        assertFalse(Stickers.esAnimado("no soy una imagen".toByteArray()))
        assertFalse(Stickers.esAnimado(byteArrayOf(0x89.toByte(), 'P'.code.toByte())))
    }

    @Test
    fun `la palabra ANIM suelta en un archivo cualquiera no lo hace animado`() {
        // Sin la comprobación de que sea un WebP, cualquier archivo con esas
        // cuatro letras dentro —un texto, un PNG con metadatos— se copiaría
        // sin recortar creyendo que tiene movimiento.
        assertFalse(Stickers.esAnimado("hola ANIM que tal".toByteArray()))
    }
}
