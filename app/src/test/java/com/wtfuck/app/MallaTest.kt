package com.wtfuck.app

import com.wtfuck.app.datos.Malla
import org.junit.Assert.*
import org.junit.Test

/**
 * Quién ofrece a quién en una llamada de grupo.
 *
 * ## Qué se arregló
 *
 * El servidor admite llamadas de hasta cuatro desde el módulo K, pero sólo
 * quien llamaba abría conexiones. En una llamada de tres, **B y C hablaban los
 * dos con A y no se oían entre sí**: la llamada de grupo existía a medias y
 * desde dentro parecía un fallo de red.
 *
 * Al cerrar la malla aparece el problema de verdad: si B y C se ofrecen a la
 * vez, cada uno recibe una oferta mientras espera una respuesta —el *glare* de
 * WebRTC— y las dos conexiones quedan a medio negociar. La regla de desempate
 * es **una comparación**, y de ella depende que la llamada conecte o no.
 *
 * Por eso se prueba aquí y no en el emulador: el fallo que importa sólo pasa
 * cuando dos aparatos deciden **simultáneamente**, y eso a mano no se
 * reproduce.
 */
class MallaTest {

    // ---------------------------------------------------------------- la regla

    /**
     * Lo único que evita el glare: si yo ofrezco, el otro no.
     *
     * Se prueba sobre ids de formas distintas —longitudes, dígitos, guiones,
     * mayúsculas— porque un UUID real no se parece a `"a"` y la comparación de
     * cadenas es sensible a todo eso.
     */
    @Test
    fun `entre dos, ofrece exactamente uno`() {
        val ids = listOf(
            "a", "b", "z",
            "018f2c1a-0000-7000-8000-000000000001",
            "018f2c1a-0000-7000-8000-000000000002",
            "AAAA", "aaaa", "0", "9", "device-10", "device-9",
        )
        for (x in ids) {
            for (y in ids) {
                if (x == y) continue
                val yo = Malla.meTocaOfrecer(x, y)
                val el = Malla.meTocaOfrecer(y, x)
                assertNotEquals(
                    "con $x y $y los dos ofrecen o ninguno lo hace: eso es el glare",
                    yo, el,
                )
            }
        }
    }

    /**
     * Un aparato no se llama a sí mismo.
     *
     * No es teórico: el propio dispositivo aparece en la lista de destinos si
     * la persona tiene la sesión abierta en dos sitios, y ofrecerse a sí mismo
     * abre una conexión que nunca contesta.
     */
    @Test
    fun `nunca a uno mismo`() {
        assertFalse(Malla.meTocaOfrecer("a", "a"))
        assertFalse(Malla.meTocaOfrecer("018f2c1a-1", "018f2c1a-1"))
    }

    /**
     * Un id vacío no se ofrece ni recibe.
     *
     * `sesion.dispositivoId` puede no estar todavía —el registro es asíncrono—
     * y `""` gana cualquier comparación contra cualquier cadena. Sin este
     * guardia, un dispositivo sin id ofrecería a **todo el mundo**.
     */
    @Test
    fun `un id vacio no participa`() {
        assertFalse(Malla.meTocaOfrecer("", "b"))
        assertFalse(Malla.meTocaOfrecer("a", ""))
        assertFalse(Malla.meTocaOfrecer("", ""))
    }

    // ------------------------------------------------------------ a quiénes

    /** A quien ya tiene conexión no se le vuelve a ofrecer. */
    @Test
    fun `no se reofrece a quien ya esta conectado`() {
        val r = Malla.aQuienesOfrecer(
            mio = "a",
            candidatos = listOf("b", "c", "d"),
            yaConectados = setOf("b"),
        )
        assertEquals(listOf("c", "d"), r)
    }

    /**
     * Volver a ofrecer no es inofensivo: la renegociación tira abajo la
     * conexión que ya funcionaba para rehacerla, y se oye como un corte.
     */
    @Test
    fun `con todos conectados no se ofrece a nadie`() {
        val r = Malla.aQuienesOfrecer("a", listOf("b", "c"), setOf("b", "c"))
        assertTrue(r.isEmpty())
    }

