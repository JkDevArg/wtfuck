package com.wtfuck.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.wtfuck.app.ui.textoConMenciones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * El texto de la burbuja propia, sobre el cian.
 *
 * ## El defecto que estas pruebas cierran
 *
 * `textoConMenciones` tiene un atajo deliberado: **sin menciones devuelve el
 * texto sin ningún tramo de color**, para no construir un `AnnotatedString`
 * por cada mensaje de la lista que más se desplaza. La decisión es correcta;
 * lo que faltaba era su consecuencia — que el color lo tiene que poner el
 * `Text` — y el `Text` no lo ponía. Caía al color de contenido por defecto.
 *
 * El resultado era texto **claro sobre cian**, ilegible, en todos los
 * mensajes propios sin menciones, que son casi todos.
 *
 * Y sobrevivió meses por una razón que vale anotar: **sólo se veía en media
 * pantalla**. En las burbujas recibidas el color por defecto coincide con el
 * correcto, así que el mismo código estaba bien de un lado y roto del otro. La
 * hora y los checks sí usaban el color bueno, lo que hacía la burbuja
 * *parecer* correcta de un vistazo.
 */
class ContrasteBurbujaTest {

    // Los dos tokens, copiados del tema. Copiados a propósito: si alguien
    // cambia uno allá y el contraste se cae, esto tiene que fallar por el
    // valor nuevo, no seguir midiendo el viejo a través de una referencia.
    private val cian = Color(0xFF6CF8F6)
    private val sobreAcentoOscuro = Color(0xFF0E1313)
    private val textoPrimarioOscuro = Color(0xFFDCE3E3)

    /** Luminancia relativa de la WCAG. */
    private fun luz(c: Color): Double {
        fun canal(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canal(c.red) + 0.7152 * canal(c.green) + 0.0722 * canal(c.blue)
    }

    private fun contraste(a: Color, b: Color): Double {
        val (x, y) = luz(a) to luz(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    // ------------------------------------------------------------------
    // Los colores
    // ------------------------------------------------------------------

    @Test
    fun `el color que va sobre el acento se lee sobre el cian`() {
        val r = contraste(sobreAcentoOscuro, cian)
        assertTrue("contraste $r, hace falta 4.5:1", r >= 4.5)
    }

    /**
     * TODAS las paletas de acento, en los dos temas.
     *
     * La razon de que las paletas sean una lista cerrada y no un selector de
     * color esta aqui: cada entrada pasa por la misma medida que tenia el
     * acento unico. Quien agregue una paleta eligiendo el color a ojo se
     * entera al correr las pruebas, no cuando alguien no pueda leer su propia
     * burbuja.
     *
     * Se comprueban las cuatro combinaciones de cada paleta:
     *
     *  - el acento sobre la superficie, en oscuro y en claro (se usa como
     *    texto e icono, asi que le toca el 4.5:1 de texto y no el 3:1 de
     *    componente);
     *  - la tinta sobre el acento, en oscuro y en claro (la burbuja propia).
     */
    @Test
    fun `cada paleta se lee en los dos temas`() {
        val supOscura = Color(0xFF161D1D)
        val supClara = Color(0xFFFFFFFF)
        val fallos = mutableListOf<String>()

        for (p in com.wtfuck.app.ui.theme.Paleta.entries) {
            val medidas = listOf(
                "acento sobre superficie oscura" to contraste(p.muestra(false), supOscura),
                "tinta sobre el acento oscuro" to contraste(p.tinta(false), p.muestra(false)),
                "acento sobre superficie clara" to contraste(p.muestra(true), supClara),
                "tinta sobre el acento claro" to contraste(p.tinta(true), p.muestra(true)),
            )
            for ((que, r) in medidas) {
                if (r < 4.5) fallos += "${p.name}: $que da %.2f".format(r)
            }
        }
        assertTrue(
            "paletas que no llegan a 4.5:1 ->\n" + fallos.joinToString("\n"),
            fallos.isEmpty(),
        )
    }

    @Test
    fun `hay mas de una paleta y la primera es la de siempre`() {
        // Si alguien deja una sola, el selector sobra. Y CIAN tiene que ser
        // la primera: es el defecto, y cambiarlo le cambiaria el color a todo
        // el mundo en una actualizacion.
        val todas = com.wtfuck.app.ui.theme.Paleta.entries
        assertTrue("solo hay una paleta", todas.size > 1)
        assertTrue("la primera deberia ser CIAN", todas.first().name == "CIAN")
    }

    @Test
    fun `el color por defecto NO se lee sobre el cian`() {
        // Esta es la prueba que importa: fija que el color por defecto es una
        // elección equivocada, no una alternativa aceptable. Si alguien vuelve
        // a dejar un `Text` sin `color` dentro de la burbuja propia, el
        // resultado es este número.
        val r = contraste(textoPrimarioOscuro, cian)
        assertTrue("contraste $r: seria legible y no lo es", r < 2.0)
    }

    // ------------------------------------------------------------------
    // El atajo que obliga a quien llama
    // ------------------------------------------------------------------

    @Test
    fun `sin menciones el texto vuelve SIN color propio`() {
        // El atajo, fijado. No para impedir que cambie, sino para que quien lo
        // lea sepa que el color es responsabilidad del `Text`: si algún día
        // esto devolviera un tramo con color, la prueba falla y quien la
        // arregle se encontrará con el comentario de arriba.
        val r = textoConMenciones("hola que tal", "joaquin", Color.Red, Color.Blue)
        assertEquals("hola que tal", r.text)
        assertTrue(
            "el atajo no puede traer color: lo pone el Text",
            r.spanStyles.isEmpty(),
        )
    }

    @Test
    fun `con menciones si vienen los colores que se pidieron`() {
        // El otro lado: cuando hay menciones, los colores pasados SE USAN. Sin
        // esto, "quitar el color del atajo" podría degenerar en "no colorear
        // nunca" sin que nada lo notara.
        val r = textoConMenciones("hola @joaquin y @tati", "joaquin", Color.Red, Color.Blue)
        assertTrue("tendria que haber tramos", r.spanStyles.isNotEmpty())
        val colores = r.spanStyles.mapNotNull { it.item.color }
        assertTrue(
            "los colores pedidos tienen que aparecer: $colores",
            colores.any { it == Color.Red } || colores.any { it == Color.Blue },
        )
    }

    @Test
    fun `una mencion a mi se distingue de una mencion a otro`() {
        val r = textoConMenciones("@joaquin y @tati", "joaquin", Color.Red, Color.Blue)
        val estilos: List<SpanStyle> = r.spanStyles.map { it.item }
        // Si las dos se dibujaran igual, marcar menciones no serviria de nada.
        assertTrue(
            "las dos menciones no pueden quedar identicas",
            estilos.distinct().size >= 2,
        )
    }
}
