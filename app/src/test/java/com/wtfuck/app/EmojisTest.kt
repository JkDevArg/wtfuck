package com.wtfuck.app

import com.wtfuck.app.datos.GRUPOS_EMOJI
import com.wtfuck.app.datos.SOPORTAN_TONO
import com.wtfuck.app.datos.TONOS
import com.wtfuck.app.datos.TONO_POR_DEFECTO
import com.wtfuck.app.datos.aplicarTono
import com.wtfuck.app.datos.buscarEmojis
import com.wtfuck.app.datos.catalogo
import com.wtfuck.app.datos.emojiSolo
import com.wtfuck.app.datos.normalizar
import com.wtfuck.app.datos.quitarTono
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Módulo Z · El catálogo de emojis, la búsqueda y los tonos de piel.
 *
 * Corre en la JVM porque nada de esto toca Android. Es la misma razón por la
 * que `Recorte` existe en vez de usar `android.graphics.Rect`: cinco pruebas
 * de recorte fallaron una vez contra el framework y ninguna contra el código.
 */
class EmojisTest {

    // ============================================================
    //  El catálogo
    // ============================================================

    /**
     * Todo emoji tiene al menos una palabra.
     *
     * Es la prueba que justifica que el glifo y sus palabras vivan en la misma
     * línea. Un emoji sin palabras **existe en la rejilla y no aparece nunca al
     * buscar**, que es la clase de defecto que nadie reporta porque nadie sabe
     * que falta.
     */
    @Test
    fun `todo emoji del catalogo tiene palabras`() {
        val sinPalabras = catalogo().filter { it.palabras.isEmpty() }
        assertTrue("Emojis sin palabras: ${sinPalabras.map { it.glifo }}", sinPalabras.isEmpty())
    }

    @Test
    fun `no hay glifos repetidos en el catalogo`() {
        val todos = GRUPOS_EMOJI.flatMap { it.second }.map { it.glifo }
        val repes = todos.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("Repetidos: $repes", repes.isEmpty())
    }

    /**
     * Las palabras van sin tildes en el archivo.
     *
     * `normalizar` las quita de los dos lados, así que una tilde no rompe nada
     * —pero deja en el archivo una palabra que parece exigirla—. Esta prueba
     * existe para que el catálogo se lea igual que se busca.
     */
    @Test
    fun `las palabras del catalogo ya estan normalizadas`() {
        val raras = catalogo().flatMap { e ->
            e.palabras.filter { it != normalizar(it) }.map { "${e.glifo} -> $it" }
        }
        assertTrue("Palabras sin normalizar: $raras", raras.isEmpty())
    }

    /** Los doce emojis con los que se etiqueta un sticker tienen que estar. */
    @Test
    fun `los emojis de etiquetar stickers estan en el catalogo`() {
        val etiquetas = listOf(
            "😂", "❤️", "👍", "😮", "😢",
            "🔥", "🎉", "👏", "🤔", "😴",
            "🙏", "💪",
        )
        val glifos = catalogo().map { it.glifo }.toSet()
        val faltan = etiquetas.filterNot { it in glifos }
        // Si uno falta, `emojiSolo` no lo reconoce y la tira de sugerencias
        // no aparece nunca para los stickers etiquetados con el.
        assertTrue("Faltan en el catalogo: $faltan", faltan.isEmpty())
    }

    // ============================================================
    //  Normalizar
    // ============================================================

    @Test
    fun `normalizar quita tildes y mayusculas`() {
        assertEquals("corazon", normalizar("Corazón"))
        assertEquals("cumpleanos", normalizar("CUMPLEAÑOS"))
        assertEquals("cafe", normalizar("  Café  "))
    }

    // ============================================================
    //  Buscar
    // ============================================================

    @Test
    fun `buscar sin tildes encuentra lo que si las tiene`() {
        val r = buscarEmojis("corazon", catalogo()).map { it.glifo }
        assertTrue("Debería traer el corazón rojo: $r", "❤️" in r)
    }

    /**
     * Prefijo y no subcadena.
     *
     * Con subcadena, "ojo" traería "enojado" y "cerrojo". Un buscador donde
     * escribir más letras trae **cosas distintas** en vez de menos cosas no se
     * usa dos veces.
     */
    @Test
    fun `buscar es por prefijo y no por subcadena`() {
        val r = buscarEmojis("ojo", catalogo()).map { it.glifo }
        val enojo = catalogo().first { it.palabras.any { p -> p == "enojo" } }.glifo
        assertTrue("\"ojo\" no debe traer $enojo por estar dentro de \"enojo\"", enojo !in r)
    }

    @Test
    fun `dos palabras afinan en vez de acumular`() {
        val una = buscarEmojis("corazon", catalogo())
        val dos = buscarEmojis("corazon roto", catalogo())
        assertTrue("Dos palabras deben traer menos, no más", dos.size < una.size)
        assertEquals(listOf("💔"), dos.map { it.glifo })
    }

    @Test
    fun `buscar vacio no trae nada`() {
        assertTrue(buscarEmojis("", catalogo()).isEmpty())
        assertTrue(buscarEmojis("   ", catalogo()).isEmpty())
    }

    @Test
    fun `buscar algo que no existe no trae nada`() {
        assertTrue(buscarEmojis("zzzzz", catalogo()).isEmpty())
    }

    // ============================================================
    //  Tonos de piel
    // ============================================================

    @Test
    fun `aplicar tono a un emoji que lo admite lo agrega`() {
        val pulgar = "👍"
        val claro = aplicarTono(pulgar, TONOS[0])
        assertNotEquals(pulgar, claro)
        assertTrue(claro.startsWith(pulgar))
        assertTrue(claro.endsWith(TONOS[0]))
    }