    /** La lista de destinos puede traer repetidos; una conexión por aparato. */
    @Test
    fun `un destino repetido se ofrece una sola vez`() {
        val r = Malla.aQuienesOfrecer("a", listOf("b", "b", "c"), emptySet())
        assertEquals(listOf("b", "c"), r)
    }

    /** El que va último por id no ofrece a nadie: le ofrecen a él. */
    @Test
    fun `el mayor no ofrece, espera`() {
        assertTrue(Malla.aQuienesOfrecer("z", listOf("a", "b"), emptySet()).isEmpty())
        assertEquals(listOf("b", "z"), Malla.aQuienesOfrecer("a", listOf("b", "z"), emptySet()))
    }

    // ------------------------------------------------------------ la llamada

    /**
     * La prueba que de verdad importa: **la malla queda cerrada**.
     *
     * Se simula la llamada entera. Quien llama ofrece a todos; cada uno que
     * contesta ofrece a los demás aplicando la regla. Al final se exige que
     * cada par tenga **una** oferta: cero es el defecto original —B y C sin
     * oírse—, dos es el glare.
     *
     * Se prueba con 2, 3 y 4 porque cuatro es el techo declarado en el módulo
     * K, y **con la llamada iniciada por cada uno de los participantes**: el
     * caso que fallaba dependía de quién había llamado.
     */
    @Test
    fun `la malla queda cerrada para toda llamada de hasta cuatro`() {
        val todos = listOf(
            "018f2c1a-0000-7000-8000-00000000000a",
            "018f2c1a-0000-7000-8000-00000000000b",
            "018f2c1a-0000-7000-8000-00000000000c",
            "018f2c1a-0000-7000-8000-00000000000d",
        )
        for (cuantos in 2..4) {
            val gente = todos.take(cuantos)
            for (quienLlama in gente) {
                val ofertas = mutableSetOf<Pair<String, String>>()

                // 1. Quien llama ofrece a todos los demás.
                for (otro in gente - quienLlama) ofertas += quienLlama to otro

                // 2. Cada uno que contesta cierra la malla con los demás.
                //    Todos deciden a la vez y sin hablarlo: es el escenario
                //    del glare, no una secuencia ordenada.
                for (yo in gente - quienLlama) {
                    val yaConectados = setOf(quienLlama)
                    for (destino in Malla.aQuienesOfrecer(yo, gente, yaConectados)) {
                        ofertas += yo to destino
                    }
                }

                // 3. Cada par, exactamente una oferta.
                for (x in gente) {
                    for (y in gente) {
                        if (x >= y) continue
                        val n = listOf(x to y, y to x).count { it in ofertas }
                        assertEquals(
                            "llamada de $cuantos iniciada por $quienLlama: " +
                                "el par ($x, $y) tiene $n ofertas",
                            1, n,
                        )
                    }
                }

                // 4. Y nadie se ofrece a sí mismo.
                assertTrue(
                    "alguien se ofrecio a si mismo",
                    ofertas.none { it.first == it.second },
                )
            }
        }
    }

    /**
     * El mismo cierre, hecho **dos veces**.
     *
     * Pasa de verdad: llega una oferta tardía, o la pantalla se recompone y el
     * servicio vuelve a mirar los destinos. Si la segunda pasada ofreciera de
     * nuevo, cortaría llamadas que ya estaban en pie.
     */
    @Test
    fun `cerrar la malla dos veces no ofrece nada la segunda`() {
        val gente = listOf("a", "b", "c")
        val conectados = mutableSetOf("a") // quien llamó
        val primera = Malla.aQuienesOfrecer("b", gente, conectados)
        conectados += primera
        val segunda = Malla.aQuienesOfrecer("b", gente, conectados)
        assertEquals(listOf("c"), primera)
        assertTrue("la segunda pasada reofrecio y habria cortado la llamada", segunda.isEmpty())
    }
}