    /**
     * Cambiar de tono no apila modificadores.
     *
     * Sin `quitarTono` al principio de `aplicarTono`, cambiar de opinión sobre
     * el tono produce un glifo con dos modificadores pegados, que se manda y
     * queda así en el chat de la otra persona.
     */
    @Test
    fun `cambiar el tono reemplaza y no apila`() {
        val pulgar = "👍"
        val claro = aplicarTono(pulgar, TONOS[0])
        val oscuro = aplicarTono(claro, TONOS[4])
        assertEquals(aplicarTono(pulgar, TONOS[4]), oscuro)
        // Un solo modificador, no dos.
        assertEquals(1, TONOS.count { oscuro.contains(it) })
        assertTrue(TONOS[0] !in oscuro)
    }

    @Test
    fun `aplicar el tono por defecto devuelve el glifo base`() {
        val pulgar = "👍"
        val claro = aplicarTono(pulgar, TONOS[2])
        assertEquals(pulgar, aplicarTono(claro, TONO_POR_DEFECTO))
    }

    @Test
    fun `aplicar tono es idempotente`() {
        val pulgar = "👍"
        val una = aplicarTono(pulgar, TONOS[1])
        assertEquals(una, aplicarTono(una, TONOS[1]))
    }

    /**
     * El modificador va **antes** del selector de variación, no después.
     *
     * ✌️ es U+270C + U+FE0F. Si el tono se pega al final queda
     * U+270C U+FE0F U+1F3FD, que media Android dibuja como la mano y un
     * cuadrado de color al lado.
     */
    @Test
    fun `en un emoji con selector de variacion el tono reemplaza al selector`() {
        val victoria = "✌️"
        assertTrue("El caso solo tiene sentido si lo admite", victoria in SOPORTAN_TONO)
        val conTono = aplicarTono(victoria, TONOS[2])
        assertEquals("✌" + TONOS[2], conTono)
        assertTrue("No debe quedar el VS16", '️' !in conTono)
    }

    @Test
    fun `quitar el tono de un emoji con selector lo devuelve entero`() {
        val victoria = "✌️"
        val conTono = aplicarTono(victoria, TONOS[3])
        assertEquals(victoria, quitarTono(conTono))
    }

    @Test
    fun `un emoji que no admite tono se devuelve tal cual`() {
        val fuego = "🔥"
        assertTrue(fuego !in SOPORTAN_TONO)
        assertEquals(fuego, aplicarTono(fuego, TONOS[0]))
    }

    /** Todos los de la lista tienen que existir en el catálogo. */
    @Test
    fun `los que admiten tono estan en el catalogo`() {
        val glifos = catalogo().map { it.glifo }.toSet()
        val fantasmas = SOPORTAN_TONO.filterNot { it in glifos }
        assertTrue("Admiten tono pero no están en la rejilla: $fantasmas", fantasmas.isEmpty())
    }

    /**
     * Las caras no admiten tono de piel, aunque tengan una mano dibujada.
     *
     * Esta prueba existe porque el defecto ya pasó: 🫡, 🫢 y 🫣 estaban en la
     * lista, y elegir un tono dibujaba la carita amarilla con un rectángulo de
     * color suelto debajo. Son **caras** —U+1FAE1 a U+1FAE3— y Unicode no les
     * da modificador, tengan una mano dibujada encima o no.
     *
     * No se deriva de nada: es un hecho de Unicode. Así que se clava aquí, que
     * es donde alguien lo va a ver si vuelve a agregarlas.
     */
    @Test
    fun `las caras con mano no estan entre los que admiten tono`() {
        val caras = listOf("🫡", "🫢", "🫣")
        val colados = caras.filter { it in SOPORTAN_TONO }
        assertTrue("Son caras, no manos: $colados", colados.isEmpty())
    }

    // ============================================================
    //  Un solo emoji
    // ============================================================

    @Test
    fun `un emoji solo se reconoce`() {
        assertEquals("😂", emojiSolo("😂"))
        assertEquals("😂", emojiSolo("  😂  "))
    }

    /**
     * Con texto alrededor, no.
     *
     * "jaja 😂" es alguien escribiendo una frase, no buscando un sticker. Una
     * tira de sugerencias que aparece a mitad de una frase tapa el teclado por
     * nada.
     */
    @Test
    fun `un emoji dentro de una frase no cuenta`() {
        assertEquals("", emojiSolo("jaja 😂"))
        assertEquals("", emojiSolo("😂 que risa"))
    }

    @Test
    fun `dos emojis seguidos no cuentan`() {
        assertEquals("", emojiSolo("😂😂"))
    }

    @Test
    fun `puntuacion y caritas de texto no cuentan`() {
        assertEquals("", emojiSolo("..."))
        assertEquals("", emojiSolo(":)"))
        assertEquals("", emojiSolo(""))
        assertEquals("", emojiSolo("   "))
    }

    @Test
    fun `un emoji con tono se reconoce y conserva su tono`() {
        val conTono = aplicarTono("👍", TONOS[2])
        assertEquals(conTono, emojiSolo(conTono))
    }

    /**
     * Un emoji que no está en el catálogo no dispara nada.
     *
     * Se exige el catálogo en vez de adivinar por rango Unicode justamente
     * para esto: lo que dispara la sugerencia es exactamente lo que puede
     * haberse usado como etiqueta.
     */
    @Test
    fun `un emoji fuera del catalogo no cuenta`() {
        // Berenjena. No está en la rejilla, así que ningún sticker puede
        // llevarla como etiqueta.
        assertEquals("", emojiSolo("🍆"))
    }
}
